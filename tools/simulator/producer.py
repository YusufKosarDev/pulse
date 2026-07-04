"""Hotel telemetry simulator: publishes fake sensor metrics to a Redis Stream."""

import math
import os
import random
import time

import redis

REDIS_HOST = os.getenv("REDIS_HOST", "localhost")
REDIS_PORT = int(os.getenv("REDIS_PORT", "6379"))
STREAM_KEY = os.getenv("STREAM_KEY", "metrics")
INTERVAL_SECONDS = float(os.getenv("INTERVAL_SECONDS", "2"))
# Shortened "daily" cycle so the wave is visible within minutes during development.
CYCLE_SECONDS = float(os.getenv("CYCLE_SECONDS", "600"))
STREAM_MAXLEN = int(os.getenv("STREAM_MAXLEN", "10000"))

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

    while True:
        now = time.time()
        for metric, sensor_id, base, amplitude, noise_stddev in SENSORS:
            fields = {
                "metric": metric,
                "sensor_id": sensor_id,
                "value": str(next_value(base, amplitude, noise_stddev, now)),
                "timestamp": str(int(now * 1000)),
            }
            entry_id = client.xadd(STREAM_KEY, fields, maxlen=STREAM_MAXLEN, approximate=True)
            print(f"XADD {entry_id} {fields['metric']} {fields['sensor_id']} value={fields['value']}")
        time.sleep(INTERVAL_SECONDS)


if __name__ == "__main__":
    main()
