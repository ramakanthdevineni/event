import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const USERNAME = __ENV.TEST_USER || 'admin';
const PASSWORD = __ENV.TEST_PASSWORD || 'Certified01$';

const apiErrors = new Rate('api_errors');
const meDuration = new Trend('me_duration', true);

export const options = {
  scenarios: {
    smoke: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '20s', target: 100 },
        { duration: '40s', target: 500 },
        { duration: '20s', target: 0 },
      ],
    },
  },
  discardResponseBodies: true,
};

function headers() {
  return { Origin: BASE, Referer: `${BASE}/home` };
}

function ensureSession() {
  const me = http.get(`${BASE}/api/me`, { headers: headers(), timeout: '15s' });
  if (me.status === 200) return true;
  const login = http.post(
    `${BASE}/api/login`,
    JSON.stringify({ username: USERNAME, password: PASSWORD }),
    { headers: { ...headers(), 'Content-Type': 'application/json', Referer: `${BASE}/login` }, timeout: '15s' }
  );
  return check(login, { 'login ok': (r) => r.status === 200 });
}

export default function () {
  if (!ensureSession()) {
    apiErrors.add(1);
    sleep(1);
    return;
  }
  const me = http.get(`${BASE}/api/me`, { headers: headers(), timeout: '15s' });
  meDuration.add(me.timings.duration);
  if (me.status !== 200) apiErrors.add(1);
  const status = http.get(`${BASE}/api/status`, { headers: headers(), timeout: '15s' });
  if (status.status !== 200) apiErrors.add(1);
  sleep(0.2);
}

export function handleSummary(data) {
  const fail = ((data.metrics.http_req_failed?.values?.rate ?? 0) * 100).toFixed(2);
  const p95 = (data.metrics.http_req_duration?.values?.['p(95)'] ?? 0).toFixed(0);
  const rps = (data.metrics.http_reqs?.values?.rate ?? 0).toFixed(2);
  const md = `# VMS Verification Load Test (500 VU peak)\n\n| Metric | Value |\n|--------|-------|\n| Throughput | ${rps} req/s |\n| Failed | ${fail}% |\n| p95 | ${p95} ms |\n`;
  return { stdout: md, '/scripts/load-test-verify.md': md };
}
