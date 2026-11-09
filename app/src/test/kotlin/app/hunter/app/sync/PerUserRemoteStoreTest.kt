package app.hunter.app.sync

import app.hunter.sync.OdometerDoc
import app.hunter.sync.RemoteSnapshot
import app.hunter.sync.RemoteStore
import app.hunter.sync.SyncDoc
import app.hunter.sync.UserProfile
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class PerUserRemoteStoreTest {
    private class Fake(val uid: String) : RemoteStore {
        var fetches = 0
        override suspend fun fetchAll(): RemoteSnapshot { fetches++; return RemoteSnapshot(emptyList(), null, null) }
        override suspend fun push(docs: List<SyncDoc>) {}
        override suspend fun pushOdometer(o: OdometerDoc) {}
        override suspend fun pushProfile(p: UserProfile) {}
        override suspend fun markDeletionPending() {}
        override suspend fun deletionPending() = false
        override suspend fun deleteAllUserData() {}
    }

    @Test fun builds_one_store_per_uid_and_reuses_it() = runTest {
        var uid: String? = "a"
        val made = mutableListOf<Fake>()
        val store = PerUserRemoteStore({ uid }) { Fake(it).also(made::add) }
        store.fetchAll(); store.fetchAll()
        assertEquals(1, made.size)
        assertEquals(2, made[0].fetches)
    }

    @Test fun rebuilds_when_the_user_changes() = runTest {
        var uid: String? = "a"
        val made = mutableListOf<Fake>()
        val store = PerUserRemoteStore({ uid }) { Fake(it).also(made::add) }
        store.fetchAll()
        uid = "b"
        store.fetchAll()
        assertEquals(listOf("a", "b"), made.map { it.uid })
        assertEquals(1, made[1].fetches)
    }

    @Test fun refuses_to_run_without_a_user() = runTest {
        val store = PerUserRemoteStore({ null }) { Fake(it) }
        assertFailsWith<IllegalStateException> { store.fetchAll() }
        assertFailsWith<IllegalStateException> { store.deletionPending() }
    }
}
