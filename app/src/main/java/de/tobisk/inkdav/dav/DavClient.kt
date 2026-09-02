package de.tobisk.inkdav.dav

import de.tobisk.inkdav.data.DavAccountEntity
import java.io.InputStream
import java.time.Instant

data class DavResource(
    val href: String,
    val displayName: String = "",
    val isCollection: Boolean = false,
    val isCalendar: Boolean = false,
    val supportsEvents: Boolean = false,
    val supportsTasks: Boolean = false,
    val etag: String? = null,
    val contentType: String? = null,
    val size: Long? = null,
    val modifiedAt: Long? = null,
    val calendarData: String? = null,
    val syncToken: String? = null,
    val ctag: String? = null,
    val currentUserPrincipalHref: String? = null,
    val calendarHomeHref: String? = null,
)

class DavHttpException(val code: Int, message: String) : Exception(message)

interface DavClient {
    suspend fun discoverCollections(account: DavAccountEntity, password: CharArray): List<DavResource>
    suspend fun calendarQuery(account: DavAccountEntity, password: CharArray, collectionHref: String, component: String, from: Instant, until: Instant): List<DavResource>
    suspend fun list(account: DavAccountEntity, password: CharArray, href: String): List<DavResource>
    suspend fun get(account: DavAccountEntity, password: CharArray, href: String): InputStream
    suspend fun put(account: DavAccountEntity, password: CharArray, href: String, body: ByteArray, contentType: String, etag: String?, createOnly: Boolean = false): String?
    suspend fun putStream(account: DavAccountEntity, password: CharArray, href: String, body: () -> InputStream, size: Long?, contentType: String, etag: String?, createOnly: Boolean = false): String? =
        body().use { put(account, password, href, it.readBytes(), contentType, etag, createOnly) }
    suspend fun delete(account: DavAccountEntity, password: CharArray, href: String, etag: String?)
    suspend fun makeCollection(account: DavAccountEntity, password: CharArray, href: String)
}
