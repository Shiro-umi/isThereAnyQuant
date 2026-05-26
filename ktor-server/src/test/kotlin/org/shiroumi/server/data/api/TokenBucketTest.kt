package org.shiroumi.server.data.api

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TokenBucketTest {
    @Test
    fun `unlimited token bucket always acquires`() {
        val bucket = TokenBucket.unlimited()
        repeat(10) {
            assertTrue(bucket.tryAcquire())
        }
    }

    @Test
    fun `single capacity per minute bucket rejects immediate second acquire`() {
        val bucket = TokenBucket.perMinute(60)
        assertTrue(bucket.tryAcquire())
        assertFalse(bucket.tryAcquire())
    }
}
