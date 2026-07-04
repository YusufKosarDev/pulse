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
