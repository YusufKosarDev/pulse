from datetime import datetime

import psycopg

from . import config

_conn: psycopg.Connection | None = None


def _connect() -> psycopg.Connection:
    return psycopg.connect(
        host=config.DB_HOST,
        port=config.DB_PORT,
        dbname=config.DB_NAME,
        user=config.DB_USER,
        password=config.DB_PASSWORD,
        autocommit=True,
    )


def _get_conn() -> psycopg.Connection:
    global _conn
    if _conn is None or _conn.closed:
        _conn = _connect()
    return _conn


def insert_anomaly(time: datetime, metric_name: str, sensor_id: str,
                   value: float, z_score: float, severity: str, detector: str) -> None:
    global _conn
    for attempt in (1, 2):
        try:
            if _conn is None or _conn.closed:
                _conn = _connect()
            _conn.execute(
                """
                INSERT INTO anomalies (time, metric_name, sensor_id, value, z_score, severity, detector)
                VALUES (%s, %s, %s, %s, %s, %s, %s)
                ON CONFLICT (metric_name, sensor_id, detector, time) DO NOTHING
                """,
                (time, metric_name, sensor_id, value, z_score, severity, detector),
            )
            return
        except psycopg.OperationalError:
            _conn = None
            if attempt == 2:
                raise


def upsert_alert(time: datetime, metric_name: str, sensor_id: str,
                 value: float, z_score: float, severity: str) -> None:
    """Fold a detection into the series' live alert, or open a new one.

    The partial unique index allows at most one non-resolved alert per
    (metric, sensor), so the conflict branch is the "still firing" case.
    Severity only escalates; z-score magnitude keeps its maximum.
    """
    global _conn
    for attempt in (1, 2):
        try:
            if _conn is None or _conn.closed:
                _conn = _connect()
            _conn.execute(
                """
                INSERT INTO alerts (metric_name, sensor_id, severity, status, anomaly_count,
                                    first_seen, last_seen, last_value, max_z_score)
                VALUES (%s, %s, %s, 'open', 1, %s, %s, %s, %s)
                ON CONFLICT (metric_name, sensor_id) WHERE status <> 'resolved'
                DO UPDATE SET
                    anomaly_count = alerts.anomaly_count + 1,
                    last_seen = GREATEST(alerts.last_seen, EXCLUDED.last_seen),
                    last_value = EXCLUDED.last_value,
                    max_z_score = GREATEST(alerts.max_z_score, EXCLUDED.max_z_score),
                    severity = CASE WHEN 'critical' IN (alerts.severity, EXCLUDED.severity)
                                    THEN 'critical' ELSE alerts.severity END
                """,
                (metric_name, sensor_id, severity, time, time, value, abs(z_score)),
            )
            return
        except psycopg.OperationalError:
            _conn = None
            if attempt == 2:
                raise


def resolve_quiet_alerts(quiet_seconds: float) -> int:
    """Auto-resolve live alerts whose series has stopped firing."""
    global _conn
    for attempt in (1, 2):
        try:
            cur = _get_conn().execute(
                """
                UPDATE alerts SET status = 'resolved', resolved_at = now()
                WHERE status <> 'resolved' AND last_seen < now() - make_interval(secs => %s)
                """,
                (quiet_seconds,),
            )
            return cur.rowcount
        except psycopg.OperationalError:
            _conn = None
            if attempt == 2:
                raise


def fetch_recent_metrics(seconds: int) -> list[tuple[str, str, float]]:
    """Recent readings in time order, for warm-starting the forecaster."""
    global _conn
    for attempt in (1, 2):
        try:
            rows = _get_conn().execute(
                """
                SELECT metric_name, sensor_id, value FROM metrics
                WHERE time > now() - make_interval(secs => %s)
                ORDER BY time ASC
                """,
                (seconds,),
            ).fetchall()
            return [(r[0], r[1], float(r[2])) for r in rows]
        except psycopg.OperationalError:
            _conn = None
            if attempt == 2:
                raise


def replace_forecast(metric_name: str, sensor_id: str, generated_at: datetime,
                     points: list[tuple[datetime, float]], threshold: float | None) -> None:
    global _conn
    for attempt in (1, 2):
        try:
            conn = _get_conn()
            with conn.transaction():
                conn.execute(
                    "DELETE FROM forecasts WHERE metric_name = %s AND sensor_id = %s",
                    (metric_name, sensor_id))
                with conn.cursor() as cur:
                    cur.executemany(
                        """
                        INSERT INTO forecasts
                            (metric_name, sensor_id, generated_at, target_time, value, threshold)
                        VALUES (%s, %s, %s, %s, %s, %s)
                        """,
                        [(metric_name, sensor_id, generated_at, target_time, value, threshold)
                         for target_time, value in points])
            return
        except psycopg.OperationalError:
            _conn = None
            if attempt == 2:
                raise


def upsert_predicted_alert(metric_name: str, sensor_id: str, threshold: float,
                           predicted_value: float, predicted_crossing_at: datetime,
                           now: datetime) -> None:
    global _conn
    for attempt in (1, 2):
        try:
            _get_conn().execute(
                """
                INSERT INTO predicted_alerts
                    (metric_name, sensor_id, threshold, predicted_value,
                     predicted_crossing_at, first_predicted_at, updated_at)
                VALUES (%s, %s, %s, %s, %s, %s, %s)
                ON CONFLICT (metric_name, sensor_id) DO UPDATE SET
                    threshold = EXCLUDED.threshold,
                    predicted_value = EXCLUDED.predicted_value,
                    predicted_crossing_at = EXCLUDED.predicted_crossing_at,
                    updated_at = EXCLUDED.updated_at
                """,
                (metric_name, sensor_id, threshold, predicted_value,
                 predicted_crossing_at, now, now))
            return
        except psycopg.OperationalError:
            _conn = None
            if attempt == 2:
                raise


def delete_predicted_alert(metric_name: str, sensor_id: str) -> None:
    global _conn
    for attempt in (1, 2):
        try:
            _get_conn().execute(
                "DELETE FROM predicted_alerts WHERE metric_name = %s AND sensor_id = %s",
                (metric_name, sensor_id))
            return
        except psycopg.OperationalError:
            _conn = None
            if attempt == 2:
                raise
