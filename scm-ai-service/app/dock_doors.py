"""Reefer dock door-open monitoring from video.

Every open minute on a reefer dock lets warm, humid air into the trailer and the cold dock. A camera
watching the door is enough to time it: the door region (ROI) is compared with a reference frame of the
closed door, and a debounced state machine turns the per-frame "changed area" into open/closed
intervals, so a person or forklift crossing the frame for a moment is not counted as an opening.

  - analyze_video(): a recorded clip -> open intervals, total and longest open time, violations.
  - Live monitors: the same DoorDetector fed from an RTSP/HTTP camera stream in a background thread;
    an open door past its limit raises a DOCK_DOOR_OPEN alert (once per opening).

The reference is the first frame (the door must be closed when the clip or stream starts) or an
uploaded still of the closed door. While the door is closed the reference slowly follows the image, so
gradual lighting changes are not mistaken for an opening.
"""
import asyncio
import base64
import logging
import tempfile
import threading
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse

import cv2
import numpy as np
from langsmith import traceable
from pydantic import BaseModel, Field

from app import scm_client
from app.config import get_settings

log = logging.getLogger(__name__)


class Roi(BaseModel):
    """Door region as fractions of the frame (0..1), so it does not depend on the camera resolution."""

    x: float = Field(ge=0, lt=1)
    y: float = Field(ge=0, lt=1)
    w: float = Field(gt=0, le=1)
    h: float = Field(gt=0, le=1)

    def pixels(self, shape: tuple[int, ...]) -> tuple[int, int, int, int]:
        height, width = shape[:2]
        x1, y1 = int(self.x * width), int(self.y * height)
        x2, y2 = min(width, int((self.x + self.w) * width)), min(height, int((self.y + self.h) * height))
        if x2 - x1 < 4 or y2 - y1 < 4:
            raise ValueError("Door region is too small")
        return x1, y1, x2, y2


class OpenInterval(BaseModel):
    start_s: float
    end_s: float
    duration_s: float
    violation: bool
    ongoing: bool = False  # still open when the clip ended


class DoorReport(BaseModel):
    door_id: str
    duration_s: float
    frames_analyzed: int
    open_intervals: list[OpenInterval]
    open_count: int
    total_open_s: float
    longest_open_s: float
    open_pct: float
    max_open_s: float
    violations: int
    timeline: list[tuple[float, float]]  # (time s, changed fraction of the door region), for charting
    keyframe: str | None = None  # base64 JPEG of the longest opening, door region outlined


@dataclass
class DoorDetector:
    """Frame-by-frame door state with debouncing. Feed frames in time order with update()."""

    roi: Roi
    reference: np.ndarray | None = None  # grayscale, blurred ROI of the closed door
    is_open: bool = False
    _candidate_since: float | None = None
    changed: float = 0.0

    def _prepare(self, frame: np.ndarray) -> np.ndarray:
        x1, y1, x2, y2 = self.roi.pixels(frame.shape)
        gray = cv2.cvtColor(frame[y1:y2, x1:x2], cv2.COLOR_BGR2GRAY)
        return cv2.GaussianBlur(gray, (5, 5), 0)

    def set_reference(self, frame: np.ndarray) -> None:
        self.reference = self._prepare(frame).astype(np.float32)

    def update(self, frame: np.ndarray, t: float) -> bool:
        """Returns True when the debounced state flipped on this frame."""
        s = get_settings()
        roi = self._prepare(frame)
        if self.reference is None or self.reference.shape != roi.shape:
            self.reference = roi.astype(np.float32)
        diff = cv2.absdiff(roi, cv2.convertScaleAbs(self.reference))
        self.changed = float((diff > s.dock_door_pixel_threshold).mean())
        looks_open = self.changed >= s.dock_door_open_fraction

        if not self.is_open and not looks_open:
            cv2.accumulateWeighted(roi.astype(np.float32), self.reference, s.dock_door_reference_rate)

        if looks_open == self.is_open:
            self._candidate_since = None
            return False
        if self._candidate_since is None:
            self._candidate_since = t
        if t - self._candidate_since >= s.dock_door_debounce_s:
            self.is_open = looks_open
            self._candidate_since = None
            return True
        return False

    @property
    def change_seen_at(self) -> float | None:
        """When the pending (not yet debounced) state change was first seen."""
        return self._candidate_since


def _keyframe(frame: np.ndarray, roi: Roi, label: str) -> str:
    canvas = frame.copy()
    x1, y1, x2, y2 = roi.pixels(frame.shape)
    cv2.rectangle(canvas, (x1, y1), (x2, y2), (0, 0, 255), 2)
    cv2.putText(canvas, label, (x1, max(y1 - 8, 14)), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 0, 255), 2, cv2.LINE_AA)
    _, buf = cv2.imencode(".jpg", canvas, [cv2.IMWRITE_JPEG_QUALITY, 80])
    return base64.b64encode(buf.tobytes()).decode()


def analyze_frames(frames, door_id: str, roi: Roi, max_open_s: float,
                   reference: np.ndarray | None = None) -> DoorReport:
    """frames: iterable of (time s, BGR frame), in time order."""
    detector = DoorDetector(roi)
    if reference is not None:
        detector.set_reference(reference)
    intervals: list[OpenInterval] = []
    timeline: list[tuple[float, float]] = []
    opened_at: float | None = None
    best: tuple[float, np.ndarray | None] = (-1.0, None)  # longest opening so far and a frame from it
    count, t, first_t = 0, 0.0, None

    for t, frame in frames:
        first_t = t if first_t is None else first_t
        count += 1
        seen = detector.change_seen_at
        if detector.update(frame, t):
            seen = t if seen is None else seen  # the change began when first seen, not after the debounce
            if detector.is_open:
                opened_at = seen
            else:
                duration = seen - opened_at
                intervals.append(OpenInterval(start_s=round(opened_at, 1), end_s=round(seen, 1),
                                              duration_s=round(duration, 1), violation=duration > max_open_s))
                opened_at = None
        if detector.is_open and opened_at is not None and t - opened_at > best[0]:
            best = (t - opened_at, frame)
        timeline.append((round(t, 2), round(detector.changed, 3)))

    if count == 0:
        raise ValueError("No frames could be read from the video")
    if detector.is_open and opened_at is not None:
        duration = t - opened_at
        intervals.append(OpenInterval(start_s=round(opened_at, 1), end_s=round(t, 1), duration_s=round(duration, 1),
                                      violation=duration > max_open_s, ongoing=True))

    span = max(t - (first_t or 0.0), 1e-9)
    total = sum(i.duration_s for i in intervals)
    longest = max((i.duration_s for i in intervals), default=0.0)
    return DoorReport(
        door_id=door_id,
        duration_s=round(span, 1),
        frames_analyzed=count,
        open_intervals=intervals,
        open_count=len(intervals),
        total_open_s=round(total, 1),
        longest_open_s=round(longest, 1),
        open_pct=round(100 * total / span, 1),
        max_open_s=max_open_s,
        violations=sum(i.violation for i in intervals),
        timeline=timeline,
        keyframe=_keyframe(best[1], roi, f"open {best[0]:.0f}s") if best[1] is not None else None,
    )


def _sampled_frames(capture: cv2.VideoCapture, sample_fps: float):
    """Yields (video time s, frame), skipping frames to about sample_fps."""
    fps = capture.get(cv2.CAP_PROP_FPS) or 0
    if fps <= 0 or fps > 240:
        fps = 25.0
    step = max(1, round(fps / sample_fps))
    index = 0
    while True:
        ok, frame = capture.read()
        if not ok:
            return
        if index % step == 0:
            yield index / fps, frame
        index += 1


def analyze_video(data: bytes, door_id: str, roi: Roi, max_open_s: float,
                  reference: np.ndarray | None = None, suffix: str = ".mp4") -> DoorReport:
    if len(data) > get_settings().dock_door_max_upload_mb * 1024 * 1024:
        raise ValueError(f"Video is larger than {get_settings().dock_door_max_upload_mb} MB")
    # OpenCV reads video from a path, not from memory.
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / f"clip{suffix if suffix.startswith('.') else '.mp4'}"
        path.write_bytes(data)
        capture = cv2.VideoCapture(str(path))
        try:
            if not capture.isOpened():
                raise ValueError("Unsupported or corrupt video")
            return analyze_frames(_sampled_frames(capture, get_settings().dock_door_sample_fps),
                                  door_id, roi, max_open_s, reference)
        finally:
            capture.release()


@traceable(name="dock_door_video_analysis", run_type="chain", tags=["vision", "cold-chain", "dock-door"],
           process_inputs=lambda i: {k: v for k, v in i.items() if k not in ("data", "reference")},
           process_outputs=lambda r: r.model_dump(exclude={"keyframe", "timeline"}) if isinstance(r, DoorReport) else {})
async def analyze(data: bytes, door_id: str, roi: Roi, max_open_s: float, reference: np.ndarray | None = None,
                  suffix: str = ".mp4", raise_alerts: bool = False) -> DoorReport:
    report = await asyncio.to_thread(analyze_video, data, door_id, roi, max_open_s, reference, suffix)
    if raise_alerts and report.violations:
        worst = max(i.duration_s for i in report.open_intervals if i.violation)
        await scm_client.raise_alert(
            "DOCK_DOOR_OPEN", "HIGH",
            f"Reefer dock door {door_id} was open {report.violations} time(s) longer than {max_open_s:.0f}s "
            f"in the recorded clip (longest {worst:.0f}s)", door_id)
    return report


# ---------------------------------------------------------------------------- live monitors

ALLOWED_SCHEMES = {"rtsp", "rtsps", "http", "https"}


class MonitorRequest(BaseModel):
    door_id: str = Field(min_length=1, max_length=64)
    source: str = Field(description="Camera stream URL (rtsp://, rtsps://, http(s)://)")
    roi: Roi
    max_open_s: float = Field(default=120, gt=0, le=3600)


@dataclass
class Monitor:
    id: str
    request: MonitorRequest
    started_at: str
    status: str = "starting"  # starting | running | reconnecting | stopped | failed
    door_open: bool = False
    open_since: float | None = None  # wall-clock epoch seconds
    openings: int = 0
    alerts_raised: int = 0
    last_changed: float = 0.0
    error: str | None = None
    _stop: threading.Event = field(default_factory=threading.Event)
    _alerted_this_opening: bool = False

    def view(self) -> dict:
        now = time.time()
        return {
            "id": self.id, "doorId": self.request.door_id, "source": self.request.source,
            "roi": self.request.roi.model_dump(), "maxOpenS": self.request.max_open_s,
            "status": self.status, "doorOpen": self.door_open,
            "openForS": round(now - self.open_since, 1) if self.door_open and self.open_since else 0.0,
            "openings": self.openings, "alertsRaised": self.alerts_raised,
            "changedFraction": round(self.last_changed, 3), "startedAt": self.started_at, "error": self.error,
        }


_monitors: dict[str, Monitor] = {}


def validate_source(source: str) -> None:
    """Only network camera streams: never a local path or other OpenCV backend string."""
    scheme = urlparse(source).scheme.lower()
    if scheme not in ALLOWED_SCHEMES:
        raise ValueError(f"Stream URL must use one of: {', '.join(sorted(ALLOWED_SCHEMES))}")


def _run(monitor: Monitor, loop: asyncio.AbstractEventLoop) -> None:
    s = get_settings()
    req = monitor.request
    detector = DoorDetector(req.roi)
    period = 1.0 / s.dock_door_sample_fps
    while not monitor._stop.is_set():
        capture = cv2.VideoCapture(req.source)
        if not capture.isOpened():
            monitor.status, monitor.error = "reconnecting", "Cannot open stream"
            monitor._stop.wait(s.dock_door_reconnect_s)
            continue
        monitor.status, monitor.error = "running", None
        try:
            while not monitor._stop.is_set():
                ok, frame = capture.read()
                if not ok:
                    monitor.status, monitor.error = "reconnecting", "Stream ended or dropped"
                    break
                now = time.time()
                seen = detector.change_seen_at
                if detector.update(frame, now):
                    monitor.door_open = detector.is_open
                    if detector.is_open:
                        monitor.open_since, monitor.openings = seen or now, monitor.openings + 1
                        monitor._alerted_this_opening = False
                    else:
                        monitor.open_since = None
                monitor.last_changed = detector.changed
                if (monitor.door_open and monitor.open_since and not monitor._alerted_this_opening
                        and now - monitor.open_since > req.max_open_s):
                    monitor._alerted_this_opening = True
                    asyncio.run_coroutine_threadsafe(_alert(monitor, now - monitor.open_since), loop)
                monitor._stop.wait(period)
        except Exception as e:  # keep the monitor alive across decoder errors
            log.exception("Dock door monitor %s failed", monitor.id)
            monitor.status, monitor.error = "reconnecting", str(e)
        finally:
            capture.release()
        if not monitor._stop.is_set():
            monitor._stop.wait(s.dock_door_reconnect_s)
    monitor.status = "stopped"


async def _alert(monitor: Monitor, open_for: float) -> None:
    try:
        await scm_client.raise_alert(
            "DOCK_DOOR_OPEN", "HIGH",
            f"Reefer dock door {monitor.request.door_id} has been open {open_for:.0f}s "
            f"(limit {monitor.request.max_open_s:.0f}s)", monitor.request.door_id)
        monitor.alerts_raised += 1
    except Exception:
        log.exception("Could not raise DOCK_DOOR_OPEN alert for %s", monitor.request.door_id)


def start_monitor(req: MonitorRequest) -> dict:
    validate_source(req.source)
    if any(m.request.door_id == req.door_id and m.status != "stopped" for m in _monitors.values()):
        raise ValueError(f"Door {req.door_id} is already monitored")
    if sum(m.status != "stopped" for m in _monitors.values()) >= get_settings().dock_door_max_monitors:
        raise ValueError("Too many monitors running")
    monitor = Monitor(id=uuid.uuid4().hex[:12], request=req,
                      started_at=datetime.now(timezone.utc).isoformat())
    _monitors[monitor.id] = monitor
    loop = asyncio.get_running_loop()
    threading.Thread(target=_run, args=(monitor, loop), name=f"dock-door-{monitor.id}", daemon=True).start()
    return monitor.view()


def list_monitors() -> list[dict]:
    return [m.view() for m in _monitors.values()]


def stop_monitor(monitor_id: str) -> dict:
    monitor = _monitors.get(monitor_id)
    if monitor is None:
        raise KeyError(monitor_id)
    monitor._stop.set()
    monitor.status = "stopped"
    return monitor.view()


def stop_all() -> None:
    for m in _monitors.values():
        m._stop.set()
