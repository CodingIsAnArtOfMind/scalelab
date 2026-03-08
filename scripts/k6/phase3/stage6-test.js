// =============================================================================
// PHASE 3 — Stage 6 Test (200K orders, 150 VUs ramped, 30s)
// =============================================================================
// Seed:  psql -U postgres -d scalelab -v stage=6 -f scripts/seed-data.sql
// Run:   k6 run scripts/k6/phase3/stage6-test.js
// =============================================================================

import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = 'http://localhost:8080';
const USERS = 200;

export const options = {
    stages: [
        { duration: '10s', target: 50 },
        { duration: '15s', target: 150 },
        { duration: '5s', target: 0 },
    ],
    thresholds: {
        http_req_duration: ['p(95)<500'],
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
    const page = randomInt(0, 5);
    const size = 20;
    const roll = Math.random();

    if (roll < 0.30) {
        const res = http.get(
            `${BASE_URL}/orders/search?status=${status}&from=${fromDate}&page=${page}&size=${size}`
        );
        check(res, { 'GET /orders/search — status 200': (r) => r.status === 200 });

    } else if (roll < 0.55) {
        const res = http.get(`${BASE_URL}/orders/${userId}?page=${page}&size=${size}`);
        check(res, { 'GET /orders/{userId} — status 200': (r) => r.status === 200 });

    } else if (roll < 0.75) {
        const res = http.get(`${BASE_URL}/orders/status/${status}?page=${page}&size=${size}`);
        check(res, { 'GET /orders/status — status 200': (r) => r.status === 200 });

    } else if (roll < 0.90) {
        const res = http.get(
            `${BASE_URL}/orders/user/${userId}/status/${status}?page=${page}&size=${size}`
        );
        check(res, { 'GET /orders/user/status — status 200': (r) => r.status === 200 });

    } else {
        const res = http.get(
            `${BASE_URL}/orders/user/${userId}/recent?from=${fromDate}&page=${page}&size=${size}`
        );
        check(res, { 'GET /orders/user/recent — status 200': (r) => r.status === 200 });
    }

    sleep(0.5);
}

