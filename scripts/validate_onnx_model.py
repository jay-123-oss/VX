from pathlib import Path
import sys

import numpy as np
import onnxruntime as ort
from onnxruntime_extensions import get_library_path
from PIL import Image

model_path = Path(sys.argv[1])
image_path = Path(sys.argv[2])
options = ort.SessionOptions()
options.register_custom_ops_library(get_library_path())
session = ort.InferenceSession(str(model_path), sess_options=options, providers=["CPUExecutionProvider"])
image_bytes = image_path.read_bytes()
input_meta = session.get_inputs()[0]
output_meta = session.get_outputs()
result = session.run(None, {input_meta.name: np.frombuffer(image_bytes, dtype=np.uint8)})
print("input_name=", input_meta.name)
print("input_type=", input_meta.type)
print("outputs=", [(item.name, item.type, item.shape) for item in output_meta])
print("result_types=", [type(item).__name__ for item in result])
if len(result) > 1:
    boxes = result[1]
    print("box_count=", len(boxes))
    if len(boxes):
        print("first_box=", boxes[0].tolist())
print("image_size=", Image.open(image_path).size)
