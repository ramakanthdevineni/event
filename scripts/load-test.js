import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const USERNAME = __ENV.TEST_USER || 'admin';
const PASSWORD = __ENV.TEST_PASSWORD || 'Certified01$';

const apiErrors = new Rate('api_errors');
const loginDuration = new Trend('login_duration', true);
const meDuration = new Trend('me_duration', true);
const statusDuration = new Trend('status_duration', true);
const venuesDuration = new Trend('venues_duration', true);
const rateLimited = new Counter('rate_limited_logins');

export const options = {
  scenarios: {
    concurrent_users: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 500 },
        { duration: '45s', target: 2000 },
        { duration: '60s', target: 5000 },
        { duration: '90s', target: 10000 },
        { duration: '60s', target: 10000 },
        { duration: '30s', target: 0 },
      ],
      gracefulRampDown: '15s',
    },
  },
  discardResponseBodies: true,
};

function headers() {
  return { Origin: BASE, Referer: `${BASE}/home` };
}

function ensureSession() {
  let me = http.get(`${BASE}/api/me`, { headers: headers(), timeout: '20s' });
  if (me.status === 200) return true;

  const login = http.post(
    `${BASE}/api/login`,
    JSON.stringify({ username: USERNAME, password: PASSWORD }),
    {
      headers: { ...headers(), 'Content-Type': 'application/json', Referer: `${BASE}/login` },
      timeout: '20s',
    }
  );
  loginDuration.add(login.timings.duration);
  if (login.status === 429) {
    rateLimited.add(1);
    return false;
  }
  return check(login, { 'login ok': (r) => r.status === 200 });
}

export default function () {
  if (!ensureSession()) {
    apiErrors.add(1);
    sleep(1);
    return;
  }

  const me = http.get(`${BASE}/api/me`, { headers: headers(), timeout: '20s' });
  meDuration.add(me.timings.duration);
  if (me.status !== 200) apiErrors.add(1);

  const status = http.get(`${BASE}/api/status`, { headers: headers(), timeout: '20s' });
  statusDuration.add(status.timings.duration);
  if (status.status !== 200) apiErrors.add(1);

  const venue = http.get(`${BASE}/api/venues/1`, { headers: headers(), timeout: '20s' });
  venuesDuration.add(venue.timings.duration);
  if (venue.status !== 200 && venue.status !== 404) apiErrors.add(1);

  sleep(0.1 + Math.random() * 0.4);
}

export function handleSummary(data) {
  const p95 = data.metrics.http_req_duration?.values?.['p(95)'] ?? 0;
  const p99 = data.metrics.http_req_duration?.values?.['p(99)'] ?? 0;
  const failRate = (data.metrics.http_req_failed?.values?.rate ?? 0) * 100;
  const rps = data.metrics.http_reqs?.values?.rate ?? 0;
  const maxVu = data.metrics.vus_max?.values?.max ?? 0;
  const totalReqs = data.metrics.http_reqs?.values?.count ?? 0;
  const rateLim = data.metrics.rate_limited_logins?.values?.count ?? 0;

  const md = [
    '# VMS Load Test Summary',
    '',
    '| Metric | Value |',
    '|--------|-------|',
    `| Max concurrent VUs | ${maxVu} |`,
    `| Total HTTP requests | ${totalReqs} |`,
    `| Throughput | ${rps.toFixed(2)} req/s |`,
    `| Failed requests | ${failRate.toFixed(2)}% |`,
    `| p95 latency | ${p95.toFixed(0)} ms |`,
    `| p99 latency | ${p99.toFixed(0)} ms |`,
    `| Login p95 | ${(data.metrics.login_duration?.values?.['p(95)'] ?? 0).toFixed(0)} ms |`,
    `| /api/me p95 | ${(data.metrics.me_duration?.values?.['p(95)'] ?? 0).toFixed(0)} ms |`,
    `| /api/status p95 | ${(data.metrics.status_duration?.values?.['p(95)'] ?? 0).toFixed(0)} ms |`,
    `| Rate-limited logins (429) | ${rateLim} |`,
    '',
  ].join('\n');

  return {
    stdout: md,
    '/scripts/load-test-summary.md': md,
    '/scripts/load-test-results.json': JSON.stringify(data, null, 2),
  };
}
