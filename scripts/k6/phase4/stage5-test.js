// =============================================================================
// PHASE 4 — Stage 5 Test (100K orders, 100 VUs, 30s)
// =============================================================================
// Async order placement + write buffering enabled.
// POST /orders returns 202 ACCEPTED immediately (zero DB calls on request thread).
// Orders batch-flushed to DB every 100ms.
// Mix of async writes (40%) + paginated reads (60%) to simulate real traffic.
//
// Seed:  psql -U postgres -d scalelab -v stage=5 -f scripts/seed-data.sql
// Run:   k6 run scripts/k6/phase4/stage5-test.js
// =============================================================================

import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = 'http://localhost:8080';
const USERS = 100;
const ACCOUNTS = 100;

export const options = {
    vus: 100,
    duration: '30s',
    thresholds: {
        'http_req_duration{type:async_write}': ['p(95)<50'],   // async writes should be < 50ms
        'http_req_duration{type:sync_write}': ['p(95)<500'],   // sync writes (baseline comparison)
        'http_req_duration{type:read}': ['p(95)<500'],         // reads same as Phase 3
        http_req_duration: ['p(95)<500'],                       // overall threshold
    },
};

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
    const daysAgo = Math.floor(Math.random() * 30);
    now.setDate(now.getDate() - daysAgo);
    return now.toISOString().split('.')[0];
}

function randomPrice() {
    return (Math.random() * 500 + 10).toFixed(2);
}

export default function () {
    const userId = randomInt(1, USERS);
    const status = randomElement(STATUSES);
    const fromDate = randomRecentDate();
    const page = randomInt(0, 5);
    const size = 20;
    const roll = Math.random();

    if (roll < 0.20) {
        // 20% — ASYNC order placement (Phase 4 — returns 202 immediately)
        const payload = JSON.stringify({
            userId: userId,
            accountId: randomInt(1, ACCOUNTS),
            symbol: randomElement(SYMBOLS),
            quantity: randomInt(1, 100),
            price: randomPrice(),
            orderType: randomElement(['BUY', 'SELL']),
        });

        const res = http.post(`${BASE_URL}/orders`, payload, {
            headers: { 'Content-Type': 'application/json' },
            tags: { type: 'async_write' },
        });

        check(res, {
            'POST /orders ASYNC — status 202': (r) => r.status === 202,
            'POST /orders ASYNC — has trackingId': (r) => {
                try {
                    const body = JSON.parse(r.body);
                    return body.trackingId !== undefined;
                } catch (e) {
                    return false;
                }
            },
        });

    } else if (roll < 0.30) {
        // 10% — SYNC order placement (Phase 3 baseline — for comparison)
        const payload = JSON.stringify({
            userId: userId,
            accountId: randomInt(1, ACCOUNTS),
            symbol: randomElement(SYMBOLS),
            quantity: randomInt(1, 100),
            price: randomPrice(),
            orderType: randomElement(['BUY', 'SELL']),
        });

        const res = http.post(`${BASE_URL}/orders/sync`, payload, {
            headers: { 'Content-Type': 'application/json' },
            tags: { type: 'sync_write' },
        });

        check(res, {
            'POST /orders/sync — status 201': (r) => r.status === 201,
        });

    } else if (roll < 0.50) {
        // 20% — search by status + date (paginated)
        const res = http.get(
            `${BASE_URL}/orders/search?status=${status}&from=${fromDate}&page=${page}&size=${size}`,
            { tags: { type: 'read' } }
        );
        check(res, { 'GET /orders/search — status 200': (r) => r.status === 200 });

    } else if (roll < 0.70) {
        // 20% — fetch by userId (paginated)
        const res = http.get(`${BASE_URL}/orders/${userId}?page=${page}&size=${size}`,
            { tags: { type: 'read' } }
        );
        check(res, { 'GET /orders/{userId} — status 200': (r) => r.status === 200 });

    } else if (roll < 0.85) {
        // 15% — filter by status (paginated)
        const res = http.get(`${BASE_URL}/orders/status/${status}?page=${page}&size=${size}`,
            { tags: { type: 'read' } }
        );
        check(res, { 'GET /orders/status — status 200': (r) => r.status === 200 });

    } else if (roll < 0.95) {
        // 10% — user + status (paginated)
        const res = http.get(
            `${BASE_URL}/orders/user/${userId}/status/${status}?page=${page}&size=${size}`,
            { tags: { type: 'read' } }
        );
        check(res, { 'GET /orders/user/status — status 200': (r) => r.status === 200 });

    } else {
        // 5% — check buffer metrics (monitoring)
        const res = http.get(`${BASE_URL}/orders/buffer/metrics`,
            { tags: { type: 'read' } }
        );
        check(res, { 'GET /orders/buffer/metrics — status 200': (r) => r.status === 200 });
    }

    sleep(0.3);
}

