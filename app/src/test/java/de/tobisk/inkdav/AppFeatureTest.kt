package de.tobisk.inkdav

import de.tobisk.inkdav.data.AccountKind
import de.tobisk.inkdav.data.CollectionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppFeatureTest {
    @Test
    fun `each application exposes only its feature plus shared server and sync screens`() {
        assertEquals(
            listOf(Destination.CALENDAR, Destination.SYNC, Destination.SERVER_SETUP),
            AppFeature.CALENDAR.destinations
        )
        assertEquals(
            listOf(Destination.TODOS, Destination.SYNC, Destination.SERVER_SETUP),
            AppFeature.TODOS.destinations
        )
        assertEquals(
            listOf(Destination.FILES, Destination.SYNC, Destination.SERVER_SETUP),
            AppFeature.FILES.destinations
        )
    }

    @Test
    fun `applications synchronize only their own server capability`() {
        assertTrue(AppFeature.CALENDAR.accepts(AccountKind.DAV))
        assertTrue(AppFeature.CALENDAR.includes(CollectionKind.CALENDAR))
        assertFalse(AppFeature.CALENDAR.includes(CollectionKind.TASK_LIST))

        assertTrue(AppFeature.TODOS.accepts(AccountKind.DAV))
        assertTrue(AppFeature.TODOS.includes(CollectionKind.TASK_LIST))
        assertFalse(AppFeature.TODOS.includes(CollectionKind.CALENDAR))

        assertTrue(AppFeature.FILES.accepts(AccountKind.NASDRIVE))
        assertTrue(AppFeature.FILES.includes(CollectionKind.FILE_ROOT))
        assertFalse(AppFeature.FILES.accepts(AccountKind.DAV))
    }
}
