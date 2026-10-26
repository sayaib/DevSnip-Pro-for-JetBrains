package pro.devsnip.api.ide.engine

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.mongodb.MongoException
import com.mongodb.MongoNamespace
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.BulkWriteOptions
import com.mongodb.client.model.CountOptions
import com.mongodb.client.model.DeleteManyModel
import com.mongodb.client.model.DeleteOneModel
import com.mongodb.client.model.EstimatedDocumentCountOptions
import com.mongodb.client.model.FindOneAndDeleteOptions
import com.mongodb.client.model.FindOneAndReplaceOptions
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.InsertOneModel
import com.mongodb.client.model.ReplaceOneModel
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.UpdateManyModel
import com.mongodb.client.model.UpdateOneModel
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.WriteModel
import org.bson.BsonArray
import org.bson.BsonBoolean
import org.bson.BsonDocument
import org.bson.BsonInt64
import org.bson.BsonString
import org.bson.BsonValue
import org.bson.json.JsonMode
import org.bson.json.JsonWriterSettings
import java.util.concurrent.TimeUnit

/**
 * The MongoDB operations the Database Client uses (the Node driver's
 * collection and database methods), on the MongoDB Java driver. Arguments
 * and results cross as canonical Extended JSON, so ObjectIds, dates, decimals
 * and binary data arrive exactly as the extension's BSON code expects.
 */
object MongoOps {
    private val JSON = JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).build()

    fun run(client: MongoClient, p: JsonObject): JsonElement {
        val args = BsonDocument.parse("{\"a\": ${p["args"]?.asString ?: "[]"}}").getArray("a")
        val options = p["options"]?.takeIf { it.isJsonPrimitive }?.asString?.let { BsonDocument.parse(it) } ?: BsonDocument()
        val database = client.getDatabase(p["db"]?.takeIf { it.isJsonPrimitive }?.asString ?: "test")
        val cap = p["cap"]?.asInt ?: 100_000
        fun arg(i: Int): BsonValue? = if (i < args.size) args[i] else null
        fun doc(i: Int): BsonDocument = arg(i)?.takeIf { it.isDocument }?.asDocument() ?: BsonDocument()
        fun collection(): MongoCollection<BsonDocument> = database.getCollection(p["collection"].asString, BsonDocument::class.java)
        val maxTime = options["maxTimeMS"]?.takeIf { it.isNumber }?.asNumber()?.longValue()
        val result: BsonValue = try {
            when (p["op"].asString) {
                "command" -> database.runCommand(doc(0), BsonDocument::class.java)
                "listDatabases" -> client.getDatabase("admin").runCommand(BsonDocument("listDatabases", BsonInt64(1)).append("nameOnly", BsonBoolean(options.getBoolean("nameOnly", BsonBoolean.TRUE).value)).append("authorizedDatabases", BsonBoolean.TRUE), BsonDocument::class.java)
                "listCollections" -> BsonArray(database.listCollections(BsonDocument::class.java).filter(doc(0)).into(mutableListOf()))
                "find" -> {
                    val find = collection().find(doc(0))
                    options["projection"]?.takeIf { it.isDocument }?.let { find.projection(it.asDocument()) }
                    options["sort"]?.takeIf { it.isDocument }?.let { find.sort(it.asDocument()) }
                    options["skip"]?.takeIf { it.isNumber }?.let { find.skip(it.asNumber().intValue()) }
                    val limit = options["limit"]?.takeIf { it.isNumber }?.asNumber()?.intValue()?.takeIf { it > 0 }
                    find.limit(if (limit != null) minOf(limit, cap) else cap)
                    maxTime?.let { find.maxTime(it, TimeUnit.MILLISECONDS) }
                    BsonArray(find.into(mutableListOf()))
                }
                "findOne" -> {
                    val find = collection().find(doc(0)).limit(1)
                    options["projection"]?.takeIf { it.isDocument }?.let { find.projection(it.asDocument()) }
                    maxTime?.let { find.maxTime(it, TimeUnit.MILLISECONDS) }
                    find.first() ?: org.bson.BsonNull.VALUE
                }
                "aggregate" -> {
                    val pipeline = arg(0)?.takeIf { it.isArray }?.asArray()?.map { it.asDocument() }.orEmpty()
                    val aggregate = collection().aggregate(pipeline)
                    options["allowDiskUse"]?.takeIf { it.isBoolean }?.let { aggregate.allowDiskUse(it.asBoolean().value) }
                    maxTime?.let { aggregate.maxTime(it, TimeUnit.MILLISECONDS) }
                    BsonArray(aggregate.iterator().use { cursor -> buildList { while (size < cap && cursor.hasNext()) add(cursor.next()) } })
                }
                "countDocuments" -> BsonInt64(collection().countDocuments(doc(0), CountOptions().apply { maxTime?.let { maxTime(it, TimeUnit.MILLISECONDS) } }))
                "estimatedDocumentCount" -> BsonInt64(collection().estimatedDocumentCount(EstimatedDocumentCountOptions().apply { maxTime?.let { maxTime(it, TimeUnit.MILLISECONDS) } }))
                "distinct" -> BsonArray(collection().distinct(arg(0)?.asString()?.value ?: "_id", doc(1), BsonValue::class.java).into(mutableListOf()))
                "insertOne" -> {
                    val document = doc(0)
                    val r = collection().insertOne(document)
                    BsonDocument("acknowledged", BsonBoolean(r.wasAcknowledged())).append("insertedId", r.insertedId ?: document["_id"] ?: org.bson.BsonNull.VALUE)
                }
                "insertMany" -> {
                    val documents = arg(0)?.asArray()?.map { it.asDocument() }.orEmpty()
                    val r = collection().insertMany(documents)
                    BsonDocument("insertedCount", BsonInt64(r.insertedIds.size.toLong())).append("insertedIds", BsonDocument().apply { r.insertedIds.forEach { (k, v) -> append(k.toString(), v) } })
                }
                "updateOne", "updateMany" -> {
                    val opts = UpdateOptions().upsert(doc(2).getBoolean("upsert", BsonBoolean.FALSE).value)
                    doc(2)["arrayFilters"]?.takeIf { it.isArray }?.let { opts.arrayFilters(it.asArray().map { f -> f.asDocument() }) }
                    val update = arg(1)
                    val r = if (update?.isArray == true) {
                        val pipeline = update.asArray().map { it.asDocument() }
                        if (p["op"].asString == "updateOne") collection().updateOne(doc(0), pipeline, opts) else collection().updateMany(doc(0), pipeline, opts)
                    } else {
                        if (p["op"].asString == "updateOne") collection().updateOne(doc(0), doc(1), opts) else collection().updateMany(doc(0), doc(1), opts)
                    }
                    updateResult(r.matchedCount, r.modifiedCount, r.upsertedId)
                }
                "replaceOne" -> {
                    val r = collection().replaceOne(doc(0), doc(1), ReplaceOptions().upsert(doc(2).getBoolean("upsert", BsonBoolean.FALSE).value))
                    updateResult(r.matchedCount, r.modifiedCount, r.upsertedId)
                }
                "deleteOne" -> BsonDocument("deletedCount", BsonInt64(collection().deleteOne(doc(0)).deletedCount))
                "deleteMany" -> BsonDocument("deletedCount", BsonInt64(collection().deleteMany(doc(0)).deletedCount))
                "findOneAndUpdate" -> {
                    val o = doc(2)
                    val opts = FindOneAndUpdateOptions().upsert(o.getBoolean("upsert", BsonBoolean.FALSE).value)
                        .returnDocument(if (o.getString("returnDocument", BsonString("before")).value == "after") ReturnDocument.AFTER else ReturnDocument.BEFORE)
                    o["projection"]?.takeIf { it.isDocument }?.let { opts.projection(it.asDocument()) }
                    o["sort"]?.takeIf { it.isDocument }?.let { opts.sort(it.asDocument()) }
                    val update = arg(1)
                    (if (update?.isArray == true) collection().findOneAndUpdate(doc(0), update.asArray().map { it.asDocument() }, opts) else collection().findOneAndUpdate(doc(0), doc(1), opts)) ?: org.bson.BsonNull.VALUE
                }
                "findOneAndReplace" -> {
                    val o = doc(2)
                    val opts = FindOneAndReplaceOptions().upsert(o.getBoolean("upsert", BsonBoolean.FALSE).value)
                        .returnDocument(if (o.getString("returnDocument", BsonString("before")).value == "after") ReturnDocument.AFTER else ReturnDocument.BEFORE)
                    collection().findOneAndReplace(doc(0), doc(1), opts) ?: org.bson.BsonNull.VALUE
                }
                "findOneAndDelete" -> {
                    val o = doc(1)
                    val opts = FindOneAndDeleteOptions()
                    o["sort"]?.takeIf { it.isDocument }?.let { opts.sort(it.asDocument()) }
                    o["projection"]?.takeIf { it.isDocument }?.let { opts.projection(it.asDocument()) }
                    collection().findOneAndDelete(doc(0), opts) ?: org.bson.BsonNull.VALUE
                }
                "bulkWrite" -> {
                    val models = arg(0)?.asArray()?.map { model(it.asDocument()) }.orEmpty()
                    val r = collection().bulkWrite(models, BulkWriteOptions().ordered(doc(1).getBoolean("ordered", BsonBoolean.TRUE).value))
                    BsonDocument("insertedCount", BsonInt64(r.insertedCount.toLong())).append("matchedCount", BsonInt64(r.matchedCount.toLong()))
                        .append("modifiedCount", BsonInt64(r.modifiedCount.toLong())).append("deletedCount", BsonInt64(r.deletedCount.toLong())).append("upsertedCount", BsonInt64(r.upserts.size.toLong()))
                }
                "createIndex" -> {
                    val o = doc(1)
                    val opts = IndexOptions()
                    o["name"]?.takeIf { it.isString }?.let { opts.name(it.asString().value) }
                    o["unique"]?.takeIf { it.isBoolean }?.let { opts.unique(it.asBoolean().value) }
                    o["sparse"]?.takeIf { it.isBoolean }?.let { opts.sparse(it.asBoolean().value) }
                    o["expireAfterSeconds"]?.takeIf { it.isNumber }?.let { opts.expireAfter(it.asNumber().longValue(), TimeUnit.SECONDS) }
                    o["partialFilterExpression"]?.takeIf { it.isDocument }?.let { opts.partialFilterExpression(it.asDocument()) }
                    BsonString(collection().createIndex(doc(0), opts))
                }
                "dropIndex" -> {
                    val index = arg(0)
                    if (index?.isString == true) collection().dropIndex(index.asString().value) else collection().dropIndex(doc(0))
                    org.bson.BsonNull.VALUE
                }
                "dropIndexes" -> { collection().dropIndexes(); org.bson.BsonNull.VALUE }
                "indexes" -> BsonArray(collection().listIndexes(BsonDocument::class.java).into(mutableListOf()))
                "drop" -> { collection().drop(); BsonBoolean.TRUE }
                "rename" -> { collection().renameCollection(MongoNamespace(database.name, arg(0)?.asString()?.value ?: error("rename needs a name"))); org.bson.BsonNull.VALUE }
                "dropDatabase" -> { database.drop(); BsonBoolean.TRUE }
                "createCollection" -> { database.createCollection(arg(0)?.asString()?.value ?: error("createCollection needs a name")); org.bson.BsonNull.VALUE }
                "stats" -> database.runCommand(BsonDocument("dbStats", BsonInt64(1)), BsonDocument::class.java)
                else -> throw DbBridge.DbException("${p["op"].asString}() is not supported here.", JsonObject())
            }
        } catch (e: MongoException) {
            throw DbBridge.DbException(e.message ?: "MongoDB reported an error.", JsonObject().apply { addProperty("code", e.code); addProperty("codeName", e.javaClass.simpleName) })
        }
        return JsonPrimitive(BsonDocument("r", result).toJson(JSON))
    }

    private fun updateResult(matched: Long, modified: Long, upserted: BsonValue?) =
        BsonDocument("matchedCount", BsonInt64(matched)).append("modifiedCount", BsonInt64(modified))
            .append("upsertedCount", BsonInt64(if (upserted != null) 1 else 0)).append("upsertedId", upserted ?: org.bson.BsonNull.VALUE)

    private fun model(op: BsonDocument): WriteModel<BsonDocument> {
        val (name, raw) = op.entries.first()
        val body = raw.asDocument()
        val upsert = body.getBoolean("upsert", BsonBoolean.FALSE).value
        return when (name) {
            "insertOne" -> InsertOneModel(body.getDocument("document"))
            "updateOne" -> UpdateOneModel(body.getDocument("filter"), body.getDocument("update"), UpdateOptions().upsert(upsert))
            "updateMany" -> UpdateManyModel(body.getDocument("filter"), body.getDocument("update"), UpdateOptions().upsert(upsert))
            "replaceOne" -> ReplaceOneModel(body.getDocument("filter"), body.getDocument("replacement"), ReplaceOptions().upsert(upsert))
            "deleteOne" -> DeleteOneModel(body.getDocument("filter"))
            "deleteMany" -> DeleteManyModel(body.getDocument("filter"))
            else -> throw DbBridge.DbException("bulkWrite does not know $name.", JsonObject())
        }
    }
}
