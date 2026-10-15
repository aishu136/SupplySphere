import asyncio
from pathlib import Path

import cv2
import numpy as np
import pytest

from app import dock_doors
from app.config import get_settings

ROI = dock_doors.Roi(x=0.25, y=0.2, w=0.5, h=0.7)  # pixels 80..240 x 48..216 of a 320x240 frame
_DOOR = np.random.default_rng(7).integers(150, 220, (168, 160, 3), dtype=np.uint8)  # ribbed door texture


def frame(state: str = "closed", brightness: int = 0) -> np.ndarray:
    img = np.full((240, 320, 3), 120, np.uint8)
    if state == "closed":
        img[48:216, 80:240] = _DOOR
    elif state == "open":
        img[48:216, 80:240] = 25  # dark trailer interior
    elif state == "passerby":  # someone walks past the closed door
        img[48:216, 80:240] = _DOOR
        img[40:230, 100:200] = 60
    return cv2.add(img, np.full_like(img, brightness)) if brightness else img


def clip(script: list[tuple[float, str]], fps: float = 5.0):
    """script: (until second, state) segments -> (t, frame) at fps."""
    t = 0.0
    for until, state in script:
        while t < until:
            yield t, frame(state)
            t = round(t + 1 / fps, 4)


def test_times_openings_and_flags_the_long_one():
    script = [(10, "closed"), (40, "open"), (50, "closed"), (51, "passerby"), (60, "closed"), (200, "open"),
              (210, "closed")]
    r = dock_doors.analyze_frames(clip(script), "WH-EAST/D3", ROI, max_open_s=120)
    assert [(i.start_s, i.end_s, i.violation) for i in r.open_intervals] == [(10.0, 40.0, False), (60.0, 200.0, True)]
    assert r.open_count == 2 and r.violations == 1
    assert r.total_open_s == 170.0
    assert r.longest_open_s == 140.0
    assert r.keyframe  # frame from the longest opening
    assert len(r.timeline) == r.frames_analyzed


def test_brief_passerby_is_not_an_opening():
    r = dock_doors.analyze_frames(clip([(5, "closed"), (6, "passerby"), (12, "closed")]), "D1", ROI, 120)
    assert r.open_intervals == []


def test_door_still_open_at_end_of_clip_is_ongoing():
    r = dock_doors.analyze_frames(clip([(5, "closed"), (30, "open")]), "D1", ROI, max_open_s=10)
    [interval] = r.open_intervals
    assert interval.ongoing and interval.violation
    assert interval.start_s == 5.0


def test_gradual_lighting_change_is_not_an_opening():
    frames = ((i / 5, frame("closed", brightness=int(i * 40 / 300))) for i in range(300))  # +40 grey levels over 60 s
    r = dock_doors.analyze_frames(frames, "D1", ROI, 120)
    assert r.open_intervals == []


def test_reference_image_allows_clip_that_starts_open():
    r = dock_doors.analyze_frames(clip([(20, "open"), (30, "closed")]), "D1", ROI, 10, reference=frame("closed"))
    [interval] = r.open_intervals
    assert (interval.start_s, interval.end_s, interval.violation) == (0.0, 20.0, True)


def test_analyzes_an_encoded_video_file(tmp_path: Path):
    path = tmp_path / "dock.avi"
    writer = cv2.VideoWriter(str(path), cv2.VideoWriter_fourcc(*"MJPG"), 10, (320, 240))
    if not writer.isOpened():
        pytest.skip("No MJPG encoder in this OpenCV build")
    for _, f in clip([(5, "closed"), (20, "open"), (25, "closed")], fps=10):
        writer.write(f)
    writer.release()

    r = asyncio.run(dock_doors.analyze(path.read_bytes(), "D2", ROI, max_open_s=10, suffix=".avi"))
    [interval] = r.open_intervals
    assert abs(interval.start_s - 5) <= 0.5 and abs(interval.duration_s - 15) <= 1
    assert interval.violation


def test_rejects_garbage_video():
    with pytest.raises(ValueError):
        dock_doors.analyze_video(b"not a video", "D1", ROI, 120)


@pytest.mark.parametrize("source", ["/etc/passwd", "file:///tmp/x.mp4", "C:\\cams\\dock.mp4", "ftp://cam/1"])
def test_live_monitor_only_accepts_stream_urls(source):
    with pytest.raises(ValueError):
        dock_doors.validate_source(source)


def test_live_monitor_alerts_once_when_door_stays_open(monkeypatch):
    s = get_settings()
    monkeypatch.setattr(s, "dock_door_debounce_s", 0.1)
    monkeypatch.setattr(s, "dock_door_sample_fps", 50.0)
    alerts = []

    class FakeCamera:
        def __init__(self, source):
            self.reads = 0

        def isOpened(self):
            return True

        def read(self):
            self.reads += 1
            return True, frame("closed" if self.reads <= 5 else "open")

        def release(self):
            pass

    async def raise_alert(type_, severity, message, entity_id):
        alerts.append((type_, entity_id, message))
        return {}

    monkeypatch.setattr(dock_doors.cv2, "VideoCapture", FakeCamera)
    monkeypatch.setattr(dock_doors.scm_client, "raise_alert", raise_alert)

    async def flow():
        m = dock_doors.start_monitor(dock_doors.MonitorRequest(
            door_id="WH-EAST/D9", source="rtsp://cam/9", roi=ROI, max_open_s=0.5))
        for _ in range(100):  # up to 5 s
            await asyncio.sleep(0.05)
            if alerts:
                break
        await asyncio.sleep(0.3)  # an alert is raised once per opening, not on every frame
        view = next(v for v in dock_doors.list_monitors() if v["id"] == m["id"])
        dock_doors.stop_monitor(m["id"])
        return view

    view = asyncio.run(flow())
    assert len(alerts) == 1
    assert alerts[0][:2] == ("DOCK_DOOR_OPEN", "WH-EAST/D9")
    assert view["doorOpen"] and view["openings"] == 1 and view["alertsRaised"] == 1


def test_analyze_endpoint(tmp_path: Path):
    from fastapi.testclient import TestClient

    from app.main import app

    path = tmp_path / "dock.avi"
    writer = cv2.VideoWriter(str(path), cv2.VideoWriter_fourcc(*"MJPG"), 10, (320, 240))
    if not writer.isOpened():
        pytest.skip("No MJPG encoder in this OpenCV build")
    for _, f in clip([(3, "closed"), (10, "open"), (12, "closed")], fps=10):
        writer.write(f)
    writer.release()

    client = TestClient(app)  # no lifespan: Kafka and workflows are not needed here
    form = {"door_id": "D4", "roi_x": "0.25", "roi_y": "0.2", "roi_w": "0.5", "roi_h": "0.7", "max_open_s": "5"}
    resp = client.post("/ai/vision/dock-doors/analyze", data=form,
                       files={"file": ("dock.avi", path.read_bytes(), "video/x-msvideo")})
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["door_id"] == "D4" and body["violations"] == 1

    bad = client.post("/ai/vision/dock-doors/analyze", data={**form, "roi_w": "0"},
                      files={"file": ("dock.avi", path.read_bytes(), "video/x-msvideo")})
    assert bad.status_code == 400
