import base64
import importlib.util
from pathlib import Path
import sys
import unittest


MODULE_PATH = Path(__file__).parents[1] / "app" / "main.py"


class FakeEngine:
    def recognize(self, image_bytes, language):
        self.image_bytes = image_bytes
        self.language = language
        return 0, [
            {
                "text": "退款规则",
                "confidence": 0.98,
                "box": [[0, 0], [10, 0], [10, 10], [0, 10]],
            }
        ]


class OcrApiContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location("ocr_main", MODULE_PATH)
        cls.module = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = cls.module
        spec.loader.exec_module(cls.module)

    def test_recognize_maps_stable_contract(self):
        engine = FakeEngine()
        result = self.module.recognize_payload(
            {
                "requestId": "req-1",
                "language": "ch",
                "imageBase64": base64.b64encode(b"small-image").decode("ascii"),
            },
            engine,
            max_image_bytes=1024,
        )

        self.assertEqual("req-1", result["requestId"])
        self.assertEqual(0, result["rotation"])
        self.assertEqual("退款规则", result["blocks"][0]["text"])
        self.assertEqual(b"small-image", engine.image_bytes)

    def test_rejects_oversized_and_invalid_base64(self):
        with self.assertRaisesRegex(ValueError, "IMAGE_TOO_LARGE"):
            self.module.recognize_payload(
                {"requestId": "r", "language": "ch", "imageBase64": base64.b64encode(b"12").decode()},
                FakeEngine(),
                max_image_bytes=1,
            )
        with self.assertRaisesRegex(ValueError, "INVALID_IMAGE_BASE64"):
            self.module.recognize_payload(
                {"requestId": "r", "language": "ch", "imageBase64": "%%%"},
                FakeEngine(),
                max_image_bytes=10,
            )


if __name__ == "__main__":
    unittest.main()
