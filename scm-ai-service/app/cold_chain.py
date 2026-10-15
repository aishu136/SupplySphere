"""Computer vision for the cold food chain (reefer trailers, cold rooms, frozen/chilled receiving).

Two kinds of image, one verdict against a temperature profile (frozen, chilled, cool):
  1. Thermal camera frames - each pixel's colour is mapped back to a temperature through the
     camera's palette and scale; regions outside the profile's range become hot/cold spots
     (door seal leaks, failed evaporator fans, pallets left on the dock, freezing injury).
  2. Ordinary dock photos - a detector trained with cold-chain classes (frost, ice, condensation,
     thawed, mold...) plus an optional Claude review that reads data-logger and thermometer
     displays, time-temperature indicator labels and use-by dates. Displayed temperatures are
     checked against the profile in code, not by the model.
"""
import base64
from functools import lru_cache
from typing import Literal

import cv2
import numpy as np
from langchain_core.messages import HumanMessage
from langsmith import traceable
from pydantic import BaseModel, Field

from app import vision
from app.config import get_settings
from app.llm import get_chat_model

ProfileName = Literal["frozen", "chilled", "cool"]
Palette = Literal["grayscale", "inferno", "jet", "turbo"]


class TemperatureProfile(BaseModel):
    name: ProfileName
    min_c: float
    max_c: float
    description: str


PROFILES: dict[str, TemperatureProfile] = {
    "frozen": TemperatureProfile(name="frozen", min_c=-30, max_c=-18,
                                 description="Frozen meat, fish, vegetables, ice cream"),
    "chilled": TemperatureProfile(name="chilled", min_c=0, max_c=4,
                                  description="Fresh meat, dairy, ready meals"),
    "cool": TemperatureProfile(name="cool", min_c=8, max_c=15,
                               description="Chill-sensitive produce (bananas, tomatoes), chocolate"),
}

_COLORMAPS = {"inferno": cv2.COLORMAP_INFERNO, "jet": cv2.COLORMAP_JET, "turbo": cv2.COLORMAP_TURBO}
_LEVELS = 32  # colours are quantised to 32 levels per channel for the palette lookup


class Spot(BaseModel):
    kind: Literal["hot", "cold"]
    peak_c: float
    area_pct: float
    box: list[int]  # x1, y1, x2, y2


class ThermalReport(BaseModel):
    min_c: float
    max_c: float
    mean_c: float
    p95_c: float
    above_range_pct: float
    below_range_pct: float
    spots: list[Spot]


class ColdChainLlmAssessment(BaseModel):
    """Cold-chain review of a photo of chilled or frozen goods."""

    displayed_temperatures_c: list[float] = Field(
        description="Temperatures shown on any visible thermometer, data logger or reefer display, in Celsius")
    indicator_status: Literal["none_visible", "ok", "triggered", "unclear"] = Field(
        description="State of any time-temperature or freeze indicator label")
    frost_or_ice: bool = Field(description="Ice crystals or frost on product or inner packaging (thaw/refreeze sign)")
    condensation: bool = Field(description="Water droplets, pooling or wet cartons")
    spoilage_signs: bool = Field(description="Mold, discoloration, leaking or swollen packaging")
    dates_read: list[str] = Field(description="Use-by / best-before dates printed on labels, as written")
    findings: list[str] = Field(description="Specific observations")
    recommended_action: str


class ColdChainResult(BaseModel):
    breach: bool
    severity: Literal["none", "minor", "major", "critical"]
    profile: TemperatureProfile
    summary: str
    thermal: ThermalReport | None = None
    detections: list[vision.Detection] = []
    codes: list[str] = []
    llm_assessment: ColdChainLlmAssessment | None = None
    annotated_image: str | None = None


def cold_chain_labels() -> set[str]:
    return {label.strip().lower() for label in get_settings().cold_chain_labels.split(",") if label.strip()}


@lru_cache
def _palette_lookup(palette: str) -> np.ndarray:
    """(32, 32, 32) table: quantised BGR colour -> nearest palette index 0..255 (cold..hot)."""
    ramp = np.arange(256, dtype=np.uint8).reshape(-1, 1)
    colors = cv2.applyColorMap(ramp, _COLORMAPS[palette]).reshape(256, 3).astype(np.float32)
    step = 256 // _LEVELS
    centers = np.arange(_LEVELS, dtype=np.float32) * step + step / 2
    grid = np.stack(np.meshgrid(centers, centers, centers, indexing="ij"), axis=-1).reshape(-1, 3)
    nearest = np.empty(len(grid), dtype=np.uint8)
    for start in range(0, len(grid), 4096):  # chunked to keep the distance matrix small
        chunk = grid[start:start + 4096]
        nearest[start:start + 4096] = np.argmin(((chunk[:, None, :] - colors[None]) ** 2).sum(-1), axis=1)
    return nearest.reshape(_LEVELS, _LEVELS, _LEVELS)


def to_temperatures(image: np.ndarray, scale_min_c: float, scale_max_c: float, palette: Palette) -> np.ndarray:
    """Per-pixel temperature (Celsius) of a rendered thermal frame, given its palette and scale."""
    if scale_max_c <= scale_min_c:
        raise ValueError("Thermal scale maximum must be above the minimum")
    if palette == "grayscale":  # white-hot
        index = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY).astype(np.float32)
    elif palette in _COLORMAPS:
        q = (image // (256 // _LEVELS)).astype(np.intp)
        index = _palette_lookup(palette)[q[..., 0], q[..., 1], q[..., 2]].astype(np.float32)
    else:
        raise ValueError(f"Unknown thermal palette {palette!r}")
    return scale_min_c + index / 255.0 * (scale_max_c - scale_min_c)


def _spots(temps: np.ndarray, mask: np.ndarray, kind: str, reduce) -> list[Spot]:
    total = temps.size
    min_area = total * get_settings().thermal_min_spot_pct / 100
    mask = cv2.morphologyEx(mask.astype(np.uint8), cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    count, labels, stats, _ = cv2.connectedComponentsWithStats(mask, connectivity=8)
    spots = []
    for i in range(1, count):
        x, y, w, h, area = stats[i]
        if area < min_area:
            continue
        spots.append(Spot(kind=kind, peak_c=round(float(reduce(temps[labels == i])), 1),
                          area_pct=round(100 * area / total, 2), box=[int(x), int(y), int(x + w), int(y + h)]))
    return sorted(spots, key=lambda s: s.area_pct, reverse=True)


def analyze_thermal(image: np.ndarray, profile: TemperatureProfile, scale_min_c: float, scale_max_c: float,
                    palette: Palette = "inferno") -> ThermalReport:
    temps = to_temperatures(image, scale_min_c, scale_max_c, palette)
    tolerance = get_settings().thermal_tolerance_c
    hot = temps > profile.max_c + tolerance
    cold = temps < profile.min_c - tolerance
    return ThermalReport(
        min_c=round(float(temps.min()), 1),
        max_c=round(float(temps.max()), 1),
        mean_c=round(float(temps.mean()), 1),
        p95_c=round(float(np.percentile(temps, 95)), 1),
        above_range_pct=round(100 * float(hot.mean()), 2),
        below_range_pct=round(100 * float(cold.mean()), 2),
        spots=_spots(temps, hot, "hot", np.max) + _spots(temps, cold, "cold", np.min),
    )


def _annotate_thermal(image: np.ndarray, report: ThermalReport) -> str:
    canvas = image.copy()
    for s in report.spots:
        color = (0, 0, 255) if s.kind == "hot" else (255, 160, 0)
        x1, y1, x2, y2 = s.box
        cv2.rectangle(canvas, (x1, y1), (x2, y2), color, 2)
        cv2.putText(canvas, f"{s.kind} {s.peak_c:.1f}C", (x1, max(y1 - 6, 12)),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.5, color, 1, cv2.LINE_AA)
    _, buf = cv2.imencode(".jpg", canvas, [cv2.IMWRITE_JPEG_QUALITY, 85])
    return base64.b64encode(buf.tobytes()).decode()


async def assess_with_llm(data: bytes, mime_type: str, profile: TemperatureProfile) -> ColdChainLlmAssessment:
    model = get_chat_model().with_structured_output(ColdChainLlmAssessment)
    b64 = base64.b64encode(data).decode()
    message = HumanMessage(content=[
        {"type": "text", "text": (
            f"You are inspecting {profile.name} food ({profile.description}) at a cold-chain receiving dock. "
            f"It must be kept between {profile.min_c} and {profile.max_c} °C. Read any thermometer, data logger "
            "or reefer unit display (convert Fahrenheit to Celsius), the state of any time-temperature or freeze "
            "indicator label, and any use-by dates. Look for frost or ice crystals inside packaging, "
            "condensation, wet or collapsed cartons, leaks and spoilage.")},
        {"type": "image_url", "image_url": {"url": f"data:{mime_type};base64,{b64}"}},
    ])
    return await model.ainvoke([message])


def _severity(hot_area_pct: float, excursion_c: float, signs: int) -> str:
    if excursion_c >= 5 or hot_area_pct >= 10:
        return "critical"
    if excursion_c >= 2 or hot_area_pct >= 2 or signs >= 2:
        return "major"
    return "minor"


def trace_inputs(inputs: dict) -> dict:
    data = inputs.get("data") or b""
    return {"image_bytes": len(data), **{k: v for k, v in inputs.items() if k not in ("data",)}}


def trace_outputs(result) -> dict:
    if isinstance(result, ColdChainResult):
        return result.model_dump(exclude={"annotated_image"})
    return {"output": str(result)[:500]}


@traceable(name="cold_chain_inspection", run_type="chain", tags=["vision", "cold-chain"],
           process_inputs=trace_inputs, process_outputs=trace_outputs)
async def inspect(data: bytes, profile: str = "chilled", mime_type: str = "image/jpeg", thermal: bool = False,
                  scale_min_c: float | None = None, scale_max_c: float | None = None, palette: Palette = "inferno",
                  use_llm: bool = False) -> ColdChainResult:
    if profile not in PROFILES:
        raise ValueError(f"Unknown temperature profile {profile!r}; use one of {', '.join(PROFILES)}")
    prof = PROFILES[profile]
    image = vision.decode_image(data)
    parts: list[str] = []
    excursion = 0.0  # worst degrees outside the range, from any source
    hot_area = 0.0
    signs = 0

    report = None
    detections: list[vision.Detection] = []
    codes: list[str] = []
    if thermal:
        if scale_min_c is None or scale_max_c is None:
            raise ValueError("A thermal image needs the camera scale (scale_min_c and scale_max_c)")
        report = analyze_thermal(image, prof, scale_min_c, scale_max_c, palette)
        hot_spots = [s for s in report.spots if s.kind == "hot"]
        cold_spots = [s for s in report.spots if s.kind == "cold"]
        hot_area = sum(s.area_pct for s in hot_spots)
        if hot_spots:
            excursion = max(excursion, max(s.peak_c for s in hot_spots) - prof.max_c)
            parts.append(f"{len(hot_spots)} hot spot(s) up to {max(s.peak_c for s in hot_spots):.1f} °C "
                         f"over {hot_area:.1f}% of the frame")
        if cold_spots:
            excursion = max(excursion, prof.min_c - min(s.peak_c for s in cold_spots))
            parts.append(f"{len(cold_spots)} cold spot(s) down to {min(s.peak_c for s in cold_spots):.1f} °C")
        annotated = _annotate_thermal(image, report)
    else:
        detections = vision.detect_objects(image)
        codes = vision.read_codes(image)
        hits = sorted({d.label for d in detections if d.label.lower() in cold_chain_labels()})
        if hits:
            signs += len(hits)
            parts.append("detector found " + ", ".join(hits))
        if codes:
            parts.append("labels: " + ", ".join(codes))
        annotated = vision.annotate(image, detections)

    assessment = await assess_with_llm(data, mime_type, prof) if use_llm else None
    if assessment:
        out_of_range = [t for t in assessment.displayed_temperatures_c if not prof.min_c <= t <= prof.max_c]
        for t in out_of_range:
            excursion = max(excursion, t - prof.max_c, prof.min_c - t)
        if out_of_range:
            parts.append("display reads " + ", ".join(f"{t:.1f} °C" for t in out_of_range))
        if assessment.indicator_status == "triggered":
            signs += 2  # a tripped indicator is direct evidence of an excursion
            parts.append("temperature indicator triggered")
        for flag, text in ((assessment.frost_or_ice, "frost/ice crystals"),
                           (assessment.condensation, "condensation"),
                           (assessment.spoilage_signs, "spoilage signs")):
            if flag:
                signs += 1
                parts.append(text)

    breach = excursion > 0 or signs > 0
    severity = _severity(hot_area, excursion, signs) if breach else "none"
    if not breach:
        parts.insert(0, f"within {prof.name} range ({prof.min_c} to {prof.max_c} °C)")
    else:
        parts.insert(0, f"{severity} cold-chain breach for {prof.name} goods")

    return ColdChainResult(
        breach=breach,
        severity=severity,
        profile=prof,
        summary="; ".join(parts),
        thermal=report,
        detections=detections,
        codes=codes,
        llm_assessment=assessment,
        annotated_image=annotated,
    )
