import logging
import threading
import time
from datetime import datetime, timezone

import redis

from . import config, db
from .detector import ZScoreDetector

log = logging.getLogger("ml.consumer")


def _ensure_group(client: redis.Redis) -> None:
    try:
        client.xgroup_create(config.STREAM_KEY, config.CONSUMER_GROUP, id="0", mkstream=True)
        log.info("Created consumer group '%s' on stream '%s'", config.CONSUMER_GROUP, config.STREAM_KEY)
    except redis.exceptions.ResponseError as e:
        if "BUSYGROUP" not in str(e):
            raise
        log.info("Consumer group '%s' already exists", config.CONSUMER_GROUP)


def _process(client: redis.Redis, detector: ZScoreDetector, entry_id: str, fields: dict) -> None:
    try:
        metric = fields["metric"]
        sensor_id = fields["sensor_id"]
        value = float(fields["value"])
        ts = datetime.fromtimestamp(int(fields["timestamp"]) / 1000, tz=timezone.utc)
    except (KeyError, ValueError) as e:
        log.warning("Discarding malformed entry %s: %s", entry_id, e)
        client.xack(config.STREAM_KEY, config.CONSUMER_GROUP, entry_id)
        return

    anomaly = detector.observe(metric, sensor_id, value)
    if anomaly is not None:
        try:
            db.insert_anomaly(ts, metric, sensor_id, value, anomaly.z_score, anomaly.severity)
        except Exception as e:
            # Leave the entry pending so it is reprocessed after a restart.
            log.error("Failed to persist anomaly for entry %s: %s", entry_id, e)
            return
        log.info("Anomaly detected: %s %s value=%.2f z=%.2f severity=%s",
                 metric, sensor_id, value, anomaly.z_score, anomaly.severity)

    client.xack(config.STREAM_KEY, config.CONSUMER_GROUP, entry_id)


def run(stop_event: threading.Event) -> None:
    client = redis.Redis(host=config.REDIS_HOST, port=config.REDIS_PORT, decode_responses=True)

    while not stop_event.is_set():
        try:
            client.ping()
            break
        except redis.exceptions.ConnectionError:
            log.warning("Redis not reachable at %s:%s, retrying", config.REDIS_HOST, config.REDIS_PORT)
            time.sleep(2)

    _ensure_group(client)
    detector = ZScoreDetector()
    log.info("Consuming stream '%s' as '%s' in group '%s' (window=%d, min=%d, |z|>=%.1f)",
             config.STREAM_KEY, config.CONSUMER_NAME, config.CONSUMER_GROUP,
             config.WINDOW_SIZE, config.MIN_POINTS, config.Z_THRESHOLD)

    # First drain entries left pending by a previous run, then consume new ones.
    read_id = "0"
    while not stop_event.is_set():
        try:
            response = client.xreadgroup(
                config.CONSUMER_GROUP, config.CONSUMER_NAME,
                {config.STREAM_KEY: read_id}, count=100, block=2000)
            if not response:
                read_id = ">"
                continue
            _, entries = response[0]
            if not entries:
                read_id = ">"
                continue
            for entry_id, fields in entries:
                _process(client, detector, entry_id, fields)
        except redis.exceptions.ConnectionError as e:
            log.error("Redis connection lost: %s", e)
            time.sleep(2)
