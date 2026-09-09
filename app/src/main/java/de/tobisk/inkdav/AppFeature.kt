package de.tobisk.inkdav

import de.tobisk.inkdav.data.AccountKind
import de.tobisk.inkdav.data.CollectionKind
import de.tobisk.inkdav.data.ObjectKind

enum class AppFeature(
    val home: Destination,
    val releaseName: String,
    val objectKind: ObjectKind
) {
    CALENDAR(Destination.CALENDAR, "InkDAV-Calendar", ObjectKind.EVENT),
    TODOS(Destination.TODOS, "InkDAV-Todos", ObjectKind.TASK),
    FILES(Destination.FILES, "InkDAV-Files", ObjectKind.FILE);

    val destinations: List<Destination>
        get() = listOf(home, Destination.SYNC, Destination.SERVER_SETUP)

    fun accepts(accountKind: AccountKind): Boolean = when (this) {
        CALENDAR, TODOS -> accountKind == AccountKind.DAV
        FILES -> accountKind == AccountKind.NASDRIVE
    }

    fun includes(collectionKind: CollectionKind): Boolean = when (this) {
        CALENDAR -> collectionKind == CollectionKind.CALENDAR
        TODOS -> collectionKind == CollectionKind.TASK_LIST
        FILES -> collectionKind == CollectionKind.FILE_ROOT
    }

    fun includes(objectKind: ObjectKind): Boolean = this.objectKind == objectKind

    companion object {
        val current: AppFeature
            get() = valueOf(BuildConfig.APP_FEATURE)
    }
}
