"""Hotel telemetry simulator: publishes fake sensor metrics to a Redis Stream."""

import math
import os
import random
import time
from collections import defaultdict

import redis

REDIS_HOST = os.getenv("REDIS_HOST", "localhost")
REDIS_PORT = int(os.getenv("REDIS_PORT", "6379"))
STREAM_KEY = os.getenv("STREAM_KEY", "metrics")
INTERVAL_SECONDS = float(os.getenv("INTERVAL_SECONDS", "2"))
# Shortened "daily" cycle so the wave is visible within minutes during development.
CYCLE_SECONDS = float(os.getenv("CYCLE_SECONDS", "600"))
STREAM_MAXLEN = int(os.getenv("STREAM_MAXLEN", "10000"))
# Chance per reading of injecting a spike (for exercising anomaly detection).
SPIKE_PROBABILITY = float(os.getenv("SPIKE_PROBABILITY", "0.01"))
# Spike magnitude in multiples of the sensor's noise stddev.
SPIKE_SIGMA = float(os.getenv("SPIKE_SIGMA", "8"))
# Redis hash for live demo ramps: HSET <key> <metric> <drift-per-tick> starts a
# gradual climb for that metric; deleting the field (or key) resets it.
DEMO_KEY = os.getenv("DEMO_KEY", "forecast:demo")

# (metric, sensor_id, base, amplitude, noise_stddev)
SENSORS = [
    ("energy_kwh", "sensor-energy-1", 40.0, 10.0, 1.5),
    ("temperature_c", "sensor-lobby-1", 22.0, 2.0, 0.3),
    ("occupancy_pct", "sensor-lobby-1", 55.0, 20.0, 4.0),
]


def next_value(base: float, amplitude: float, noise_stddev: float, t: float) -> float:
    wave = amplitude * math.sin(2 * math.pi * t / CYCLE_SECONDS)
    noise = random.gauss(0, noise_stddev)
    return round(base + wave + noise, 2)


def main() -> None:
    client = redis.Redis(host=REDIS_HOST, port=REDIS_PORT, decode_responses=True)
    client.ping()
    print(f"Connected to Redis at {REDIS_HOST}:{REDIS_PORT}, publishing to '{STREAM_KEY}' every {INTERVAL_SECONDS}s")

    drift = defaultdict(float)
    while True:
        now = time.time()
        try:
            demo = client.hgetall(DEMO_KEY)
        except redis.exceptions.RedisError:
            demo = {}
        for metric, sensor_id, base, amplitude, noise_stddev in SENSORS:
            if metric in demo:
                try:
                    drift[metric] += float(demo[metric])
                except ValueError:
                    pass
            elif drift[metric]:
                drift[metric] = 0.0
            value = round(next_value(base, amplitude, noise_stddev, now) + drift[metric], 2)
            spiked = random.random() < SPIKE_PROBABILITY
            if spiked:
                value = round(value + random.choice((-1, 1)) * SPIKE_SIGMA * noise_stddev, 2)
            fields = {
                "metric": metric,
                "sensor_id": sensor_id,
                "value": str(value),
                "timestamp": str(int(now * 1000)),
            }
            entry_id = client.xadd(STREAM_KEY, fields, maxlen=STREAM_MAXLEN, approximate=True)
            tag = " [SPIKE]" if spiked else ""
            if drift[metric]:
                tag += f" [DEMO drift={drift[metric]:+.2f}]"
            print(f"XADD {entry_id} {fields['metric']} {fields['sensor_id']} value={fields['value']}{tag}")
        time.sleep(INTERVAL_SECONDS)


if __name__ == "__main__":
    main()
