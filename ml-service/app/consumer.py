import json
import logging
import threading
import time
from datetime import datetime, timezone

import redis

from . import config, db
from .detector import EwmaDetector, ZScoreDetector
from .forecaster import ForecastEngine

log = logging.getLogger("ml.consumer")


def _publish_event(client: redis.Redis, payload: dict) -> None:
    """Best-effort dashboard fan-out; must never break stream processing."""
    try:
        client.publish(config.EVENTS_CHANNEL, json.dumps(payload))
    except redis.exceptions.RedisError as e:
        log.warning("Event publish failed: %s", e)


def _ensure_group(client: redis.Redis) -> None:
    try:
        client.xgroup_create(config.STREAM_KEY, config.CONSUMER_GROUP, id="0", mkstream=True)
        log.info("Created consumer group '%s' on stream '%s'", config.CONSUMER_GROUP, config.STREAM_KEY)
    except redis.exceptions.ResponseError as e:
        if "BUSYGROUP" not in str(e):
            raise
        log.info("Consumer group '%s' already exists", config.CONSUMER_GROUP)


def _process(client: redis.Redis, detectors: list[tuple[str, object]],
             engine: ForecastEngine, entry_id: str, fields: dict) -> None:
    try:
        metric = fields["metric"]
        sensor_id = fields["sensor_id"]
        value = float(fields["value"])
        ts = datetime.fromtimestamp(int(fields["timestamp"]) / 1000, tz=timezone.utc)
    except (KeyError, ValueError) as e:
        log.warning("Discarding malformed entry %s: %s", entry_id, e)
        client.xack(config.STREAM_KEY, config.CONSUMER_GROUP, entry_id)
        return

    engine.observe(metric, sensor_id, value)

    for name, detector in detectors:
        anomaly = detector.observe(metric, sensor_id, value)
        if anomaly is None:
            continue
        alert = None
        try:
            db.insert_anomaly(ts, metric, sensor_id, value,
                              anomaly.z_score, anomaly.severity, name)
            if name == config.ALERT_DETECTOR:
                alert = db.upsert_alert(ts, metric, sensor_id, value,
                                        anomaly.z_score, anomaly.severity)
        except Exception as e:
            # Leave the entry pending so it is reprocessed after a restart.
            log.error("Failed to persist anomaly for entry %s: %s", entry_id, e)
            return
        log.info("Anomaly detected [%s]: %s %s value=%.2f z=%.2f severity=%s",
                 name, metric, sensor_id, value, anomaly.z_score, anomaly.severity)
        _publish_event(client, {
            "type": "anomaly", "detector": name, "time": ts.isoformat(),
            "metricName": metric, "sensorId": sensor_id, "value": value,
            "zScore": anomaly.z_score, "severity": anomaly.severity,
        })
        if alert is not None:
            if alert["anomalyCount"] == 1:
                _publish_event(client, {"type": "alert-opened", "alert": alert})
            _publish_event(client, {"type": "alerts-changed"})

    client.xack(config.STREAM_KEY, config.CONSUMER_GROUP, entry_id)


def _dead_letter(client: redis.Redis, entry_id: str, fields: dict, deliveries: int) -> None:
    payload = dict(fields)
    payload["original_id"] = entry_id
    payload["reason"] = f"max deliveries exceeded ({deliveries})"
    client.xadd(config.DLQ_KEY, payload)
    client.xack(config.STREAM_KEY, config.CONSUMER_GROUP, entry_id)
    log.error("Moved poison entry %s to '%s' after %d deliveries",
              entry_id, config.DLQ_KEY, deliveries)


def _reclaim_pending(client: redis.Redis, detectors: list[tuple[str, object]],
                     engine: ForecastEngine) -> None:
    """Take over entries another (or a crashed) consumer left unacknowledged."""
    try:
        pending = client.xpending_range(
            config.STREAM_KEY, config.CONSUMER_GROUP, "-", "+", count=100)
        deliveries = {p["message_id"]: p["times_delivered"] for p in pending}

        response = client.xautoclaim(
            config.STREAM_KEY, config.CONSUMER_GROUP, config.CONSUMER_NAME,
            min_idle_time=config.RECLAIM_MIN_IDLE_MS, start_id="0-0", count=100)
        entries = response[1]  # (next_start, claimed_entries, [deleted_ids])
    except redis.exceptions.ResponseError as e:
        log.warning("Reclaim scan failed: %s", e)
        return

    for entry_id, fields in entries:
        count = deliveries.get(entry_id, 1)
        if count >= config.MAX_DELIVERIES:
            _dead_letter(client, entry_id, fields, count)
        else:
            log.info("Reclaimed pending entry %s (delivery #%d)", entry_id, count + 1)
            _process(client, detectors, engine, entry_id, fields)


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
    # Both detectors score the same stream so they can be compared on equal terms.
    detectors: list[tuple[str, object]] = [
        ("zscore", ZScoreDetector()),
        ("ewma", EwmaDetector()),
    ]
    engine = ForecastEngine(publish=lambda payload: _publish_event(client, payload))
    engine.warm_start()
    log.info("Consuming stream '%s' as '%s' in group '%s' (detectors=%s, min=%d, |z|>=%.1f)",
             config.STREAM_KEY, config.CONSUMER_NAME, config.CONSUMER_GROUP,
             [name for name, _ in detectors], config.MIN_POINTS, config.Z_THRESHOLD)

    # First drain entries left pending by a previous run, then consume new ones.
    read_id = "0"
    last_reclaim = time.monotonic()
    last_forecast = time.monotonic()
    last_alert_sweep = time.monotonic()
    while not stop_event.is_set():
        try:
            if time.monotonic() - last_reclaim >= config.RECLAIM_INTERVAL_S:
                last_reclaim = time.monotonic()
                _reclaim_pending(client, detectors, engine)
            if time.monotonic() - last_alert_sweep >= config.ALERT_SWEEP_INTERVAL_S:
                last_alert_sweep = time.monotonic()
                try:
                    resolved = db.resolve_quiet_alerts(config.ALERT_AUTO_RESOLVE_S)
                    if resolved:
                        log.info("Auto-resolved %d quiet alert(s)", len(resolved))
                        for alert in resolved:
                            _publish_event(client, {"type": "alert-resolved",
                                                    "reason": "auto", "alert": alert})
                        _publish_event(client, {"type": "alerts-changed"})
                except Exception as e:
                    log.warning("Alert sweep failed: %s", e)
            if time.monotonic() - last_forecast >= config.FORECAST_REFRESH_S:
                last_forecast = time.monotonic()
                engine.refresh()
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
                _process(client, detectors, engine, entry_id, fields)
        except redis.exceptions.ConnectionError as e:
            log.error("Redis connection lost: %s", e)
            time.sleep(2)
