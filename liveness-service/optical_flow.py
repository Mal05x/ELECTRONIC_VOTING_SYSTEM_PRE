"""
optical_flow.py — Inter-frame motion analysis for burst liveness detection.

Physics behind this:
  A real human face produces micro-movements between frames even when
  consciously holding still — breathing displaces the torso, eye
  micro-saccades shift gaze, minor head tremor exists at ~2–8 Hz.
  These produce a non-zero optical flow field with magnitude ~0.1–2.0 px/frame.

  A static spoof (printed photo, frozen replay frame, paused video) produces
  near-zero inter-frame flow — the scene is literally not moving.

  An active replay (video playing) produces motion, but the motion pattern
  is often periodic, uniform across the frame, or inconsistent with the
  natural fall-off from face centre → periphery.

Algorithm (Farnebäck dense optical flow):
  1. Convert all burst frames to grayscale
  2. Compute dense optical flow between each consecutive pair
  3. Compute mean flow magnitude per pair
  4. Average magnitudes across all pairs
  5. Normalise to [0, 1] with FLOW_MIN/MAX thresholds from config

Score interpretation:
  score < FLOW_MIN_MOTION  → near-static  → likely printed/frozen attack
  score in [MIN, MAX]      → natural motion → interpolated to [0.7, 1.0]
  score > FLOW_MAX_MOTION  → excessive motion → penalised (user shaking)
"""
import cv2
import numpy as np
import config


def compute_burst_flow_score(frames: list[np.ndarray]) -> float:
    """
    Compute a normalised [0, 1] motion liveness score from a list of BGR frames.

    Args:
        frames: List of BGR images (minimum 2). Typically 5 frames at 200 ms apart.

    Returns:
        float in [0, 1]:
          0.0 → completely static (strong spoof signal)
          ~0.85 → typical real face
          1.0 → strong natural motion
    """
    if len(frames) < 2:
        return 0.0

    grays = [cv2.cvtColor(f, cv2.COLOR_BGR2GRAY) for f in frames]

    pair_magnitudes: list[float] = []
    for prev, curr in zip(grays, grays[1:]):
        flow = cv2.calcOpticalFlowFarneback(
            prev, curr,
            flow=None,
            pyr_scale=0.5,
            levels=3,
            winsize=15,
            iterations=3,
            poly_n=5,
            poly_sigma=1.2,
            flags=0
        )
        mag, _ = cv2.cartToPolar(flow[..., 0], flow[..., 1])
        pair_magnitudes.append(float(np.mean(mag)))

    avg_mag = float(np.mean(pair_magnitudes))
    return _normalise_magnitude(avg_mag)


def _normalise_magnitude(avg_mag: float) -> float:
    """
    Map average pixel flow magnitude → [0, 1] liveness score.

    Below FLOW_MIN_MOTION:
      Linear ramp 0 → 0.30 as mag goes 0 → MIN.
      Capped at 0.30 so even the most generous interpretation of a
      near-static frame cannot hit the liveness threshold alone.

    In [FLOW_MIN_MOTION, FLOW_MAX_MOTION]:
      Linear ramp 0.70 → 1.00 (healthy motion range).

    Above FLOW_MAX_MOTION:
      Penalise proportionally — excessive motion suggests shaking/blur.
      Score floors at 0.40 (it's probably still a real person, just
      moving too much to be reliable).
    """
    lo = config.FLOW_MIN_MOTION
    hi = config.FLOW_MAX_MOTION

    if avg_mag < lo:
        # Near-static — suspicious
        return (avg_mag / lo) * 0.30

    if avg_mag > hi:
        # Excessive motion
        excess = (avg_mag - hi) / hi
        return max(0.40, 1.0 - excess * 0.6)

    # Natural range — interpolate [0.70, 1.00]
    t = (avg_mag - lo) / (hi - lo)
    return 0.70 + t * 0.30
