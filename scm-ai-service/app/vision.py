"""Computer vision for dock / receiving inspection.

Three complementary signals on one photo of an inbound package or pallet:
  1. Object detection (Ultralytics YOLO) - counts items; with custom-trained weights whose classes
     include damage labels (dent, tear, crushed, wet...) it also flags damage.
  2. Label reading (OpenCV) - decodes QR codes and 1D barcodes to match the shipment.
  3. Optional multimodal review by Claude on Bedrock - a structured damage assessment for cases a
     detector wasn't trained for.
"""
import base64
import logging
from collections import Counter
from functools import lru_cache
from typing import Any, Literal

import cv2
import numpy as np
from langchain_core.messages import HumanMessage
from langsmith import traceable
from pydantic import BaseModel, Field

from app.config import get_settings
from app.llm import get_chat_model

log = logging.getLogger(__name__)


class Detection(BaseModel):
    label: str
    confidence: float
    box: list[int]  # x1, y1, x2, y2


class LlmAssessment(BaseModel):
    """Visual damage assessment of a shipment photo."""

    damaged: bool = Field(description="True if the packaging or goods show any visible damage")
    severity: Literal["none", "minor", "major", "critical"]
    findings: list[str] = Field(description="Specific observations, e.g. 'crushed corner on top-left carton'")
    recommended_action: str = Field(description="What the receiving team should do next")


class InspectionResult(BaseModel):
    damaged: bool
    summary: str
    item_counts: dict[str, int]
    detections: list[Detection]
    codes: list[str]
    llm_assessment: LlmAssessment | None = None
    annotated_image: str | None = None  # base64 JPEG with boxes drawn


def decode_image(data: bytes) -> np.ndarray:
    image = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        raise ValueError("Unsupported or corrupt image")
    return image


@lru_cache
def _yolo():
    try:
        from ultralytics import YOLO

        return YOLO(get_settings().yolo_model)
    except Exception:  # weights missing / no network: detection is skipped, other signals still run
        log.exception("YOLO model unavailable; object detection disabled")
        return None


def detect_objects(image: np.ndarray) -> list[Detection]:
    model = _yolo()
    if model is None:
        return []
    result = model.predict(image, conf=get_settings().vision_confidence, verbose=False)[0]
    return [
        Detection(
            label=result.names[int(box.cls)],
            confidence=round(float(box.conf), 3),
            box=[int(v) for v in box.xyxy[0].tolist()],
        )
        for box in result.boxes
    ]


def read_codes(image: np.ndarray) -> list[str]:
    codes: list[str] = []
    ok, decoded, _, _ = cv2.QRCodeDetector().detectAndDecodeMulti(image)
    if ok:
        codes.extend(d for d in decoded if d)
    if hasattr(cv2, "barcode"):
        try:
            result = cv2.barcode.BarcodeDetector().detectAndDecodeWithType(image)
            ok, decoded = result[0], result[1]
            if ok:
                codes.extend(d for d in decoded if d)
        except cv2.error:
            log.debug("1D barcode decoding failed", exc_info=True)
    return list(dict.fromkeys(codes))


def damage_labels() -> set[str]:
    return {label.strip().lower() for label in get_settings().damage_labels.split(",") if label.strip()}


def annotate(image: np.ndarray, detections: list[Detection]) -> str:
    canvas = image.copy()
    damage = damage_labels()
    for d in detections:
        color = (0, 0, 230) if d.label.lower() in damage else (0, 170, 0)
        x1, y1, x2, y2 = d.box
        cv2.rectangle(canvas, (x1, y1), (x2, y2), color, 2)
        cv2.putText(canvas, f"{d.label} {d.confidence:.2f}", (x1, max(y1 - 6, 12)),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.5, color, 1, cv2.LINE_AA)
    _, buf = cv2.imencode(".jpg", canvas, [cv2.IMWRITE_JPEG_QUALITY, 85])
    return base64.b64encode(buf.tobytes()).decode()


async def assess_with_llm(data: bytes, mime_type: str) -> LlmAssessment:
    model = get_chat_model().with_structured_output(LlmAssessment)
    b64 = base64.b64encode(data).decode()
    message = HumanMessage(content=[
        {"type": "text", "text": "You are inspecting an inbound shipment photo at a receiving dock. "
                                 "Assess the packaging and any visible goods for damage."},
        {"type": "image_url", "image_url": {"url": f"data:{mime_type};base64,{b64}"}},
    ])
    return await model.ainvoke([message])


def trace_inputs(inputs: dict) -> dict:
    """LangSmith view of inspect(): the photo's size, not its bytes."""
    data = inputs.get("data") or b""
    return {"image_bytes": len(data), "mime_type": inputs.get("mime_type"), "use_llm": inputs.get("use_llm")}


def trace_outputs(result: Any) -> dict:
    """LangSmith view of the result, without the base64 annotated image."""
    if isinstance(result, InspectionResult):
        return result.model_dump(exclude={"annotated_image"})
    return {"output": str(result)[:500]}


@traceable(name="vision_inspection", run_type="chain", tags=["vision"],
           process_inputs=trace_inputs, process_outputs=trace_outputs)
async def inspect(data: bytes, mime_type: str = "image/jpeg", use_llm: bool = False) -> InspectionResult:
    image = decode_image(data)
    detections = detect_objects(image)
    codes = read_codes(image)
    counts = dict(Counter(d.label for d in detections))
    damage_hits = [d for d in detections if d.label.lower() in damage_labels()]

    assessment = await assess_with_llm(data, mime_type) if use_llm else None
    damaged = bool(damage_hits) or bool(assessment and assessment.damaged)

    parts = []
    if damage_hits:
        parts.append("detector found " + ", ".join(sorted({d.label for d in damage_hits})))
    if assessment and assessment.damaged:
        parts.append(f"{assessment.severity} damage: " + "; ".join(assessment.findings))
    if not damaged:
        parts.append("no damage detected")
    if counts:
        parts.append("items: " + ", ".join(f"{n} {label}" for label, n in counts.items()))
    if codes:
        parts.append("labels: " + ", ".join(codes))

    return InspectionResult(
        damaged=damaged,
        summary="; ".join(parts),
        item_counts=counts,
        detections=detections,
        codes=codes,
        llm_assessment=assessment,
        annotated_image=annotate(image, detections),
    )
