package pro.devsnip.api.core.toolkit

/**
 * Tool metadata exported from the VS Code extension (scripts/export-backend-api.js):
 * titles, fields, defaults and examples are the extension's own, not copies typed by hand.
 */
object ToolCatalog {
    @Suppress("UNCHECKED_CAST")
    fun load(resource: String): List<ToolDescriptor> {
        val text = ToolCatalog::class.java.getResourceAsStream(resource)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Missing toolkit resource $resource")
        val list = Js.parseJson(text) as List<Map<String, Any?>>
        return list.map { t ->
            ToolDescriptor(
                id = t["id"] as String,
                command = t["command"] as String,
                title = t["title"] as String,
                summary = t["summary"] as String,
                guide = t["guide"] as? String,
                keywords = (t["keywords"] as? List<String>).orEmpty(),
                icon = t["icon"] as? String,
                live = t["live"] == true,
                network = t["network"] == true,
                runLabel = t["runLabel"] as? String,
                fields = (t["fields"] as? List<Map<String, Any?>>).orEmpty().map(::field),
                examples = (t["examples"] as? List<Map<String, Any?>>).orEmpty().map { ToolExample(it["label"] as String, (it["values"] as Map<String, Any>)) },
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun field(f: Map<String, Any?>): FieldSpec = FieldSpec(
        id = f["id"] as String,
        label = f["label"] as String,
        kind = FieldKind.valueOf((f["kind"] as String).uppercase()),
        default = f["default"],
        placeholder = f["placeholder"] as? String,
        help = f["help"] as? String,
        options = (f["options"] as? List<Map<String, Any?>>).orEmpty().map { FieldOption(it["value"] as String, it["label"] as String) },
        rows = (f["rows"] as? Double)?.toInt(),
        min = f["min"] as? Double,
        max = f["max"] as? Double,
        step = f["step"] as? Double,
        language = f["language"] as? String,
        required = f["required"] == true,
        showIf = (f["showIf"] as? Map<String, Any?>)?.let { ShowIf(it["field"] as String, it["equals"] as List<Any>) },
        fromEditor = f["fromEditor"] == true,
        group = f["group"] as? String,
        width = f["width"] as? String,
    )
}
