package de.tobisk.inkdav.dav

import de.tobisk.inkdav.data.AccountKind

internal fun normalizeDavBaseUrl(value: String, kind: AccountKind): String {
    val withSlash = value.trim().let { if (it.endsWith('/')) it else "$it/" }
    if (kind != AccountKind.DAV) return withSlash
    return Regex("/addressbooks/", RegexOption.IGNORE_CASE).replaceFirst(withSlash, "/calendars/")
}
