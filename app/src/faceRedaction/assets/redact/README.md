# On-device face detector

`face_detection_yunet_2026may.tflite` is the face detector `redact/YuNetFaceDetector` runs on a photo being sent
(ADR 2026-10.whkk). Loaded locally at runtime; nothing is downloaded.

**YuNet** (opencv_zoo, `models/face_detection_yunet` at commit `eb9f4f2d4b79b58e5513b5d25d5f0243a8357bf4`): the
2026may ONNX, whose input height and width are dynamic, converted to TFLite by onnx2tf 2.6.9 (`flatbuffer_direct`,
no TensorFlow). Input `[1, H, W, 3]` float32, BGR, 0 to 255 unscaled, H and W multiples of 32; outputs `cls_*`,
`obj_*`, `bbox_*` and `kps_*` per stride 8, 16 and 32. fp32, 239 KB, stored as an ordinary Git blob (no LFS, see
`.gitattributes`) and uncompressed in the APK (`noCompress "tflite"`) so LiteRT can map it.

**Generated file, do not edit by hand.** `scripts/face-model.py build` reproduces it byte for byte from the pinned
ONNX, and `scripts/face-model.py` exits 1 unless it is that build (SHA-256
`708ad1b2d60407e2eaac5cbbfb76102c79a1e5a51b2b5a3076ad201236ec980b`).

## Attribution

Source: <https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet>

```
MIT License

Copyright (c) 2020 Shiqi Yu <shiqi.yu@gmail.com>

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
