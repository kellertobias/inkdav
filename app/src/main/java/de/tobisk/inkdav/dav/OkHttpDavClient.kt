package de.tobisk.inkdav.dav

import android.util.Xml
import de.tobisk.inkdav.data.DavAccountEntity
import java.io.FilterInputStream
import java.io.InputStream
import java.net.URI
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.xmlpull.v1.XmlPullParser

internal data class ParsedMultiStatus(val resources: List<DavResource>, val syncToken: String?)

class OkHttpDavClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()
) : DavClient {
    override suspend fun discoverCollections(account: DavAccountEntity, password: CharArray): List<DavResource> {
        val principal = propfind(account, password, account.baseUrl, 0, DISCOVERY_PROPERTIES).firstOrNull()
        val principalUrl = principal?.currentUserPrincipalHref?.let { resolve(account.baseUrl, it) } ?: account.baseUrl
        val home = propfind(account, password, principalUrl, 0, HOME_PROPERTIES).firstOrNull()?.calendarHomeHref
        val homeUrl = home?.let { resolve(principalUrl, it) } ?: account.baseUrl
        return propfind(account, password, homeUrl, 1, COLLECTION_PROPERTIES)
            .filter { it.href.trimEnd('/') != URI(homeUrl).path.trimEnd('/') }
    }

    override suspend fun calendarQuery(
        account: DavAccountEntity,
        password: CharArray,
        collectionHref: String,
        component: String,
        from: Instant,
        until: Instant
    ): List<DavResource> {
        val format = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC)
        val componentFilter = if (component == "VTODO") {
            "<c:comp-filter name=\"VTODO\"/>"
        } else {
            "<c:comp-filter name=\"VEVENT\"><c:time-range start=\"${format.format(
                from
            )}\" end=\"${format.format(until)}\"/></c:comp-filter>"
        }
        val body = """<?xml version="1.0" encoding="utf-8" ?>
            <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
              <d:prop><d:getetag/><c:calendar-data/></d:prop>
              <c:filter><c:comp-filter name="VCALENDAR">$componentFilter</c:comp-filter></c:filter>
            </c:calendar-query>
        """.trimIndent()
        return xmlRequest(account, password, resolve(account.baseUrl, collectionHref), "REPORT", body, mapOf("Depth" to "1")).resources
    }

    override suspend fun syncCollection(
        account: DavAccountEntity,
        password: CharArray,
        collectionHref: String,
        syncToken: String
    ): DavSyncResult {
        val body = """<?xml version="1.0" encoding="utf-8" ?>
            <d:sync-collection xmlns:d="DAV:">
              <d:sync-token>${xmlEscape(syncToken)}</d:sync-token>
              <d:sync-level>1</d:sync-level>
              <d:prop><d:getetag/></d:prop>
            </d:sync-collection>
        """.trimIndent()
        val parsed = xmlRequest(
            account,
            password,
            resolve(account.baseUrl, collectionHref),
            "REPORT",
            body,
            mapOf("Depth" to "1")
        )
        return DavSyncResult(
            resources = parsed.resources,
            nextSyncToken = parsed.syncToken?.takeIf(String::isNotBlank)
                ?: error("DAV sync report did not return a sync token")
        )
    }

    override suspend fun calendarMultiget(
        account: DavAccountEntity,
        password: CharArray,
        collectionHref: String,
        hrefs: List<String>
    ): List<DavResource> {
        if (hrefs.isEmpty()) return emptyList()
        val requested = hrefs.joinToString("\n") { "<d:href>${xmlEscape(it)}</d:href>" }
        val body = """<?xml version="1.0" encoding="utf-8" ?>
            <c:calendar-multiget xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
              <d:prop><d:getetag/><c:calendar-data/></d:prop>
              $requested
            </c:calendar-multiget>
        """.trimIndent()
        return xmlRequest(
            account,
            password,
            resolve(account.baseUrl, collectionHref),
            "REPORT",
            body,
            mapOf("Depth" to "1")
        ).resources
    }

    override suspend fun list(account: DavAccountEntity, password: CharArray, href: String): List<DavResource> = propfind(account, password, resolve(account.baseUrl, href), 1, FILE_PROPERTIES)
        .filter { it.href.trimEnd('/') != URI(resolve(account.baseUrl, href)).path.trimEnd('/') }

    override suspend fun get(account: DavAccountEntity, password: CharArray, href: String): InputStream = withContext(Dispatchers.IO) {
        val response = execute(account, password, Request.Builder().url(resolve(account.baseUrl, href)).get())
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw DavHttpException(code, "DAV download failed ($code)")
        }
        object : FilterInputStream(response.body.byteStream()) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    response.close()
                }
            }
        }
    }

    override suspend fun put(
        account: DavAccountEntity,
        password: CharArray,
        href: String,
        body: ByteArray,
        contentType: String,
        etag: String?,
        createOnly: Boolean
    ): String? = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(resolve(account.baseUrl, href))
            .put(body.toRequestBody(contentType.toMediaType()))
        if (createOnly) builder.header("If-None-Match", "*") else etag?.let { builder.header("If-Match", it) }
        execute(account, password, builder).use {
            if (!it.isSuccessful) throw DavHttpException(it.code, "DAV upload failed (${it.code})")
            it.header("ETag")
        }
    }

    override suspend fun putStream(
        account: DavAccountEntity,
        password: CharArray,
        href: String,
        body: () -> InputStream,
        size: Long?,
        contentType: String,
        etag: String?,
        createOnly: Boolean
    ): String? = withContext(Dispatchers.IO) {
        val requestBody = object : RequestBody() {
            override fun contentType() = contentType.toMediaType()
            override fun contentLength(): Long = size ?: -1
            override fun writeTo(sink: BufferedSink) {
                body().use { input -> sink.writeAll(input.source()) }
            }
        }
        val builder = Request.Builder().url(resolve(account.baseUrl, href)).put(requestBody)
        if (createOnly) builder.header("If-None-Match", "*") else etag?.let { builder.header("If-Match", it) }
        execute(account, password, builder).use {
            if (!it.isSuccessful) throw DavHttpException(it.code, "DAV upload failed (${it.code})")
            it.header("ETag")
        }
    }

    override suspend fun delete(account: DavAccountEntity, password: CharArray, href: String, etag: String?) = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(resolve(account.baseUrl, href)).delete()
        etag?.let { builder.header("If-Match", it) }
        execute(account, password, builder).use {
            if (!it.isSuccessful && it.code != 404) throw DavHttpException(it.code, "DAV delete failed (${it.code})")
        }
    }

    override suspend fun move(
        account: DavAccountEntity,
        password: CharArray,
        sourceHref: String,
        destinationHref: String,
        etag: String?
    ) = withContext(Dispatchers.IO) {
        val destination = resolve(account.baseUrl, destinationHref)
        val builder = Request.Builder().url(resolve(account.baseUrl, sourceHref))
            .method("MOVE", ByteArray(0).toRequestBody(null))
            .header("Destination", destination)
            .header("Overwrite", "F")
        etag?.let { builder.header("If-Match", it) }
        execute(account, password, builder).use {
            if (!it.isSuccessful) throw DavHttpException(it.code, "DAV move failed (${it.code})")
        }
    }

    override suspend fun makeCollection(account: DavAccountEntity, password: CharArray, href: String) = withContext(Dispatchers.IO) {
        execute(
            account,
            password,
            Request.Builder().url(resolve(account.baseUrl, href)).method("MKCOL", ByteArray(0).toRequestBody(null))
        ).use {
            if (!it.isSuccessful && it.code != 405) throw DavHttpException(it.code, "DAV folder creation failed (${it.code})")
        }
    }

    private suspend fun propfind(account: DavAccountEntity, password: CharArray, url: String, depth: Int, properties: String) = xmlRequest(
        account,
        password,
        url,
        "PROPFIND",
        "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\"><d:prop>$properties</d:prop></d:propfind>",
        mapOf(
            "Depth" to depth.toString()
        )
    ).resources

    private suspend fun xmlRequest(
        account: DavAccountEntity,
        password: CharArray,
        url: String,
        method: String,
        xml: String,
        headers: Map<String, String>
    ): ParsedMultiStatus = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url).method(method, xml.toRequestBody("application/xml; charset=utf-8".toMediaType()))
        headers.forEach(builder::header)
        execute(account, password, builder).use {
            if (!it.isSuccessful && it.code != 207) {
                throw DavHttpException(it.code, "$method failed (${it.code}) at ${URI(url).rawPath}")
            }
            parseMultiStatus(it.body.byteStream())
        }
    }

    private fun execute(account: DavAccountEntity, password: CharArray, builder: Request.Builder): okhttp3.Response {
        val passwordString = password.concatToString()
        // Callers own and wipe the CharArray after a complete multi-request DAV operation.
        return http.newCall(builder.header("Authorization", Credentials.basic(account.username, passwordString)).build()).execute()
    }

    internal fun parseMultiStatus(stream: InputStream, parser: XmlPullParser = Xml.newPullParser()): ParsedMultiStatus {
        parser.apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(stream, Charsets.UTF_8.name())
        }
        val result = mutableListOf<DavResource>()
        var response: MutableMap<String, String>? = null
        val tagStack = ArrayDeque<String>()
        var isCollection = false
        var isCalendar = false
        var events = false
        var tasks = false
        var syncToken: String? = null
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    val currentTag = parser.name.lowercase()
                    tagStack.addLast(currentTag)
                    if (currentTag ==
                        "response"
                    ) {
                        response = mutableMapOf()
                        isCollection = false
                        isCalendar = false
                        events = false
                        tasks = false
                    }
                    if (response != null && currentTag == "collection") isCollection = true
                    if (response != null && currentTag == "calendar") isCalendar = true
                    if (response != null &&
                        currentTag == "comp"
                    ) {
                        when (parser.getAttributeValue(null, "name")?.uppercase()) {
                            "VEVENT" -> events = true
                            "VTODO" ->
                                tasks =
                                    true
                        }
                    }
                }
                XmlPullParser.TEXT -> if (parser.text.isNotBlank() && tagStack.isNotEmpty()) {
                    val currentTag = tagStack.last()
                    val parentTag = tagStack.elementAtOrNull(tagStack.size - 2)
                    if (response == null && currentTag == "sync-token") {
                        syncToken = (syncToken.orEmpty() + parser.text).trim()
                    } else if (response != null) {
                        val key = when {
                            currentTag == "href" && parentTag == "current-user-principal" -> "current-user-principal"
                            currentTag == "href" && parentTag == "calendar-home-set" -> "calendar-home-set"
                            currentTag == "status" && parentTag == "response" -> "response-status"
                            else -> currentTag
                        }
                        response[key] = (response[key].orEmpty() + parser.text).trim()
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("response", true) && response != null) {
                        val r = response
                        r["href"]?.let { href ->
                            result += DavResource(
                                href = href, displayName = r["displayname"].orEmpty(), isCollection = isCollection,
                                isCalendar = isCalendar, supportsEvents = events, supportsTasks = tasks,
                                etag = r["getetag"], contentType = r["getcontenttype"], size = r["getcontentlength"]?.toLongOrNull(),
                                modifiedAt = r["getlastmodified"]?.let(::httpDate), calendarData = r["calendar-data"],
                                syncToken = r["sync-token"], ctag = r["getctag"], currentUserPrincipalHref = r["current-user-principal"],
                                calendarHomeHref = r["calendar-home-set"],
                                deleted = r["response-status"]?.contains(" 404 ") == true
                            )
                        }
                        response = null
                    }
                    if (tagStack.isNotEmpty()) tagStack.removeLast()
                }
            }
            parser.next()
        }
        return ParsedMultiStatus(result, syncToken)
    }

    private fun httpDate(value: String) = runCatching {
        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.getOrNull()
    private fun resolve(base: String, href: String): String = URI(base).resolve(href).toString()
    private fun xmlEscape(value: String) = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    companion object {
        private const val DISCOVERY_PROPERTIES = "<d:current-user-principal/><c:calendar-home-set/>"
        private const val HOME_PROPERTIES = "<c:calendar-home-set/>"
        private const val COLLECTION_PROPERTIES = "<d:displayname/><d:resourcetype/><c:supported-calendar-component-set/><d:sync-token/><cs:getctag/>"
        private const val FILE_PROPERTIES = "<d:displayname/><d:resourcetype/><d:getetag/><d:getcontenttype/><d:getcontentlength/><d:getlastmodified/>"
    }
}
