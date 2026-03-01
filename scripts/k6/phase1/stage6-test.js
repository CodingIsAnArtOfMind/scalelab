// =============================================================================
// STAGE 6 — Stress Test (200 users, 200,000 orders, 150 VUs, 30s, ramped)
// =============================================================================
// Seed:  psql -U postgres -d scalelab -v stage=6 -f scripts/seed-data.sql
// Run:   k6 run scripts/k6/phase1/stage6-test.js
// =============================================================================

import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = 'http://localhost:8080';
const USERS = 200;

export const options = {
    stages: [
        { duration: '10s', target: 50 },    // ramp up
        { duration: '15s', target: 150 },   // hold at peak
        { duration: '5s', target: 0 },      // ramp down
    ],
    thresholds: {
        http_req_duration: ['p(95)<3000'],
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

