"""PaddleOCR HTTP 适配层。

模型采用进程内惰性单例：健康检查不会触发模型下载，第一次 OCR 请求才初始化。
服务日志只记录 requestId 与错误码，不记录图片 Base64 和识别正文。
"""

import base64
import binascii
from io import BytesIO
import os
from threading import Lock

try:
    from fastapi import FastAPI, HTTPException
except ImportError:  # 允许不安装运行时依赖时执行纯契约单测
    FastAPI = None
    HTTPException = RuntimeError


MAX_IMAGE_BYTES = int(os.getenv("OCR_MAX_IMAGE_BYTES", str(20 * 1024 * 1024)))
MAX_IMAGE_PIXELS = int(os.getenv("OCR_MAX_IMAGE_PIXELS", "40000000"))
_engine = None
_engine_lock = Lock()


class PaddleEngine:
    """隔离 PaddleOCR 版本差异，API 层只消费稳定的块结构。"""

    def __init__(self):
        from paddleocr import PaddleOCR

        self._ocr = PaddleOCR(use_doc_orientation_classify=True, lang="ch")

    def recognize(self, image_bytes, language):
        import numpy as np
        from PIL import Image

        image = np.asarray(Image.open(BytesIO(image_bytes)).convert("RGB"))
        raw = self._ocr.ocr(image)
        blocks = []
        for page in raw or []:
            for line in page or []:
                if not isinstance(line, (list, tuple)) or len(line) < 2:
                    continue
                box, recognition = line[0], line[1]
                text, confidence = recognition[0], float(recognition[1])
                blocks.append({"text": text, "confidence": confidence, "box": box})
        return 0, blocks


def get_engine():
    global _engine
    if _engine is None:
        with _engine_lock:
            if _engine is None:
                _engine = PaddleEngine()
    return _engine


def _decode_image(encoded, max_image_bytes):
    if not isinstance(encoded, str) or not encoded:
        raise ValueError("INVALID_IMAGE_BASE64")
    if len(encoded) > ((max_image_bytes + 2) // 3) * 4 + 4:
        raise ValueError("IMAGE_TOO_LARGE")
    try:
        image = base64.b64decode(encoded, validate=True)
    except (binascii.Error, ValueError) as error:
        raise ValueError("INVALID_IMAGE_BASE64") from error
    if not image:
        raise ValueError("INVALID_IMAGE")
    if len(image) > max_image_bytes:
        raise ValueError("IMAGE_TOO_LARGE")
    return image


def _validate_pixels(image_bytes, max_pixels):
    if max_pixels is None:
        return
    try:
        from PIL import Image

        with Image.open(BytesIO(image_bytes)) as image:
            width, height = image.size
            if width <= 0 or height <= 0:
                raise ValueError("INVALID_IMAGE")
            if width * height > max_pixels:
                raise ValueError("IMAGE_PIXELS_EXCEEDED")
            image.verify()
    except ValueError:
        raise
    except Exception as error:
        raise ValueError("INVALID_IMAGE") from error


def recognize_payload(payload, engine, max_image_bytes=MAX_IMAGE_BYTES, max_pixels=None):
    request_id = payload.get("requestId")
    if not isinstance(request_id, str) or not request_id.strip():
        raise ValueError("REQUEST_ID_REQUIRED")
    language = payload.get("language", "ch")
    image = _decode_image(payload.get("imageBase64"), max_image_bytes)
    _validate_pixels(image, max_pixels)
    rotation, blocks = engine.recognize(image, language)
    return {"requestId": request_id, "rotation": int(rotation), "blocks": blocks or []}


if FastAPI is not None:
    app = FastAPI(title="order-logistics-paddle-ocr", version="1.0.0")

    @app.get("/health")
    def health():
        return {"status": "UP"}

    @app.post("/v1/ocr")
    def recognize(payload: dict):
        try:
            return recognize_payload(payload, get_engine(), MAX_IMAGE_BYTES, MAX_IMAGE_PIXELS)
        except ValueError as error:
            raise HTTPException(status_code=400, detail={"code": str(error)}) from error
        except Exception as error:
            raise HTTPException(status_code=503, detail={"code": "OCR_ENGINE_FAILED"}) from error
else:
    app = None
