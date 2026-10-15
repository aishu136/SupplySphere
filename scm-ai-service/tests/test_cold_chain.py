import asyncio

import cv2
import numpy as np
import pytest

from app import cold_chain, vision


def _png(image: np.ndarray) -> bytes:
    ok, buf = cv2.imencode(".png", image)
    assert ok
    return buf.tobytes()


def _thermal_frame(base: int, hot: int | None = None, palette: int | None = cv2.COLORMAP_INFERNO) -> np.ndarray:
    """A 200x200 rendered thermal frame: uniform level `base`, optional 60x60 patch at level `hot`."""
    levels = np.full((200, 200), base, np.uint8)
    if hot is not None:
        levels[20:80, 100:160] = hot
    if palette is None:
        return cv2.cvtColor(levels, cv2.COLOR_GRAY2BGR)
    return cv2.applyColorMap(levels, palette)


def _level(temp_c: float, lo: float = -30, hi: float = 20) -> int:
    return round((temp_c - lo) / (hi - lo) * 255)


@pytest.fixture(autouse=True)
def no_yolo(monkeypatch):
    monkeypatch.setattr(vision, "_yolo", lambda: None)


@pytest.mark.parametrize("palette, cv_palette", [("inferno", cv2.COLORMAP_INFERNO), ("jet", cv2.COLORMAP_JET),
                                                ("grayscale", None)])
def test_palette_maps_back_to_temperature(palette, cv_palette):
    frame = _thermal_frame(_level(-20), palette=cv_palette)
    temps = cold_chain.to_temperatures(frame, -30, 20, palette)
    assert abs(float(temps.mean()) - (-20)) < 1.0


def test_frozen_load_within_range_passes():
    result = asyncio.run(cold_chain.inspect(_png(_thermal_frame(_level(-22))), "frozen", thermal=True,
                                            scale_min_c=-30, scale_max_c=20))
    assert not result.breach
    assert result.severity == "none"
    assert result.thermal.spots == []


def test_hot_spot_on_frozen_pallet_is_a_breach():
    frame = _thermal_frame(_level(-22), hot=_level(6))
    result = asyncio.run(cold_chain.inspect(_png(frame), "frozen", thermal=True, scale_min_c=-30, scale_max_c=20))
    assert result.breach
    assert result.severity == "critical"  # 24 °C over the limit
    [spot] = result.thermal.spots
    assert spot.kind == "hot"
    assert abs(spot.peak_c - 6) < 1.0
    assert spot.box == [100, 20, 160, 80]
    assert "hot spot" in result.summary


def test_cold_spot_on_chilled_goods_flags_freezing():
    frame = _thermal_frame(_level(2), hot=_level(-8))
    result = asyncio.run(cold_chain.inspect(_png(frame), "chilled", thermal=True, scale_min_c=-30, scale_max_c=20))
    assert result.breach
    assert [s.kind for s in result.thermal.spots] == ["cold"]


def test_thermal_needs_a_scale():
    with pytest.raises(ValueError):
        asyncio.run(cold_chain.inspect(_png(_thermal_frame(100)), "frozen", thermal=True))


def test_unknown_profile_rejected():
    with pytest.raises(ValueError):
        asyncio.run(cold_chain.inspect(_png(_thermal_frame(100)), "tropical"))


def test_frost_detection_on_photo_is_a_breach(monkeypatch):
    monkeypatch.setattr(vision, "detect_objects", lambda _: [
        vision.Detection(label="carton", confidence=0.9, box=[10, 10, 100, 100]),
        vision.Detection(label="frost", confidence=0.7, box=[20, 20, 60, 60]),
    ])
    result = asyncio.run(cold_chain.inspect(_png(np.full((200, 200, 3), 180, np.uint8)), "frozen"))
    assert result.breach
    assert "detector found frost" in result.summary


def test_display_reading_from_llm_is_checked_against_profile(monkeypatch):
    async def fake_llm(data, mime_type, profile):
        return cold_chain.ColdChainLlmAssessment(
            displayed_temperatures_c=[7.5], indicator_status="ok", frost_or_ice=False, condensation=False,
            spoilage_signs=False, dates_read=["2026-10-20"], findings=["logger display reads 7.5 °C"],
            recommended_action="Check the reefer log")

    monkeypatch.setattr(cold_chain, "assess_with_llm", fake_llm)
    result = asyncio.run(cold_chain.inspect(_png(np.full((200, 200, 3), 180, np.uint8)), "chilled", use_llm=True))
    assert result.breach
    assert result.severity == "major"  # 3.5 °C over the 4 °C limit
    assert "display reads 7.5 °C" in result.summary
