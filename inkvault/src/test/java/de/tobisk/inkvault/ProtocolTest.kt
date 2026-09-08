package de.tobisk.inkvault

import de.tobisk.inkvault.data.*
import de.tobisk.inkvault.sync.*
import java.nio.file.Files
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    private class Session : SessionStore {
        override var access = "access"
        override var refresh = "refresh"
        override fun accept(body: JSONObject) {
            access = body.getString("accessToken")
            refresh = body.getString("refreshToken")
        }
    }

    @Test fun apiGateAndOptionalFeatures() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"apiVersion":1,"minClientApiVersion":1,"features":["syncFileReferences"]}"""))
            val client = ObsidiSyncClient(server.url("/").toString(), "alice", "main", Session(), allowLoopbackForTests = true)
            assertEquals(setOf("syncFileReferences"), client.info())
            assertEquals("/v1/server/info", server.takeRequest().path)
            server.enqueue(MockResponse().setBody("""{"apiVersion":2,"minClientApiVersion":2}"""))
            assertThrows(IllegalArgumentException::class.java) { client.info() }
        }
    }

    @Test fun syncUsesNativeRevisionManifestAndReferenceContract() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"status":"ok","serverHead":"abc","files":[],"conflicts":[]}"""))
            val client = ObsidiSyncClient(server.url("/").toString(), "alice", "main", Session(), allowLoopbackForTests = true)
            client.sync(null, "distinct-device", "InkVault", emptyList(), JSONArray(), true)
            val request = server.takeRequest()
            val body = JSONObject(request.body.readUtf8())
            assertEquals("/v1/users/alice/vaults/main/sync", request.path)
            assertTrue(body.isNull("baseHead"))
            assertEquals("reference", body.getString("fileContent"))
            assertEquals("distinct-device", body.getString("clientId"))
            assertEquals("Bearer access", request.getHeader("Authorization"))
        }
    }

    @Test fun refreshRetriesExactlyOnceAndStoresRotatedSession() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody("""{"accessToken":"new","refreshToken":"rotated"}"""))
            server.enqueue(MockResponse().setBody("[]"))
            val session = Session()
            val client = ObsidiSyncClient(server.url("/").toString(), "a", "v", session, allowLoopbackForTests = true)
            client.history("note.md")
            server.takeRequest()
            assertEquals("/v1/auth/session/refresh", server.takeRequest().path)
            assertEquals("Bearer new", server.takeRequest().getHeader("Authorization"))
            assertEquals("rotated", session.refresh)
        }
    }

    @Test fun badUploadAcknowledgementCannotLoopOrComplete() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"uploadId":"staged","chunkSize":2}"""))
            server.enqueue(MockResponse().setBody("""{"received":0}"""))
            val file = Files.createTempFile("upload", ".bin").toFile()
            file.writeText("abcd")
            try {
                val client = ObsidiSyncClient(server.url("/").toString(), "a", "v", Session(), allowLoopbackForTests = true)
                assertThrows(IllegalArgumentException::class.java) { client.upload("a.bin", file, VaultPath.sha256(file.inputStream())) }
                assertEquals(2, server.requestCount)
            } finally {
                file.delete()
            }
        }
    }

    @Test fun referencesAreFetchedAtExactRevisionAndVerified() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("bad"))
            val root = Files.createTempDirectory("blob-test").toFile()
            try {
                val client = ObsidiSyncClient(server.url("/").toString(), "a", "v", Session(), allowLoopbackForTests = true)
                val hash = VaultPath.sha256("expected".toByteArray())
                assertThrows(IllegalArgumentException::class.java) { client.download(JSONObject().put("path", "Note A.md").put("sha256", hash).put("size", 3), "revision", BlobStore(root)) }
                val path = server.takeRequest().path!!
                assertTrue(path.contains("path=Note%20A.md"))
                assertTrue(path.contains("hash=revision"))
                assertFalse(java.io.File(root, hash).exists())
            } finally {
                root.deleteRecursively()
            }
        }
    }
}
