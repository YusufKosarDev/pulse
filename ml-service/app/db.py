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
