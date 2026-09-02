package de.tobisk.inkdav.dav

import de.tobisk.inkdav.data.AccountKind

internal fun normalizeDavBaseUrl(value: String, kind: AccountKind): String {
    val withSlash = value.trim().let { if (it.endsWith('/')) it else "$it/" }
    if (kind != AccountKind.DAV) return withSlash
    return Regex("/addressbooks/", RegexOption.IGNORE_CASE).replaceFirst(withSlash, "/calendars/")
}

internal fun normalizeCalendarCollectionHref(value: String): String {
    val lower = value.lowercase()
    val calendarRoot = lower.indexOf("/dav/calendars/")
    if (calendarRoot < 0) return value
    val appendedPrincipal = lower.indexOf("/dav/principals/", calendarRoot + "/dav/calendars/".length)
    if (appendedPrincipal < 0) return value
    return value.substring(0, appendedPrincipal).trimEnd('/') + "/"
}
