#!/usr/bin/env python3
"""Build the bundled face detector, check the app's pin, and write the vectors its tests replay.

The face detector is YuNet (opencv_zoo, MIT): the 2026may ONNX, whose height and width are dynamic, converted to
TFLite by onnx2tf's TensorFlow-free `flatbuffer_direct` backend. The conversion is deterministic, so the asset's
SHA-256 is pinned here and a rebuild either reproduces it byte for byte or fails.

  scripts/face-model.py                  exit 1 unless the asset is the pinned build (stdlib only)
  <venv>/bin/python scripts/face-model.py build
                                         fetch the pinned ONNX, convert it, check the pin, write the asset
  <venv>/bin/python scripts/face-model.py fixture
                                         rewrite the instrumented test's photo from NASA's original
  <venv>/bin/python scripts/face-model.py vectors
                                         rewrite the reference outputs the JVM and instrumented tests compare with

`build`, `fixture` and `vectors` need a venv with `onnx2tf==2.6.9` (it brings onnxruntime, numpy and opencv);
keep it off the root disk: `uv venv --python 3.12 <dir> && VIRTUAL_ENV=<dir> uv pip install onnx2tf==2.6.9`.

The vectors' expected faces come from OpenCV's own `FaceDetectorYN`, not from our decoder, so the Kotlin decoder is
held to the reference implementation rather than to a copy of itself.
"""

import argparse
import base64
import hashlib
import json
import os
import struct
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSET = ROOT / "app/src/faceRedaction/assets/redact/face_detection_yunet_2026may.tflite"
TEST_ASSETS = ROOT / "app/src/androidTest/assets/redact"
FIXTURE = TEST_ASSETS / "crew.jpg"
# Base64 text, not raw bytes: F-Droid's source scanner refuses a binary anywhere in the tree.
VECTOR = TEST_ASSETS / "yunet_heads.b64"
VECTOR_FACES = TEST_ASSETS / "yunet_heads_faces.json"
FIXTURE_FACES = TEST_ASSETS / "crew_faces.json"

ZOO_COMMIT = "eb9f4f2d4b79b58e5513b5d25d5f0243a8357bf4"
ONNX_URL = f"https://media.githubusercontent.com/media/opencv/opencv_zoo/{ZOO_COMMIT}/models/face_detection_yunet/face_detection_yunet_2026may.onnx"
ONNX_SHA256 = "ebafce4e3c118d6554634be5c27ab333b4c047a9a8c3faf1d7cf93101c22f0f0"
ONNX2TF = "2.6.9"
TFLITE_SHA256 = "708ad1b2d60407e2eaac5cbbfb76102c79a1e5a51b2b5a3076ad201236ec980b"

# The official Expedition 75 crew portrait (NASA/Josh Valcarcel, JSC, 2024): a US Government work, public domain.
NASA_URL = "https://images-assets.nasa.gov/image/iss075-s-002/iss075-s-002~large.jpg"
NASA_SHA256 = "e051d35b50e262f838646383204859fb4e92415e2755a8a42351cfa3ffbb9243"
FIXTURE_EDGE = 768

# The JVM vector: a 375 x 300 crop of the fixture holding four faces, shrunk to one 160 x 128 input (no padding).
VECTOR_CROP = (37, 97, 412, 397)
VECTOR_SIZE = (160, 128)
HEADS = [f"{kind}_{s}" for kind in ("cls", "obj", "bbox") for s in (8, 16, 32)]
THRESHOLD = 0.3
NMS_IOU = 0.3
REFERENCE_FLOOR = 0.2


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def fetch(url: str, digest: str, dest: Path) -> Path:
    urllib.request.urlretrieve(url, dest)
    got = sha256(dest)
    if got != digest:
        sys.exit(f"{url}: sha256 {got}, pinned {digest}")
    return dest


def check() -> int:
    if not ASSET.exists():
        print(f"missing {ASSET.relative_to(ROOT)}")
        return 1
    got = sha256(ASSET)
    if got != TFLITE_SHA256:
        print(f"{ASSET.relative_to(ROOT)}: sha256 {got}, pinned {TFLITE_SHA256}")
        return 1
    print(f"{ASSET.relative_to(ROOT)} is the pinned build")
    return 0


def build() -> int:
    with tempfile.TemporaryDirectory() as tmp:
        onnx = fetch(ONNX_URL, ONNX_SHA256, Path(tmp) / "face_detection_yunet_2026may.onnx")
        bin_dir = str(Path(sys.executable).parent)
        env = dict(os.environ, PATH=bin_dir + os.pathsep + os.environ.get("PATH", ""))  # onnx2tf runs onnxsim by name
        subprocess.run([sys.executable, "-m", "onnx2tf", "-i", str(onnx), "-o", tmp + "/out", "-v", "error"], check=True, env=env)
        out = Path(tmp) / "out/face_detection_yunet_2026may_float32.tflite"
        got = sha256(out)
        if got != TFLITE_SHA256:
            sys.exit(f"onnx2tf {ONNX2TF} built sha256 {got}, pinned {TFLITE_SHA256}: a different onnx2tf, or a new pin")
        ASSET.parent.mkdir(parents=True, exist_ok=True)
        ASSET.write_bytes(out.read_bytes())
    print(f"wrote {ASSET.relative_to(ROOT)}")
    return 0


def fixture() -> int:
    import cv2

    with tempfile.TemporaryDirectory() as tmp:
        img = cv2.imread(str(fetch(NASA_URL, NASA_SHA256, Path(tmp) / "large.jpg")))
    h, w = img.shape[:2]
    k = FIXTURE_EDGE / max(h, w)
    small = cv2.resize(img, (round(w * k), round(h * k)), interpolation=cv2.INTER_AREA)
    TEST_ASSETS.mkdir(parents=True, exist_ok=True)
    cv2.imwrite(str(FIXTURE), small, [cv2.IMWRITE_JPEG_QUALITY, 80])
    print(f"wrote {FIXTURE.relative_to(ROOT)} ({small.shape[1]} x {small.shape[0]})")
    return 0


def _onnx(tmp: str):
    import onnxruntime as ort

    onnx = fetch(ONNX_URL, ONNX_SHA256, Path(tmp) / "face_detection_yunet_2026may.onnx")
    return onnx, ort.InferenceSession(str(onnx), providers=["CPUExecutionProvider"])


def _heads(session, bgr):
    """Raw head outputs for a BGR uint8 image whose sides are multiples of 32."""
    import numpy as np

    blob = np.ascontiguousarray(bgr.transpose(2, 0, 1)[None].astype(np.float32))
    names = [o.name for o in session.get_outputs()]
    return dict(zip(names, session.run(None, {session.get_inputs()[0].name: blob})))


def _opencv_faces(onnx: Path, bgr, threshold: float):
    """OpenCV's FaceDetectorYN on one image: the reference decode, threshold and NMS."""
    import cv2

    h, w = bgr.shape[:2]
    detector = cv2.FaceDetectorYN.create(str(onnx), "", (w, h), threshold, NMS_IOU, 5000)
    _, faces = detector.detect(bgr)
    return [] if faces is None else [[round(float(v), 4) for v in (*f[:4], f[14])] for f in faces]


def _passes(bgr):
    """The detector's passes: full size, then halved and halved again while the halved long edge stays >= 320 px."""
    import cv2

    out, img = [bgr], bgr
    while len(out) < 3 and max(img.shape[:2]) // 2 >= 320:
        img = cv2.resize(img, (img.shape[1] // 2, img.shape[0] // 2), interpolation=cv2.INTER_AREA)
        out.append(img)
    return out


def vectors() -> int:
    import cv2
    import numpy as np

    with tempfile.TemporaryDirectory() as tmp:
        onnx, session = _onnx(tmp)
        photo = cv2.imread(str(FIXTURE))

        x0, y0, x1, y1 = VECTOR_CROP
        crop = cv2.resize(photo[y0:y1, x0:x1], VECTOR_SIZE, interpolation=cv2.INTER_AREA)
        heads = _heads(session, crop)
        raw = b"YNH1" + struct.pack("<ii", *VECTOR_SIZE)
        raw += crop.astype(np.uint8).tobytes()  # H x W x BGR
        raw += b"".join(heads[name].astype("<f4").tobytes() for name in HEADS)
        VECTOR.write_bytes(base64.encodebytes(raw))  # 76-column lines, as `base64 -w 76` writes
        VECTOR_FACES.write_text(json.dumps(_opencv_faces(onnx, crop, THRESHOLD), indent=1) + "\n")

        # The whole photo through every pass, each pass's faces mapped back to the photo, then one NMS over all.
        faces = []
        for img in _passes(photo):
            sx, sy = photo.shape[1] / img.shape[1], photo.shape[0] / img.shape[0]
            ph, pw = -(-img.shape[0] // 32) * 32, -(-img.shape[1] // 32) * 32
            padded = np.zeros((ph, pw, 3), np.uint8)
            padded[: img.shape[0], : img.shape[1]] = img
            for x, y, w, h, score in _opencv_faces(onnx, padded, REFERENCE_FLOOR):
                faces.append([x * sx, y * sy, w * sx, h * sy, score])
        kept = _nms(faces)
        FIXTURE_FACES.write_text(json.dumps([[round(v, 2) for v in f[:4]] + [f[4]] for f in kept], indent=1) + "\n")
    print(f"wrote {VECTOR.relative_to(ROOT)}, {VECTOR_FACES.name}, {FIXTURE_FACES.name}")
    return 0


def _nms(faces):
    kept = []
    for f in sorted(faces, key=lambda f: -f[4]):
        if all(_iou(f, k) <= NMS_IOU for k in kept):
            kept.append(f)
    return kept


def _iou(a, b):
    iw = max(0.0, min(a[0] + a[2], b[0] + b[2]) - max(a[0], b[0]))
    ih = max(0.0, min(a[1] + a[3], b[1] + b[3]) - max(a[1], b[1]))
    inter = iw * ih
    return inter / (a[2] * a[3] + b[2] * b[3] - inter)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("command", nargs="?", default="check", choices=["check", "build", "fixture", "vectors"])
    return {"check": check, "build": build, "fixture": fixture, "vectors": vectors}[parser.parse_args().command]()


if __name__ == "__main__":
    sys.exit(main())
