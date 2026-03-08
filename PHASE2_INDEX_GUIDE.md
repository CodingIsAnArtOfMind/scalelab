# Phase 2 — Index Implementation Guide

## How We Added Indexes

### Approach: Direct SQL on PostgreSQL

We added indexes directly on the database using SQL scripts, NOT through application code.

**Why not Liquibase / Flyway?**

| Approach | When to use |
|----------|-------------|
| Direct SQL scripts | Learning, experiments, quick testing |
| Liquibase / Flyway | Production projects, team collaboration, version control of DB changes |

In production projects (like Zerodha), you would use Liquibase or Flyway to:
- Track every DB change in version control
- Auto-apply migrations on deployment
- Rollback if something goes wrong

For this learning project, direct SQL is simpler and lets us add/remove indexes instantly to measure impact.

---

## Script Used

File: `scripts/phase2-add-indexes.sql`

Run command:
```
psql -U postgres -d scalelab -f scripts/phase2-add-indexes.sql
```

### Index 1 — Single Column Index on user_id
```sql
CREATE INDEX idx_orders_user_id ON orders(user_id);
```

**What it does:**
- Creates a B-tree index (default index type in PostgreSQL) on the `user_id` column
- PostgreSQL builds a sorted tree structure of all user_id values
- Each entry in the tree points to the actual row location on disk

**Before (no index):**
```
Query: SELECT * FROM orders WHERE user_id = 1

PostgreSQL: "I have no idea where user_id=1 rows are.
             Let me read ALL 200,000 rows and check each one."
             → Seq Scan → 199,044 rows scanned → 26.5ms
```

**After (with index):**
```
Query: SELECT * FROM orders WHERE user_id = 1

PostgreSQL: "Let me check the index tree for user_id=1.
             Found 956 row locations. Let me fetch only those."
             → Bitmap Index Scan → 956 rows scanned → 0.6ms
```

---

### Index 2 — Single Column Index on status
```sql
CREATE INDEX idx_orders_status ON orders(status);
```

**What it does:**
- B-tree index on `status` column
- Since status has low cardinality (only 3 values: PENDING, EXECUTED, CANCELLED),
  this index is less selective but still helps avoid full table scans

**Used by:** `GET /orders/status/{status}`

---

### Index 3 — Composite Index on (status, created_at DESC)
```sql
CREATE INDEX idx_orders_status_created_at ON orders(status, created_at DESC);
```

**What it does:**
- A single index on TWO columns together
- Data is sorted first by `status`, then by `created_at` in descending order within each status
- This is the most powerful index — it handles filtering AND sorting in one operation

**Why composite?** Because our heaviest query filters by status AND sorts by created_at:
```sql
SELECT * FROM orders
WHERE status = 'EXECUTED' AND created_at >= '2026-02-15'
ORDER BY created_at DESC;
```

**Without composite index:**
1. Scan all rows → find matching status
2. Filter by date
3. Sort results in memory (or spill to disk if too large)

**With composite index:**
1. Jump directly to 'EXECUTED' section in the index
2. Walk backwards (already sorted DESC) until created_at < '2026-02-15'
3. No sort needed — data comes out in the right order

**Why DESC in the index?**
Because our queries use `ORDER BY created_at DESC`. If the index stores data in DESC order,
PostgreSQL can read it sequentially without re-sorting. This eliminated the disk spill problem
we saw in Phase 1.

---

## Index Types Explained

### What is a B-tree Index?

B-tree (Balanced Tree) is the default index type in PostgreSQL. Think of it like a phone book:

```
Without index (Seq Scan):
  Read page 1... nope
  Read page 2... nope
  Read page 3... found one!
  Read page 4... nope
  ... read ALL 200,000 pages

With B-tree index:
  Look up "user_id = 1" in the index tree
  Index says: "rows are on pages 5, 12, 47, 89..."
  Jump directly to those pages
```

### What is a Bitmap Index Scan?

This is the scan type PostgreSQL chose for our `user_id` query. It works in 2 steps:

**Step 1 — Bitmap Index Scan:**
- Scan the index to find all matching row locations
- Build a "bitmap" (a map of which disk pages contain matching rows)
- This is fast because it only reads the small index, not the full table

**Step 2 — Bitmap Heap Scan:**
- Use the bitmap to fetch only the pages that contain matching rows
- Read pages in sequential order (efficient for disk I/O)

```
Our query result:
  Bitmap Index Scan on idx_orders_user_id
    Index Cond: (user_id = 1)
    Rows: 956                    ← found exactly 956 matches in the index
  Bitmap Heap Scan on orders
    Heap Blocks: exact=787       ← only needed to read 787 pages (not all 2,523)
    Execution Time: 0.610 ms     ← vs 26.500 ms without index
```

**Why Bitmap instead of regular Index Scan?**
PostgreSQL chooses Bitmap when many rows match (956 rows for user_id=1).
For a query returning just 1-2 rows, it would use a regular Index Scan instead.
Bitmap is more efficient when fetching many scattered rows because it groups page reads together.

### What is an Index Scan?

This is the scan type PostgreSQL chose for our composite index queries. It works differently from Bitmap:

- Walks through the index in order
- For each index entry, immediately fetches the row from the table
- Returns rows in index order (no sorting needed)

```
Our query result:
  Index Scan using idx_orders_status_created_at on orders
    Index Cond: (status = 'EXECUTED' AND created_at >= '2026-02-15')
    Execution Time: 14.365 ms    ← vs 26.615 ms without index
```

PostgreSQL chose Index Scan here (not Bitmap) because:
- The composite index is already sorted by created_at DESC
- Walking the index in order gives us the ORDER BY for free
- No separate sort step needed

---

## Scan Type Comparison

| Scan Type | How it works | When PostgreSQL uses it |
|-----------|-------------|----------------------|
| Seq Scan | Reads every row in the table | No useful index exists |
| Index Scan | Walks the index, fetches rows one by one | Few rows match, or index order matches ORDER BY |
| Bitmap Index Scan | Builds a page map from index, then fetches pages | Many rows match, scattered across pages |
| Index Only Scan | Reads only the index, never touches the table | All needed columns are in the index |

---

## How This Would Look With Liquibase

For reference, if we were using Liquibase in a production project, the same indexes would be defined as:

```xml
<!-- db/changelog/phase2-indexes.xml -->
<databaseChangeLog>
    <changeSet id="phase2-001" author="scalelab">
        <createIndex tableName="orders" indexName="idx_orders_user_id">
            <column name="user_id"/>
        </createIndex>
    </changeSet>

    <changeSet id="phase2-002" author="scalelab">
        <createIndex tableName="orders" indexName="idx_orders_status">
            <column name="status"/>
        </createIndex>
    </changeSet>

    <changeSet id="phase2-003" author="scalelab">
        <sql>
            CREATE INDEX idx_orders_status_created_at ON orders(status, created_at DESC);
        </sql>
    </changeSet>
</databaseChangeLog>
```

We are NOT using Liquibase in this project to keep things simple.
The direct SQL approach is better for experimentation because we can add/remove indexes instantly.

---

## What to Run Next

The indexes are applied on Stage 6 data (200,000 orders). Now we need to load test to measure
the real impact under concurrent traffic.

### Testing Plan

Run these two tests and compare with Phase 1 baselines:

**Test 1 — Stage 5 (100K orders, 100 VUs)**
```
# First re-seed Stage 5 data (indexes will remain)
psql -U postgres -d scalelab -v stage=5 -f scripts/seed-data.sql

# Run load test
k6 run scripts/k6/phase1/stage5-test.js
```

**Test 2 — Stage 6 (200K orders, 150 VUs)**
```
# Re-seed Stage 6 data
psql -U postgres -d scalelab -v stage=6 -f scripts/seed-data.sql

# Run load test
k6 run scripts/k6/phase1/stage6-test.js
```

**Why start from Stage 5 (not Stage 1)?**
Stage 1 and Stage 2 were already fast in Phase 1 (11ms, no bottleneck).
Indexes won't show dramatic improvement on tiny datasets.
The real proof is at Stage 5 and Stage 6 where Phase 1 was failing.

