-- =============================================================================
-- DB MONITORING QUERIES — Run in DBeaver during load testing
-- =============================================================================
-- Open each query in a separate DBeaver tab so you can refresh quickly.
-- Run these WHILE k6 is running to observe real-time DB behavior.
-- =============================================================================


-- =========================================================================
-- 1. ACTIVE QUERIES — See what PostgreSQL is doing right now
-- =========================================================================
-- Look for: long-running queries, many "active" connections, waiting queries
SELECT
    pid,
    state,
    query_start,
    NOW() - query_start AS duration,
    LEFT(query, 80) AS query_preview
FROM pg_stat_activity
WHERE datname = 'scalelab'
  AND state != 'idle'
ORDER BY query_start;


-- =========================================================================
-- 2. CONNECTION COUNT — How many connections are open?
-- =========================================================================
-- HikariCP default pool = 10 connections.
-- If you see 10 active connections, pool is exhausted → requests start waiting.
SELECT
    state,
    count(*) AS connections
FROM pg_stat_activity
WHERE datname = 'scalelab'
GROUP BY state
ORDER BY connections DESC;


-- =========================================================================
-- 3. SLOW QUERIES — Which queries are taking the longest?
-- =========================================================================
-- Look for Seq Scan queries with high total_exec_time.
-- Reset stats first if needed: SELECT pg_stat_statements_reset();
-- NOTE: Requires pg_stat_statements extension (may not be enabled by default)
-- If not available, skip this query.
SELECT
    LEFT(query, 100) AS query_preview,
    calls,
    ROUND(total_exec_time::numeric, 2) AS total_ms,
    ROUND(mean_exec_time::numeric, 2) AS avg_ms,
    ROUND(max_exec_time::numeric, 2) AS max_ms,
    rows
FROM pg_stat_statements
WHERE query LIKE '%orders%'
ORDER BY total_exec_time DESC
LIMIT 10;


-- =========================================================================
-- 4. TABLE SCAN STATS — Is PostgreSQL doing sequential scans?
-- =========================================================================
-- seq_scan = full table scans (BAD at scale)
-- idx_scan = index scans (GOOD)
-- Right now idx_scan should be 0 for orders (no indexes!)
SELECT
    relname AS table_name,
    seq_scan,
    seq_tup_read,
    idx_scan,
    idx_tup_fetch,
    n_live_tup AS row_count
FROM pg_stat_user_tables
WHERE relname IN ('users', 'accounts', 'orders', 'reports')
ORDER BY seq_scan DESC;


-- =========================================================================
-- 5. TABLE SIZE — How much disk space are tables using?
-- =========================================================================
SELECT
    relname AS table_name,
    pg_size_pretty(pg_total_relation_size(relid)) AS total_size,
    pg_size_pretty(pg_relation_size(relid)) AS data_size,
    n_live_tup AS row_count
FROM pg_stat_user_tables
WHERE relname IN ('users', 'accounts', 'orders', 'reports')
ORDER BY pg_total_relation_size(relid) DESC;


-- =========================================================================
-- 6. LOCK MONITORING — Are queries blocking each other?
-- =========================================================================
-- During heavy writes (POST /orders), look for lock contention.
SELECT
    blocked.pid AS blocked_pid,
    blocked.query AS blocked_query,
    blocking.pid AS blocking_pid,
    blocking.query AS blocking_query
FROM pg_locks bl
JOIN pg_stat_activity blocked ON bl.pid = blocked.pid
JOIN pg_locks kl ON bl.locktype = kl.locktype
    AND bl.relation = kl.relation
    AND bl.pid != kl.pid
JOIN pg_stat_activity blocking ON kl.pid = blocking.pid
WHERE NOT bl.granted;


-- =========================================================================
-- 7. RESET SCAN STATS (run BEFORE starting a load test)
-- =========================================================================
-- This resets the seq_scan / idx_scan counters so you get clean numbers
-- for each test run.
-- SELECT pg_stat_reset();

