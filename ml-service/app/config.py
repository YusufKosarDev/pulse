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
