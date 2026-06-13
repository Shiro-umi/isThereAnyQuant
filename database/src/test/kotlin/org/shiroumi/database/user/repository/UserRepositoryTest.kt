package org.shiroumi.database.user.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database

class UserRepositoryTest {

    private fun createTestDb(): Database = Database.connect(
        url = "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1",
        driver = "org.h2.Driver",
    )

    @Test
    fun `tracking follow start date can be set and cleared`() = runTest {
        val db = createTestDb()
        val repository = UserRepository(db)
        repository.ensureSchema()

        val user = repository.create("plan_test", "hash", null)

        assertNull(repository.getTrackingFollowStartDate(user.id))

        repository.setTrackingFollowStartDate(user.id, "2026-05-20")
        assertEquals("2026-05-20", repository.getTrackingFollowStartDate(user.id))

        repository.setTrackingFollowStartDate(user.id, null)
        assertNull(repository.getTrackingFollowStartDate(user.id))
    }
}
