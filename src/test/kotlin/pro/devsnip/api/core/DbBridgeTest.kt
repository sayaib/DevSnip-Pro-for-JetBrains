package pro.devsnip.api.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import pro.devsnip.api.ide.engine.DbBridge
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The Database Client's drivers against real servers: an embedded PostgreSQL
 * always, and MongoDB when `mongod` is installed. Values come back tagged as
 * the driver stand-ins (webview/shims/db-*.js) expect.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DbBridgeTest {
    private val bridge = DbBridge()
    private var postgres: io.zonky.test.db.postgres.embedded.EmbeddedPostgres? = null
    private var mongod: Process? = null
    private var mongoPort = 0
    private var mongoDir: Path? = null

    @BeforeAll
    fun start() {
        postgres = runCatching {
            // The IDE test class loader lists a resource twice, which the default resolver refuses; take the first.
            io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder()
                .setPgBinaryResolver { system, architecture ->
                    val arch = when (architecture) { "aarch64", "arm64" -> "arm_64"; "x86_64", "amd64" -> "x86_64"; else -> architecture }
                    javaClass.classLoader.getResourceAsStream("postgres-${system.lowercase()}-$arch.txz")
                }
                .start()
        }.onFailure { System.err.println("DEVSNIP-PG: $it") }.getOrNull()
        val binary = listOf("/opt/homebrew/bin/mongod", "/usr/local/bin/mongod", "/usr/bin/mongod").firstOrNull { Files.isExecutable(Path.of(it)) }
        if (binary != null) {
            mongoPort = ServerSocket(0).use { it.localPort }
            mongoDir = Files.createTempDirectory("devsnip-mongo")
            mongod = ProcessBuilder(binary, "--port", mongoPort.toString(), "--dbpath", mongoDir.toString(), "--bind_ip", "127.0.0.1", "--quiet")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            Thread.sleep(2500)
        }
    }

    @AfterAll
    fun stop() {
        bridge.closeAll()
        postgres?.close()
        mongod?.destroy()
        mongod?.waitFor(10, TimeUnit.SECONDS)
        mongoDir?.toFile()?.deleteRecursively()
    }

    private fun obj(json: String) = JsonParser.parseString(json).asJsonObject

    private fun pg(): Int {
        val server = postgres
        assumeTrue(server != null, "embedded PostgreSQL could not start here")
        val answer = bridge.handle("db.connect", obj("""{"kind":"postgresql","host":"localhost","port":${server!!.port},"user":"postgres","database":"postgres","ssl":null}"""))!!.asJsonObject
        assertTrue(answer["pid"].asInt > 0, "the backend pid for pg_cancel_backend")
        return answer["id"].asInt
    }

    private fun query(id: Int, sql: String, params: String = "[]") =
        bridge.handle("db.query", obj("""{"id":$id,"sql":${com.google.gson.JsonPrimitive(sql)},"params":$params}"""))!!.asJsonObject["results"].asJsonArray

    @Test
    fun `PostgreSQL values come back as pg returns them`() {
        val id = pg()
        query(id, "CREATE TABLE t (id serial PRIMARY KEY, name text, n int4, big int8, price numeric(10,2), ok bool, doc jsonb, raw bytea, at timestamptz)")
        val inserted = query(id, "INSERT INTO t (name, n, big, price, ok, doc, raw, at) VALUES (\$1, \$2, \$3, \$4, \$5, \$6, decode('0102', 'hex'), '2026-01-02 03:04:05+00')",
            """["Ada", 7, "9007199254740993", "12.50", true, "{\"a\":1}"]""")
        assertEquals(1, inserted[0].asJsonObject["updateCount"].asInt)
        val set = query(id, "SELECT name, n, big, price, ok, doc, raw, at FROM t")[0].asJsonObject
        val row = set["rows"].asJsonArray[0].asJsonArray
        assertEquals(listOf("name", "n", "big", "price", "ok", "doc", "raw", "at"), set["columns"].asJsonArray.map { it.asJsonObject["name"].asString })
        assertEquals("Ada", row[0].asString)
        assertEquals(7, row[1].asInt)
        assertEquals("9007199254740993", row[2].asString, "int8 stays text, as pg leaves it")
        assertEquals("12.50", row[3].asString, "numeric stays the server's text")
        assertEquals(true, row[4].asBoolean)
        assertEquals("{\"a\": 1}", row[5].asJsonObject["\$json"].asString)
        assertEquals("AQI=", row[6].asJsonObject["\$bytes"].asString)
        assertTrue(row[7].asString.startsWith("2026-01-02"))
        assertEquals("jsonb", set["columns"].asJsonArray[5].asJsonObject["type"].asString)
    }

    @Test
    fun `PostgreSQL scripts give every result and errors give their position`() {
        val id = pg()
        val results = query(id, "CREATE TEMP TABLE s(a int); INSERT INTO s VALUES (1),(2); SELECT count(*) AS n FROM s")
        assertEquals(3, results.size())
        assertEquals(2, results[1].asJsonObject["updateCount"].asInt)
        assertEquals("2", results[2].asJsonObject["rows"].asJsonArray[0].asJsonArray[0].asString)
        val error = assertThrows<DbBridge.DbException> { query(id, "SELECT * FRM s") }
        assertEquals("42601", error.data["code"].asString)
        assertEquals("10", error.data["position"].asString)
    }

    @Test
    fun `a running query can be cancelled from another thread`() {
        val id = pg()
        val canceller = Thread {
            Thread.sleep(500)
            bridge.handle("db.cancel", obj("""{"id":$id}"""))
        }
        canceller.start()
        val error = assertThrows<DbBridge.DbException> { query(id, "SELECT pg_sleep(10)") }
        assertTrue(error.data["cancelled"]?.asBoolean == true, error.message)
        canceller.join()
    }

    @Test
    fun `MongoDB documents cross as canonical extended JSON`() {
        assumeTrue(mongod?.isAlive == true, "mongod is not installed here")
        val id = bridge.handle("mongo.connect", obj("""{"url":"mongodb://127.0.0.1:$mongoPort/app","connectTimeoutMs":5000}"""))!!.asJsonObject["id"].asInt
        fun op(op: String, args: String, options: String = "{}", collection: String = "people") =
            JsonParser.parseString(bridge.handle("mongo.op", obj("""{"id":$id,"db":"app","collection":"$collection","op":"$op","args":${com.google.gson.JsonPrimitive(args)},"options":${com.google.gson.JsonPrimitive(options)}}"""))!!.asString).asJsonObject["r"]
        op("insertOne", """[{"_id":{"${'$'}oid":"64b7f0000000000000000001"},"name":"Ada","born":{"${'$'}date":{"${'$'}numberLong":"-4000000000000"}}}]""")
        op("insertMany", """[[{"name":"Linus"},{"name":"Grace"}]]""")
        assertEquals(3L, op("countDocuments", "[{}]").asJsonObject["${'$'}numberLong"].asLong)
        val found = op("find", """[{"name":"Ada"}]""").asJsonArray
        assertEquals("64b7f0000000000000000001", found[0].asJsonObject["_id"].asJsonObject["${'$'}oid"].asString)
        assertTrue(found[0].asJsonObject["born"].asJsonObject.has("${'$'}date"))
        val updated = op("updateOne", """[{"name":"Linus"},{"${'$'}set":{"kernel":true}},{}]""").asJsonObject
        assertEquals(1L, updated["modifiedCount"].asJsonObject["${'$'}numberLong"].asLong)
        val names = op("listCollections", "[{}]", collection = "people").asJsonArray.map { it.asJsonObject["name"].asString }
        assertTrue("people" in names)
        bridge.handle("mongo.close", obj("""{"id":$id}"""))
    }

    @Suppress("unused")
    private fun JsonArray.strings() = map { it.asString }

    @Suppress("unused")
    private fun JsonObject.str(key: String) = get(key)?.asString
}
