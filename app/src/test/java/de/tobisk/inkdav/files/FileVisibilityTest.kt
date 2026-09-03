package de.tobisk.inkdav.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileVisibilityTest {
    @Test
    fun `dot files and folders are hidden by name`() {
        assertTrue(isDotFileName(".git"))
        assertTrue(isDotFileName(".nomedia"))
        assertFalse(isDotFileName("notes.md"))
        assertFalse(isDotFileName("project.hidden.md"))
    }

    @Test
    fun `mirror visibility checks the final path component`() {
        assertTrue(isDotPath("Documents/.drafts"))
        assertFalse(isDotPath("Documents/archive/.drafts/note.md"))
        assertFalse(isDotPath("Documents/notes.md"))
    }
}
