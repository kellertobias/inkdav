package de.tobisk.inkdav.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileVisibilityTest {
    @Test
    fun hiddenFolderIsExcludedUntilHiddenFoldersAreShown() {
        val key = localFolderVisibilityKey("content://root/folder")
        assertFalse(isFileFolderVisible(true, key, setOf(key), showHidden = false))
        assertTrue(isFileFolderVisible(true, key, setOf(key), showHidden = true))
    }

    @Test
    fun filesAreNeverFilteredByFolderVisibility() {
        val key = remoteFolderVisibilityKey("document")
        assertTrue(isFileFolderVisible(false, key, setOf(key), showHidden = false))
    }

    @Test
    fun folderKeysAreNamespacedBySource() {
        assertNotEquals(remoteFolderVisibilityKey("same"), mirrorFolderVisibilityKey("same"))
        assertNotEquals(mirrorFolderVisibilityKey("same"), localFolderVisibilityKey("same"))
    }
}
