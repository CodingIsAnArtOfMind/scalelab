// =============================================================================
// PHASE 4 — ASYNC Test (ramp to 500 writes/sec + reads)
// =============================================================================
// Purpose: EXACT same load as sync-baseline.js but using POST /orders (async).
// Same VUs, same ramp, same 60/40 write/read ratio, same sleep.
// Compare results side-by-side with sync-baseline.js.
//
// Run sync first:  k6 run scripts/k6/phase4/sync-baseline.js
// Then this:       k6 run scripts/k6/phase4/async-test.js
// =============================================================================

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend, Rate } from 'k6/metrics';

const BASE_URL = 'http://localhost:8080';
const USERS = 100;
const ACCOUNTS = 100;

// ── Custom Metrics ──────────────────────────────────────────────────────────
const asyncWriteLatency = new Trend('async_write_latency', true);
const readLatency = new Trend('read_latency', true);
const asyncWriteCount = new Counter('async_write_count');
const readCount = new Counter('read_count');
const asyncWriteErrors = new Counter('async_write_errors');
const asyncWriteRate = new Rate('async_write_success_rate');

export const options = {
    // EXACT same ramp as sync-baseline.js — apples-to-apples
    stages: [
        { duration: '10s', target: 50 },    // warm up — ~150 writes/sec
        { duration: '15s', target: 100 },   // medium  — ~300 writes/sec
        { duration: '15s', target: 150 },   // heavy   — ~450 writes/sec
        { duration: '20s', target: 200 },   // max     — ~600 writes/sec (push past 500)
        { duration: '10s', target: 0 },     // cool down
    ],
    thresholds: {
        async_write_latency: ['p(95)<100', 'avg<30'],  // async should be MUCH faster
        read_latency: ['p(95)<1000'],
        http_req_failed: ['rate<0.05'],                  // tighter error budget (async is safer)
    },
};

// ── Helpers ─────────────────────────────────────────────────────────────────
const STATUSES = ['PENDING', 'EXECUTED', 'CANCELLED'];
const SYMBOLS = ['AAPL', 'GOOGL', 'MSFT', 'AMZN', 'TSLA', 'NVDA', 'META', 'NFLX'];

function randomInt(min, max) {
    return Math.floor(Math.random() * (max - min + 1)) + min;
}
function randomElement(arr) {
    return arr[Math.floor(Math.random() * arr.length)];
}
function randomRecentDate() {
    const now = new Date();
    now.setDate(now.getDate() - Math.floor(Math.random() * 30));
    return now.toISOString().split('.')[0];
}
function randomPrice() {
    return (Math.random() * 500 + 10).toFixed(2);
}
function makeOrderPayload(userId) {
    return JSON.stringify({
        userId: userId,
        accountId: randomInt(1, ACCOUNTS),
        symbol: randomElement(SYMBOLS),
        quantity: randomInt(1, 100),
        price: randomPrice(),
        orderType: randomElement(['BUY', 'SELL']),
    });
}

// ── Main VU Loop ────────────────────────────────────────────────────────────
export default function () {
    const userId = randomInt(1, USERS);
    const roll = Math.random();

    if (roll < 0.60) {
        // ─── 60% ASYNC WRITES ──────────────────────────────────────
        const res = http.post(`${BASE_URL}/orders`, makeOrderPayload(userId), {
            headers: { 'Content-Type': 'application/json' },
            tags: { name: 'POST /orders (async)' },
        });

        asyncWriteLatency.add(res.timings.duration);
        asyncWriteCount.add(1);
        const ok = check(res, {
            'ASYNC write — 202': (r) => r.status === 202,
            'has trackingId': (r) => {
                try { return JSON.parse(r.body).trackingId !== undefined; }
                catch (e) { return false; }
            },
        });
        asyncWriteRate.add(ok);
        if (!ok) asyncWriteErrors.add(1);

    } else if (roll < 0.75) {
        // ─── 15% READS — search by status + date ───────────────────
        const res = http.get(
            `${BASE_URL}/orders/search?status=${randomElement(STATUSES)}&from=${randomRecentDate()}&page=${randomInt(0, 3)}&size=20`,
            { tags: { name: 'GET /orders/search' } }
        );
        readLatency.add(res.timings.duration);
        readCount.add(1);
        check(res, { 'search — 200': (r) => r.status === 200 });

    } else if (roll < 0.85) {
        // ─── 10% READS — by userId ─────────────────────────────────
        const res = http.get(
            `${BASE_URL}/orders/${userId}?page=${randomInt(0, 3)}&size=20`,
            { tags: { name: 'GET /orders/{userId}' } }
        );
        readLatency.add(res.timings.duration);
        readCount.add(1);
        check(res, { 'user orders — 200': (r) => r.status === 200 });

    } else if (roll < 0.93) {
        // ─── 8% READS — by status ──────────────────────────────────
        const res = http.get(
            `${BASE_URL}/orders/status/${randomElement(STATUSES)}?page=${randomInt(0, 3)}&size=20`,
            { tags: { name: 'GET /orders/status' } }
        );
        readLatency.add(res.timings.duration);
        readCount.add(1);
        check(res, { 'status — 200': (r) => r.status === 200 });

    } else {
        // ─── 7% READS — user + status ──────────────────────────────
        const res = http.get(
            `${BASE_URL}/orders/user/${userId}/status/${randomElement(STATUSES)}?page=${randomInt(0, 3)}&size=20`,
            { tags: { name: 'GET /orders/user/status' } }
        );
        readLatency.add(res.timings.duration);
        readCount.add(1);
        check(res, { 'user+status — 200': (r) => r.status === 200 });
    }

    sleep(0.1);  // same sleep as sync-baseline → same iteration rate
}

// ── Summary ─────────────────────────────────────────────────────────────────
export function handleSummary(data) {
    const duration = 70;
    const m = data.metrics;
    const writes = m.async_write_count ? m.async_write_count.values.count : 0;
    const reads = m.read_count ? m.read_count.values.count : 0;
    const errors = m.async_write_errors ? m.async_write_errors.values.count : 0;
    const w = m.async_write_latency ? m.async_write_latency.values : null;
    const r = m.read_latency ? m.read_latency.values : null;
    const writesPerSec = (writes / duration).toFixed(1);
    const readsPerSec = (reads / duration).toFixed(1);

    console.log('\n' + '='.repeat(65));
    console.log('  PHASE 4 — ASYNC TEST RESULTS');
    console.log('='.repeat(65));
    console.log(`  Total ASYNC writes:    ${writes}  (${writesPerSec}/sec)`);
    console.log(`  Total reads:           ${reads}  (${readsPerSec}/sec)`);
    console.log(`  Write errors:          ${errors}`);
    console.log(`  ──────────────────────────────────────`);
    if (w) {
        console.log(`  ASYNC write avg:       ${w.avg.toFixed(2)} ms`);
        console.log(`  ASYNC write p90:       ${w['p(90)'].toFixed(2)} ms`);
        console.log(`  ASYNC write p95:       ${w['p(95)'].toFixed(2)} ms`);
        console.log(`  ASYNC write min:       ${w.min.toFixed(2)} ms`);
        console.log(`  ASYNC write max:       ${w.max.toFixed(2)} ms`);
    }
    console.log(`  ──────────────────────────────────────`);
    if (r) {
        console.log(`  Read avg:              ${r.avg.toFixed(2)} ms`);
        console.log(`  Read p95:              ${r['p(95)'].toFixed(2)} ms`);
    }
    console.log('='.repeat(65));
    console.log('  Check buffer metrics:');
    console.log('  curl http://localhost:8080/orders/buffer/metrics');
    console.log('='.repeat(65));
    console.log('  🔥 Compare with SYNC BASELINE!');
    console.log('='.repeat(65) + '\n');

    return {};
}

