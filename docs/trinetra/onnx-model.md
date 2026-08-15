# Offline object detection model

The current milestone bundles `app/src/main/assets/models/yolov8n_with_pre_post_processing.onnx`, the pre/post-processing YOLOv8 model used by the official ONNX Runtime Android object-detection example. It is loaded lazily only after the user presses **वस्तु खोजें**. No frame is uploaded to a server.

The model accepts a `uint8` encoded image through the `image` input and returns `scaled_box_out_next` rows in the form `[center_x, center_y, width, height, confidence, class_id]`, plus the post-processed image output. The Android app registers the ONNX Runtime Extensions custom-op library because the model includes image decode/pre-processing operators.

The bundled model uses a fixed COCO vocabulary, not true open-vocabulary YOLO-World prompts. Hindi aliases such as `व्यक्ति`, `बोतल`, `कुर्सी`, `कार`, and `पानी` are mapped to the available COCO labels. A future YOLO-World model must be exported with a frozen offline vocabulary and tested against the same Android output contract before replacing this asset.

Source model example and runtime contract: https://github.com/microsoft/onnxruntime-inference-examples/tree/main/mobile/examples/object_detection/android

Model SHA-256:

`09891302b98beff8ed17a94a9f6b3c5d6ff2297c5c8192c97041e7c9796da89a`

The model and any downstream use must be reviewed against the applicable upstream model/runtime licenses before public or commercial distribution.
