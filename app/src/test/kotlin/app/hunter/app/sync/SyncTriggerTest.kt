package app.hunter.app.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncTriggerTest {
    @Test fun requests_sync_only_when_something_is_unsynced() = runTest {
        var unsynced = true
        var requests = 0
        val t = SyncTrigger(hasUnsynced = { unsynced }, request = { requests++ })
        t.onLocalChange()
        assertEquals(1, requests)
        unsynced = false
        t.onLocalChange()
        assertEquals(1, requests) // as escritas do próprio sync (markClean) não geram laço
    }

    @Test fun a_failing_check_never_throws_into_the_database_thread() = runTest {
        var requests = 0
        val t = SyncTrigger(hasUnsynced = { error("db fechado") }, request = { requests++ })
        t.onLocalChange()
        assertEquals(0, requests)
    }
}
