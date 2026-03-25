package io.scalelab.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Phase 4 — Async + Write Buffering Configuration
 *
 * Problems solved:
 * 1. POST /orders blocked the request thread for 3 DB round-trips (find user, find account, save)
 * 2. Every single POST hit DB directly — no batching under heavy write load
 *
 * Solution:
 * - CompletableFuture: request thread returns 202 ACCEPTED immediately
 * - Dedicated thread pool: order validation + save runs on background threads
 * - Write buffer: orders queue in memory, flush to DB in batches every 100ms
 *
 * Why CompletableFuture over WebFlux?
 * - We already use Spring MVC + JPA (blocking stack)
 * - WebFlux requires rewriting everything to reactive (R2DBC, reactive Redis)
 * - CompletableFuture gives async processing within the existing servlet stack
 * - Kafka (Phase 5) will replace this for true async decoupling
 */
@Slf4j
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    /**
     * Thread pool for async order validation + processing.
     * Separate from Tomcat request threads to avoid thread starvation.
     *
     * Core: 8 threads (handles steady-state order processing)
     * Max: 16 threads (burst capacity during spikes)
     * Queue: 1000 (buffer before rejection — sized for write buffering)
     */
    @Bean(name = "orderProcessingExecutor")
    public Executor orderProcessingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("order-async-");
        executor.setRejectedExecutionHandler((r, e) -> {
            log.error("Order processing queue full! Rejected. Active: {}, Queue: {}",
                    e.getActiveCount(), e.getQueue().size());
            throw new java.util.concurrent.RejectedExecutionException(
                    "Order processing queue is full. Try again later.");
        });
        executor.initialize();
        log.info("Order processing executor initialized — core: {}, max: {}, queue: {}",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), executor.getQueueCapacity());
        return executor;
    }
}
