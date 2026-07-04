# Pulse

Real-time anomaly detection & alerting platform for operational telemetry
(energy, temperature, occupancy, booking rate).

## Architecture

| Component        | Stack                  | Port |
|------------------|------------------------|------|
| `ingest-service` | Java 21 / Spring Boot  | 8081 |
| `ml-service`     | Python / FastAPI       | 8000 |
| `frontend`       | React + TypeScript     | 5173 |
| `simulator`      | Python (Docker)        | —    |
| Redis            | Docker (redis:7)       | 6379 |
| TimescaleDB      | Docker (pg16)          | 5435 |

The simulator publishes fake hotel telemetry (energy, temperature, occupancy)
to the Redis Stream `metrics` every 2 seconds; `ingest-service` consumes it
via the `pulse-ingest` consumer group.

## Prerequisites

- Docker Desktop
- JDK 21 + Maven (`JAVA_HOME` must point to JDK 21; Maven uses it even if an older `java` is on `PATH`)
- Node.js 20+

## Running locally

```bash
# 1. Infrastructure + ml-service (Redis, TimescaleDB, FastAPI)
cp .env.example .env   # first time only
docker compose up -d --build

# 2. Ingest service
cd ingest-service
mvn spring-boot:run

# 3. Frontend
cd frontend
npm install            # first time only
npm run dev
```

## Verify

- Redis: `docker exec pulse-redis redis-cli ping` → `PONG`
- TimescaleDB: `docker exec pulse-timescaledb pg_isready -U pulse` → `accepting connections`
- ml-service: http://localhost:8000/health → `{"status":"UP"}`
- ingest-service: http://localhost:8081/health → `{"status":"UP"}`
- Frontend: http://localhost:5173 → "Pulse" page
- Stream flow: `docker logs pulse-simulator` → `XADD ...` lines; ingest-service log → `Received metric: ...` lines
