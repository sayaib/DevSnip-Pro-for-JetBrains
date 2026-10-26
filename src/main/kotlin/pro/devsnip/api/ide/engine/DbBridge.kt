package pro.devsnip.api.ide.engine

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.sql.CallableStatement
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Types
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Database Client's drivers, for the extension host: the extension's own
 * adapters (SQL building, paging, editing, the query console) run in the
 * host page and talk to their Node drivers (pg, mysql2, mssql, oracledb,
 * mongodb, ioredis); those are stand-ins (webview/shims/db-*.js) for the
 * JDBC drivers, the MongoDB Java driver and Jedis here.
 *
 * Values cross as JSON with tags the stand-ins turn into what each Node
 * driver returns: {"$big"}, {"$num"} (exact text), {"$bytes"} (base64),
 * {"$json"} (text), {"$date"} (epoch ms), {"$ts"} (wall-clock text) and
 * {"$interval"}. Connections close with the extension host.
 */
class DbBridge {
    /** An error the driver stand-ins read details from (pg's position, SQL Server's line, Oracle's offset). */
    class DbException(message: String, val data: JsonObject) : Exception(message)

    private class Sql(val kind: String, val connection: Connection) {
        @Volatile
        var running: Statement? = null
        val lock = Any()
    }

    private val sql = ConcurrentHashMap<Int, Sql>()
    private val mongo = ConcurrentHashMap<Int, com.mongodb.client.MongoClient>()
    private val redisClients = ConcurrentHashMap<Int, redis.clients.jedis.Jedis>()
    private val nextId = AtomicInteger(1)
    private val tempFiles = mutableListOf<Path>()

    fun handle(method: String, p: JsonObject): JsonElement? = when (method) {
        "db.connect" -> connect(p)
        "db.query" -> query(p)
        "db.call" -> call(p)
        "db.cancel" -> { sql[p["id"].asInt]?.running?.let { runCatching { it.cancel() } }; null }
        "db.close" -> { sql.remove(p["id"].asInt)?.let { runCatching { it.connection.close() } }; null }
        "mongo.connect" -> mongoConnect(p)
        "mongo.op" -> MongoOps.run(mongo[p["id"].asInt] ?: throw DbException("The MongoDB connection is closed.", JsonObject()), p)
        "mongo.close" -> { mongo.remove(p["id"].asInt)?.let { runCatching { it.close() } }; null }
        "redis.connect" -> redisConnect(p)
        "redis.exec" -> redisExec(p)
        "redis.close" -> { redisClients.remove(p["id"].asInt)?.let { runCatching { it.close() } }; null }
        else -> throw EngineHost.CodedException("Unknown request $method", "EINVAL")
    }

    // ---------------------------------------------------------------- SQL

    private fun connect(p: JsonObject): JsonElement {
        val kind = p.str("kind")
        val host = p.str("host").ifEmpty { "localhost" }
        val port = p["port"]?.takeIf { it.isJsonPrimitive }?.asInt
        val database = p.str("database")
        val timeoutMs = p["connectTimeoutMs"]?.asInt ?: 10_000
        val props = Properties()
        p.str("user").takeIf { it.isNotEmpty() }?.let { props["user"] = it }
        p.str("password").takeIf { it.isNotEmpty() }?.let { props["password"] = it }
        val ssl = p["ssl"] as? JsonObject
        val connection: Connection = try {
            when (kind) {
                "postgresql" -> {
                    props["ApplicationName"] = p.str("applicationName").ifEmpty { "DevSnip Pro" }
                    props["connectTimeout"] = (timeoutMs / 1000).coerceAtLeast(1).toString()
                    props["loginTimeout"] = (timeoutMs / 1000).coerceAtLeast(1).toString()
                    p.str("options").takeIf { it.isNotBlank() }?.let { props["options"] = it }
                    postgresSsl(ssl, props)
                    org.postgresql.Driver().connect("jdbc:postgresql://${hostPort(host, port ?: 5432)}/${encode(database.ifEmpty { "postgres" })}", props)
                }
                "mariadb" -> {
                    props["connectTimeout"] = timeoutMs.toString()
                    props["tinyInt1isBit"] = "false"
                    props["useServerPrepStmts"] = "false"
                    props["allowMultiQueries"] = "false"
                    props["permitMysqlScheme"] = "true"
                    props["allowPublicKeyRetrieval"] = (ssl == null).toString()
                    props["sslMode"] = when {
                        ssl == null -> "disable"
                        ssl["rejectUnauthorized"]?.asBoolean == false -> "trust"
                        else -> "verify-full"
                    }
                    org.mariadb.jdbc.Driver().connect("jdbc:mariadb://${hostPort(host, port ?: 3306)}/${encode(database)}", props)
                }
                "sqlserver" -> {
                    val instance = p.str("instanceName")
                    val url = StringBuilder("jdbc:sqlserver://").append(host)
                    if (instance.isNotEmpty()) url.append('\\').append(instance) else if (port != null) url.append(':').append(port)
                    if (database.isNotEmpty()) props["databaseName"] = database
                    props["encrypt"] = (ssl != null).toString()
                    props["trustServerCertificate"] = (p["trustServerCertificate"]?.asBoolean ?: true).toString()
                    props["applicationName"] = p.str("applicationName").ifEmpty { "DevSnip Pro" }
                    props["loginTimeout"] = (timeoutMs / 1000).coerceAtLeast(1).toString()
                    com.microsoft.sqlserver.jdbc.SQLServerDriver().connect(url.toString(), props)
                }
                "oracle" -> {
                    props["oracle.net.CONNECT_TIMEOUT"] = timeoutMs.toString()
                    props["oracle.jdbc.timezoneAsRegion"] = "false"
                    oracle.jdbc.OracleDriver().connect("jdbc:oracle:thin:@${p.str("connectString")}", props)
                }
                else -> throw DbException("Unknown database driver $kind.", JsonObject())
            } ?: throw DbException("The driver did not accept the connection settings.", JsonObject())
        } catch (e: SQLException) {
            throw error(kind, e)
        }
        try {
            if (p["readOnly"]?.asBoolean == true && kind != "sqlserver") connection.isReadOnly = true
            (p["init"] as? JsonArray)?.forEach { statement -> connection.createStatement().use { it.execute(statement.asString) } }
        } catch (e: SQLException) {
            runCatching { connection.close() }
            throw error(kind, e)
        }
        val id = nextId.getAndIncrement()
        sql[id] = Sql(kind, connection)
        return JsonObject().apply {
            addProperty("id", id)
            addProperty("version", runCatching { connection.metaData.databaseProductVersion }.getOrDefault(""))
            // The session ids the adapters use to cancel a console query (pg_cancel_backend, KILL QUERY).
            when (kind) {
                "postgresql" -> scalar(connection, "SELECT pg_backend_pid()")?.let { addProperty("pid", it.toInt()) }
                "mariadb" -> scalar(connection, "SELECT CONNECTION_ID()")?.let { addProperty("threadId", it.toLong()) }
            }
        }
    }

    private fun hostPort(host: String, port: Int) = (if (host.contains(':') && !host.startsWith("[")) "[$host]" else host) + ":$port"
    private fun encode(s: String) = java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")

    private fun scalar(connection: Connection, query: String): String? = runCatching {
        connection.createStatement().use { s -> s.executeQuery(query).use { rs -> if (rs.next()) rs.getString(1) else null } }
    }.getOrNull()

    /** libpq's sslmode for pgjdbc: CA, certificate and key from the connection string's files. */
    private fun postgresSsl(ssl: JsonObject?, props: Properties) {
        if (ssl == null) {
            props["sslmode"] = "disable"
            return
        }
        val verify = ssl["rejectUnauthorized"]?.asBoolean != false
        props["sslmode"] = when {
            !verify -> "require"
            ssl["verifyHost"]?.asBoolean == false -> "verify-ca"
            else -> "verify-full"
        }
        val ca = ssl["ca"]?.takeIf { it.isJsonPrimitive }?.asString
        if (ca != null) props["sslrootcert"] = temp("ca", ".pem", ca.toByteArray()).toString()
        else if (verify) props["sslfactory"] = "org.postgresql.ssl.DefaultJavaSSLFactory"
        ssl["cert"]?.takeIf { it.isJsonPrimitive }?.asString?.let { props["sslcert"] = temp("cert", ".pem", it.toByteArray()).toString() }
        ssl["key"]?.takeIf { it.isJsonPrimitive }?.asString?.let { pem ->
            val body = Regex("-----BEGIN PRIVATE KEY-----([\\s\\S]+?)-----END PRIVATE KEY-----").find(pem)?.groupValues?.get(1)
                ?: throw DbException("The sslkey must be a PKCS#8 key (BEGIN PRIVATE KEY).", JsonObject().apply { addProperty("hint", "Convert it: openssl pkcs8 -topk8 -nocrypt -in key.pem -out key.pk8.pem") })
            props["sslkey"] = temp("key", ".pk8", Base64.getMimeDecoder().decode(body.trim())).toString()
        }
    }

    @Synchronized
    private fun temp(prefix: String, suffix: String, bytes: ByteArray): Path {
        val file = Files.createTempFile("devsnip-$prefix-", suffix)
        Files.write(file, bytes)
        file.toFile().deleteOnExit()
        tempFiles.add(file)
        return file
    }

    private fun connectionOf(p: JsonObject): Sql = sql[p["id"].asInt] ?: throw DbException("The database connection is closed.", JsonObject().apply { addProperty("code", "ECONNCLOSED") })

    /**
     * Runs SQL with positional parameters (`?`, `$1` for pg, `@p1` for SQL
     * Server, `:1` for Oracle): every result set and update count, rows
     * capped at maxRows.
     */
    private fun query(p: JsonObject): JsonElement {
        val db = connectionOf(p)
        val params = (p["params"] as? JsonArray ?: JsonArray()).toList()
        val maxRows = p["maxRows"]?.asInt ?: 0
        val timeout = ((p["timeoutMs"]?.asInt ?: 0) + 999) / 1000
        var text = p.str("sql")
        if (db.kind == "sqlserver" && params.isNotEmpty()) text = sqlServerParams(text, params)
        if (db.kind == "postgresql" && params.isNotEmpty()) text = text.replace(Regex("\\$(\\d+)"), "?")
        if (db.kind == "oracle" && params.isNotEmpty()) text = text.replace(Regex(":(\\d+)\\b"), "?")
        synchronized(db.lock) {
            val statement: Statement = if (params.isEmpty()) db.connection.createStatement()
            else if (db.kind == "mariadb" && p["generatedKeys"]?.asBoolean == true) db.connection.prepareStatement(text, Statement.RETURN_GENERATED_KEYS)
            else db.connection.prepareStatement(text)
            statement.use {
                db.running = statement
                try {
                    // oracledb commits only when asked (autoCommit); the JDBC connection follows the request.
                    p["autoCommit"]?.takeIf { it.isJsonPrimitive }?.asBoolean?.let { if (db.connection.autoCommit != it) db.connection.autoCommit = it }
                    if (timeout > 0) statement.queryTimeout = timeout
                    if (statement is PreparedStatement) bind(db.kind, statement, if (db.kind == "sqlserver") sqlServerBindOrder(params) else params)
                    var hasResult = if (statement is PreparedStatement) statement.execute() else statement.execute(text, if (db.kind == "mariadb" && p["generatedKeys"]?.asBoolean == true) Statement.RETURN_GENERATED_KEYS else Statement.NO_GENERATED_KEYS)
                    val results = JsonArray()
                    var guard = 0
                    while (guard++ < 1000) {
                        if (hasResult) {
                            statement.resultSet.use { rs -> results.add(resultSet(db.kind, rs, maxRows)) }
                        } else {
                            val count = statement.updateCount
                            if (count == -1) break
                            results.add(JsonObject().apply {
                                addProperty("updateCount", count)
                                if (db.kind == "mariadb") runCatching { statement.generatedKeys.use { keys -> if (keys.next()) addProperty("insertId", keys.getLong(1)) } }
                            })
                        }
                        hasResult = statement.moreResults
                    }
                    return JsonObject().apply {
                        add("results", results)
                        warnings(statement)?.let { add("warnings", it) }
                    }
                } catch (e: SQLException) {
                    throw error(db.kind, e)
                } finally {
                    db.running = null
                }
            }
        }
    }

    /** PL/SQL with OUT binds (Oracle's DBMS_OUTPUT.GET_LINE): names in order of appearance. */
    private fun call(p: JsonObject): JsonElement {
        val db = connectionOf(p)
        val binds = p.getAsJsonObject("binds") ?: JsonObject()
        val names = mutableListOf<String>()
        val text = p.str("sql").replace(Regex(":([A-Za-z_][A-Za-z0-9_]*)")) { m -> names += m.groupValues[1]; "?" }
        synchronized(db.lock) {
            try {
                db.connection.prepareCall(text).use { call: CallableStatement ->
                    db.running = call
                    names.forEachIndexed { i, name ->
                        val bind = binds[name]?.takeIf { it.isJsonObject }?.asJsonObject
                        if (bind?.get("out")?.asBoolean == true) call.registerOutParameter(i + 1, if (bind.str("type") == "number") Types.NUMERIC else Types.VARCHAR)
                        else call.setObject(i + 1, bind?.get("value")?.let(::plain) ?: binds[name]?.let(::plain))
                    }
                    call.execute()
                    return JsonObject().apply {
                        add("outBinds", JsonObject().apply {
                            names.forEachIndexed { i, name ->
                                val bind = binds[name]?.takeIf { it.isJsonObject }?.asJsonObject
                                if (bind?.get("out")?.asBoolean == true) {
                                    val value = if (bind.str("type") == "number") call.getBigDecimal(i + 1)?.let { JsonPrimitive(it) } else call.getString(i + 1)?.let(::JsonPrimitive)
                                    add(name, value ?: JsonNull.INSTANCE)
                                }
                            }
                        })
                    }
                }
            } catch (e: SQLException) {
                throw error(db.kind, e)
            } finally {
                db.running = null
            }
        }
    }

    private fun warnings(statement: Statement): JsonArray? {
        var warning = runCatching { statement.warnings }.getOrNull() ?: return null
        val out = JsonArray()
        while (out.size() < 50) {
            out.add(warning.message ?: "")
            warning = warning.nextWarning ?: break
        }
        return out
    }

    /** sp_executesql with @p1..@pN typed from the values, as tedious infers parameter types. */
    private fun sqlServerParams(text: String, params: List<JsonElement>): String {
        val declared = params.indices.joinToString(", ") { i -> "@p${i + 1} ${sqlServerType(params[i])}" }
        return "EXEC sp_executesql N'${text.replace("'", "''")}', N'$declared'" + params.indices.joinToString("") { ", @p${it + 1} = ?" }
    }

    private fun sqlServerBindOrder(params: List<JsonElement>) = params

    private fun sqlServerType(value: JsonElement): String = when {
        value.isJsonNull -> "nvarchar(max)"
        value.isJsonPrimitive && value.asJsonPrimitive.isBoolean -> "bit"
        value.isJsonPrimitive && value.asJsonPrimitive.isNumber -> if (value.asString.contains('.') || value.asString.contains('e', true)) "float" else "bigint"
        value.isJsonObject && value.asJsonObject.has("\$bytes") -> "varbinary(max)"
        else -> "nvarchar(max)"
    }

    private fun bind(kind: String, statement: PreparedStatement, params: List<JsonElement>) {
        params.forEachIndexed { i, value ->
            val index = i + 1
            when {
                value.isJsonNull -> statement.setNull(index, Types.NULL)
                value.isJsonPrimitive && value.asJsonPrimitive.isBoolean -> statement.setBoolean(index, value.asBoolean)
                value.isJsonPrimitive && value.asJsonPrimitive.isNumber -> {
                    val text = value.asString
                    if (text.contains('.') || text.contains('e', true)) statement.setDouble(index, value.asDouble) else statement.setLong(index, value.asLong)
                }
                value.isJsonObject && value.asJsonObject.has("\$bytes") -> statement.setBytes(index, Base64.getDecoder().decode(value.asJsonObject["\$bytes"].asString))
                value.isJsonObject || value.isJsonArray -> statement.setString(index, value.toString())
                // Text binds as text; PostgreSQL then casts it to the column's type (pg sends text parameters too).
                else -> if (kind == "postgresql") statement.setObject(index, value.asString, Types.OTHER) else statement.setString(index, value.asString)
            }
        }
    }

    private fun plain(value: JsonElement): Any? = when {
        value.isJsonNull -> null
        value.isJsonPrimitive && value.asJsonPrimitive.isBoolean -> value.asBoolean
        value.isJsonPrimitive && value.asJsonPrimitive.isNumber -> value.asBigDecimal
        value.isJsonPrimitive -> value.asString
        else -> value.toString()
    }

    private fun resultSet(kind: String, rs: ResultSet, maxRows: Int): JsonObject {
        val meta = rs.metaData
        val count = meta.columnCount
        val columns = JsonArray()
        for (i in 1..count) {
            columns.add(JsonObject().apply {
                addProperty("name", meta.getColumnLabel(i))
                addProperty("type", meta.getColumnTypeName(i) ?: "")
                addProperty("jdbcType", meta.getColumnType(i))
                runCatching { meta.getTableName(i) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { addProperty("table", it) }
            })
        }
        val rows = JsonArray()
        var more = false
        while (rs.next()) {
            if (maxRows > 0 && rows.size() >= maxRows) {
                more = true
                break
            }
            val row = JsonArray()
            for (i in 1..count) row.add(value(kind, rs, i, meta.getColumnType(i), meta.getColumnTypeName(i)?.lowercase() ?: ""))
            rows.add(row)
        }
        return JsonObject().apply {
            add("columns", columns)
            add("rows", rows)
            if (more) addProperty("more", true)
        }
    }

    private fun tag(name: String, value: String) = JsonObject().apply { addProperty(name, value) }

    private val wallClock = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    /** One value, tagged for the driver stand-in. */
    private fun value(kind: String, rs: ResultSet, i: Int, type: Int, typeName: String): JsonElement {
        val raw: JsonElement = when {
            typeName == "json" || typeName == "jsonb" -> rs.getString(i)?.let { tag("\$json", it) }
            kind == "postgresql" && type in setOf(Types.BIT, Types.BOOLEAN) && typeName == "bool" -> rs.getBoolean(i).let { if (rs.wasNull()) null else JsonPrimitive(it) }
            kind == "postgresql" && typeName in setOf("int2", "int4", "oid", "float4", "float8") -> rs.getString(i)?.let { JsonPrimitive(it.toDouble().let { d -> if (d % 1.0 == 0.0 && typeName.startsWith("int")) d.toLong() as Number else d }) }
            kind == "postgresql" && typeName == "bytea" -> rs.getBytes(i)?.let { tag("\$bytes", Base64.getEncoder().encodeToString(it)) }
            // Everything else from PostgreSQL stays the server's text, so it round-trips exactly.
            kind == "postgresql" -> rs.getString(i)?.let(::JsonPrimitive)
            type == Types.BOOLEAN || (type == Types.BIT && kind != "mariadb") -> rs.getBoolean(i).let { if (rs.wasNull()) null else JsonPrimitive(it) }
            type in setOf(Types.TINYINT, Types.SMALLINT, Types.INTEGER) -> rs.getLong(i).let { if (rs.wasNull()) null else JsonPrimitive(it) }
            type == Types.BIGINT -> rs.getString(i)?.let { tag("\$big", it) }
            type in setOf(Types.REAL, Types.FLOAT, Types.DOUBLE) || typeName in setOf("binary_float", "binary_double") -> rs.getDouble(i).let { if (rs.wasNull()) null else if (it.isFinite()) JsonPrimitive(it) else JsonPrimitive(it.toString()) }
            type in setOf(Types.NUMERIC, Types.DECIMAL) -> rs.getBigDecimal(i)?.let { tag("\$num", it.toPlainString()) }
            type in setOf(Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB) || (kind == "mariadb" && type == Types.BIT) ->
                (if (type == Types.BLOB) rs.getBlob(i)?.let { blob -> blob.getBytes(1L, minOf(blob.length(), MAX_BYTES).toInt()) } else rs.getBytes(i))?.let { tag("\$bytes", Base64.getEncoder().encodeToString(it)) }
            type == Types.CLOB || type == Types.NCLOB -> rs.getString(i)?.let(::JsonPrimitive)
            kind == "sqlserver" && typeName == "datetimeoffset" -> rs.getObject(i, OffsetDateTime::class.java)?.let { tag("\$date", it.toInstant().toEpochMilli().toString()) }
            kind == "sqlserver" && type in setOf(Types.TIMESTAMP, Types.DATE, Types.TIME) -> rs.getObject(i, LocalDateTime::class.java)?.let { tag("\$date", it.toInstant(ZoneOffset.UTC).toEpochMilli().toString()) }
            kind == "oracle" && type == -101 -> rs.getObject(i, OffsetDateTime::class.java)?.let { tag("\$date", it.toInstant().toEpochMilli().toString()) }
            kind == "oracle" && (type == Types.TIMESTAMP || type == Types.DATE || type == -102) -> rs.getObject(i, LocalDateTime::class.java)?.let { tag("\$ts", it.format(wallClock)) }
            kind == "oracle" && (type == -103 || type == -104) -> rs.getString(i)?.let { tag("\$interval", it) }
            else -> rs.getString(i)?.let(::JsonPrimitive)
        } ?: JsonNull.INSTANCE
        return raw
    }

    /** The driver's error with the details its Node counterpart reports. */
    private fun error(kind: String, e: SQLException): DbException {
        val data = JsonObject()
        e.sqlState?.let { data.addProperty("sqlState", it) }
        data.addProperty("errno", e.errorCode)
        var message = e.message ?: "The database reported an error."
        when (kind) {
            "postgresql" -> (e as? org.postgresql.util.PSQLException)?.serverErrorMessage?.let { server ->
                message = server.message ?: message
                data.addProperty("code", server.sqlState ?: e.sqlState ?: "")
                if (server.position > 0) data.addProperty("position", server.position.toString())
                server.detail?.let { data.addProperty("detail", it) }
                server.hint?.let { data.addProperty("hint", it) }
            } ?: data.addProperty("code", e.sqlState ?: "")
            "mariadb" -> {
                message = message.replace(Regex("^\\(conn=\\d+\\)\\s*"), "")
                data.addProperty("sqlMessage", message)
                data.addProperty("code", e.sqlState ?: "")
            }
            "sqlserver" -> (e as? com.microsoft.sqlserver.jdbc.SQLServerException)?.sqlServerError?.let { server ->
                data.addProperty("lineNumber", server.lineNumber)
                data.addProperty("number", server.errorNumber)
            }
            "oracle" -> {
                var cause: Throwable? = e
                while (cause != null) {
                    if (cause is oracle.jdbc.OracleDatabaseException) {
                        data.addProperty("offset", cause.errorPosition)
                        break
                    }
                    cause = cause.cause
                }
                data.addProperty("errorNum", e.errorCode)
                message = message.trim()
            }
        }
        if (e.sqlState == "57014" || message.contains("cancel", ignoreCase = true)) data.addProperty("cancelled", true)
        return DbException(message, data)
    }

    // ------------------------------------------------------------- MongoDB

    private fun mongoConnect(p: JsonObject): JsonElement {
        val url = p.str("url")
        val connection = com.mongodb.ConnectionString(url)
        val timeout = (p["connectTimeoutMs"]?.asInt ?: 10_000).toLong()
        val settings = com.mongodb.MongoClientSettings.builder()
            .applyConnectionString(connection)
            .applyToClusterSettings { it.serverSelectionTimeout(timeout, java.util.concurrent.TimeUnit.MILLISECONDS) }
            .applyToSocketSettings { it.connectTimeout(timeout, java.util.concurrent.TimeUnit.MILLISECONDS).readTimeout((p["socketTimeoutMs"]?.asLong ?: 40_000L), java.util.concurrent.TimeUnit.MILLISECONDS) }
            .applyToConnectionPoolSettings { it.maxSize(4) }
            .applicationName("DevSnip Pro")
            .build()
        val client = try {
            com.mongodb.client.MongoClients.create(settings)
        } catch (e: Exception) {
            throw DbException(e.message ?: "MongoDB could not be reached.", JsonObject())
        }
        val id = nextId.getAndIncrement()
        mongo[id] = client
        return JsonObject().apply {
            addProperty("id", id)
            connection.database?.let { addProperty("dbName", it) }
        }
    }

    // --------------------------------------------------------------- Redis

    private fun redisConnect(p: JsonObject): JsonElement {
        val config = redis.clients.jedis.DefaultJedisClientConfig.builder()
            .connectionTimeoutMillis(p["connectTimeoutMs"]?.asInt ?: 10_000)
            .socketTimeoutMillis(p["commandTimeoutMs"]?.asInt ?: 30_000)
            .database(p["db"]?.asInt ?: 0)
            .clientName("devsnip-pro")
            .apply {
                p.str("user").takeIf { it.isNotEmpty() }?.let(::user)
                p.str("password").takeIf { it.isNotEmpty() }?.let(::password)
                if (p["tls"]?.asBoolean == true) {
                    ssl(true)
                    if (p["rejectUnauthorized"]?.asBoolean == false) sslSocketFactory(TrustAll.socketFactory).hostnameVerifier { _, _ -> true }
                }
            }
            .build()
        val jedis = try {
            redis.clients.jedis.Jedis(redis.clients.jedis.HostAndPort(p.str("host").ifEmpty { "localhost" }, p["port"]?.asInt ?: 6379), config).also { it.connect(); it.ping() }
        } catch (e: Exception) {
            throw DbException(e.message ?: "Redis could not be reached.", JsonObject())
        }
        val id = nextId.getAndIncrement()
        redisClients[id] = jedis
        return JsonObject().apply { addProperty("id", id) }
    }

    /** Commands one by one, or as MULTI / EXEC: [[error, reply], ...] as ioredis's pipeline and multi give them. */
    private fun redisExec(p: JsonObject): JsonElement {
        val jedis = redisClients[p["id"].asInt] ?: throw DbException("The Redis connection is closed.", JsonObject())
        val commands = (p["commands"] as? JsonArray ?: JsonArray()).map { c -> c.asJsonArray.map { it.asString } }
        val transaction = p["transaction"]?.asBoolean == true
        val out = JsonArray()
        synchronized(jedis) {
            if (transaction) {
                val queued = mutableListOf<String?>()
                send(jedis, listOf("MULTI"))
                for (command in commands) queued += runCatching { send(jedis, command); null }.getOrElse { it.message ?: "ERR" }
                val replies = try {
                    send(jedis, listOf("EXEC")) as? List<*>
                } catch (e: Exception) {
                    throw DbException(e.message ?: "EXEC failed", JsonObject())
                }
                commands.indices.forEach { i ->
                    val reply = replies?.getOrNull(i)
                    out.add(JsonArray().apply {
                        val failure = queued[i] ?: (reply as? Exception)?.message
                        if (failure != null) { add(failure); add(JsonNull.INSTANCE) } else { add(JsonNull.INSTANCE); add(reply(reply)) }
                    })
                }
            } else {
                for (command in commands) {
                    out.add(JsonArray().apply {
                        try {
                            val reply = send(jedis, command)
                            add(JsonNull.INSTANCE)
                            add(reply(reply))
                        } catch (e: Exception) {
                            if (e is redis.clients.jedis.exceptions.JedisConnectionException) throw DbException(e.message ?: "The Redis connection was lost.", JsonObject())
                            add(e.message ?: "ERR")
                            add(JsonNull.INSTANCE)
                        }
                    })
                }
            }
        }
        return out
    }

    private fun send(jedis: redis.clients.jedis.Jedis, command: List<String>): Any? {
        val name = command.first()
        val protocol = redis.clients.jedis.commands.ProtocolCommand { name.toByteArray(StandardCharsets.UTF_8) }
        return jedis.sendCommand(protocol, *command.drop(1).map { it.toByteArray(StandardCharsets.UTF_8) }.toTypedArray())
    }

    /** RESP replies as ioredis returns them: bulk and status strings as text, integers as numbers, arrays as arrays. */
    private fun reply(value: Any?): JsonElement = when (value) {
        null -> JsonNull.INSTANCE
        is ByteArray -> JsonPrimitive(String(value, StandardCharsets.UTF_8))
        is Long, is Int -> JsonPrimitive(value as Number)
        is Double -> JsonPrimitive(value.toString())
        is String -> JsonPrimitive(value)
        is List<*> -> JsonArray().apply { value.forEach { add(reply(it)) } }
        is Map<*, *> -> JsonArray().apply { value.forEach { (k, v) -> add(reply(k)); add(reply(v)) } }
        is Exception -> JsonPrimitive(value.message ?: "ERR")
        else -> JsonPrimitive(value.toString())
    }

    fun closeAll() {
        sql.values.forEach { runCatching { it.running?.cancel(); it.connection.close() } }
        sql.clear()
        mongo.values.forEach { runCatching { it.close() } }
        mongo.clear()
        redisClients.values.forEach { runCatching { it.close() } }
        redisClients.clear()
        synchronized(this) { tempFiles.forEach { runCatching { Files.deleteIfExists(it) } }; tempFiles.clear() }
    }

    /** For Redis with skip_verify: TLS without checking the server's certificate (as the extension's option says). */
    private object TrustAll {
        val socketFactory: javax.net.ssl.SSLSocketFactory by lazy {
            val manager = object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) = Unit
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
            }
            javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), java.security.SecureRandom()) }.socketFactory
        }
    }

    companion object {
        private const val MAX_BYTES = 16L * 1024 * 1024
        private fun JsonObject.str(key: String): String = get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: ""
    }
}
