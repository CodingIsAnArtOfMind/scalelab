# ScaleLab — Phase 1 Overview
## Zerodha-Inspired Systems Engineering Lab

---

## What Are We Building?

A **simplified trading/order platform** backend — inspired by how Zerodha works internally.
This is NOT a production system. This is a **learning lab** where we intentionally build things
the wrong way first, observe the problems, and then fix them step by step.

Think of it like this:
> Zerodha handles millions of orders per day. We're going to build a system that would
> completely fall apart at that scale — and then learn WHY it falls apart and HOW to fix it.

---

## Phase 1 — The Naive Monolith

### Architecture

```
Client (curl / Postman)
        │
        ▼
  REST Controllers          ← receives HTTP requests
        │
        ▼
   Service Layer            ← business logic (all synchronous)
        │
        ▼
   JPA Repository           ← ORM queries (no optimization)
        │
        ▼
    PostgreSQL              ← single database, no indexes, no read replicas
```

**Everything is intentionally simple and unoptimized.**

---

### What We Have (4 Modules)

#### 1. User Management
- Create users (name + email)
- Fetch users
- `POST /users` → `GET /users` → `GET /users/{id}`

#### 2. Account Management
- Create trading accounts linked to users
- Each account has a balance
- `POST /accounts` → `GET /accounts/user/{userId}`

#### 3. Order Placement
- Place BUY/SELL orders for stock symbols
- Orders start as PENDING (no actual execution logic yet)
- `POST /orders` → `GET /orders/{userId}`

#### 4. Basic Reporting
- Generate reports by computing order aggregates ON THE FLY
- Every report generation loads ALL orders from DB into memory, calculates totals
- `POST /reports/{userId}` → `GET /reports/{userId}`

---

### Database Tables (4 tables, NO indexes except primary keys)

```
┌──────────────┐     ┌──────────────┐     ┌──────────────────┐     ┌──────────────┐
│    users     │     │   accounts   │     │     orders       │     │   reports    │
├──────────────┤     ├──────────────┤     ├──────────────────┤     ├──────────────┤
│ id (PK)      │     │ id (PK)      │     │ id (PK)          │     │ id (PK)      │
│ name         │     │ user_id      │     │ user_id          │     │ user_id      │
│ email        │     │ balance      │     │ account_id       │     │ total_orders │
│ created_at   │     │ created_at   │     │ symbol           │     │ total_volume │
│ updated_at   │     │ updated_at   │     │ quantity         │     │ generated_at │
└──────────────┘     └──────────────┘     │ price            │     │ updated_at   │
                                          │ order_type       │     └──────────────┘
                                          │ status           │
                                          │ created_at       │
                                          │ updated_at       │
                                          └──────────────────┘

⚠️  NO indexes on user_id, account_id, symbol, or status
⚠️  NO foreign key constraints enforced at DB level
⚠️  Lookups by user_id will do FULL TABLE SCANS
```

### Timestamps & Execution Time Logging

All entities have `created_at` and `updated_at` timestamps. These are auto-set via JPA
lifecycle hooks (`@PrePersist` / `@PreUpdate`). This is critical for later experiments:
- Orders in last 5 minutes
- Orders during market open spike
- Time-based filtering and debugging

All critical service methods log execution time in milliseconds:
```
Placing order — took 35 ms
Generating report — took 120 ms
Fetching orders — took 8 ms
```
This makes it easy to spot slowdowns during load testing without needing external tools.

---

### What Problems Are We Setting Up? (Preview of Future Experiments)

| # | Problem | Where It Lives In Our Code | What Will Break |
|---|---------|---------------------------|-----------------|
| 1 | **Full Table Scan** | `OrderRepository.findByUserId()` — no index on user_id | Queries get slower as orders table grows to millions of rows |
| 2 | **Reporting Blocks Trading** | `ReportService.generateReport()` runs heavy aggregation on same DB as order placement | Report queries lock rows and slow down order inserts |
| 3 | **Synchronous Processing** | `OrderService.placeOrder()` does user lookup + account lookup + order insert all inline | API latency stacks up under high concurrency |
| 4 | **Connection Pool Exhaustion** | Default HikariCP pool (10 connections) with no tuning | 50+ concurrent requests = timeouts |
| 5 | **N+1 Queries** | No JPA relationships defined, but when we add them with lazy loading — boom | Fetching 100 orders fires 101 SQL queries |
| 6 | **No Cache** | Every `findById()` hits PostgreSQL directly, every single time | Repeated reads for same user = wasted DB round trips |
| 7 | **Load Test Collapse** | Everything above combined under 1000+ concurrent users | System response time goes from 5ms to 30+ seconds |

---

### What Phase 1 Does NOT Have (Intentionally)

- ❌ No indexes (except auto-generated primary keys)
- ❌ No caching (Redis comes later)
- ❌ No message queues (Kafka comes later)
- ❌ No async processing
- ❌ No pagination on queries
- ❌ No connection pool tuning
- ❌ No rate limiting
- ❌ No monitoring/metrics (Prometheus/Grafana come later)
- ❌ No read replicas or DB separation

---

### How to Run Phase 1

```bash
# 1. Make sure PostgreSQL is running locally
# 2. Create the database (one time)
psql -U postgres -c "CREATE DATABASE scalelab;"

# 3. Start the app
./mvnw spring-boot:run

# 4. Test the APIs
# Create a user
curl -X POST http://localhost:8080/users \
  -H "Content-Type: application/json" \
  -d '{"name":"Raza","email":"raza@test.com"}'

# Create an account
curl -X POST http://localhost:8080/accounts \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"balance":100000.00}'

# Place an order
curl -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"accountId":1,"symbol":"RELIANCE","quantity":10,"price":2450.50,"orderType":"BUY"}'

# Get orders for a user
curl http://localhost:8080/orders/1

# Generate a report
curl -X POST http://localhost:8080/reports/1

# Get reports for a user
curl http://localhost:8080/reports/1
```

---

### What Happens Next?

After Phase 1 is running, we go experiment by experiment:

```
Phase 1 (NOW)  → Build naive system, get it running
Experiment 1   → Insert 1M orders, observe full table scan slowness
Experiment 2   → Run reports while placing orders, watch them block each other
Experiment 3   → Hit /orders with 100 concurrent requests, measure latency spike
Experiment 4   → Push 200 concurrent connections, watch pool exhaustion
Experiment 5   → Add JPA relationships, observe N+1 query explosion
Experiment 6   → Benchmark repeated reads, see unnecessary DB load
Experiment 7   → Full load test — watch everything collapse together
Final          → Burst write test — understand write amplification
```

Each experiment = observe the problem → measure it → understand why → then fix it.

**That's the whole point. We learn by breaking things.**

