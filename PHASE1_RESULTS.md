# Phase 1 — Performance Baseline Results

## Overview
These are the baseline performance numbers **before any optimization**.
No indexes (except primary keys), no caching, no async processing, no pagination.

Use these numbers as reference when we optimize in later phases.

---

## System Configuration

| Component         | Details                              |
|-------------------|--------------------------------------|
| Machine           | MacBook Pro 14-inch M3, 18 GB RAM    |
| JVM               | -Xms512m -Xmx2g                     |
| Spring Boot       | Default HikariCP pool (10 connections) |
| PostgreSQL        | Local, no indexes except primary keys |
| k6 test script    | `scripts/k6/bottleneck-test.js`      |

### Traffic Distribution (bottleneck test)
| Weight | Endpoint                                     | Query Type                        |
|--------|----------------------------------------------|-----------------------------------|
| 30%    | GET /orders/search?status=X&from=DATE        | Status + date range + sort        |
| 25%    | GET /orders/{userId}                         | All orders by user                |
| 20%    | GET /orders/status/{status}                  | All orders by status + sort       |
| 15%    | GET /orders/user/{userId}/status/{status}    | User + status filter + sort       |
| 10%    | GET /orders/user/{userId}/recent?from=DATE   | User + date range + sort          |

---

## Baseline Results (No Indexes, No Optimization)

### Stage 1 — 100 Orders, 20 VUs, 30s

| Metric              | Value      |
|---------------------|------------|
| Orders in DB        | 100        |
| Virtual Users       | 20         |
| Duration            | 30s        |
| Total Requests      | 1,180      |
| Requests/sec        | 39.00      |
| Avg Latency         | 11.91 ms   |
| Median Latency      | 8.62 ms    |
| p90 Latency         | 16.33 ms   |
| p95 Latency         | 18.06 ms   |
| Max Latency         | 177.55 ms  |
| Failed Requests     | 0.00%      |
| Data Received       | 3.6 MB     |

**Observation:** System handles everything with ease. All data fits in memory. No visible stress.

---

### Stage 4 — 50,000 Orders, 50 VUs, 30s

| Metric              | Value      |
|---------------------|------------|
| Orders in DB        | 50,000     |
| Virtual Users       | 50         |
| Duration            | 30s        |
| Total Requests      | 2,731      |
| Requests/sec        | 89.00      |
| Avg Latency         | 54.43 ms   |
| Median Latency      | 21.44 ms   |
| p90 Latency         | 163.11 ms  |
| p95 Latency         | 220.97 ms  |
| Max Latency         | 506.27 ms  |
| Failed Requests     | 0.00%      |
| Data Received       | 3.4 GB     |

**Observation:** Latency increase clearly visible. Median is still low (21ms) but tail latency (p90/p95) is 10x worse than Stage 1. The 3.4 GB data transfer shows PostgreSQL is returning massive result sets due to no pagination.

---

### Stage 5 — 100,000 Orders, 100 VUs, 30s

| Metric              | Value      |
|---------------------|------------|
| Orders in DB        | 100,000    |
| Virtual Users       | 100        |
| Duration            | 30s        |
| Total Requests      | 1,778      |
| Requests/sec        | 55.99      |
| Avg Latency         | 1.23 s     |
| Median Latency      | 1.22 s     |
| p90 Latency         | 1.82 s     |
| p95 Latency         | 2.09 s     |
| Max Latency         | 3.95 s     |
| Failed Requests     | 0.00%      |
| Data Received       | 4.5 GB     |
| Threshold Crossed   | YES - p95 exceeded 2s |

**Observation:** System is clearly struggling. Average latency jumped to 1.23 seconds (103x slower than Stage 1). The p95 threshold FAILED at 2.09s. PostgreSQL is doing full table scans on 100K rows for every request. The 4.5 GB data transfer in 30 seconds shows massive result sets being returned without pagination. Requests/sec dropped compared to Stage 4 despite having 2x more VUs — system is saturated.

---

### Stage 6 — 200,000 Orders, 150 VUs (ramped), 30s

| Metric              | Value      |
|---------------------|------------|
| Orders in DB        | 200,000    |
| Virtual Users       | 150 (ramped: 50 -> 150 -> 0) |
| Duration            | 30s        |
| Total Requests      | 769        |
| Requests/sec        | 23.07      |
| Avg Latency         | 2.80 s     |
| Median Latency      | 2.65 s     |
| p90 Latency         | 5.38 s     |
| p95 Latency         | 5.96 s     |
| Max Latency         | 9.41 s     |
| Failed Requests     | 0.00%      |
| Data Received       | 3.9 GB     |
| Threshold Crossed   | YES - p95 exceeded 3s (5.96s) |

**Observation:** System is completely overwhelmed. Average latency is 2.8 seconds, with some requests taking 9.41 seconds. Throughput collapsed from 56 req/s (Stage 5) to just 23 req/s despite more VUs — the system cannot process requests faster than this. The p95 threshold FAILED badly at 5.96s (limit was 3s). The 10-connection HikariCP pool is saturated — 150 VUs competing for 10 DB connections means massive queuing. This is a production outage scenario.

---

## Degradation Summary

| Metric          | Stage 1 (100) | Stage 4 (50K) | Stage 5 (100K) | Stage 6 (200K) |
|-----------------|---------------|----------------|----------------|----------------|
| VUs             | 20            | 50             | 100            | 150            |
| Avg Latency     | 11.91 ms      | 54.43 ms       | 1.23 s         | 2.80 s         |
| p95 Latency     | 18.06 ms      | 220.97 ms      | 2.09 s         | 5.96 s         |
| Max Latency     | 177.55 ms     | 506.27 ms      | 3.95 s         | 9.41 s         |
| Requests/sec    | 39.00         | 89.00          | 55.99          | 23.07          |
| Failed          | 0.00%         | 0.00%          | 0.00%          | 0.00%          |
| Data Received   | 3.6 MB        | 3.4 GB         | 4.5 GB         | 3.9 GB         |
| Threshold       | PASS          | PASS           | FAIL (p95>2s)  | FAIL (p95>3s)  |

---

## PostgreSQL Query Plan (EXPLAIN ANALYZE)

### How do we know it is a full table scan?

Three pieces of evidence from PostgreSQL:

### Evidence 1 — EXPLAIN ANALYZE shows "Seq Scan"

When you run `EXPLAIN ANALYZE` on any query, PostgreSQL tells you exactly how it executes it.

**Query: SELECT * FROM orders WHERE user_id = 1**
```
Seq Scan on orders  (cost=0.00..2511.00 rows=980 width=68)
  Filter: (user_id = 1)
  Rows Removed by Filter: 99011     <-- scanned 99,011 rows to find 989
  Execution Time: 16.449 ms
```
"Seq Scan" = Sequential Scan = Full Table Scan. PostgreSQL reads EVERY row in the table.

**Query: SELECT * FROM orders WHERE status = 'EXECUTED' AND created_at >= '2026-02-15' ORDER BY created_at DESC**
```
Sort  (Sort Method: quicksort  Memory: 3382kB)
  -> Seq Scan on orders
       Filter: (created_at >= '2026-02-15' AND status = 'EXECUTED')
       Rows Removed by Filter: 70402     <-- scanned 70,402 irrelevant rows
       Execution Time: 26.615 ms
```
Scans all 100K rows, filters down to 29,598, then sorts them in memory.

**Query: SELECT * FROM orders WHERE status = 'EXECUTED' ORDER BY created_at DESC**
```
Sort  (Sort Method: external merge  Disk: 4952kB)    <-- sorting spilled to DISK!
  -> Seq Scan on orders
       Filter: (status = 'EXECUTED')
       Rows Removed by Filter: 40155
       Execution Time: 44.807 ms
```
This is the worst one. Returns ~60K rows. The sort is so large it spills from memory to disk ("external merge Disk: 4952kB").

### Evidence 2 — pg_stat_user_tables shows scan counters

```
table_name | seq_scan     | idx_scan | row_count
-----------+--------------+----------+----------
orders     | 13,029       | 0        | 100,000
users      | 45           | 0        | 100
reports    | 31           | 0        | 100
accounts   | 27           | 0        | 100
```

- `seq_scan = 13,029` means PostgreSQL did 13,029 full table scans on the orders table during our load test
- `idx_scan = 0` means ZERO index scans were used — not a single query used an index
- `seq_tup_read = 765,785,443` means PostgreSQL read 765 MILLION row tuples total (100K rows x ~7,657 scans)

### Evidence 3 — Only one index exists on orders table

```
indexname   | indexdef
------------+--------------------------------------------------
orders_pkey | CREATE UNIQUE INDEX orders_pkey ON orders (id)
```

Only the primary key index (id) exists. No index on user_id, status, or created_at.
So any WHERE clause on those columns forces a full table scan.

### Query Plan Comparison Across Stages

Query: SELECT * FROM orders WHERE user_id = 1

| Stage   | Orders  | Scan Type | Rows Scanned | Rows Returned | Execution Time |
|---------|---------|-----------|--------------|---------------|----------------|
| Stage 1 | 100     | Seq Scan  | 87           | 13            | 0.017 ms       |
| Stage 4 | 50,000  | Seq Scan  | 49,521       | 479           | 2.024 ms       |
| Stage 5 | 100,000 | Seq Scan  | 99,011       | 989           | 16.449 ms      |
| Stage 6 | 200,000 | Seq Scan  | 199,044      | 956           | 26.500 ms      |


---

## DB Stats (from pg_stat_user_tables)

Run after each test:
```sql
SELECT relname, seq_scan, seq_tup_read, idx_scan, n_live_tup
FROM pg_stat_user_tables
WHERE relname = 'orders';
```

### Stage 5 Stats (after load test — stats were cumulative, not reset before test)

| Table    | seq_scan | seq_tup_read  | idx_scan | idx_tup_fetch | row_count |
|----------|----------|---------------|----------|---------------|-----------|
| orders   | 13,029   | 765,785,443   | 0        | 0             | 100,000   |
| users    | 45       | 2,030         | 0        | 0             | 100       |
| reports  | 31       | 565           | 0        | 0             | 100       |
| accounts | 27       | 780           | 0        | 0             | 100       |

### Stage 6 Stats (after load test — stats were reset before test with pg_stat_reset())

| Table    | seq_scan | seq_tup_read  | idx_scan | idx_tup_fetch | row_count |
|----------|----------|---------------|----------|---------------|-----------|
| orders   | 1,942    | 309,400,000   | 0        | 0             | 200,000   |
| users    | 0        | 0             | 0        | 0             | 200       |
| reports  | 0        | 0             | 0        | 0             | 200       |
| accounts | 0        | 0             | 0        | 0             | 200       |

### Key Insight — Stage 6 Efficiency

Stage 6 only completed 769 requests but did 1,942 sequential scans (multiple scans per request because some endpoints trigger multiple queries). Each scan reads all 200,000 rows. Total: 309 MILLION row reads for just 769 API responses.

That means PostgreSQL read ~402,000 rows per API request on average. This is extreme waste.

---

## Connection Pool Analysis

### How to check if the connection pool is full

Run this query in DBeaver **while k6 is running**:
```sql
SELECT state, count(*) AS connections
FROM pg_stat_activity
WHERE datname = 'scalelab'
GROUP BY state
ORDER BY connections DESC;
```

### What the results mean

| state    | meaning                                                    |
|----------|------------------------------------------------------------|
| `active` | Connection is currently executing a query                  |
| `idle`   | Connection is open but not doing anything (waiting in pool)|
| `idle in transaction` | Connection is in a transaction but not executing — BAD if many |

### Connection Pool Configuration

| Setting                  | Value |
|--------------------------|-------|
| HikariCP max pool size   | 10 (Spring Boot default)  |
| PostgreSQL max_connections | 100                      |

### Why you saw 6 active + 11 idle

The 17 total connections break down as:

| Source              | Connections | Why                                      |
|---------------------|-------------|------------------------------------------|
| HikariCP (app)      | 10          | Spring Boot's default connection pool    |
| DBeaver             | ~5-7        | Your DBeaver tabs/sessions               |
| psql / monitoring   | ~1          | Any terminal psql sessions               |

During the load test you saw **6 active** because:
- HikariCP pool has 10 connections
- At that moment 6 of them were executing queries
- The other 4 were idle (between requests)
- The remaining idle connections are DBeaver's

### When is the pool actually exhausted?

The pool is exhausted when **all 10 HikariCP connections are active AND requests start queuing**. You can observe this through:

1. **Latency spike** — requests wait for a free connection (we saw avg 2.8s at Stage 6)
2. **Active connections stay at 10** — run the query repeatedly during load and if active stays at 10, pool is maxed
3. **Spring Boot logs** — HikariCP logs warnings when connections are waited on too long:
   ```
   HikariPool-1 - Connection is not available, request timed out after 30000ms
   ```
4. **Throughput drops** — Stage 6 dropped from 56 req/s to 23 req/s because 150 VUs were competing for 10 connections

### The real bottleneck at Stage 6

```
150 VUs competing for 10 DB connections

Each connection holds a query for ~26ms (single scan) to ~45ms (status scan with sort)
But under concurrent load, queries queue up and total wait becomes 2-9 seconds

This is why avg latency was 2.8s even though a single EXPLAIN ANALYZE shows 26ms
```

### Why didn't we see connection pool exhaustion errors initially?

HikariCP default `connectionTimeout` is **30 seconds**. A thread waits up to 30s for a free connection
before throwing `SQLTransientConnectionException`. Our slowest request was 9.41s — still under 30s.

So the pool WAS saturated (all 10 connections busy), but requests **queued silently** instead of failing.
The 2.8s avg latency was mostly **waiting for a free connection**, not actual query time.

### After lowering connection timeout to 5 seconds — ALL 3 errors appeared:

**Error 1 — Connection Leak Detection (warning)**
```
Connection leak detection triggered for org.postgresql.jdbc.PgConnection@6a6b4cd
on thread http-nio-8080-exec-1, stack trace follows
java.lang.Exception: Apparent connection leak detected
```
A connection was held longer than 2 seconds (our `leak-detection-threshold=2000ms`).
HikariCP thinks it might be leaked because the query is too slow.

**Error 2 — Leak Resolved (info)**
```
Previously reported leaked connection org.postgresql.jdbc.PgConnection@29d66ebb
on thread http-nio-8080-exec-131 was returned to the pool (unleaked)
```
The connection came back eventually — not a real leak, just a very slow query.

**Error 3 — Connection Pool Exhaustion (the critical one)**
```
ScaleLabPool - Connection is not available, request timed out after 5001ms
(total=10, active=10, idle=0, waiting=52)
```
Decoded:
- `total=10` — pool has 10 connections total
- `active=10` — ALL 10 connections are busy executing queries
- `idle=0` — ZERO free connections available
- `waiting=52` — 52 threads are queued waiting for a connection

52 requests stuck in a queue with no DB connection. After 5 seconds of waiting,
HikariCP gave up and threw the error. In production, this means users see 500 errors.

### What happened step by step

```
1. 150 VUs send requests simultaneously
2. HikariCP has 10 connections
3. First 10 requests get connections and start querying
4. Remaining 140 requests enter the wait queue
5. Each query takes 26-45ms, but under load they pile up
6. Connections get recycled, some waiting threads get served
7. But threads waiting longer than 5 seconds get:
   → SQLTransientConnectionException (500 error to the client)
8. At peak: active=10, idle=0, waiting=52
   → 52 threads stuck, system is overwhelmed
```

### HikariCP Settings Used to Expose This

```properties
spring.datasource.hikari.maximum-pool-size=10           # 10 connections (default)
spring.datasource.hikari.connection-timeout=5000        # fail after 5s wait (was 30s)
spring.datasource.hikari.leak-detection-threshold=2000  # warn if connection held > 2s
spring.datasource.hikari.pool-name=ScaleLabPool
logging.level.com.zaxxer.hikari=DEBUG
```

---

## Root Causes Identified

1. **No index on user_id** — every query does a full table scan
2. **No index on status** — filtering by status scans all rows
3. **No index on created_at** — date range queries scan all rows
4. **No pagination** — APIs return ALL matching rows (thousands per request)
5. **No caching** — same data fetched from DB on every request
6. **Synchronous processing** — all operations block the request thread
7. **Connection pool exhaustion** — CONFIRMED: `active=10, idle=0, waiting=52` observed at Stage 6. 150 VUs competing for 10 connections causes timeouts and 500 errors

---

## What Comes Next — Progressive Optimization Roadmap

Each phase fixes ONE problem. We measure the improvement against Phase 1 baselines before moving on.

| Phase | Focus                  | What We Add                                      | Problem It Solves                    |
|-------|------------------------|--------------------------------------------------|--------------------------------------|
| **2** | **Index Optimization** | Indexes on user_id, status, created_at           | Full table scans (Seq Scan)          |
| **3** | **Pagination**         | LIMIT/OFFSET on all list APIs                    | Massive result sets (GB of data)     |
| **4** | **Caching (Redis)**    | Cache frequently accessed data                   | Repeated DB calls for same data      |
| **5** | **Async (Kafka)**      | Queue-based order processing                     | Synchronous bottlenecks, write load  |

**Rule:** Do NOT skip phases. Each optimization is measured independently so we understand its exact impact.

