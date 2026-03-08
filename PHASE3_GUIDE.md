# Phase 3 — Implementation Guide

## Redis Commands Reference

### Install & Start
```bash
# Install Redis (macOS)
brew install redis

# Start Redis as a background service (auto-starts on login)
brew services start redis

# OR start manually (foreground, stops when terminal closes)
redis-server

# Stop Redis service
brew services stop redis
```

### Verify Redis is Running
```bash
# Ping Redis — should return PONG
redis-cli ping

# Check Redis info
redis-cli INFO server | head -10
```

### Monitor Cache During Load Testing
```bash
# Watch all commands hitting Redis in real-time (run during k6 test)
redis-cli MONITOR

# Check cache stats — hits vs misses
redis-cli INFO stats | grep keyspace_hits
redis-cli INFO stats | grep keyspace_misses

# Count total cached keys
redis-cli DBSIZE

# List all cached keys
redis-cli KEYS '*'

# View a specific cached value
redis-cli GET "orders::user:1:page:0:size:20"

# Flush all cache (reset before a test)
redis-cli FLUSHALL
```

### Useful During Experiments
```bash
# Reset stats before a load test (so hit/miss counts start from 0)
redis-cli CONFIG RESETSTAT

# Check memory usage
redis-cli INFO memory | grep used_memory_human

# Check number of connected clients
redis-cli INFO clients | grep connected_clients
```

---

## Code Changes Explained

### What Changed and Why

Phase 3 adds two optimizations:
1. **Pagination** — return 20 rows per page instead of ALL rows
2. **Redis Caching** — serve repeated requests from memory instead of hitting DB

---

### Full Walkthrough: GET /orders/{userId}

Let's trace what happens when k6 calls `GET /orders/5?page=0&size=20` — step by step.

#### Phase 2 (before — no pagination, no cache)

```
1. k6 sends:  GET /orders/5
2. Controller receives request
3. Service calls: orderRepository.findByUserId(5)
4. Hibernate generates: SELECT * FROM orders WHERE user_id = 5
5. PostgreSQL returns ALL 1000 orders for user 5
6. Service converts all 1000 to OrderResponse objects
7. Controller returns JSON array of 1000 objects
8. k6 receives ~500KB of JSON

Next identical request? Same thing — hits DB again, returns 1000 rows again.
```

#### Phase 3 (after — paginated + cached)

**First request** (cache MISS):
```
1. k6 sends:  GET /orders/5?page=0&size=20

2. Controller receives:
   @GetMapping("/{userId}")
   public ResponseEntity<PagedResponse<OrderResponse>> getOrdersByUserId(
       @PathVariable Long userId,                    // 5
       @RequestParam(defaultValue = "0") int page,   // 0
       @RequestParam(defaultValue = "20") int size)   // 20
   
   Calls: orderService.getOrdersByUserId(5, 0, 20)

3. Service method has @Cacheable:
   @Cacheable(value = "orders", key = "'user:' + #userId + ':page:' + #page + ':size:' + #size")
   
   Spring checks Redis for key: "orders::user:5:page:0:size:20"
   Redis says: KEY NOT FOUND (cache miss)
   
   So the method body executes:

4. Service calls: orderRepository.findByUserId(5, PageRequest.of(0, 20))

5. Hibernate generates:
   SELECT * FROM orders WHERE user_id = 5 ORDER BY id LIMIT 20 OFFSET 0
   
   PostgreSQL returns ONLY 20 rows (not 1000!)

6. Also generates a count query:
   SELECT count(*) FROM orders WHERE user_id = 5
   
   Returns: 1000 (total orders for user 5)

7. Service builds PagedResponse:
   {
     "content": [... 20 orders ...],
     "page": 0,
     "size": 20,
     "totalElements": 1000,
     "totalPages": 50,
     "last": false
   }

8. Spring Cache STORES this response in Redis:
   Key:   "orders::user:5:page:0:size:20"
   Value: (serialized PagedResponse object)
   TTL:   60 seconds

9. Controller returns ~5KB of JSON (vs ~500KB before)
```

**Second identical request** (cache HIT — within 60 seconds):
```
1. k6 sends:  GET /orders/5?page=0&size=20

2. Controller calls: orderService.getOrdersByUserId(5, 0, 20)

3. @Cacheable checks Redis for key: "orders::user:5:page:0:size:20"
   Redis says: FOUND! (cache hit)
   
   THE METHOD BODY NEVER EXECUTES.
   No DB query. No Hibernate. No PostgreSQL.
   
   Spring returns the cached PagedResponse directly.

4. Controller returns the cached response.
   
   Total time: ~0.1ms (Redis lookup) vs ~5ms (DB query)
```

**After 60 seconds** (cache expired):
```
1. TTL expires → Redis deletes the key
2. Next request → cache miss → hits DB again → caches result for another 60s
```

**When a new order is placed** (cache evicted):
```
1. POST /orders  (place new order)

2. Service method has @CacheEvict:
   @CacheEvict(value = "orders", allEntries = true)
   public OrderResponse placeOrder(CreateOrderRequest request)
   
   After the order is saved, ALL entries in the "orders" cache are deleted.
   This ensures stale data is never served.

3. Next GET request → cache miss → fetches fresh data from DB
```

---

### File-by-File Changes

#### 1. pom.xml — Added Dependencies
```xml
<!-- Redis client for Spring Cache -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```
This pulls in Lettuce (Redis client) and Spring Data Redis.

#### 2. application.properties — Redis + Pagination Config
```properties
# Pagination defaults
app.pagination.default-page=0
app.pagination.default-size=20
app.pagination.max-size=100

# Redis
spring.data.redis.host=localhost
spring.data.redis.port=6379
app.cache.ttl-seconds=60
```

#### 3. RedisConfig.java — Cache Manager Setup
```java
@Configuration
@EnableCaching    // This annotation enables Spring's @Cacheable/@CacheEvict
public class RedisConfig {
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // TTL = 60 seconds
        // Uses JDK serialization to store Java objects in Redis
        // Null values are not cached
    }
}
```

#### 4. PagedResponse.java — New DTO
```java
public class PagedResponse<T> implements Serializable {
    private List<T> content;      // the 20 orders on this page
    private int page;             // current page number (0-based)
    private int size;             // page size (20)
    private long totalElements;   // total orders matching the query (e.g. 1000)
    private int totalPages;       // total pages available (e.g. 50)
    private boolean last;         // true if this is the last page
}
```
Implements `Serializable` because Redis needs to serialize it.

#### 5. OrderRepository.java — Added Paginated Queries
```java
// Phase 1/2 — returns ALL rows
List<Order> findByUserId(Long userId);

// Phase 3 — returns one page of rows
Page<Order> findByUserId(Long userId, Pageable pageable);
```
Spring Data JPA automatically generates:
- `SELECT * FROM orders WHERE user_id = ? LIMIT 20 OFFSET 0` (data query)
- `SELECT count(*) FROM orders WHERE user_id = ?` (count query for totalElements)

Both Phase 1/2 methods are kept for backward compatibility.

#### 6. OrderService.java — Added @Cacheable + Pagination
```java
// Phase 2 (before):
public List<OrderResponse> getOrdersByUserId(Long userId) {
    List<Order> orders = orderRepository.findByUserId(userId);  // ALL rows
    return orders.stream().map(this::mapToResponse).collect(...);
}

// Phase 3 (after):
@Cacheable(value = "orders", key = "'user:' + #userId + ':page:' + #page + ':size:' + #size")
public PagedResponse<OrderResponse> getOrdersByUserId(Long userId, int page, int size) {
    Pageable pageable = PageRequest.of(page, size);             // LIMIT 20 OFFSET 0
    Page<Order> orderPage = orderRepository.findByUserId(userId, pageable);
    return toPagedResponse(orderPage);                          // only 20 rows
}
```

#### 7. OrderController.java — Added page/size Parameters
```java
// Phase 2 (before):
@GetMapping("/{userId}")
public ResponseEntity<List<OrderResponse>> getOrdersByUserId(@PathVariable Long userId)

// Phase 3 (after):
@GetMapping("/{userId}")
public ResponseEntity<PagedResponse<OrderResponse>> getOrdersByUserId(
    @PathVariable Long userId,
    @RequestParam(defaultValue = "0") int page,      // optional, defaults to 0
    @RequestParam(defaultValue = "20") int size)      // optional, defaults to 20
```
Max size is capped at 100: `Math.min(size, 100)` to prevent abuse.

#### 8. UserService.java — Added Caching
```java
@Cacheable(value = "users", key = "#id")
public UserResponse getUserById(Long id)
// User data rarely changes — cached for 60s
// Eliminates repeated DB calls during order placement verification
```

#### 9. ReportService.java — Added Caching
```java
@Cacheable(value = "reports", key = "#userId")
public List<ReportResponse> getReportsByUserId(Long userId)

@CacheEvict(value = "reports", key = "#userId")
public ReportResponse generateReport(Long userId)
// Generating a new report evicts the old cached one
```

#### 10. DTOs — Added Serializable
```java
public class OrderResponse implements Serializable { ... }
public class UserResponse implements Serializable { ... }
public class ReportResponse implements Serializable { ... }
```
Required for Redis to serialize/deserialize these objects.

---

### Cache Key Structure

| Endpoint | Cache Name | Redis Key Example |
|----------|-----------|-------------------|
| GET /orders/5?page=0&size=20 | orders | `orders::user:5:page:0:size:20` |
| GET /orders/status/EXECUTED?page=0&size=20 | orders | `orders::status:EXECUTED:page:0:size:20` |
| GET /orders/search?status=EXECUTED&from=...&page=0&size=20 | orders | `orders::search:EXECUTED:from:2026-02-15T00:00:00:page:0:size:20` |
| GET /users/5 | users | `users::5` |
| GET /reports/5 | reports | `reports::5` |

---

### Cache Eviction Rules

| Action | What Gets Evicted | Why |
|--------|-------------------|-----|
| POST /orders (new order) | ALL entries in "orders" cache | New order changes any query result |
| POST /users (new user) | ALL entries in "users" cache | User list changed |
| POST /reports/{userId} | Report for that userId | Report regenerated with fresh data |

---

## Testing Plan

### Prerequisites
```bash
# Redis must be running
redis-cli ping    # should return PONG

# Seed data
psql -U postgres -d scalelab -v stage=5 -f scripts/seed-data.sql

# Flush Redis cache before test
redis-cli FLUSHALL
```

### Run Tests
```bash
# Stage 5 (100K orders, 100 VUs)
k6 run scripts/k6/phase3/stage5-test.js

# Stage 6 (200K orders, 150 VUs)
psql -U postgres -d scalelab -v stage=6 -f scripts/seed-data.sql
k6 run scripts/k6/phase3/stage6-test.js
```

### What to Check After Test
```bash
# Cache hit/miss ratio
redis-cli INFO stats | grep keyspace_hits
redis-cli INFO stats | grep keyspace_misses

# Number of cached keys
redis-cli DBSIZE

# Memory used by cache
redis-cli INFO memory | grep used_memory_human
```

