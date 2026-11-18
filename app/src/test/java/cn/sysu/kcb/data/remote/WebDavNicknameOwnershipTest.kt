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

    @Test
    fun establishedNicknameDoesNotNeedAnotherProbe() {
        assertFalse(WebDavSyncService.shouldProbeNicknameAvailability("Alice", "Alice", "Alice"))
        assertFalse(WebDavSyncService.shouldProbeNicknameAvailability("Alice", "Alice", ""))
        assertFalse(WebDavSyncService.shouldProbeNicknameAvailability("alice", "Alice", "Alice"))
        assertFalse(WebDavSyncService.shouldProbeNicknameAvailability("", "Alice", "Alice"))
    }

    @Test
    fun newOrChangedNicknameStillNeedsAProbe() {
        assertTrue(WebDavSyncService.shouldProbeNicknameAvailability("Bob", "Alice", "Alice"))
        assertTrue(WebDavSyncService.shouldProbeNicknameAvailability("Bob", "Alice", ""))
        assertTrue(WebDavSyncService.shouldProbeNicknameAvailability("Alice", "", ""))
    }
}
