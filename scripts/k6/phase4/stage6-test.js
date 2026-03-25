// =============================================================================
// PHASE 4 — Stage 6 Test (200K orders, 150 VUs ramped, 30s)
// =============================================================================
// Heavy write-focused test to stress the async write buffer.
// 50% writes (async + sync mix) + 50% reads to show write amplification fix.
//
// Seed:  psql -U postgres -d scalelab -v stage=6 -f scripts/seed-data.sql
// Run:   k6 run scripts/k6/phase4/stage6-test.js
// =============================================================================

import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = 'http://localhost:8080';
const USERS = 200;
const ACCOUNTS = 200;

export const options = {
    stages: [
        { duration: '10s', target: 50 },
        { duration: '15s', target: 150 },
        { duration: '5s', target: 0 },
    ],
    thresholds: {
        'http_req_duration{type:async_write}': ['p(95)<50'],
        'http_req_duration{type:sync_write}': ['p(95)<500'],
        'http_req_duration{type:read}': ['p(95)<500'],
        http_req_duration: ['p(95)<500'],
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

    if (roll < 0.30) {
        // 30% — ASYNC order placement (Phase 4 — the thing we're testing)
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

    } else if (roll < 0.40) {
        // 10% — SYNC order placement (baseline comparison)
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

    } else if (roll < 0.60) {
        // 20% — search by status + date
        const res = http.get(
            `${BASE_URL}/orders/search?status=${status}&from=${fromDate}&page=${page}&size=${size}`,
            { tags: { type: 'read' } }
        );
        check(res, { 'GET /orders/search — status 200': (r) => r.status === 200 });

    } else if (roll < 0.75) {
        // 15% — fetch by userId
        const res = http.get(`${BASE_URL}/orders/${userId}?page=${page}&size=${size}`,
            { tags: { type: 'read' } }
        );
        check(res, { 'GET /orders/{userId} — status 200': (r) => r.status === 200 });

    } else if (roll < 0.85) {
        // 10% — filter by status
        const res = http.get(`${BASE_URL}/orders/status/${status}?page=${page}&size=${size}`,
            { tags: { type: 'read' } }
        );
        check(res, { 'GET /orders/status — status 200': (r) => r.status === 200 });

    } else if (roll < 0.95) {
        // 10% — user + status
        const res = http.get(
            `${BASE_URL}/orders/user/${userId}/status/${status}?page=${page}&size=${size}`,
            { tags: { type: 'read' } }
        );
        check(res, { 'GET /orders/user/status — status 200': (r) => r.status === 200 });

    } else {
        // 5% — buffer metrics
        const res = http.get(`${BASE_URL}/orders/buffer/metrics`,
            { tags: { type: 'read' } }
        );
        check(res, { 'GET /orders/buffer/metrics — status 200': (r) => r.status === 200 });
    }

    sleep(0.3);
}

