# Pulse

Real-time anomaly detection & alerting platform for operational telemetry —
energy consumption, temperature, occupancy and similar hotel/facility metrics.

A simulator publishes live sensor readings to a Redis Stream. Two independent
consumers process the stream: an ingestion service persists every reading to a
TimescaleDB hypertable, and a detection service flags statistical outliers and
records them as anomalies. A React dashboard shows the live series with
anomalies marked in place, plus a rolling alert list.

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
                      │ group: pulse-ml        │       anomalies      polls REST
                      └────────────────────────┘                      every 3 s
```

- **Redis Streams with consumer groups**: both services read the same stream
  independently, each with its own cursor and acknowledgements. A stopped
  consumer loses nothing — pending entries are drained on restart.
- **TimescaleDB**: raw metrics live in a hypertable (`metrics`); detected
  anomalies in a regular table (`anomalies`). Schema is owned by
  `ingest-service` via Flyway migrations.
- **Detection**: rolling-window z-score per (metric, sensor) pair — window 50,
  minimum 20 points before scoring, |z| ≥ 3 flags an anomaly, |z| ≥ 4.5 is
  critical. All thresholds configurable via environment variables.

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

Prerequisites: Docker Desktop, JDK 21 + Maven (`JAVA_HOME` must point to
JDK 21), Node.js 20+.

```bash
# 1. Infrastructure: Redis, TimescaleDB, ml-service, simulator
cp .env.example .env   # first time only
docker compose up -d --build

# 2. Ingestion service (applies DB migrations on startup)
cd ingest-service
mvn spring-boot:run

# 3. Dashboard
cd frontend
npm install            # first time only
npm run dev
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

## API

| Endpoint | Description |
|----------|-------------|
| `GET /api/metrics/names` | Available metric names |
| `GET /api/metrics/recent?metric=X&minutes=10` | Time series for one metric |
| `GET /api/anomalies/recent?metric=X&minutes=10` | Anomalies for one metric |
| `GET /api/anomalies/latest?limit=20` | Latest anomalies across all metrics |

## Known limitations & roadmap

The z-score detector is intentionally simple and explainable, which comes with
a known weakness: on strongly trending segments (e.g. the steep part of a daily
cycle) the rolling mean lags behind and borderline values can be flagged as
false positives. Planned improvements, roughly in order:

- Trend-aware detection (EWMA / detrending) to cut false positives
- WebSocket push instead of dashboard polling
- Alert lifecycle (acknowledge/resolve) and notification channels (webhook, email)
- Downsampling with `time_bucket` for longer chart ranges
- Reclaiming stale pending stream entries (XAUTOCLAIM)
