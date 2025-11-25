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
     * @param requestsPerSecond Maximum number of requests allowed per second
     */
    public RateLimiter(int requestsPerSecond) {
        this.bucket = Bucket.builder()
                .addLimit(Bandwidth.classic(requestsPerSecond,
                        Refill.intervally(requestsPerSecond, Duration.ofSeconds(1))))
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

}
