// =============================================================================
// PHASE 4 — SYNC Baseline Test (ramp to 500 writes/sec + reads)
// =============================================================================
// Purpose: Establish SYNC write baseline before comparing with async.
// Ramps from 50 → 200 VUs. 60% writes + 40% reads mixed.
// At 200 VUs with sleep(0.1): ~1000 req/sec total → ~600 writes/sec.
// This WILL stress sync writes — thread pool contention, connection pool pressure.
//
// Seed first:  psql -U postgres -d scalelab -v stage=5 -f scripts/seed-data.sql
// Run:         k6 run scripts/k6/phase4/sync-baseline.js
// Then run:    k6 run scripts/k6/phase4/async-test.js (compare results)
// =============================================================================

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend, Rate } from 'k6/metrics';

const BASE_URL = 'http://localhost:8080';
const USERS = 100;
const ACCOUNTS = 100;

// ── Custom Metrics ──────────────────────────────────────────────────────────
const syncWriteLatency = new Trend('sync_write_latency', true);
const readLatency = new Trend('read_latency', true);
const syncWriteCount = new Counter('sync_write_count');
const readCount = new Counter('read_count');
const syncWriteErrors = new Counter('sync_write_errors');
const syncWriteRate = new Rate('sync_write_success_rate');

export const options = {
    // Gradual ramp: 50 → 100 → 150 → 200 VUs
    // Each VU does ~5 iterations/sec (sleep 0.1 + ~100ms response)
    // At 200 VUs: ~1000 req/sec total → ~600 writes/sec (60% ratio)
    stages: [
        { duration: '10s', target: 50 },    // warm up — ~150 writes/sec
        { duration: '15s', target: 100 },   // medium  — ~300 writes/sec
        { duration: '15s', target: 150 },   // heavy   — ~450 writes/sec
        { duration: '20s', target: 200 },   // max     — ~600 writes/sec (push past 500)
        { duration: '10s', target: 0 },     // cool down
    ],
    thresholds: {
        sync_write_latency: ['p(95)<1000'],   // sync will be slow — 1s threshold
        read_latency: ['p(95)<1000'],
        http_req_failed: ['rate<0.10'],        // allow up to 10% errors under stress
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

// ── Main VU Loop ────────────────────────────────────────────────────��───────
export default function () {
    const userId = randomInt(1, USERS);
    const roll = Math.random();

    if (roll < 0.60) {
        // ─── 60% SYNC WRITES ───────────────────────────────────────
        const res = http.post(`${BASE_URL}/orders/sync`, makeOrderPayload(userId), {
            headers: { 'Content-Type': 'application/json' },
            tags: { name: 'POST /orders/sync' },
        });

        syncWriteLatency.add(res.timings.duration);
        syncWriteCount.add(1);
        const ok = check(res, { 'SYNC write — 201': (r) => r.status === 201 });
        syncWriteRate.add(ok);
        if (!ok) syncWriteErrors.add(1);

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

    sleep(0.1);  // ~5 iterations/sec per VU
}

// ── Summary ─────────────────────────────────────────────────────────────────
export function handleSummary(data) {
    const duration = 70;
    const m = data.metrics;
    const writes = m.sync_write_count ? m.sync_write_count.values.count : 0;
    const reads = m.read_count ? m.read_count.values.count : 0;
    const errors = m.sync_write_errors ? m.sync_write_errors.values.count : 0;
    const w = m.sync_write_latency ? m.sync_write_latency.values : null;
    const r = m.read_latency ? m.read_latency.values : null;
    const writesPerSec = (writes / duration).toFixed(1);
    const readsPerSec = (reads / duration).toFixed(1);

    console.log('\n' + '='.repeat(65));
    console.log('  PHASE 4 — SYNC BASELINE RESULTS');
    console.log('='.repeat(65));
    console.log(`  Total SYNC writes:     ${writes}  (${writesPerSec}/sec)`);
    console.log(`  Total reads:           ${reads}  (${readsPerSec}/sec)`);
    console.log(`  Write errors:          ${errors}`);
    console.log(`  ──────────────────────────────────────`);
    if (w) {
        console.log(`  SYNC write avg:        ${w.avg.toFixed(2)} ms`);
        console.log(`  SYNC write p90:        ${w['p(90)'].toFixed(2)} ms`);
        console.log(`  SYNC write p95:        ${w['p(95)'].toFixed(2)} ms`);
        console.log(`  SYNC write min:        ${w.min.toFixed(2)} ms`);
        console.log(`  SYNC write max:        ${w.max.toFixed(2)} ms`);
    }
    console.log(`  ──────────────────────────────────────`);
    if (r) {
        console.log(`  Read avg:              ${r.avg.toFixed(2)} ms`);
        console.log(`  Read p95:              ${r['p(95)'].toFixed(2)} ms`);
    }
    console.log('='.repeat(65));
    console.log('  ⏭️  Now run: k6 run scripts/k6/phase4/async-test.js');
    console.log('='.repeat(65) + '\n');

    return {};
}

