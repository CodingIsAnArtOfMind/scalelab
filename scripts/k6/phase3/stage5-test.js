// =============================================================================
// PHASE 3 — Stage 5 Test (100K orders, 100 VUs, 30s)
// =============================================================================
// Pagination + Redis caching enabled.
// All endpoints now use ?page=0&size=20 (returns 20 rows per page, not ALL)
// Repeated requests hit Redis cache instead of DB.
//
// Seed:  psql -U postgres -d scalelab -v stage=5 -f scripts/seed-data.sql
// Run:   k6 run scripts/k6/phase3/stage5-test.js
// =============================================================================

import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = 'http://localhost:8080';
const USERS = 100;

export const options = {
    vus: 100,
    duration: '30s',
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
    const page = randomInt(0, 5);  // random page 0-5
    const size = 20;               // fixed page size
    const roll = Math.random();

    if (roll < 0.30) {
        // 30% — search by status + date (paginated)
        const res = http.get(
            `${BASE_URL}/orders/search?status=${status}&from=${fromDate}&page=${page}&size=${size}`
        );
        check(res, { 'GET /orders/search — status 200': (r) => r.status === 200 });

    } else if (roll < 0.55) {
        // 25% — fetch by userId (paginated)
        const res = http.get(`${BASE_URL}/orders/${userId}?page=${page}&size=${size}`);
        check(res, { 'GET /orders/{userId} — status 200': (r) => r.status === 200 });

    } else if (roll < 0.75) {
        // 20% — filter by status (paginated)
        const res = http.get(`${BASE_URL}/orders/status/${status}?page=${page}&size=${size}`);
        check(res, { 'GET /orders/status — status 200': (r) => r.status === 200 });

    } else if (roll < 0.90) {
        // 15% — user + status (paginated)
        const res = http.get(
            `${BASE_URL}/orders/user/${userId}/status/${status}?page=${page}&size=${size}`
        );
        check(res, { 'GET /orders/user/status — status 200': (r) => r.status === 200 });

    } else {
        // 10% — user recent orders (paginated)
        const res = http.get(
            `${BASE_URL}/orders/user/${userId}/recent?from=${fromDate}&page=${page}&size=${size}`
        );
        check(res, { 'GET /orders/user/recent — status 200': (r) => r.status === 200 });
    }

    sleep(0.5);
}

