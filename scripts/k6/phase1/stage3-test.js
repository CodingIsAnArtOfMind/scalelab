// =============================================================================
// STAGE 3 — Start Noticing Latency (100 users, 10,000 orders, 50 VUs, 30s)
// =============================================================================
// Seed:  psql -U postgres -d scalelab -v stage=3 -f scripts/seed-data.sql
// Run:   k6 run scripts/k6/phase1/stage3-test.js
// =============================================================================

import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = 'http://localhost:8080';
const USERS = 100;

export const options = {
    vus: 50,
    duration: '30s',
    thresholds: {
        http_req_duration: ['p(95)<2000'],
    },
};

const STATUSES = ['PENDING', 'EXECUTED', 'CANCELLED'];

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

export default function () {
    const userId = randomInt(1, USERS);
    const status = randomElement(STATUSES);
    const fromDate = randomRecentDate();
    const roll = Math.random();

    if (roll < 0.30) {
        const res = http.get(`${BASE_URL}/orders/search?status=${status}&from=${fromDate}`);
        check(res, { 'GET /orders/search — status 200': (r) => r.status === 200 });

    } else if (roll < 0.55) {
        const res = http.get(`${BASE_URL}/orders/${userId}`);
        check(res, { 'GET /orders/{userId} — status 200': (r) => r.status === 200 });

    } else if (roll < 0.75) {
        const res = http.get(`${BASE_URL}/orders/status/${status}`);
        check(res, { 'GET /orders/status — status 200': (r) => r.status === 200 });

    } else if (roll < 0.90) {
        const res = http.get(`${BASE_URL}/orders/user/${userId}/status/${status}`);
        check(res, { 'GET /orders/user/status — status 200': (r) => r.status === 200 });

    } else {
        const res = http.get(`${BASE_URL}/orders/user/${userId}/recent?from=${fromDate}`);
        check(res, { 'GET /orders/user/recent — status 200': (r) => r.status === 200 });
    }

    sleep(0.5);
}

