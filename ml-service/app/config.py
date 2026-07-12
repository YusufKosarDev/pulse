import os

REDIS_HOST = os.getenv("REDIS_HOST", "localhost")
REDIS_PORT = int(os.getenv("REDIS_PORT", "6379"))
STREAM_KEY = os.getenv("STREAM_KEY", "metrics")
CONSUMER_GROUP = os.getenv("CONSUMER_GROUP", "pulse-ml")
CONSUMER_NAME = os.getenv("CONSUMER_NAME", "ml-1")

DB_HOST = os.getenv("DB_HOST", "localhost")
DB_PORT = int(os.getenv("DB_PORT", "5435"))
DB_NAME = os.getenv("POSTGRES_DB", "pulse")
DB_USER = os.getenv("POSTGRES_USER", "pulse")
DB_PASSWORD = os.getenv("POSTGRES_PASSWORD", "")

# Rolling z-score parameters
WINDOW_SIZE = int(os.getenv("WINDOW_SIZE", "50"))
MIN_POINTS = int(os.getenv("MIN_POINTS", "20"))
Z_THRESHOLD = float(os.getenv("Z_THRESHOLD", "3.0"))
# |z| >= Z_THRESHOLD * CRITICAL_FACTOR is classified as critical instead of warning.
CRITICAL_FACTOR = float(os.getenv("CRITICAL_FACTOR", "1.5"))

# EWMA detector parameters
EWMA_ALPHA = float(os.getenv("EWMA_ALPHA", "0.2"))    # level smoothing
EWMA_BETA = float(os.getenv("EWMA_BETA", "0.05"))     # spread smoothing
# Residuals are clamped to +/- WINSOR_K sigmas before updating level/spread,
# so a single outlier cannot inflate the baseline.
WINSOR_K = float(os.getenv("WINSOR_K", "4.0"))

# Pending-entry recovery (XAUTOCLAIM)
RECLAIM_INTERVAL_S = float(os.getenv("RECLAIM_INTERVAL_S", "30"))
RECLAIM_MIN_IDLE_MS = int(os.getenv("RECLAIM_MIN_IDLE_MS", "60000"))
MAX_DELIVERIES = int(os.getenv("MAX_DELIVERIES", "5"))
DLQ_KEY = os.getenv("DLQ_KEY", "metrics-dlq")

# Pub/sub channel for dashboard events (relayed to SSE clients by ingest-service).
EVENTS_CHANNEL = os.getenv("EVENTS_CHANNEL", "pulse-events")

# Alert lifecycle: anomalies from this detector fold into grouped alert rows.
ALERT_DETECTOR = os.getenv("ALERT_DETECTOR", "ewma")
# A live alert with no new detections for this long is resolved automatically.
ALERT_AUTO_RESOLVE_S = float(os.getenv("ALERT_AUTO_RESOLVE_S", "300"))
ALERT_SWEEP_INTERVAL_S = float(os.getenv("ALERT_SWEEP_INTERVAL_S", "30"))

# Forecasting (hand-rolled additive Holt-Winters)
SEASON_SECONDS = float(os.getenv("SEASON_SECONDS", "600"))        # must match the simulator cycle
FORECAST_SAMPLE_INTERVAL_S = float(os.getenv("FORECAST_SAMPLE_INTERVAL_S", "2"))
FORECAST_MIN_SEASONS = int(os.getenv("FORECAST_MIN_SEASONS", "2"))
FORECAST_HORIZON_MIN = float(os.getenv("FORECAST_HORIZON_MIN", "10"))
FORECAST_REFRESH_S = float(os.getenv("FORECAST_REFRESH_S", "15"))
FORECAST_POINT_INTERVAL_S = float(os.getenv("FORECAST_POINT_INTERVAL_S", "30"))
# A crossing must be predicted on this many consecutive refreshes before an alert is raised.
FORECAST_CONFIRMATIONS = int(os.getenv("FORECAST_CONFIRMATIONS", "3"))
FORECAST_ALPHA = float(os.getenv("FORECAST_ALPHA", "0.3"))
FORECAST_BETA = float(os.getenv("FORECAST_BETA", "0.05"))
FORECAST_GAMMA = float(os.getenv("FORECAST_GAMMA", "0.1"))
# An open prediction episode is graded a miss when no real crossing happens
# within this many minutes past the last predicted crossing time.
FORECAST_OUTCOME_GRACE_MIN = float(os.getenv("FORECAST_OUTCOME_GRACE_MIN", "5"))
# Hard cap on episode age, measured from the moment the alert was raised. A
# continuously re-confirmed episode keeps pushing its grace deadline forward
# and could otherwise stay open for hours, crediting a much later crossing to
# a long-stale estimate. Three times the forecast horizon by default.
FORECAST_OUTCOME_MAX_AGE_MIN = float(os.getenv("FORECAST_OUTCOME_MAX_AGE_MIN", "30"))
# Absolute operational limits per metric; forecasting predicts crossings of these.
THRESHOLDS = os.getenv(
    "THRESHOLDS",
    '{"energy_kwh": 65.0, "temperature_c": 26.0, "occupancy_pct": 95.0}')
