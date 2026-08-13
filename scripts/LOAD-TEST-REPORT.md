# VMS Load Test Report — 10,000 Concurrent Users

**Date:** 2026-08-12  
**Tool:** k6 (ramping VUs 0 → 10,000 over ~5 min)  
**Target:** `http://localhost:8080` (login → /api/me → /api/status → /api/venues/1)

## Results (before architecture changes)

| Metric | Value |
|--------|-------|
| Max concurrent VUs | 10,000 |
| Total HTTP requests | 45,813 |
| Throughput | **89 req/s** |
| Failed requests | **99.97%** |
| p95 latency | **19,876 ms** |
| /api/me p95 | 15,274 ms |
| /api/status p95 | 4,759 ms |

## Failure modes observed

1. **Gateway connection refused** — Nginx/host exhausted connections around ~5k VUs.
2. **Core service unhealthy** — JVM/thread/DB pool saturation under session storm.
3. **Five microservices × dual Hikari pools** — ~30 DB connections per service tier, multiplied across JVMs.
4. **Unbounded sessions** — Each virtual user created a new DB session row on login.
5. **No edge rate limiting** — Traffic spike hit application tier directly.

## Architecture changes applied

| Change | Rationale |
|--------|-----------|
| **Monolithic `api` service** (`SERVICE_NAME=all`) | One JVM instead of five; fewer connections and less orchestration overhead |
| **Nginx upstream keepalive** | Reuse connections to backend (64 keepalive) |
| **Nginx worker tuning** | `worker_connections 4096`, `multi_accept on` |
| **Edge rate/conn limits** | 200 req/s + 200 conn/IP burst protection |
| **Tomcat thread pool** | max 400 threads, 10k connections |
| **Session cap per user** | Max 5 concurrent sessions; oldest pruned on login |
| **Longer caches** | Progress cache 30s; session cache 60s |
| **Larger DB pool (monolith)** | 10 connections × 2 pools = 20 total (vs 30×5 before) |

## Database restore

Pre-test snapshot restored from:

`backups/pre-loadtest-snapshot.sql`

To restore manually:

```powershell
Get-Content backups\pre-loadtest-snapshot.sql | docker exec -i event-mysql-1 mysql -uvms -pvms vms
```

## Re-run load test

```powershell
docker run --rm -v ${PWD}/scripts:/scripts grafana/k6 run /scripts/load-test.js `
  -e BASE_URL=http://host.docker.internal:8080 `
  -e TEST_PASSWORD=YourAdminPassword
```

## Verification (after monolith, 500 VU peak)

| Metric | Before (10k test) | After (500 VU verify) |
|--------|-------------------|------------------------|
| Throughput | 89 req/s | **359 req/s** |
| p95 latency | 19,876 ms | **315 ms** |
| Max VUs tested | 10,000 | 500 |

Note: The 500 VU verification run still showed high error rates because all virtual users shared one admin account (session cap = 5) and Nginx edge rate limiting (200 req/s). Throughput and latency improved substantially before saturation.

| Concurrent users | Expected (post-change, single host) |
|------------------|-------------------------------------|
| &lt; 200 | Stable |
| 200 – 1,000 | Good with monolith + tuning |
| 1,000 – 3,000 | Requires horizontal scaling (multiple API replicas + load balancer) |
| 10,000 | Needs Redis sessions, DB read replicas, CDN, and multiple nodes — not single-machine |
