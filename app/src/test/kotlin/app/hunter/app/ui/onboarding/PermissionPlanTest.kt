package app.hunter.app.ui.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PermissionPlanTest {
    private fun ids(sdk: Int) = PermissionPlan.steps(sdk).map { it.id }
    private fun all(sdk: Int) = PermissionPlan.steps(sdk).flatMap { it.permissions }

    @Test fun `Android 8 pede só localização`() {
        assertEquals(listOf("location"), ids(26))
        assertEquals(
            listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"),
            all(26),
        )
    }

    @Test fun `Android 12 inclui Bluetooth`() {
        assertEquals(listOf("location", "bluetooth"), ids(31))
        assertTrue("android.permission.BLUETOOTH_SCAN" in all(31))
        assertTrue("android.permission.BLUETOOTH_CONNECT" in all(31))
        assertFalse("android.permission.BLUETOOTH_SCAN" in all(30))
    }

    @Test fun `Android 13 inclui notificações`() {
        assertEquals(listOf("location", "bluetooth", "notifications"), ids(33))
        assertTrue("android.permission.POST_NOTIFICATIONS" in all(33))
        assertFalse("android.permission.POST_NOTIFICATIONS" in all(32))
    }

    @Test fun `nenhuma etapa contém ACCESS_BACKGROUND_LOCATION`() {
        for (sdk in 26..36) assertFalse("android.permission.ACCESS_BACKGROUND_LOCATION" in all(sdk), "sdk $sdk")
    }
}
