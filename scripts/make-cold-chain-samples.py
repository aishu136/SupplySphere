"""Generates sample media for trying the cold-chain features without a thermal camera or dock CCTV.

    scm-ai-service/.venv/Scripts/python scripts/make-cold-chain-samples.py      # Windows
    scm-ai-service/.venv/bin/python scripts/make-cold-chain-samples.py          # macOS / Linux

Writes to data/samples/ (git-ignored):
  reefer-thermal-ok.png       frozen load at about -22 °C (ironbow palette, scale -30 to 20 °C)
  reefer-thermal-hotspot.png  the same load with a warm patch near the door seal (about -5 °C)
  dock-door.avi               4 minutes of a roll-up reefer door: a 30 s opening, someone walking past,
                              then a 145 s opening, the second one over the 2-minute limit
  dock-door-closed.png        a still of the closed door, for the optional reference upload

The door sits at x 25%, y 15%, width 50%, height 80% of the frame, the Dock doors page's default region.
"""
from pathlib import Path

import cv2
import numpy as np

OUT = Path(__file__).resolve().parent.parent / "data" / "samples"
SCALE_MIN, SCALE_MAX = -30.0, 20.0
rng = np.random.default_rng(42)


def thermal(temps_c: np.ndarray) -> np.ndarray:
    levels = np.clip((temps_c - SCALE_MIN) / (SCALE_MAX - SCALE_MIN) * 255, 0, 255).astype(np.uint8)
    return cv2.applyColorMap(levels, cv2.COLORMAP_INFERNO)


def reefer_load(hotspot: bool) -> np.ndarray:
    h, w = 360, 480
    temps = np.full((h, w), -22.0) + rng.normal(0, 0.4, (h, w))
    # Pallet stacks are slightly colder than the air gaps between them.
    for x in range(40, w - 40, 110):
        temps[60:330, x:x + 90] -= 1.5
    if hotspot:
        yy, xx = np.mgrid[0:h, 0:w]
        warm = np.exp(-(((xx - 400) / 55) ** 2 + ((yy - 120) / 70) ** 2))  # warm air leaking past the seal
        temps += warm * 17
    return thermal(cv2.GaussianBlur(temps.astype(np.float32), (7, 7), 0))


W, H = 640, 360
DOOR = (160, 54, 480, 342)  # x1, y1, x2, y2 = 25%, 15%, 75%, 95%


def dock(open_fraction: float, passerby_x: int | None = None) -> np.ndarray:
    img = np.full((H, W, 3), (128, 132, 136), np.uint8)
    cv2.rectangle(img, (0, 342), (W, H), (90, 92, 95), -1)  # dock floor
    x1, y1, x2, y2 = DOOR
    # Trailer interior behind the door: dark, with frosted pallets.
    cv2.rectangle(img, (x1, y1), (x2, y2), (28, 26, 24), -1)
    for px in (190, 300, 400):
        cv2.rectangle(img, (px, 210), (px + 70, 342), (70, 90, 105), -1)
    # Roll-up door: horizontal slats, raised from the bottom by open_fraction.
    bottom = int(y2 - open_fraction * (y2 - y1))
    for y in range(y1, bottom, 12):
        shade = 205 if (y // 12) % 2 else 185
        cv2.rectangle(img, (x1, y), (x2, min(y + 12, bottom)), (shade, shade, shade - 10), -1)
    cv2.rectangle(img, (x1 - 6, y1 - 6), (x2 + 6, y2), (60, 200, 230), 6)  # yellow door frame
    if passerby_x is not None:  # a person walking in front of the door
        cv2.rectangle(img, (passerby_x, 120), (passerby_x + 70, 342), (60, 70, 160), -1)
        cv2.circle(img, (passerby_x + 35, 100), 26, (120, 150, 200), -1)
    noise = rng.integers(-4, 5, img.shape, dtype=np.int16)
    return np.clip(img.astype(np.int16) + noise, 0, 255).astype(np.uint8)


def door_clip(path: Path, fps: int = 4, seconds: int = 240) -> None:
    openings = [(20, 50), (70, 215)]  # door fully up between these times, 2 s to roll up or down
    passerby = (58, 59.5)
    writer = cv2.VideoWriter(str(path), cv2.VideoWriter_fourcc(*"MJPG"), fps, (W, H))
    if not writer.isOpened():
        raise SystemExit("This OpenCV build cannot write MJPG video")
    for i in range(fps * seconds):
        t = i / fps
        frac = 0.0
        for start, end in openings:
            if start <= t < end:
                frac = min(1.0, (t - start) / 2, (end - t) / 2)
        walker = None
        if passerby[0] <= t < passerby[1]:
            walker = int(100 + (t - passerby[0]) / (passerby[1] - passerby[0]) * 400)
        writer.write(dock(frac, walker))
    writer.release()


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    cv2.imwrite(str(OUT / "reefer-thermal-ok.png"), reefer_load(hotspot=False))
    cv2.imwrite(str(OUT / "reefer-thermal-hotspot.png"), reefer_load(hotspot=True))
    cv2.imwrite(str(OUT / "dock-door-closed.png"), dock(0.0))
    door_clip(OUT / "dock-door.avi")
    for f in sorted(OUT.iterdir()):
        print(f"{f}  ({f.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main()
