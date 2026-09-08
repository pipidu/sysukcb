package cn.sysu.kcb.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavNicknameOwnershipTest {
    @Test
    fun lastUploadedWinsOverSaved() {
        assertTrue(WebDavSyncService.ownsNickname("Alice", "Bob", "Alice"))
        assertFalse(WebDavSyncService.ownsNickname("Bob", "Bob", "Alice"))
        assertFalse(WebDavSyncService.ownsNickname("Bob", "Alice", "Alice"))
    }

    @Test
    fun legacyWithoutLastUploadedTreatsSavedAsOwn() {
        assertTrue(WebDavSyncService.ownsNickname("Alice", "Alice", ""))
        assertTrue(WebDavSyncService.ownsNickname("alice", "Alice", " "))
        assertFalse(WebDavSyncService.ownsNickname("Bob", "Alice", ""))
        assertFalse(WebDavSyncService.ownsNickname("Alice", "", ""))
    }

    @Test
    fun blankNicknameIsNeverOwned() {
        assertFalse(WebDavSyncService.ownsNickname("", "Alice", "Alice"))
        assertFalse(WebDavSyncService.ownsNickname("   ", "Alice", ""))
    }
}
