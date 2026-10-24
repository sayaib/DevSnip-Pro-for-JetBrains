package pro.devsnip.api.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HistoryAndRedactionTest {
    @Test
    fun `redacts credential-like query values before they are stored`() {
        assertEquals(
            "https://e.com/x?api_key=[redacted]&page=2&access_token=[redacted]&Signature=[redacted]",
            Redaction.redactUrl("https://e.com/x?api_key=abc&page=2&access_token=t&Signature=s"),
        )
        assertEquals("https://e.com/x?page=2&q=search", Redaction.redactUrl("https://e.com/x?page=2&q=search"))
        assertEquals("not a url", Redaction.redactUrl("not a url"))
    }

    @Test
    fun `a templated URL keeps its variables but not a literal secret`() {
        assertEquals(
            "{{baseUrl}}/items?api_key=[redacted]&token={{token}}&page=2",
            Redaction.redactTemplateUrl("{{baseUrl}}/items?api_key=hunter2&token={{token}}&page=2"),
        )
    }

    @Test
    fun `masks secrets for display`() {
        assertEquals("***", Redaction.mask("short"))
        assertEquals("abcdef...wxyz (26 chars)", Redaction.mask("abcdefghijklmnopqrstuvwxyz"))
        assertEquals(
            listOf("Accept" to "*/*", "Authorization" to "[redacted]", "Set-Cookie" to "[redacted]", "X-Api-Key" to "[redacted]"),
            Redaction.sanitizeHeaders(listOf("Accept" to "*/*", "Authorization" to "Bearer x", "Set-Cookie" to "sid=1", "X-Api-Key" to "k")),
        )
    }

    @Test
    fun `literal credentials are dropped from snapshots, variable references are kept`() {
        val snapshot = HistorySnapshots.snapshot(
            ApiRequest(
                url = "https://e.com/?token=abc",
                headers = listOf(
                    KeyValue("Accept", "application/json"),
                    KeyValue("Authorization", "Bearer real-secret-token"),
                    KeyValue("X-Api-Key", "{{apiKey}}"),
                    KeyValue("Cookie", "sid=abc"),
                    KeyValue("X-Auth", "Bearer {{token}}"),
                ),
                params = listOf(KeyValue("password", "hunter2"), KeyValue("page", "1")),
            ),
        )
        assertEquals(listOf(KeyValue("Accept", "application/json"), KeyValue("X-Api-Key", "{{apiKey}}"), KeyValue("X-Auth", "Bearer {{token}}")), snapshot.headers)
        assertEquals(listOf(KeyValue("page", "1")), snapshot.params)
        assertEquals("https://e.com/?token=[redacted]", snapshot.url)
    }

    @Test
    fun `auth keeps names and variable references, never typed secrets`() {
        assertEquals(emptyMap<String, String>(), HistorySnapshots.snapshot(ApiRequest(url = "https://e.com", auth = AuthConfig.Bearer("real-token"))).auth)
        assertEquals(mapOf("token" to "{{token}}"), HistorySnapshots.snapshot(ApiRequest(url = "https://e.com", auth = AuthConfig.Bearer(" {{token}} "))).auth)
        assertEquals(mapOf("username" to "ada"), HistorySnapshots.snapshot(ApiRequest(url = "https://e.com", auth = AuthConfig.Basic("ada", "pw"))).auth)
        val apiKey = HistorySnapshots.snapshot(ApiRequest(url = "https://e.com", auth = AuthConfig.ApiKey("X-Key", "{{apiKey}}", ApiKeyLocation.QUERY)))
        assertEquals(mapOf("keyName" to "X-Key", "keyLocation" to "QUERY", "keyValue" to "{{apiKey}}"), apiKey.auth)
        assertEquals(AuthConfig.ApiKey("X-Key", "{{apiKey}}", ApiKeyLocation.QUERY), HistorySnapshots.toRequest(apiKey).auth)
    }

    @Test
    fun `large bodies are not kept and GraphQL fields only for GraphQL requests`() {
        assertNull(HistorySnapshots.snapshot(ApiRequest(url = "https://e.com", data = "x".repeat(20_001))).data)
        assertEquals("{\"a\":1}", HistorySnapshots.snapshot(ApiRequest(url = "https://e.com", data = "{\"a\":1}")).data)
        assertNull(HistorySnapshots.snapshot(ApiRequest(url = "https://e.com", graphqlQuery = "ignored")).graphqlQuery)
        val gql = HistorySnapshots.snapshot(ApiRequest(url = "https://e.com", requestType = RequestType.GRAPHQL, graphqlQuery = "{ users { id } }", graphqlOperationName = "Users"))
        assertEquals("{ users { id } }", gql.graphqlQuery)
        assertEquals(RequestType.GRAPHQL, HistorySnapshots.toRequest(gql).requestType)
    }

    @Test
    fun `history keeps the newest 50, redacted, and survives storage`() {
        val log = HistoryLog()
        repeat(55) { log.add(HistoryItem("id$it", "https://e.com/$it?key=secret$it", "GET", it.toLong(), 200, 10, 5, 1, HistorySnapshots.snapshot(ApiRequest(url = "https://e.com/$it")))) }
        assertEquals(50, log.all().size)
        assertEquals("id54", log.all().first().id)
        assertEquals("https://e.com/54?key=[redacted]", log.all().first().url)

        val stored = log.toStorageJson()
        assertFalse(stored.contains("secret"))
        val restored = HistoryLog.fromStorageJson(stored)
        assertEquals(log.all(), restored.all())
        assertEquals(0, HistoryLog.fromStorageJson("{corrupt").all().size)
    }

    @Test
    fun `exports JSON without snapshots and CSV safe from formula injection`() {
        val log = HistoryLog()
        log.add(HistoryItem("1", "https://e.com/a", "POST", 0, 201, 12, 34, 2, HistorySnapshots.snapshot(ApiRequest(url = "https://e.com/a", data = "secret body"))))
        log.add(HistoryItem("2", "=HYPERLINK(\"http://evil\")", "GET", 1000, 0, 5, null, 1))
        val json = log.exportJson()
        assertFalse(json.contains("secret body"))
        assertTrue(json.contains("\"status\": 201"))

        val csv = log.exportCsv().lines()
        assertEquals("id,method,url,status,responseTime,size,attempts,timestamp", csv[0])
        assertEquals("\"2\",\"GET\",\"'=HYPERLINK(\"\"http://evil\"\")\",0,5,,1,1970-01-01T00:00:01Z", csv[1])
        assertEquals("\"1\",\"POST\",\"https://e.com/a\",201,12,34,2,1970-01-01T00:00:00Z", csv[2])
    }
}
