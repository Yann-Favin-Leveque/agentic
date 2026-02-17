package io.github.yannfavinleveque.agentic.support;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;

import java.time.Duration;

/**
 * Token bucket rate limiter using Bucket4j.
 * Limits the rate of requests per second.
 */
public class RateLimiter {

    private final Bucket bucket;

    /**
     * Creates a rate limiter with the specified requests per second limit.
     *
     * Bucket starts FULL to allow immediate bursts at startup.
     * Capacity = requestsPerSecond (allows burst of N requests immediately).
     * Refill rate = requestsPerSecond tokens per second.
     *
     * @param requestsPerSecond Maximum number of requests allowed per second
     */
    public RateLimiter(int requestsPerSecond) {
        // Use simple() instead of classic() to start with FULL bucket
        // This allows immediate bursts of 'requestsPerSecond' requests
        this.bucket = Bucket.builder()
                .addLimit(Bandwidth.simple(requestsPerSecond, Duration.ofSeconds(1)))
                .build();
    }

    /**
     * Attempts to consume one token from the bucket.
     *
     * @return true if a token was consumed, false if rate limit exceeded
     */
    public boolean tryConsume() {
        return bucket.tryConsume(1);
    }

    /**
     * Blocks until a token is available and consumes it.
     * More efficient than busy-wait loop with tryConsume().
     */
    public void consume() {
        try {
            bucket.asBlocking().consume(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for rate limit token", e);
        }
    }

    /**
     * Asynchronously waits for a token and consumes it (non-blocking!).
     * Returns a CompletableFuture that completes when token is acquired.
     */
    public java.util.concurrent.CompletableFuture<Void> consumeAsync(java.util.concurrent.ExecutorService executor) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                bucket.asBlocking().consume(1);
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for rate limit token", e);
            }
        }, executor);
    }

}
