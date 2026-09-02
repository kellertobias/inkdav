package de.tobisk.inkdav.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {
    @Test
    fun `semantic versions compare numerically`() {
        assertTrue(compareSemanticVersions("1.10.0", "1.9.9") > 0)
        assertTrue(compareSemanticVersions("2.0.0", "1.99.99") > 0)
        assertEquals(0, compareSemanticVersions("1.0.1", "1.0.1"))
    }

    @Test
    fun `release selection requires exact tablet apk and checksum`() {
        val release = selectReleaseAssets(
            "1.2.3",
            listOf(
                ReleaseAsset("InkDAV-v1.2.3-boox-note-air5c.apk", "https://example.test/app.apk", "sha256:abc"),
                ReleaseAsset("InkDAV-v1.2.3-boox-note-air5c.apk.sha256", "https://example.test/app.sha256", null),
                ReleaseAsset("source.zip", "https://example.test/source.zip", null)
            )
        )

        assertEquals("InkDAV-v1.2.3-boox-note-air5c.apk", release.apk.name)
        assertEquals("InkDAV-v1.2.3-boox-note-air5c.apk.sha256", release.checksum.name)
    }
}
