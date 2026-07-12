# Pulse

Real-time anomaly detection & alerting platform for operational telemetry —
energy consumption, temperature, occupancy and similar hotel/facility metrics.

A simulator publishes live sensor readings to a Redis Stream. Two independent
consumers process the stream: an ingestion service persists every reading to a
TimescaleDB hypertable, and a detection service flags statistical outliers and
records them as anomalies. Consecutive anomalies on one series group into a
single alert with an open → acknowledged → resolved lifecycle. A React
dashboard shows the live series with anomalies marked in place, plus an alert
panel with acknowledge/resolve actions.

<!-- screenshot: dashboard with anomaly markers and alert list -->

## Architecture

```
                      ┌────────────────────────┐
                 ┌───▶│ ingest-service (Java)  │────┐  persist + REST API
┌───────────┐    │    │ group: pulse-ingest    │    ▼
│ simulator │────┤    └────────────────────────┘  ┌─────────────┐   ┌───────────┐
│ (Python)  │    │      Redis Stream "metrics"    │ TimescaleDB │◀──│ dashboard │
└───────────┘    │    ┌────────────────────────┐  └─────────────┘   │ (React)   │
                 └───▶│ ml-service (Python)    │────┘  write        └───────────┘
                      │ group: pulse-ml        │       anomalies     REST backfill
                      └────────────────────────┘                     + SSE push
```

- **Redis Streams with consumer groups**: both services read the same stream
  independently, each with its own cursor and acknowledgements. A stopped
  consumer loses nothing — pending entries are drained on restart, and a
  periodic reclaim (XAUTOCLAIM semantics) takes over entries a crashed
  consumer never acknowledged. Writes are idempotent (unique indexes +
  `ON CONFLICT DO NOTHING`), so at-least-once redelivery cannot create
  duplicates; entries that keep failing are moved to a `metrics-dlq`
  dead-letter stream after a configurable number of attempts.
- **TimescaleDB**: raw metrics live in a hypertable (`metrics`); detected
  anomalies in a regular table (`anomalies`). Schema is owned by
  `ingest-service` via Flyway migrations. Chart ranges beyond a few minutes
  are downsampled server-side into fixed-width buckets (average per bucket,
  sized for ~300 points per response), so a 24-hour range costs the same to
  render as a 10-minute one; the dashboard offers 10 m / 1 h / 6 h / 24 h.
  TimescaleDB is the preferred backend for the time-partitioned hypertable,
  but the dependency is abstracted, not hard-wired: the schema turns `metrics`
  into a hypertable only when the extension is present and otherwise degrades
  gracefully to an ordinary indexed table on plain PostgreSQL, and the
  downsampling query is written in portable SQL that runs identically on both.
  This keeps the time-series optimisation where it is available while allowing
  deployment on a stock PostgreSQL (e.g. a free managed instance) with no code
  changes.
- **Detection**: EWMA-based z-score per (metric, sensor) pair — an
  exponentially weighted level (α = 0.2) absorbs smooth trends, values are
  scored against the previous level/spread estimates, and updates are
  winsorized so a single outlier cannot inflate the baseline. |z| ≥ 3 flags an
  anomaly, |z| ≥ 4.5 is critical; minimum 20 points before scoring. A plain
  rolling-window z-score runs in shadow mode alongside it (every anomaly row
  is tagged with its `detector`), which is how the two were compared before
  EWMA became the default. All parameters configurable via environment
  variables.
- **Live updates**: the dashboard opens one `GET /api/stream` connection
  (server-sent events) and receives `metric`, `anomaly`, `alerts-changed` and
  `forecast-changed` events as they happen — no polling. `ingest-service`
  emits metric events directly from the stream consumer and relays the rest
  from a Redis pub/sub channel (`pulse-events`) that ml-service publishes on.
  On (re)connect the client backfills the window over REST, so a dropped
  connection loses nothing; `EventSource` reconnects on its own.
- **Alert lifecycle**: raw detections stay in `anomalies` (they mark the
  chart), while the default detector's detections also fold into grouped
  `alerts` — one live alert per (metric, sensor), enforced by a partial unique
  index and an idempotent upsert, so a spike wave is one alert with a
  detection count and first/last-seen range, not twenty rows. Alerts are
  acknowledged/resolved from the dashboard; a live alert with no new
  detections for `ALERT_AUTO_RESOLVE_S` (default 5 min) resolves itself, and
  new detections after a resolve open a fresh alert.
- **Forecast accuracy tracking**: every predicted alert becomes a graded
  episode — `hit` when the value really crosses the threshold (error measured
  against the raise-time estimate, plus warning lead time), `miss` when
  nothing happens within a grace period past the last estimate, and real
  crossings the forecaster never warned about are recorded as `unwarned`.
  Crossings count on a below→above edge only, so a value hovering at the
  threshold is one event. The dashboard shows a rolling 24 h scorecard
  (hit rate, median and average |error|, median and average lead) with the
  recent episode list. Headline error and lead are reported as medians, which
  are not skewed by the occasional long-lived episode; the averages are kept
  alongside for context.

  Accuracy is measured two ways, and the two sets of numbers answer
  different questions:
  - The *offline calibration* (`ml-service/tools/calibrate.py`, 32
    sustained-ramp scenarios — the design-target case) reports signed errors:
    raise-time estimates are unbiased on average (~0.1 min, early and late
    cancelling out), the freshest estimate within the last minute before the
    crossing stays inside ±1 minute, and alerts lead the crossing by
    ~4 minutes.
  - The *live scorecard* grades every episode in normal operation, always
    against the estimate at the moment the alert was raised — the number an
    operator actually saw and acted on. Over several days of live running:
    roughly 6–7 warnings in 10 were followed by a real crossing (79 % on
    temperature, 70 % on occupancy), the raise-time estimate was off by
    ~9 minutes (median |error|; the average runs a few minutes higher, pulled
    up by a handful of episodes graded against an old estimate), and alerts
    fired a median ~10 minutes before the crossing. The freshest estimate
    before each crossing landed within ~1 minute (median), consistent with the
    calibration. So "±1 minute" and "~9 minutes" do not contradict each other:
    the first grades the final estimate on ramp-only data, the second grades
    the first estimate on everything the system saw.

  An episode is also capped at `FORECAST_OUTCOME_MAX_AGE_MIN` (default 30 min)
  from the moment it is raised: a continuously re-confirmed prediction keeps
  pushing its own grace deadline forward, so without the cap it could stay
  open for hours and then credit a much later crossing to a long-stale
  estimate. Past the cap the episode is graded a `miss`. This bounds how far
  an estimate can drift before it is settled; it applies to episodes graded
  from here on, and does not rewrite outcomes already recorded.

  The live numbers also expose the model's real limits, deliberately left
  visible rather than filtered out: injected single-reading spikes cross the
  threshold with no warning and grade as `unwarned` (a smooth trend model
  cannot foresee a step change; two more `unwarned` came from crossings in
  the first minute after a service restart), and on `energy_kwh`, whose
  threshold sits far above the signal's normal range, upswing extrapolation
  raises warnings that rarely materialize (1 hit in 11 episodes).
- **Notification channels**: alert lifecycle events — `alert-opened` when a
  new alert starts and `alert-resolved` (with `"reason": "auto"` or
  `"manual"`) when one ends — go out over two optional channels. With
  `WEBHOOK_URL` set they are POSTed as JSON (full alert row in the payload);
  with `SMTP_HOST` + `ALERT_EMAIL_TO` set they are also emailed with a
  human-readable summary. Both are asynchronous and best-effort (failures
  only logged), so a slow receiver never slows down processing, and both are
  disabled by default.
- **Forecasting**: hand-rolled additive Holt-Winters (level + trend +
  seasonal) per metric extrapolates a 10-minute horizon against absolute
  operational thresholds; a crossing predicted on three consecutive refreshes
  raises a predicted alert, which clears itself once the forecast no longer
  crosses. The model warm-starts from recent TimescaleDB history, so
  forecasts are available shortly after a restart. Measured accuracy — the
  offline calibration and the live scorecard, and why their numbers differ —
  is described under *Forecast accuracy tracking* above.

## Components

| Component        | Stack                          | Port |
|------------------|--------------------------------|------|
| `ingest-service` | Java 21, Spring Boot, Flyway   | 8081 |
| `ml-service`     | Python, FastAPI                | 8000 |
| `frontend`       | React, TypeScript, Vite        | 5173 |
| `simulator`      | Python (Docker only)           | —    |
| Redis            | redis:7                        | 6379 |
| TimescaleDB      | timescale/timescaledb (pg16)   | 5435 |

## Running locally

Prerequisites: Docker Desktop and Node.js 20+ (for the dashboard).
JDK 21 + Maven are only needed for backend development outside Docker
(`JAVA_HOME` must point to JDK 21).

```bash
# 1. Full backend: Redis, TimescaleDB, ingest-service, ml-service, simulator
cp .env.example .env   # first time only
docker compose up -d --build

# 2. Dashboard
cd frontend
npm install            # first time only
npm run dev
```

All backend services restart automatically (`restart: unless-stopped`);
`ingest-service` applies Flyway migrations on startup.

For backend development with hot reload you can run the ingestion service
directly instead of its container — but not both at once, they both bind
port 8081:

```bash
docker compose stop ingest-service
cd ingest-service
mvn spring-boot:run
```

Open http://localhost:5173 — the selected metric streams into the chart;
anomalies appear as red/orange dots and in the "Recent alerts" panel.

### Triggering an anomaly

The simulator injects a spike with 1% probability per reading
(`SPIKE_PROBABILITY`). To trigger one immediately:

```bash
docker exec pulse-redis redis-cli XADD metrics '*' \
  metric energy_kwh sensor_id sensor-energy-1 value 95.0 \
  timestamp $(date +%s%3N)
```

Within a few seconds the dashboard marks the point and the alert list shows a
`critical` entry. Verify in the database:

```sql
SELECT time, metric_name, value, z_score, severity
FROM anomalies ORDER BY time DESC LIMIT 10;
```

### Triggering a predicted alert

Start a gradual ramp (no restart needed) and watch the dashed forecast line
climb toward the threshold; a forecast banner appears within ~a minute:

```bash
docker exec pulse-redis redis-cli HSET forecast:demo temperature_c 0.02
docker exec pulse-redis redis-cli DEL forecast:demo    # stop the ramp
```

## API

| Endpoint | Description |
|----------|-------------|
| `GET /api/metrics/names` | Available metric names |
| `GET /api/metrics/recent?metric=X&minutes=10` | Time series for one metric (bucketed via `time_bucket` on long ranges) |
| `GET /api/anomalies/recent?metric=X&minutes=10` | Anomalies for one metric |
| `GET /api/anomalies/latest?limit=20` | Latest anomalies across all metrics |
| `GET /api/alerts?limit=20` | Grouped alerts, live ones first |
| `POST /api/alerts/{id}/acknowledge` | Mark an open alert as acknowledged |
| `POST /api/alerts/{id}/resolve` | Resolve an open or acknowledged alert |
| `GET /api/forecasts?metric=X` | Forecast curve + threshold for one metric |
| `GET /api/predicted-alerts` | Active predicted threshold crossings |
| `GET /api/forecast-outcomes?limit=20` | Graded prediction episodes (hit/miss/unwarned) |
| `GET /api/forecast-outcomes/stats?hours=24` | Hit rate, avg error and lead over a window |
| `GET /api/stream` | Server-sent events: `metric`, `anomaly`, `alerts-changed`, `forecast-changed`, `forecast-outcomes-changed` |

## Tests

```bash
# ingest-service: unit tests + repository integration tests. The integration
# tests run against the compose TimescaleDB in a separate pulse_test database
# (each test rolls back); they skip themselves when the stack is not running.
cd ingest-service && mvn test

# ml-service: detector and forecaster unit tests, run inside the container
docker compose run --rm --no-deps ml-service \
  sh -c "pip install -q -r requirements-dev.txt && python -m pytest tests -q"
```

## Known limitations & roadmap

Detection is intentionally simple statistics, not heavy modelling. The first
detector was a plain rolling-window z-score; it lagged on trending segments
(false positives around |z| ≈ 3) and its window std was inflated by the daily
cycle itself, which diluted real spikes. The EWMA detector was run against it
in shadow mode on identical data: in a ~30-minute live comparison with 19
injected spikes, EWMA caught 19/19 as critical with fewer borderline false
positives, while the rolling z-score missed 4 and downgraded most others to
warnings. EWMA has been the default since; the comparison setup remains in
place. Known remaining limits: detector state is in-memory (warm-up restarts
with the service) and seasonality is not modelled explicitly.

Planned improvements, roughly in order:

- Deployment to a public host — everything else on the original roadmap has
  shipped (push updates, alert lifecycle, notifications, downsampling,
  forecast accuracy tracking)

