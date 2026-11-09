package app.hunter.app.ui

import app.hunter.app.account.AuthState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileUiStateTest {
    private fun user(verified: Boolean = true, name: String = "Ana", email: String? = "ana@x.com") =
        AuthState.SignedIn("u1", name, email, verified)

    @Test fun visitor_sees_notice_and_sign_in_and_nothing_else() {
        val ui = profileUi(AuthState.Guest, pendingSync = false)
        assertFalse(ui.signedIn)
        assertEquals("Visitante", ui.title)
        assertTrue(ui.notice!!.contains("desinstalar apaga tudo"))
        assertTrue(ui.showSignIn)
        assertTrue(ui.signInEnabled)
        assertFalse(ui.showSignOut)
        assertFalse(ui.showDelete)
        assertFalse(ui.showResendVerification)
    }

    @Test fun visitor_without_firebase_cannot_sign_in() {
        val ui = profileUi(AuthState.Guest, pendingSync = false, authAvailable = false)
        assertTrue(ui.showSignIn)
        assertFalse(ui.signInEnabled)
    }

    @Test fun signed_in_without_pending_can_sign_out_and_delete() {
        val ui = profileUi(user(), pendingSync = false)
        assertTrue(ui.signedIn)
        assertEquals("Ana", ui.title)
        assertEquals("ana@x.com", ui.subtitle)
        assertNull(ui.notice)
        assertFalse(ui.showSignIn)
        assertTrue(ui.showSignOut)
        assertTrue(ui.signOutEnabled)
        assertNull(ui.signOutHint)
        assertTrue(ui.showDelete)
        assertTrue(ui.deleteEnabled)
    }

    @Test fun signed_in_with_pending_blocks_sign_out_only() {
        val ui = profileUi(user(), pendingSync = true)
        assertTrue(ui.showSignOut)
        assertFalse(ui.signOutEnabled)
        assertEquals("Ainda sincronizando, tente de novo com internet.", ui.signOutHint)
        assertTrue(ui.deleteEnabled)
    }

    @Test fun unverified_email_shows_notice_and_resend() {
        val ui = profileUi(user(verified = false), pendingSync = false)
        assertNotNull(ui.notice)
        assertTrue(ui.notice!!.contains("onfirme"))
        assertTrue(ui.showResendVerification)
        assertTrue(ui.signOutEnabled)
    }

    @Test fun blank_name_falls_back_to_email() {
        assertEquals("ana@x.com", profileUi(user(name = ""), false).title)
        assertEquals("Conta", profileUi(user(name = "", email = null), false).title)
    }

    @Test fun deletion_resuming_disables_everything_and_offers_retry() {
        val ui = profileUi(user(), pendingSync = false, deletionPending = true)
        assertTrue(ui.deletionResuming)
        assertFalse(ui.signOutEnabled)
        assertFalse(ui.deleteEnabled)
        assertTrue(ui.notice!!.contains("exclusão"))
    }

    @Test fun deletion_pending_on_visitor_is_ignored() {
        val ui = profileUi(AuthState.Guest, pendingSync = false, deletionPending = true)
        assertFalse(ui.deletionResuming)
    }

    @Test fun delete_confirmation_requires_exact_word() {
        assertTrue(deleteConfirmed("APAGAR"))
        assertTrue(deleteConfirmed("  APAGAR "))
        assertFalse(deleteConfirmed("apagar"))
        assertFalse(deleteConfirmed("APAGA"))
        assertFalse(deleteConfirmed(""))
    }
}
