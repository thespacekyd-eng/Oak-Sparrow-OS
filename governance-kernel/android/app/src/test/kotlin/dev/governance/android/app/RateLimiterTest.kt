package dev.governance.android.app

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.property.checkAll
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int

/**
 * Property-based tests for [AgentBinderRateLimiter.TokenBucket].
 *
 * These run on JVM — no Android framework needed. The token bucket
 * is tested independently of the AIDL plumbing.
 */
class RateLimiterTest : FunSpec({

    test("fresh bucket allows burst capacity requests") {
        val bucket = AgentBinderRateLimiter.TokenBucket(
            refillRate = 10.0,
            capacity = 50,
        )
        var consumed = 0
        repeat(50) {
            if (bucket.tryConsume()) consumed++
        }
        consumed shouldBe 50
    }

    test("bucket rejects requests beyond capacity without refill") {
        val bucket = AgentBinderRateLimiter.TokenBucket(
            refillRate = 0.0, // no refill
            capacity = 10,
        )
        repeat(10) { bucket.tryConsume() }
        bucket.tryConsume() shouldBe false
    }

    test("bucket refills over time") {
        val bucket = AgentBinderRateLimiter.TokenBucket(
            refillRate = 1000.0, // very fast refill for test
            capacity = 100,
        )
        // Drain the bucket
        repeat(100) { bucket.tryConsume() }
        bucket.tryConsume() shouldBe false

        // Wait a bit for refill
        Thread.sleep(20)

        // Should have refilled some tokens
        bucket.tryConsume() shouldBe true
    }

    test("concurrent consumption never exceeds capacity") {
        val bucket = AgentBinderRateLimiter.TokenBucket(
            refillRate = 0.0, // no refill — fixed supply
            capacity = 200,
        )

        val consumed = java.util.concurrent.atomic.AtomicInteger(0)
        val threads = (1..50).map {
            Thread {
                repeat(100) {
                    if (bucket.tryConsume()) consumed.incrementAndGet()
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Total consumed must not exceed capacity
        consumed.get() shouldBeLessThanOrEqual 200
        // Should have consumed most of them
        consumed.get() shouldBeGreaterThan 180
    }

    test("property: consumed + rejected always equals attempted") {
        checkAll(100, Arb.int(1..500)) { attempts ->
            val capacity = 100
            val bucket = AgentBinderRateLimiter.TokenBucket(
                refillRate = 0.0,
                capacity = capacity,
            )
            var consumed = 0
            var rejected = 0
            repeat(attempts) {
                if (bucket.tryConsume()) consumed++ else rejected++
            }
            consumed + rejected shouldBe attempts
            consumed shouldBeLessThanOrEqual capacity
        }
    }
})
