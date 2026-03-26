// =============================================================================
// PHASE 4 — Write Stress Test (pure write comparison: async vs sync)
// =============================================================================
// Purpose: Isolate the async write buffer improvement.
// Sends 100% order writes — half async, half sync — to directly compare latencies.
//
// Run:   k6 run scripts/k6/phase4/write-stress-test.js
// =============================================================================

import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = 'http://localhost:8080';
const USERS = 100;
const ACCOUNTS = 100;

export const options = {
    stages: [
        { duration: '5s', target: 25 },
        { duration: '15s', target: 100 },
        { duration: '10s', target: 100 },
        { duration: '5s', target: 0 },
    ],
    thresholds: {
        'http_req_duration{type:async_write}': ['p(95)<50', 'avg<10'],
        'http_req_duration{type:sync_write}': ['p(95)<500'],
    },
};

const SYMBOLS = ['AAPL', 'GOOGL', 'MSFT', 'AMZN', 'TSLA', 'NVDA', 'META', 'NFLX'];

function randomInt(min, max) {
    return Math.floor(Math.random() * (max - min + 1)) + min;
}

function randomElement(arr) {
    return arr[Math.floor(Math.random() * arr.length)];
}

function randomPrice() {
    return (Math.random() * 500 + 10).toFixed(2);
}

function makeOrderPayload() {
    return JSON.stringify({
        userId: randomInt(1, USERS),
        accountId: randomInt(1, ACCOUNTS),
        symbol: randomElement(SYMBOLS),
        quantity: randomInt(1, 100),
        price: randomPrice(),
        orderType: randomElement(['BUY', 'SELL']),
    });
}

export default function () {
    const roll = Math.random();

    if (roll < 0.50) {
        // 50% — ASYNC writes (Phase 4)
        const res = http.post(`${BASE_URL}/orders`, makeOrderPayload(), {
            headers: { 'Content-Type': 'application/json' },
            tags: { type: 'async_write' },
        });

        check(res, {
            'ASYNC — 202 ACCEPTED': (r) => r.status === 202,
            'ASYNC — has trackingId': (r) => {
                try { return JSON.parse(r.body).trackingId !== undefined; }
                catch (e) { return false; }
            },
        });
    } else {
        // 50% — SYNC writes (Phase 3 baseline)
        const res = http.post(`${BASE_URL}/orders/sync`, makeOrderPayload(), {
            headers: { 'Content-Type': 'application/json' },
            tags: { type: 'sync_write' },
        });

        check(res, {
            'SYNC — 201 CREATED': (r) => r.status === 201,
        });
    }

    sleep(0.1);
}

// After test completes, check buffer metrics
export function handleSummary(data) {
    // Print buffer flush stats
    const metricsRes = http.get(`${BASE_URL}/orders/buffer/metrics`);
    console.log(`\n📊 Write Buffer Metrics: ${metricsRes.body}\n`);
    return {};
}

