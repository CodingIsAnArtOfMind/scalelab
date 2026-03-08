-- =============================================================================
-- PHASE 2 — Add Indexes to Orders Table
-- =============================================================================
-- Purpose: Fix full table scan problem identified in Phase 1.
-- Only adding indexes — no other changes.
--
-- Run: psql -U postgres -d scalelab -f scripts/phase2-add-indexes.sql
-- =============================================================================

-- Show current indexes BEFORE
\echo ''
\echo '=== BEFORE: Indexes on orders table ==='
SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'orders';

-- =============================================================================
-- INDEX 1: user_id
-- Fixes: GET /orders/{userId}
--        GET /orders/user/{userId}/status/{status}
--        GET /orders/user/{userId}/recent
-- =============================================================================
CREATE INDEX IF NOT EXISTS idx_orders_user_id ON orders(user_id);

-- =============================================================================
-- INDEX 2: status
-- Fixes: GET /orders/status/{status}
-- =============================================================================
CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status);

-- =============================================================================
-- INDEX 3: composite (status, created_at DESC)
-- Fixes: GET /orders/search?status=X&from=DATE
-- The DESC on created_at helps with ORDER BY created_at DESC
-- =============================================================================
CREATE INDEX IF NOT EXISTS idx_orders_status_created_at ON orders(status, created_at DESC);

-- =============================================================================
-- Update query planner statistics after adding indexes
-- =============================================================================
ANALYZE orders;

-- Show indexes AFTER
\echo ''
\echo '=== AFTER: Indexes on orders table ==='
SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'orders';

-- =============================================================================
-- Verify: EXPLAIN ANALYZE with new indexes
-- =============================================================================
\echo ''
\echo '=== Query Plan: WHERE user_id = 1 ==='
EXPLAIN ANALYZE SELECT * FROM orders WHERE user_id = 1;

\echo ''
\echo '=== Query Plan: WHERE status = EXECUTED AND created_at >= date ORDER BY created_at DESC ==='
EXPLAIN ANALYZE SELECT * FROM orders WHERE status = 'EXECUTED' AND created_at >= '2026-02-15' ORDER BY created_at DESC;

\echo ''
\echo '=== Query Plan: WHERE status = EXECUTED ORDER BY created_at DESC ==='
EXPLAIN ANALYZE SELECT * FROM orders WHERE status = 'EXECUTED' ORDER BY created_at DESC;

\echo ''
\echo '✅ Phase 2 indexes applied!'

