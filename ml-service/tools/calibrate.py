"""Offline calibration harness for forecast crossing accuracy.

Replays the simulator signal (sine + noise + a sustained ramp) through the
same Holt-Winters model the service runs, using the configured forecast
parameters, and measures how far the predicted threshold-crossing time lands
from the actual one. Scenarios span two ramp slopes, four sine phases and
four noise seeds.

Reported per configuration:
  - FIRST: error of the crossing estimate at the moment the alert is first
    raised (minutes; > 0 means the estimate was earlier than reality)
  - LATE:  error of the freshest estimate issued within the last minute
    before the actual crossing
  - lead:  how many minutes before the actual crossing the alert was raised

Run inside the service container:

    docker compose run --rm --no-deps ml-service python -m tools.calibrate
"""

import json
import math
import random

from app import config
from app.forecaster import HoltWinters, first_crossing

# Mirrors the simulator's temperature_c sensor.
BASE, AMPLITUDE, NOISE = 22.0, 2.0, 0.3
SEASON = int(config.SEASON_SECONDS / config.FORECAST_SAMPLE_INTERVAL_S)
STEP_S = config.FORECAST_SAMPLE_INTERVAL_S
THRESHOLD = json.loads(config.THRESHOLDS)["temperature_c"]
REFRESH_STEPS = max(1, round(config.FORECAST_REFRESH_S / STEP_S))
WARM_SEASONS = config.FORECAST_MIN_SEASONS + 1
MAX_RAMP_STEPS = 2000

SLOPES = (0.02, 0.03)          # drift per sample, as used by the demo ramp
PHASES = (0, SEASON // 4, SEASON // 2, 3 * SEASON // 4)
SEEDS = (1, 2, 3, 4)


def signal(step: int, ramp_start: int, slope: float, rng: random.Random) -> float:
    value = BASE + AMPLITUDE * math.sin(2 * math.pi * step / SEASON) + rng.gauss(0, NOISE)
    if step >= ramp_start:
        value += slope * (step - ramp_start)
    return value


def run_scenario(slope: float, phase_offset: int, seed: int) -> dict | None:
    rng = random.Random(seed)
    model = HoltWinters(season_length=SEASON)
    ramp_start = WARM_SEASONS * SEASON + phase_offset

    step = 0
    confirm = 0
    above = 0
    alert_step = None
    first_prediction = None
    predictions: list[tuple[int, int]] = []  # (made_at_step, predicted_step)
    actual_step = None

    while step < ramp_start + MAX_RAMP_STEPS:
        value = signal(step, ramp_start, slope, rng)
        model.observe(value)

        if step >= ramp_start:
            if value >= THRESHOLD:
                above += 1
                if above >= 3 and actual_step is None:
                    actual_step = step - 2
            else:
                above = 0

            if actual_step is None and step % REFRESH_STEPS == 0 and model.ready:
                hit = first_crossing(model.forecast(300), THRESHOLD)
                if hit is not None:
                    confirm += 1
                    predictions.append((step, step + hit))
                    if confirm >= config.FORECAST_CONFIRMATIONS and alert_step is None:
                        alert_step = step
                        first_prediction = step + hit
                else:
                    confirm = 0

        if actual_step is not None:
            break
        step += 1

    if actual_step is None or first_prediction is None:
        return None

    to_min = STEP_S / 60.0
    late = [p for made_at, p in predictions if made_at >= actual_step - 60 / STEP_S]
    return {
        "err_first": (actual_step - first_prediction) * to_min,
        "err_late": (actual_step - late[0]) * to_min if late else None,
        "lead": (actual_step - alert_step) * to_min,
    }


def main() -> None:
    firsts, lates, leads = [], [], []
    misses = 0
    for slope in SLOPES:
        for phase in PHASES:
            for seed in SEEDS:
                result = run_scenario(slope, phase, seed)
                if result is None:
                    misses += 1
                    continue
                firsts.append(result["err_first"])
                leads.append(result["lead"])
                if result["err_late"] is not None:
                    lates.append(result["err_late"])

    n = len(firsts)
    print(f"scenarios: {n} completed, {misses} without crossing/alert")
    print(f"FIRST estimate error (min): mean {sum(firsts)/n:+.2f}  "
          f"range [{min(firsts):+.2f}, {max(firsts):+.2f}]  (>0 = early)")
    print(f"LATE  estimate error (min): mean {sum(lates)/len(lates):+.2f}  "
          f"range [{min(lates):+.2f}, {max(lates):+.2f}]")
    print(f"alert lead time (min):      mean {sum(leads)/n:.2f}")


if __name__ == "__main__":
    main()
