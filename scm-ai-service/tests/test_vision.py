import asyncio

import cv2
import numpy as np
import pytest

from app import vision


def _png(image: np.ndarray) -> bytes:
    ok, buf = cv2.imencode(".png", image)
    assert ok
    return buf.tobytes()


def _qr_image(text: str) -> np.ndarray:
    qr = cv2.QRCodeEncoder.create().encode(text)
    qr = cv2.resize(qr, (qr.shape[1] * 8, qr.shape[0] * 8), interpolation=cv2.INTER_NEAREST)
    canvas = np.full((qr.shape[0] + 80, qr.shape[1] + 80), 255, np.uint8)
    canvas[40:40 + qr.shape[0], 40:40 + qr.shape[1]] = qr
    return cv2.cvtColor(canvas, cv2.COLOR_GRAY2BGR)


@pytest.fixture(autouse=True)
def no_yolo(monkeypatch):
    monkeypatch.setattr(vision, "_yolo", lambda: None)


def test_reads_shipment_qr_label():
    codes = vision.read_codes(_qr_image("TRK-DEMO000002"))
    assert codes == ["TRK-DEMO000002"]


def test_clean_image_is_not_damaged():
    result = asyncio.run(vision.inspect(_png(_qr_image("TRK-DEMO000001"))))
    assert not result.damaged
    assert result.codes == ["TRK-DEMO000001"]
    assert "no damage detected" in result.summary
    assert result.annotated_image


def test_damage_class_from_detector_flags_damage(monkeypatch):
    monkeypatch.setattr(vision, "detect_objects", lambda _: [
        vision.Detection(label="box", confidence=0.91, box=[10, 10, 100, 100]),
        vision.Detection(label="dent", confidence=0.77, box=[20, 20, 60, 60]),
    ])
    result = asyncio.run(vision.inspect(_png(np.full((200, 200, 3), 200, np.uint8))))
    assert result.damaged
    assert result.item_counts == {"box": 1, "dent": 1}
    assert "detector found dent" in result.summary


def test_rejects_non_image():
    with pytest.raises(ValueError):
        asyncio.run(vision.inspect(b"not an image"))
