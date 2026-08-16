# TFLite model assets

`ObjectDetectorHelper` looks for `models/yolov8n-int8.tflite` and `models/coco_classes.txt` by default. The binary model is intentionally not committed to this repository; place a compatible offline model at that path before running live detection.

The helper accepts a quantized or float input tensor with a square `320 x 320 x 3` image shape. It supports the common YOLO output layout `[1, channels, anchors]` or `[1, anchors, channels]`, where the first four channels are `cx, cy, width, height`, followed by class scores. It also supports the standard four-output MobileNet SSD contract: locations, classes, scores, and number of detections.

For a different model, construct `ObjectDetectorHelper` with the custom `modelAssetPath`, `labelsAssetPath`, `inputSize`, and confidence threshold. Inference runs offline and attempts GPU acceleration first, then NNAPI, then CPU.
