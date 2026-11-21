#!/usr/bin/env python3
"""Writes the HEIC fixture `ImagePipelineInstrumentedTest` ingests: a camera photo stored sideways.

    python3 -m venv /media/walter/data/knit-heic-fixture/venv
    /media/walter/data/knit-heic-fixture/venv/bin/pip install pillow-heif==1.1.1
    /media/walter/data/knit-heic-fixture/venv/bin/python -I scripts/gen-heic-fixture.py

The pixels are stored as the sensor read them, landscape 640 x 480, and an EXIF orientation of 6 ("rotate 90 clockwise
to view") says the photo was taken in portrait, the way a Samsung or Xiaomi camera writes its HEIC: no `irot` box, the
turn left to the EXIF. The EXIF also carries a GPS position and the camera's make and model, which no sent picture may
keep. A red block sits in the stored top-left corner and a green one in the stored bottom-right, so a viewer that turns
the picture upright finds red at the top right and green at the bottom left.

The script checks what it wrote: libheif must not have folded the orientation into a transform box, which would leave a
fixture that tests nothing the EXIF path does.
"""

import io
import sys
from pathlib import Path

import pillow_heif
import pillow_heif.heif as heif_module
from PIL import Image, ImageDraw

OUT = Path(__file__).resolve().parent.parent / "app/src/androidTest/assets/codec/camera-sideways.heic"

WIDTH, HEIGHT = 640, 480
BLOCK = 120

# EXIF tags (TIFF / GPS IFD numbers).
ORIENTATION = 0x0112
MAKE = 0x010F
MODEL = 0x0110
GPS_IFD = 0x8825
GPS_LAT_REF, GPS_LAT, GPS_LON_REF, GPS_LON = 1, 2, 3, 4


def picture() -> Image.Image:
    image = Image.new("RGB", (WIDTH, HEIGHT), (40, 90, 160))
    draw = ImageDraw.Draw(image)
    draw.rectangle([0, 0, BLOCK - 1, BLOCK - 1], fill=(220, 30, 30))
    draw.rectangle([WIDTH - BLOCK, HEIGHT - BLOCK, WIDTH - 1, HEIGHT - 1], fill=(30, 200, 60))
    return image


def exif() -> bytes:
    data = Image.Exif()
    data[ORIENTATION] = 6
    data[MAKE] = "samsung"
    data[MODEL] = "SM-S918B"
    gps = data.get_ifd(GPS_IFD)
    gps[GPS_LAT_REF] = "N"
    gps[GPS_LAT] = (37.0, 25.0, 19.0)
    gps[GPS_LON_REF] = "W"
    gps[GPS_LON] = (122.0, 5.0, 2.0)
    return data.tobytes()


def main() -> int:
    OUT.parent.mkdir(parents=True, exist_ok=True)
    heif = pillow_heif.from_pillow(picture())
    heif.info["exif"] = exif()
    buffer = io.BytesIO()
    # pillow-heif hands the EXIF orientation to libheif, which writes it as an `irot` box (Apple's layout). Tell the
    # encoder the picture is upright so the EXIF tag is the only thing that turns it.
    heif_module.__dict__["_get_orientation_for_encoder"] = lambda info: 1
    heif.save(buffer, quality=80, save_to_12bit=False)
    OUT.write_bytes(buffer.getvalue())

    back = pillow_heif.open_heif(OUT, convert_hdr_to_8bit=True)
    stored = (back.size[0], back.size[1])
    raw = OUT.read_bytes()
    if b"irot" in raw or b"imir" in raw:
        print(f"{OUT}: libheif wrote a transform box; the fixture would not test the EXIF turn", file=sys.stderr)
        return 1
    if stored != (WIDTH, HEIGHT):
        print(f"{OUT}: stored {stored}, wanted {(WIDTH, HEIGHT)}", file=sys.stderr)
        return 1
    print(f"{OUT}: {len(raw)} B, stored {stored[0]} x {stored[1]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
