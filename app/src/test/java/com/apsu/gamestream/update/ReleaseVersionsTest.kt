package com.apsu.gamestream.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseVersionsTest {

    @Test
    fun detectsNewerSemanticTags() {
        assertTrue(ReleaseVersions.isNewer("v0.1.2", "0.1.1"))
        assertTrue(ReleaseVersions.isNewer("0.2.0", "0.1.9"))
        assertTrue(ReleaseVersions.isNewer("v1.0.0", "0.9.9"))
    }

    @Test
    fun rejectsSameOrOlderVersions() {
        assertFalse(ReleaseVersions.isNewer("v0.1.1", "0.1.1"))
        assertFalse(ReleaseVersions.isNewer("0.1.0", "0.1.1"))
        assertFalse(ReleaseVersions.isNewer("release", "0.1.1"))
    }

    @Test
    fun parsesLatestReleaseResponse() {
        val release = ReleaseVersions.parseReleaseInfo(
            """
            {
              "tag_name": "v0.1.2",
              "name": "Solaris 0.1.2",
              "html_url": "https://github.com/vairacing-tech/Solaris/releases/tag/v0.1.2"
            }
            """.trimIndent(),
        )

        requireNotNull(release)
        assertEquals("v0.1.2", release.tagName)
        assertEquals("0.1.2", release.versionName)
        assertEquals("Solaris 0.1.2", release.name)
        assertEquals("https://github.com/vairacing-tech/Solaris/releases/tag/v0.1.2", release.htmlUrl)
    }
}
