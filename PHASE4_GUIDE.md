# Phase 4 — Async Order Processing + Write Buffering

## Problems Identified (from Phase 3)

### 1. Synchronous POST /orders blocks request thread
```
Request Thread → findUser(DB call #1) → findAccount(DB call #2) → save(DB call #3) → respond
Total: 3 DB round-trips, ~5-15ms blocking the Tomcat thread
```
Under 100 VUs all firing POST /orders, all 200 Tomcat threads get tied up waiting for DB.

### 2. Write amplification — every POST hits DB directly
```
100 concurrent POSTs → 100 individual INSERT statements → 100 DB round-trips
```
No batching. Each order is its own transaction. Connection pool (10 connections) saturates.

---

## Solution: CompletableFuture + Write Buffer

### Why CompletableFuture (not WebFlux)? — Deep Dive

#### The core question: What happens at 1000 requests/sec?

**For POST /orders (async write buffer):**
Both approaches are equally fast. The request thread does zero DB calls — it pushes to an in-memory queue and returns. Whether that thread is a Tomcat thread (Spring MVC) or a Netty event loop thread (WebFlux), it's < 1ms either way. No difference.

**For GET /orders/stream/{userId} (SSE connections):**
This is where the difference matters. Let's understand both thread models:

#### Spring MVC + SseEmitter (what we use)

```
How SseEmitter actually works:

1. Client calls GET /orders/stream/1
2. Tomcat thread handles the request, creates SseEmitter, returns it
3. Spring MVC switches to ASYNC servlet mode (Servlet 3.1+)
4. Tomcat thread is RELEASED back to the pool     ← key point
5. SseEmitter holds only: open TCP socket + async context (no thread)
6. When we call emitter.send(), it briefly borrows a thread to write bytes

So: 1000 SSE connections ≠ 1000 blocked threads
    1000 SSE connections = 1000 open TCP sockets + 1000 async contexts
```

**What actually limits us (Spring MVC SSE):**

| Resource | Limit | Impact at 1000 SSE connections |
|----------|-------|-------------------------------|
| Tomcat threads | 200 (default) | **NOT consumed** — released after subscribe() returns |
| TCP sockets | OS file descriptor limit (~10K) | 1000 is fine |
| Memory per socket | ~16KB kernel buffer | 1000 × 16KB = 16MB — fine |
| emitter.send() calls | Brief thread borrow from Tomcat pool | At 10 events/sec × 1000 users = 10K writes/sec — could contend |

**The real bottleneck:** When `emitter.send()` is called, it needs to write to the socket. In Spring MVC, this write happens on a Tomcat NIO thread. If we push 10,000 SSE events/sec (1000 users × 10 events each), those writes compete with incoming HTTP requests for the same Tomcat thread pool.

Under heavy load (1000 req/sec incoming + 10K SSE writes/sec), **thread contention on the Tomcat pool** becomes the bottleneck — not thread exhaustion.

#### WebFlux + Flux SSE (the alternative)

```
How WebFlux SSE works:

1. Client calls GET /orders/stream/1
2. Netty event loop thread handles the request (there are only 2-4 of these)
3. Returns Flux<OrderProgressEvent> — a reactive stream
4. Netty event loop writes SSE events NON-BLOCKING using epoll/kqueue
5. Zero thread is ever blocked or borrowed for writes
6. All I/O is handled by the OS kernel's event notification system

So: 1000 SSE connections = 1000 open sockets, ~0 threads consumed
    10,000 SSE writes/sec = handled by 2-4 event loop threads
```

#### Head-to-head comparison

| Factor | Spring MVC + SseEmitter | WebFlux + Flux SSE |
|--------|------------------------|-------------------|
| **Thread model** | Thread-per-request (but SSE uses async servlet) | Event loop (2-4 threads total) |
| **SSE connection cost** | 1 open socket + async context, no thread held | 1 open socket, no thread held |
| **SSE write cost** | Borrows Tomcat thread briefly per send() | Zero — non-blocking kernel I/O |
| **1000 SSE connections** | ✅ Works — sockets only, threads free | ✅ Works — even more efficient |
| **10K SSE writes/sec** | ⚠️ Competes with HTTP requests for thread pool | ✅ Handled by event loop, no contention |
| **1000 SSE + 1000 HTTP req/sec** | ⚠️ Thread pool pressure (SSE writes + HTTP) | ✅ All non-blocking, no pressure |
| **JPA/Hibernate** | ✅ Full support | ❌ Blocks event loop → defeats purpose |
| **Redis cache** | ✅ Spring Cache works | Needs reactive Lettuce client |
| **Code complexity** | Low (imperative) | High (reactive, Mono/Flux everywhere) |
| **Migration effort** | Zero (existing stack) | Rewrite 80%+ of the app |
| **DB driver** | JDBC (blocking, works fine) | Needs R2DBC (reactive, different API) |

#### Why we chose SseEmitter (not WebFlux) for Phase 4

1. **Our SSE load is light.** Users watching progress = maybe 50-100 concurrent SSE connections, not 10,000. SseEmitter handles this easily.

2. **JPA is blocking.** Our entire data layer (repositories, Hibernate, HikariCP) is blocking JDBC. WebFlux with blocking DB calls is **worse** than Spring MVC — it blocks the few event loop threads, freezing the entire server. We'd need to rewrite everything to R2DBC.

3. **The real write bottleneck is DB, not threads.** The write buffer + batch flush already solved the core problem. WebFlux wouldn't make `saveAll()` faster — the DB is the bottleneck, not the thread model.

4. **Diminishing returns.** Going from 3 blocked DB calls per request → 0 (write buffer) is a 100% improvement. Going from "SSE borrows thread briefly" → "SSE uses event loop" is maybe 5-10% improvement at our scale.

#### When you SHOULD switch to WebFlux

| Scenario | SseEmitter enough? | Need WebFlux? |
|----------|-------------------|---------------|
| 50-500 SSE connections | ✅ Yes | No |
| 1000+ SSE connections | ⚠️ Maybe — test first | Recommended |
| 10K+ SSE connections | ❌ Thread pool contention | ✅ Required |
| Microservice with no DB (just SSE relay) | ✅ Works | Ideal use case |
| Heavy DB reads + SSE | ✅ (DB is bottleneck, not SSE) | No gain unless R2DBC |

**Verdict:** SseEmitter for Phase 4 (right tool for our scale). If SSE connections grow past 1000, switch to WebFlux **for the SSE endpoint only** (hybrid approach) or move to WebSocket.

### Architecture

```
BEFORE (Phase 3 — synchronous):
┌──────────────┐     ┌──────────┐     ┌──────────┐
│ POST /orders │ ──▶ │ findUser │ ──▶ │ findAcct │ ──▶ save() ──▶ 201 CREATED
│ (blocked)    │     │ (DB #1)  │     │ (DB #2)  │     (DB #3)
└──────────────┘     └──────────┘     └──────────┘
Total: ~5-15ms, 3 DB calls, thread blocked entire time


AFTER (Phase 4 — async + buffered + failure tracking):

                    ┌──────────────────────────────────────────────────────────┐
                    │                 REQUEST THREAD (< 1ms)                   │
                    │                                                          │
  POST /orders ──▶  │  Build Order ──▶ Write Buffer ──▶ 202 ACCEPTED          │
                    │     entity        (queue.offer)    { trackingId,         │
                    │   (no DB call)    (no DB call)       status: RECEIVED }  │
                    └──────────────────────────────────────────────────────────┘
                                           │
                    ┌──────────────────────▼───────────────────────────────────┐
                    │              BACKGROUND THREADS                           │
                    │                                                          │
                    │  ┌─────────────────────────────────────┐                │
                    │  │ @Scheduled flushBuffer (every 100ms)│                │
                    │  │                                     │                │
                    │  │  Drain queue → saveAll(batch)       │                │
                    │  │  ✅ Success → status = PENDING       │                │
                    │  │  ❌ Failure → status = FAILED        │                │
                    │  │             + failureReason set     │                │
                    │  │             + indexed by userId     │                │
                    │  └─────────────────────────────────────┘                │
                    │                                                          │
                    │  ┌─────────────────────────────────────┐                │
                    │  │ @Async validateOrderAsync           │                │
                    │  │                                     │                │
                    │  │  existsById(user) + existsById(acc) │                │
                    │  │  ❌ Not found → status = REJECTED    │                │
                    │  └─────────────────────────────────────┘                │
                    └──────────────────────────────────────────────────────────┘
                                           │
                    ┌──────────────────────▼───────────────────────────────────┐
                    │              CLIENT FAILURE DISCOVERY                     │
                    │                                                          │
                    │  Option 1: SSE Stream (real-time, no polling)            │
                    │            GET /orders/stream/{userId}                   │
                    │            Server pushes events as they happen:          │
                    │              QUEUED → FLUSHING (5/50) → SAVED            │
                    │              or QUEUED → FAILED (reason)                 │
                    │              or QUEUED → REJECTED (validation)           │
                    │            ✅ Best for UI progress bar                    │
                    │            ✅ Client gets failure notification instantly  │
                    │                                                          │
                    │  Option 2: GET /orders/track/{trackingId}  (polling)     │
                    │            → { status: RECEIVED|PENDING|REJECTED|FAILED, │
                    │               orderId, failureReason }                  │
                    │                                                          │
                    │  Option 3: GET /orders/failures/{userId}   (batch check) │
                    │            → [ all FAILED + REJECTED orders for user ]   │
                    │            → empty list = everything OK                 │
                    └──────────────────────────────────────────────────────────┘

SSE Event Flow (GET /orders/stream/{userId}):
┌────────┐        ┌────────────┐        ┌─────────────┐
│ Client │◀─SSE───│ SSE Emitter│◀─event─│ WriteBuffer │
│ (UI)   │        │ (per user) │        │ (flush)     │
└────────┘        └────────────┘        └─────────────┘
    │                                          │
    │  event: queued                           │ order enters buffer
    │  data: { trackingId, pendingInQueue }    │
    │                                          │
    │  event: flushing                         │ batch progress
    │  data: { batchTotal:50, batchSaved:25 }  │
    │                                          │
    │  event: saved                            │ order in DB
    │  data: { trackingId, orderId }           │
    │                                          │
    │  event: failed                           │ flush failed
    │  data: { trackingId, failureReason }     │
    │                                          │
    │  event: heartbeat (every 15s)            │ keep-alive
    └──────────────────────────────────────────┘
```

### How the client knows if something went wrong

The 202 ACCEPTED response is **not a guarantee** — it means "received, not confirmed."

**3 ways to discover failures (best → worst):**

| Method | Endpoint | Real-time? | Use case |
|--------|----------|-----------|----------|
| **SSE Stream** | `GET /orders/stream/{userId}` | ✅ Yes — push | UI progress bar, instant failure alerts |
| **Track Poll** | `GET /orders/track/{trackingId}` | ❌ No — pull | Simple API clients, one-off checks |
| **Failures List** | `GET /orders/failures/{userId}` | ❌ No — pull | Batch check, "did anything fail?" |

| Scenario | What happens | SSE event pushed | Fallback |
|----------|-------------|-----------------|----------|
| Happy path | Buffer flush succeeds | `saved { orderId }` | `GET /track/{id}` → PENDING |
| DB down | Batch flush fails | `failed { failureReason }` | `GET /track/{id}` → FAILED |
| OOM / buffer overflow | Queue rejects task | N/A (immediate 503) | `POST /orders` → 503 |
| Invalid user/account | Async validation fails | `rejected { reason }` | `GET /track/{id}` → REJECTED |
| Didn't save trackingId | Client lost the ID | SSE still delivers events | `GET /failures/{userId}` |
| Tracking expired | Entry cleaned (5 min TTL) | SSE auto-closes (5 min) | Order in DB or lost (Phase 5 Kafka fixes) |

---

## What Changed

### New Files
| File | Purpose |
|------|---------|
| `config/AsyncConfig.java` | Thread pool (8-16 threads) for async order processing |
| `dto/OrderAcceptedResponse.java` | 202 response with trackingId, userId, failureReason |
| `dto/OrderProgressEvent.java` | SSE event DTO with progress bar data (batchTotal, batchSaved) |
| `service/OrderWriteBufferService.java` | In-memory queue + batch flush every 100ms + SSE event push |
| `service/OrderStreamService.java` | SSE emitter management per userId + heartbeat keep-alive |

### Modified Files
| File | Change |
|------|--------|
| `service/OrderService.java` | Added `placeOrderAsync()` + `placeOrderSync()` + `validateOrderAsync()` + `getFailedOrders()` |
| `controller/OrderController.java` | `POST /orders` → 202 async, `POST /orders/sync` → 201 sync, `GET /failures/{userId}` |
| `exception/GlobalExceptionHandler.java` | Added `RejectedExecutionException` handler (503) |
| `application.properties` | HikariCP 10→20, JPA batch inserts, write buffer config |

### New Endpoints
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/orders` | Async — returns 202 + trackingId (Phase 4) |
| POST | `/orders/sync` | Sync — returns 201 + order (Phase 3 baseline) |
| GET | `/orders/track/{trackingId}` | Poll order processing status |
| GET | `/orders/failures/{userId}` | All FAILED + REJECTED orders for a user |
| GET | `/orders/stream/{userId}` | **SSE** — real-time progress events (progress bar) |
| GET | `/orders/buffer/metrics` | Write buffer monitoring |

### Order Lifecycle
```
RECEIVED  → order accepted into write buffer (returned to client immediately)
PENDING   → order saved to DB (batch flushed successfully)
REJECTED  → validation failed (user/account not found — async check)
FAILED    → infrastructure failure (DB down, OOM, connection refused)
```

---

## OrderStreamService — Code Walkthrough

### The Big Picture

SSE is a **one-way radio channel.** The user tunes in (subscribes), and the server broadcasts updates on that channel. The user doesn't keep asking "is it done yet?" — the server tells them.

```
User (browser)                          Server
     │                                     │
     │── GET /orders/stream/1 ────────────▶│  subscribe(userId=1) — open radio channel
     │◀── "connected" ────────────────────│
     │                                     │
     │── POST /orders {AAPL} ────────────▶│  bufferOrder() 
     │◀── 202 { trackingId: ORD-1 } ─────│
     │                                     │
     │◀── event:queued { ORD-1 } ─────────│  pushEvent() — "order entered buffer"
     │                                     │
     │                              100ms later: @Scheduled flushBuffer fires
     │                                     │
     │◀── event:saved { ORD-1, id:123 } ──│  pushEvent() — "order in DB"
     │◀── event:flushing { 1/1, 100% } ───│  pushEvent() — "batch complete"
     │                                     │
     │     ... 15 seconds pass ...         │
     │◀── event:heartbeat ────────────────│  sendHeartbeats() — "still alive"
     │                                     │
     │     ... 5 minutes pass ...          │
     │── connection closed ───────────────▶│  removeEmitter() — cleanup
```

### Method-by-Method Breakdown

#### 1. `subscribe(Long userId)` — Open the radio channel

**Called by:** `OrderController.streamOrderProgress()` → `GET /orders/stream/{userId}`

```java
public SseEmitter subscribe(Long userId) {
    // Create a pipe that stays open for 5 minutes
    SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);

    // Store it in the map: userId → [emitter1, emitter2, ...]
    // Why a List? User might have 2 browser tabs open → 2 SSE connections
    emitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(emitter);

    // Register cleanup callbacks (see removeEmitter below)
    emitter.onCompletion(() -> removeEmitter(userId, emitter));
    emitter.onTimeout(() -> removeEmitter(userId, emitter));
    emitter.onError(e -> removeEmitter(userId, emitter));

    // Send "you're connected" message
    emitter.send(SseEmitter.event().name("connected").data(...));

    return emitter;
    // After this returns:
    // - Tomcat thread is FREE (async servlet mode)
    // - TCP socket stays open — browser is listening
    // - emitter is stored in the map — other services use it to push events
}
```

#### 2. `pushEvent(Long userId, OrderProgressEvent event)` — Send update to user

**Called by:** `OrderWriteBufferService` at 4 points + `OrderService` at 1 point:

| When (caller) | Event pushed | What user sees |
|------|-------------|---------------|
| `bufferOrder()` — order enters queue | `QUEUED { trackingId, pendingInQueue: 5 }` | "Order accepted" — progress 10% |
| `flushBuffer()` — order saved to DB | `SAVED { trackingId, orderId: 123 }` | "Order saved!" — progress 100% ✅ |
| `flushBuffer()` — batch progress | `FLUSHING { batchTotal: 50, batchSaved: 25 }` | Progress bar: 50% |
| `flushBuffer()` catch — DB failed | `FAILED { trackingId, failureReason }` | "Order failed ❌" |
| `validateOrderAsync()` — invalid user | `REJECTED { trackingId, reason }` | "User not found" |

```java
public void pushEvent(Long userId, OrderProgressEvent event) {
    List<SseEmitter> userEmitters = emitters.get(userId);
    if (userEmitters == null || userEmitters.isEmpty()) {
        return;  // nobody listening for this user — skip (zero cost)
    }

    for (SseEmitter emitter : userEmitters) {
        emitter.send(event);  // writes bytes to the open TCP socket
        // if send() fails → IOException → removeEmitter() cleans up
    }
}
```

**Key insight:** If userId=1 has no SSE connection open, `pushEvent` does NOTHING. Zero cost. The event is skipped. The tracking map still has the status — the user can always poll `/track/{id}` later.

#### 3. `removeEmitter(userId, emitter)` — Cleanup dead connections

**The problem it solves: memory leak + dead connections.**

Without cleanup:
```
User opens tab    → emitter added to map
User closes tab   → TCP connection dies, but emitter STILL in the map
User opens tab    → another emitter added
... after 1000 opens/closes → map has 1000 dead objects → memory leak
... pushEvent tries to write to dead emitters → IOException every time
```

With cleanup:
```
User closes tab   → onCompletion fires  → removeEmitter() → map cleaned
5 min timeout     → onTimeout fires     → removeEmitter() → map cleaned
Network error     → onError fires       → removeEmitter() → map cleaned
send() fails      → IOException caught  → removeEmitter() → map cleaned
```

**3 triggers registered in subscribe():**
```java
emitter.onCompletion(() -> removeEmitter(userId, emitter));  // browser tab closed
emitter.onTimeout(() -> removeEmitter(userId, emitter));     // 5 min timeout
emitter.onError(e -> removeEmitter(userId, emitter));        // network error
```

Plus called inside `pushEvent()` when `emitter.send()` throws IOException.

**What it does:**
```java
private void removeEmitter(Long userId, SseEmitter emitter) {
    List<SseEmitter> userEmitters = emitters.get(userId);
    userEmitters.remove(emitter);        // remove this one dead emitter
    if (userEmitters.isEmpty()) {
        emitters.remove(userId);          // no emitters left? clean the key too
    }
}
```

#### 4. Why `CopyOnWriteArrayList` (not `ArrayList`)?

Multiple threads touch the emitter list **at the same time**:

```
Thread A: @Scheduled flushBuffer → pushEvent → iterating over list    (reading)
Thread B: User opens new tab     → subscribe → adding to list         (writing)
Thread C: emitter.send() fails   → removeEmitter → removing from list (writing)
Thread D: @Scheduled heartbeat   → iterating over list                (reading)
```

With `ArrayList`:
```
Thread A reads index 2 while Thread C removes index 1
→ ConcurrentModificationException → crash
```

With `CopyOnWriteArrayList`:
```
Thread A reads from a SNAPSHOT (copy) of the list
Thread C writes to a NEW copy — Thread A's iteration is unaffected
No crash, no locking, thread-safe
```

**Why COW and not `synchronizedList`?**
- Our usage is **read-heavy** (pushEvent called 100s of times/sec) and **write-rare** (subscribe/remove happens occasionally)
- COW: reads are lock-free (fast), writes create a copy (slow but rare)
- synchronizedList: every read AND write locks → pushEvent becomes a bottleneck

#### 5. `sendHeartbeats()` — Ping every 15 seconds

**Called by:** `@Scheduled(fixedRate = 15000)` — Spring runs it automatically.

**The problem it solves: silent connection death.**

```
Without heartbeat:
  User connects → proxy/load balancer sees no traffic for 30s → kills connection
  User thinks connected, but events never arrive

With heartbeat:
  Server pings every 15s → proxy sees traffic → keeps connection alive
  If user actually disconnected → send() throws IOException → removeEmitter() cleans up
```

It doubles as a **dead connection detector** — if `emitter.send()` fails, we know the connection is dead and clean it up immediately.

#### 6. How SSE tracks "how much data is inserted in DB"

**SSE itself doesn't track anything.** The write buffer (`OrderWriteBufferService`) tracks progress and pushes events through SSE at specific points:

```java
flushWriteBuffer() {
    List<Order> saved = orderRepository.saveAll(orders);  // batch insert to DB

    for (int i = 0; i < batch.size(); i++) {
        // After EACH order is saved → push SAVED event
        pushEvent(userId, SAVED { trackingId, orderId });

        // Every 10 orders (or at the end) → push FLUSHING progress
        if ((i + 1) % 10 == 0 || i == batch.size() - 1) {
            pushEvent(userId, FLUSHING { 
                batchTotal: 50,      // total orders in this batch
                batchSaved: i + 1,   // how many saved so far
                pendingInQueue: 3    // orders still waiting in buffer
            });
        }
    }
}
```

**Example: User placed 50 orders, buffer flushes them:**

```
SSE events pushed to client:

event:saved    → { trackingId: ORD-1, orderId: 101 }
event:saved    → { trackingId: ORD-2, orderId: 102 }
... (8 more)
event:flushing → { batchTotal: 50, batchSaved: 10 }   ← UI shows 20%

event:saved    → { trackingId: ORD-11, orderId: 111 }
... (8 more)
event:flushing → { batchTotal: 50, batchSaved: 20 }   ← UI shows 40%

... continues ...

event:flushing → { batchTotal: 50, batchSaved: 50 }   ← UI shows 100% ✅
```

The UI matches `trackingId` in each SAVED event to update individual order progress bars. The FLUSHING event gives overall batch progress.

#### Method Usage Summary

| Method | Called By | Purpose |
|--------|----------|---------|
| `subscribe()` | `OrderController` → `GET /stream/{userId}` | Open SSE connection, store emitter |
| `pushEvent()` | `WriteBufferService` (4 places) + `OrderService` (1 place) | Send event to user's open connection |
| `removeEmitter()` | `onCompletion/onTimeout/onError` + `pushEvent` on IOException | Cleanup dead connections, prevent memory leak |
| `sendHeartbeats()` | `@Scheduled(fixedRate = 15000)` — auto by Spring | Keep connections alive, detect dead ones |
| `countConnections()` | `subscribe()` + `removeEmitter()` | Logging — how many SSE connections active |

---

## Configuration Changes

### application.properties
```properties
# HikariCP: 10 → 20 (async workers + request threads need connections)
spring.datasource.hikari.maximum-pool-size=20

# JPA batch inserts (saveAll batches into fewer JDBC calls)
spring.jpa.properties.hibernate.jdbc.batch_size=50
spring.jpa.properties.hibernate.order_inserts=true
spring.jpa.properties.hibernate.order_updates=true

# Write buffer tuning
app.write-buffer.flush-interval-ms=100
app.write-buffer.tracking-ttl-minutes=5
```

### Thread Pool (AsyncConfig)
```
Core:  8 threads   (steady state)
Max:   16 threads  (burst)
Queue: 1000 tasks  (buffer before rejection → 503)
```

---

## Running Phase 4

### 1. Build
```bash
./mvnw clean package -DskipTests
```

### 2. Start the app
```bash
java -Xms512m -Xmx2g -jar target/scalelab-0.0.1-SNAPSHOT.jar
```

### 3. Quick smoke test
```bash
# 1. Open SSE stream first (in a separate terminal — keeps connection open)
curl -N http://localhost:8080/orders/stream/1

# 2. In another terminal, place an async order
curl -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"accountId":1,"symbol":"AAPL","quantity":10,"price":150.50,"orderType":"BUY"}'
# Expected: 202 { "trackingId": "ORD-...", "status": "RECEIVED", "userId": 1, ... }

# The SSE terminal should show events:
#   event: queued
#   data: { "eventType":"QUEUED", "trackingId":"ORD-...", ... }
#
#   event: saved
#   data: { "eventType":"SAVED", "orderId":12345, ... }
#
#   event: flushing
#   data: { "eventType":"FLUSHING", "batchTotal":1, "batchSaved":1, ... }

# 3. Sync order (Phase 3 baseline — no SSE events, blocks thread)
curl -X POST http://localhost:8080/orders/sync \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"accountId":1,"symbol":"AAPL","quantity":10,"price":150.50,"orderType":"BUY"}'

# 4. Track an order by polling
curl http://localhost:8080/orders/track/ORD-1741234567890-1

# 5. Check if any orders failed (empty list = all good)
curl http://localhost:8080/orders/failures/1

# 6. Buffer metrics
curl http://localhost:8080/orders/buffer/metrics
```

**Browser UI — Auto-connect SSE from 202 response (full pattern):**

```javascript
// ============================================================
// ORDER PROGRESS MANAGER
// ============================================================
// The UI only needs this ONE piece of code.
// It auto-connects to SSE on first order, reuses connection
// for all subsequent orders, and shows individual progress
// per trackingId in a drawer/dropdown.
// ============================================================

let sseConnection = null;  // one SSE connection per userId (reused)
const orderProgress = {};  // { trackingId → { status, progress, orderId, error } }

// Called when user clicks "Place Order"
async function placeOrder(orderData) {
  const res = await fetch('/orders', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(orderData)
  });

  const data = await res.json();
  // data = {
  //   trackingId: "ORD-1741234567890-1",
  //   status: "RECEIVED",
  //   userId: 1,
  //   streamUrl: "/orders/stream/1",     ← SSE endpoint
  //   trackUrl: "/orders/track/ORD-..."  ← polling fallback
  // }

  // Step 1: Add to progress drawer immediately
  orderProgress[data.trackingId] = {
    status: 'RECEIVED',
    progress: 0,
    symbol: orderData.symbol,
    quantity: orderData.quantity
  };
  renderProgressDrawer();  // show in UI

  // Step 2: Auto-open SSE if not already connected
  if (!sseConnection) {
    connectSSE(data.streamUrl);
  }

  return data;
}

// Opens ONE SSE connection per user (called once, reused for all orders)
function connectSSE(streamUrl) {
  sseConnection = new EventSource(streamUrl);

  sseConnection.addEventListener('queued', (e) => {
    const event = JSON.parse(e.data);
    if (orderProgress[event.trackingId]) {
      orderProgress[event.trackingId].status = 'QUEUED';
      orderProgress[event.trackingId].progress = 10;
    }
    renderProgressDrawer();
  });

  sseConnection.addEventListener('flushing', (e) => {
    const event = JSON.parse(e.data);
    // Progress bar: batchSaved / batchTotal
    const pct = Math.round((event.batchSaved / event.batchTotal) * 100);
    // Update all orders that are currently being flushed
    Object.keys(orderProgress).forEach(id => {
      if (orderProgress[id].status === 'QUEUED') {
        orderProgress[id].progress = 10 + Math.round(pct * 0.8);  // 10-90%
      }
    });
    renderProgressDrawer();
  });

  sseConnection.addEventListener('saved', (e) => {
    const event = JSON.parse(e.data);
    if (orderProgress[event.trackingId]) {
      orderProgress[event.trackingId].status = 'SAVED';
      orderProgress[event.trackingId].progress = 100;
      orderProgress[event.trackingId].orderId = event.orderId;
    }
    renderProgressDrawer();
    showToast(`Order ${event.orderId} saved ✅`);
  });

  sseConnection.addEventListener('failed', (e) => {
    const event = JSON.parse(e.data);
    if (orderProgress[event.trackingId]) {
      orderProgress[event.trackingId].status = 'FAILED';
      orderProgress[event.trackingId].error = event.failureReason;
    }
    renderProgressDrawer();
    showToast(`Order failed: ${event.failureReason} ❌`, 'error');
  });

  sseConnection.addEventListener('rejected', (e) => {
    const event = JSON.parse(e.data);
    if (orderProgress[event.trackingId]) {
      orderProgress[event.trackingId].status = 'REJECTED';
      orderProgress[event.trackingId].error = event.message;
    }
    renderProgressDrawer();
  });

  sseConnection.addEventListener('heartbeat', () => { /* keep-alive, ignore */ });

  sseConnection.onerror = () => {
    sseConnection.close();
    sseConnection = null;  // will reconnect on next order
  };
}

// Renders progress drawer (all active orders)
function renderProgressDrawer() {
  // orderProgress = {
  //   "ORD-...-1": { status: "SAVED",    progress: 100, symbol: "AAPL", orderId: 123 },
  //   "ORD-...-2": { status: "QUEUED",   progress: 10,  symbol: "GOOGL" },
  //   "ORD-...-3": { status: "FAILED",   progress: 0,   symbol: "TSLA", error: "DB down" },
  // }
  //
  // Render each as a row in a drawer/dropdown with:
  //   [AAPL BUY 10] ████████████████████ 100% ✅
  //   [GOOGL SELL 5] ██░░░░░░░░░░░░░░░░░ 10%  ⏳
  //   [TSLA BUY 20]  FAILED: DB down     ❌
}

// Example: user places 3 orders rapidly
placeOrder({ userId: 1, accountId: 1, symbol: 'AAPL',  quantity: 10, price: 150, orderType: 'BUY' });
placeOrder({ userId: 1, accountId: 1, symbol: 'GOOGL', quantity: 5,  price: 200, orderType: 'SELL' });
placeOrder({ userId: 1, accountId: 1, symbol: 'TSLA',  quantity: 20, price: 300, orderType: 'BUY' });
// → SSE stream opened ONCE on first placeOrder()
// → All 3 orders appear in drawer with individual progress bars
// → Events auto-update each order: QUEUED → FLUSHING → SAVED (or FAILED)
```

**How this works end-to-end:**
```
User clicks "Buy AAPL" → POST /orders
  ← 202 { trackingId: "ORD-1", streamUrl: "/orders/stream/1" }
  → UI adds "ORD-1" to drawer, opens SSE stream

User clicks "Sell GOOGL" → POST /orders
  ← 202 { trackingId: "ORD-2", streamUrl: "/orders/stream/1" }
  → UI adds "ORD-2" to drawer (SSE already open, reuses it)

100ms later: @Scheduled flush fires
  → SSE pushes: event:saved { trackingId:"ORD-1", orderId:123 }
  → SSE pushes: event:saved { trackingId:"ORD-2", orderId:124 }
  → Drawer updates: both show 100% ✅

If DB was down:
  → SSE pushes: event:failed { trackingId:"ORD-1", failureReason:"Connection refused" }
  → Drawer updates: ORD-1 shows ❌ FAILED with reason
```

### 4. Load tests

```bash
# Stage 5: 100 VUs, 30s, mixed reads + writes
k6 run scripts/k6/phase4/stage5-test.js

# Stage 6: 150 VUs ramped, 30s, heavy writes
k6 run scripts/k6/phase4/stage6-test.js

# Write stress: 100 VUs, pure writes, async vs sync comparison
k6 run scripts/k6/phase4/write-stress-test.js
```

---

## Expected Improvements

| Metric | Phase 3 (sync) | Phase 4 (async) | Why |
|--------|----------------|-----------------|-----|
| POST /orders latency | 5-15ms | < 2ms | Zero DB calls on request thread |
| POST /orders p95 | 20-50ms | < 10ms | No connection pool contention |
| DB round-trips per order | 3 | ~0.02 (amortized, batch of 50) | Write buffer batches 50+ orders |
| Connection pool usage | Saturated at 10 | Relaxed at 20 | Async frees connections faster |
| Throughput (writes/sec) | Limited by pool | 5-10x higher | Batching + async |

---

## Real-World Production Patterns — How Big Companies Handle This

### The Question
> "If I have 1000 users/sec uploading data, they all see a progress bar, and the system does heavy writes — how do real companies handle this? Do they use reactive DBs?"

### The Short Answer
**No, almost nobody uses reactive databases in production for this.** They use a **layered architecture** where different technologies solve different problems. Here's how:

### The 4-Layer Pattern (used by Uber, Stripe, Robinhood, YouTube, etc.)

```
┌─────────────────────────────────────────────────────────────────────────┐
│ LAYER 1: ACCEPT — instant response, zero processing                    │
│                                                                         │
│   POST /orders → write to Kafka/SQS/Redis → 202 ACCEPTED (< 1ms)      │
│                                                                         │
│   Technology: Any HTTP server (Spring MVC, Express, Go net/http)        │
│   Threads needed: 200 Tomcat threads handle 10K+ req/sec easily         │
│   Because: each request does ZERO processing — just queue.offer()       │
│                                                                         │
│   ✅ This is what our Phase 4 write buffer does (in-memory version)     │
│   ✅ Phase 5 Kafka will make this durable + distributed                 │
└────────────────────────────────┬────────────────────────────────────────┘
                                 │
┌────────────────────────────────▼────────────────────────────────────────┐
│ LAYER 2: PROCESS — background workers, not on request thread            │
│                                                                         │
│   Kafka Consumer / SQS Worker → validate → batch write to DB            │
│                                                                         │
│   Technology: Separate JVM processes (not the HTTP server)              │
│   DB: Regular PostgreSQL with JDBC (blocking). NOT reactive.            │
│   Scale: Run 10 consumer instances, each processing 100 orders/sec     │
│                                                                         │
│   Why blocking DB is fine here:                                         │
│   - Workers are dedicated to processing, no HTTP requests to compete    │
│   - Batch inserts (saveAll) minimize DB round-trips                     │
│   - If DB is slow, workers just process slower (backpressure via queue) │
│                                                                         │
│   ✅ This is what our @Scheduled flushBuffer does (single-JVM version)  │
└────────────────────────────────┬────────────────────────────────────────┘
                                 │
┌────────────────────────────────▼────────────────────────────────────────┐
│ LAYER 3: NOTIFY — push status to client (NOT on the HTTP server)        │
│                                                                         │
│   Dedicated WebSocket/SSE server → push events to connected clients     │
│                                                                         │
│   3 common patterns in production:                                      │
│                                                                         │
│   Pattern A: Dedicated WebSocket Server (most common)                   │
│   ┌──────────┐     ┌─────────────┐     ┌──────────────┐               │
│   │  Worker   │────▶│ Redis Pub/  │────▶│  WebSocket   │──▶ Client    │
│   │ (saves)   │     │ Sub channel │     │  Server      │               │
│   └──────────┘     └─────────────┘     │ (Netty/Go/   │               │
│                                         │  Node.js)    │               │
│                                         └──────────────┘               │
│   - WebSocket server is SEPARATE from the API server                    │
│   - Uses Netty/Node.js/Go (event-loop, handles 100K+ connections)      │
│   - API server doesn't hold ANY connections for progress                │
│   - Redis Pub/Sub bridges the worker → WebSocket server                 │
│   - Used by: Uber, Slack, Discord, Robinhood                           │
│                                                                         │
│   Pattern B: Firebase/Pusher (managed service)                          │
│   ┌──────────┐     ┌─────────────┐     ┌──────────────┐               │
│   │  Worker   │────▶│  Firebase   │────▶│  Client SDK  │──▶ UI        │
│   │ (saves)   │     │  Realtime   │     │  (auto-sub)  │               │
│   └──────────┘     └─────────────┘     └──────────────┘               │
│   - Zero server-side WebSocket code                                     │
│   - Firebase/Pusher handles millions of connections                     │
│   - Worker just calls firebase.update(orderId, {status: "SAVED"})      │
│   - Client SDK auto-receives the update                                 │
│   - Used by: startups, mobile apps, rapid prototyping                   │
│                                                                         │
│   Pattern C: Polling (simplest, works fine for most cases)              │
│   ┌──────────┐                          ┌──────────────┐               │
│   │  Client   │──GET /track/{id}───────▶│  API Server  │               │
│   │ (timer)   │◀──{ status: SAVED }─────│  (cached)    │               │
│   └──────────┘                          └──────────────┘               │
│   - Client polls every 1-2 seconds until status != RECEIVED             │
│   - API server returns from Redis cache (no DB hit)                     │
│   - Simple, reliable, no WebSocket complexity                           │
│   - Progress bar updates on each poll (every 1-2s)                      │
│   - Used by: Stripe (payment processing), GitHub (CI/CD builds)         │
│                                                                         │
│   ✅ Our Phase 4 uses SseEmitter (between Pattern A and C)              │
│   ✅ Works for 100-1000 connections, no separate server needed           │
└────────────────────────────────┬────────────────────────────────────────┘
                                 │
┌────────────────────────────────▼────────────────────────────────────────┐
│ LAYER 4: SCALE — horizontal scaling                                     │
│                                                                         │
│   Load Balancer → N API servers (stateless)                             │
│   Kafka partitions → M consumer workers (stateless)                     │
│   Redis cluster → shared state (tracking, cache)                        │
│                                                                         │
│   At 1000 req/sec:                                                      │
│   - 3 API servers (each handling 333 req/sec) → easy                    │
│   - 5 Kafka consumers (each processing 200 orders/sec) → easy          │
│   - 1 Redis cluster (handles 100K+ ops/sec) → easy                     │
│   - 1 PostgreSQL (handles 5K+ batch inserts/sec) → easy                │
│                                                                         │
│   The secret: NO SINGLE SERVER handles 1000 req/sec + progress bars     │
│   + writes + reads. Each layer is independently scalable.               │
└─────────────────────────────────────────────────────────────────────────┘
```

### Do They Use Reactive Databases (R2DBC)?

**Almost never.** Here's why:

| Question | Answer |
|----------|--------|
| Does Uber use R2DBC? | No. They use regular MySQL/PostgreSQL with blocking JDBC in Java workers. |
| Does Netflix use R2DBC? | They use reactive for HTTP gateway (Zuul 2 / Spring Cloud Gateway), but DB access is still blocking JDBC behind the gateway. |
| Does Stripe use R2DBC? | No. Ruby on Rails with blocking ActiveRecord. At their scale. |
| Who actually uses R2DBC? | Very few companies. Some use it for specific microservices that are pure HTTP proxies with no heavy DB logic. |

**Why reactive DB is rare in production:**

```
The problem reactive solves:
  Thread A waits for DB response → thread is wasted (blocked)
  With 200 threads and 200 slow queries → server is frozen

How production actually solves this (without reactive):
  1. Make queries fast (indexes, pagination) → thread is blocked for 2ms, not 2s
  2. Use connection pooling (HikariCP) → threads share DB connections efficiently
  3. Use caching (Redis) → most reads don't hit DB at all
  4. Use async writes (Kafka/queue) → writes don't block request thread
  5. Scale horizontally → 3 servers × 200 threads = 600 threads

After doing 1-5, the "thread is blocked waiting for DB" problem
basically disappears. Reactive would save you from blocking 2ms.
That's not worth rewriting your entire app for.
```

**When reactive IS worth it:**
- API gateways (pure HTTP proxy, no DB) — Spring Cloud Gateway uses WebFlux
- Real-time streaming servers (pure WebSocket relay, no DB) — Netty
- Services that call 10+ downstream APIs per request — reactive avoids thread-per-call

**When reactive is NOT worth it:**
- CRUD apps with a database — just use blocking JDBC + indexes + caching
- Apps that already use JPA/Hibernate — can't migrate to R2DBC without rewriting
- Apps where DB is the bottleneck — reactive doesn't make your DB faster

### How Our Phase 4 Maps to Production

| Production Layer | Our Phase 4 Implementation | Production Version |
|-----------------|---------------------------|-------------------|
| Layer 1: Accept | Write buffer (in-memory queue) | Kafka / SQS / Redis Stream |
| Layer 2: Process | @Scheduled flushBuffer (same JVM) | Kafka Consumer (separate JVM) |
| Layer 3: Notify | SseEmitter (same server) | Dedicated WebSocket server + Redis Pub/Sub |
| Layer 4: Scale | Single server | Load balancer + N servers + Kafka partitions |

**What Phase 5 (Kafka) will improve:**
- Layer 1: In-memory queue → Kafka (durable, survives crash)
- Layer 2: Same-JVM flush → Kafka Consumer (separate process, scalable)
- Layer 3: SseEmitter stays (or upgrade to Redis Pub/Sub + WebSocket)
- Layer 4: Kafka partitions enable horizontal scaling

### Summary: The Progress Bar Problem

> "How do I show a progress bar for 1000 users uploading at the same time?"

```
Wrong approach:  SSE/WebSocket from your API server holding 1000 connections
                 while also handling 1000+ API requests on the same server
                 → thread contention, mixed concerns, scaling nightmare

Right approach:  API server ONLY does: accept → queue → 202 (< 1ms, stateless)
                 Separate worker: queue → process → write to DB
                 Separate notifier: Redis Pub/Sub → WebSocket/SSE → client
                 Each layer scales independently

Simplest approach that works for 90% of cases:
                 API server: accept → queue → 202
                 Client: polls GET /track/{id} every 1 second
                 No WebSocket, no SSE, no reactive, no complexity
                 Works perfectly up to 10K users
```

---

## What's Still Not Solved (→ Phase 5: Kafka)

1. **Durability** — orders in the in-memory buffer are lost on crash
2. **Distributed** — write buffer is single-JVM only, can't scale horizontally
3. **Backpressure** — queue size is fixed (1000), rejects after that
4. **Ordering guarantees** — batch flush doesn't guarantee FIFO per user
5. **SSE at extreme scale** — SseEmitter doesn't hold threads (uses async servlet), but `emitter.send()` borrows Tomcat NIO threads. At 10K+ SSE connections with frequent events, write contention appears. Production fix: separate WebSocket server + Redis Pub/Sub.

Phase 5 (Kafka) solves 1-4 with durable, distributed, ordered message queues.

