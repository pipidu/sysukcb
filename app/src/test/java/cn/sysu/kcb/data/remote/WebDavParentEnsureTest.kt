package cn.sysu.kcb.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavParentEnsureTest {
    @Test
    fun nutstorePathSkipsDavRootAndCreatesSubfolder() {
        assertEquals(
            listOf("/dav/sysukcb/"),
            WebDavClient.webDavParentsToCreate(listOf("dav", "sysukcb", "sysukcb.json")),
        )
    }

    @Test
    fun knownParentsAreNotCreatedAgain() {
        assertEquals(
            emptyList<String>(),
            WebDavClient.webDavParentsToCreate(
                listOf("dav", "sysukcb", "sysukcb.json"),
                alreadyEnsured = setOf("/dav/sysukcb/"),
            ),
        )
        assertEquals(
            emptyList<String>(),
            WebDavClient.webDavParentsToCreate(
                listOf("dav", "sysukcb", "alice.json"),
                alreadyEnsured = setOf("/DAV/sysukcb/"),
            ),
        )
    }

    @Test
    fun deeperPathsCreateEachMissingFolder() {
        assertEquals(
            listOf("/dav/a/", "/dav/a/b/"),
            WebDavClient.webDavParentsToCreate(listOf("dav", "a", "b", "c.json")),
        )
        assertEquals(
            listOf("/dav/a/b/"),
            WebDavClient.webDavParentsToCreate(
                listOf("dav", "a", "b", "c.json"),
                alreadyEnsured = setOf("/dav/a/"),
            ),
        )
    }

    @Test
    fun rootFileAndShortPathsNeedNoParents() {
        assertTrue(WebDavClient.webDavParentsToCreate(listOf("sysukcb.json")).isEmpty())
        assertTrue(WebDavClient.webDavParentsToCreate(emptyList()).isEmpty())
        assertEquals(
            listOf("/sysukcb/"),
            WebDavClient.webDavParentsToCreate(listOf("sysukcb", "sysukcb.json")),
        )
    }
}
