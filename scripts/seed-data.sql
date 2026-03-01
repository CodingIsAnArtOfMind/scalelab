-- =============================================================================
-- SEED DATA SCRIPT — Phase 1 (Graduated Stages)
-- =============================================================================
-- Purpose: Insert realistic test data for performance experiments.
--          Data grows in stages so you can observe the turning point.
--
-- HOW TO USE:
--   Run with -v flag to set the stage:
--
--   psql -U postgres -d scalelab -v stage=1 -f scripts/seed-data.sql
--   psql -U postgres -d scalelab -v stage=2 -f scripts/seed-data.sql
--   psql -U postgres -d scalelab -v stage=3 -f scripts/seed-data.sql
--   ... and so on
--
--   Stage 1:   10 users,   10 accounts,      100 orders,   10 reports  (basic API testing)
--   Stage 2:   50 users,   50 accounts,    1,000 orders,   50 reports  (verify queries)
--   Stage 3:  100 users,  100 accounts,   10,000 orders,  100 reports  (start noticing latency)
--   Stage 4:  100 users,  100 accounts,   50,000 orders,  100 reports  (full table scan visible)
--   Stage 5:  100 users,  100 accounts,  100,000 orders,  100 reports  (clear performance problem)
--   Stage 6:  200 users,  200 accounts,  200,000 orders,  200 reports  (stress test)
--
-- Each stage RESETS all data and inserts fresh.
-- Uses PostgreSQL generate_series() for fast batch inserts.
-- =============================================================================

-- =============================================================================
-- STAGE CONFIG — resolve stage into user_count and order_count
-- =============================================================================
DROP TABLE IF EXISTS _seed_config;
CREATE TEMP TABLE _seed_config (user_count INT, order_count INT);

INSERT INTO _seed_config
SELECT
    CASE :stage
        WHEN 1 THEN 10
        WHEN 2 THEN 50
        WHEN 3 THEN 100
        WHEN 4 THEN 100
        WHEN 5 THEN 100
        WHEN 6 THEN 200
        ELSE 10
    END,
    CASE :stage
        WHEN 1 THEN 100
        WHEN 2 THEN 1000
        WHEN 3 THEN 10000
        WHEN 4 THEN 50000
        WHEN 5 THEN 100000
        WHEN 6 THEN 200000
        ELSE 100
    END;

-- Show what we're about to insert
\echo ''
\echo '=============================='
\echo '  SEEDING STAGE' :stage
\echo '=============================='
SELECT user_count, order_count FROM _seed_config;

-- =============================================================================
-- RESET — Clean existing data (safe to re-run)
-- =============================================================================
TRUNCATE TABLE reports, orders, accounts, users RESTART IDENTITY CASCADE;

-- =============================================================================
-- 1. INSERT USERS
-- =============================================================================
INSERT INTO users (name, email, created_at, updated_at)
SELECT
    'User_' || i,
    'user' || i || '@scalelab.io',
    NOW() - (random() * interval '30 days'),
    NOW() - (random() * interval '30 days')
FROM generate_series(1, (SELECT user_count FROM _seed_config)) AS i;

-- =============================================================================
-- 2. INSERT ACCOUNTS (1 per user)
-- =============================================================================
INSERT INTO accounts (user_id, balance, created_at, updated_at)
SELECT
    id,
    ROUND((10000 + random() * 490000)::numeric, 2),
    created_at,
    updated_at
FROM users;

-- =============================================================================
-- 3. INSERT ORDERS
-- =============================================================================
-- Realistic trading data:
--   - Spread across all users and their accounts
--   - 10 stock symbols (Indian + US market mix)
--   - Varied quantities (1–500)
--   - Price range: 100 to 3500
--   - Mix of BUY/SELL order types
--   - Status weighted towards EXECUTED (~60%)
--   - created_at spread over last 30 days
-- =============================================================================
INSERT INTO orders (user_id, account_id, symbol, quantity, price, order_type, status, created_at, updated_at)
SELECT
    (1 + floor(random() * (SELECT user_count FROM _seed_config)))::bigint AS user_id,
    (1 + floor(random() * (SELECT user_count FROM _seed_config)))::bigint AS account_id,
    (ARRAY['RELIANCE', 'TCS', 'INFY', 'HDFCBANK', 'ICICIBANK', 'AAPL', 'GOOGL', 'TSLA', 'AMZN', 'NFLX'])
        [1 + floor(random() * 10)::int] AS symbol,
    (1 + floor(random() * 500))::int AS quantity,
    ROUND((100 + random() * 3400)::numeric, 2) AS price,
    (ARRAY['BUY', 'SELL'])[1 + floor(random() * 2)::int] AS order_type,
    (ARRAY['PENDING', 'EXECUTED', 'EXECUTED', 'EXECUTED', 'CANCELLED'])
        [1 + floor(random() * 5)::int] AS status,
    NOW() - (random() * interval '30 days') AS created_at,
    NOW() - (random() * interval '30 days') AS updated_at
FROM generate_series(1, (SELECT order_count FROM _seed_config));

-- =============================================================================
-- 4. INSERT REPORTS (1 per user, computed from actual orders)
-- =============================================================================
-- Each user gets one report with real aggregated data from the orders table.
-- total_orders = count of orders for that user
-- total_volume = sum(price * quantity) for that user
-- This mirrors what the app's ReportService.generateReport() does.
-- =============================================================================
INSERT INTO reports (user_id, total_orders, total_volume, generated_at, updated_at)
SELECT
    u.id AS user_id,
    COALESCE(o.total_orders, 0) AS total_orders,
    COALESCE(o.total_volume, 0) AS total_volume,
    NOW() - (random() * interval '7 days') AS generated_at,
    NOW() - (random() * interval '7 days') AS updated_at
FROM users u
LEFT JOIN (
    SELECT
        user_id,
        count(*) AS total_orders,
        sum(price * quantity) AS total_volume
    FROM orders
    GROUP BY user_id
) o ON u.id = o.user_id;

-- =============================================================================
-- 5. VERIFY COUNTS
-- =============================================================================
\echo ''
\echo '--- Row Counts ---'
SELECT 'users' AS table_name, count(*) AS row_count FROM users
UNION ALL
SELECT 'accounts', count(*) FROM accounts
UNION ALL
SELECT 'orders', count(*) FROM orders
UNION ALL
SELECT 'reports', count(*) FROM reports
ORDER BY table_name;

-- =============================================================================
-- 6. SAMPLE DATA CHECK
-- =============================================================================
\echo ''
\echo '--- Sample Orders ---'
SELECT id, user_id, account_id, symbol, quantity, price, order_type, status, created_at
FROM orders
LIMIT 5;

\echo ''
\echo '--- Status Distribution ---'
SELECT status, count(*) AS count
FROM orders
GROUP BY status
ORDER BY count DESC;

\echo ''
\echo '--- Symbol Distribution ---'
SELECT symbol, count(*) AS count
FROM orders
GROUP BY symbol
ORDER BY count DESC;

\echo ''
\echo '--- Sample Reports ---'
SELECT id, user_id, total_orders, total_volume, generated_at
FROM reports
LIMIT 5;

-- =============================================================================
-- 7. QUICK PERFORMANCE CHECK — observe Seq Scan
-- =============================================================================
\echo ''
\echo '--- Query Plan (expect Seq Scan — no index on user_id) ---'
EXPLAIN ANALYZE SELECT * FROM orders WHERE user_id = 1;

-- Cleanup
DROP TABLE IF EXISTS _seed_config;

\echo ''
\echo '✅ Stage' :stage 'seeding complete!'

