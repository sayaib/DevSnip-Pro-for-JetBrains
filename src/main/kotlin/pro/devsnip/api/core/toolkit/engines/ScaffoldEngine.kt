package pro.devsnip.api.core.toolkit.engines

import pro.devsnip.api.core.toolkit.GeneratedFile
import pro.devsnip.api.core.toolkit.Js
import pro.devsnip.api.core.toolkit.Message
import pro.devsnip.api.core.toolkit.MessageKind
import pro.devsnip.api.core.toolkit.Output
import pro.devsnip.api.core.toolkit.Stat
import pro.devsnip.api.core.toolkit.ToolInputError
import pro.devsnip.api.core.toolkit.ToolResult
import pro.devsnip.api.core.toolkit.Values
import pro.devsnip.api.core.toolkit.required
import pro.devsnip.api.core.toolkit.str

/**
 * API Resource Scaffolder: a validated CRUD API (paginated list, get, create,
 * update, delete) for Express, NestJS, Next.js, Fastify, Spring Boot,
 * ASP.NET Core and FastAPI. Port of `src/toolkits/engines/web-scaffold.ts`
 * (resource part) and `web-scaffold-backend.ts`; the templates are the
 * extension's own, converted mechanically.
 */
object ScaffoldEngine {
    private const val D = "$"
    private const val TQ = "\"\"\""

    data class Field(val name: String, val type: String, val required: Boolean, val unique: Boolean, val values: List<String>)
    data class Scaffold(val files: List<GeneratedFile>, val steps: List<String>)

    /** The tool's `run`, from sections/web.ts. */
    fun run(values: Values): ToolResult {
        val fields = parseFields(required(values, "fields", "Fields"))
        val resource = required(values, "resource", "Resource")
        val framework = str(values, "framework", "express")
        val sqlDb = str(values, "sqlDb", "postgres")
        val r = when (framework) {
            "spring" -> {
                val pkg = str(values, "packageName", "com.example.demo").trim()
                if (!pkg.matches(Regex("^[a-z_][\\w]*(\\.[a-z_][\\w]*)*$"))) throw ToolInputError("The base package must look like com.example.demo (lower-case segments separated by dots).")
                springResource(resource, fields, pkg, sqlDb)
            }
            "aspnet" -> {
                val ns = str(values, "namespace", "MyApi").trim()
                if (!ns.matches(Regex("^[A-Za-z_]\\w*(\\.[A-Za-z_]\\w*)*$"))) throw ToolInputError("The root namespace must look like MyApi or Shop.Api.")
                aspnetResource(resource, fields, ns, sqlDb)
            }
            "fastapi" -> fastapiResource(resource, fields, sqlDb)
            else -> scaffoldResource(resource, fields, framework, str(values, "db", "prisma"))
        }
        return ToolResult(
            stats = listOf(Stat("Fields", fields.size.toString()), Stat("Files", r.files.size.toString())),
            messages = r.steps.map { Message(MessageKind.INFO, it) },
            outputs = listOf(Output.Files("Files", r.files)),
        )
    }

    // ---------------------------------------------------------------------------
    // Names and fields
    // ---------------------------------------------------------------------------

    fun words(name: String): List<String> =
        name.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }.map { it.lowercase() }

    fun pascalOf(name: String) = words(name).joinToString("") { it[0].uppercase() + it.substring(1) }.ifEmpty { "Item" }
    fun camelOf(name: String) = pascalOf(name).let { it[0].lowercase() + it.substring(1) }
    fun kebabOf(name: String) = words(name).joinToString("-").ifEmpty { "item" }
    fun pluralOf(word: String): String = when {
        Regex("[^aeiou]y$", RegexOption.IGNORE_CASE).containsMatchIn(word) -> word.dropLast(1) + "ies"
        Regex("(s|x|z|ch|sh)$", RegexOption.IGNORE_CASE).containsMatchIn(word) -> word + "es"
        else -> word + "s"
    }

    private val TYPE_ALIASES = mapOf(
        "str" to "string", "string" to "string", "text" to "text", "int" to "int", "integer" to "int", "number" to "number", "float" to "number",
        "decimal" to "number", "double" to "number", "bool" to "boolean", "boolean" to "boolean", "date" to "date", "datetime" to "date",
        "timestamp" to "date", "email" to "email", "url" to "url", "uri" to "url", "uuid" to "uuid", "json" to "json", "object" to "json",
        "string[]" to "string[]", "number[]" to "number[]", "int[]" to "number[]", "tags" to "string[]",
    )

    /** Lines like "price: number!", "status: enum(draft|published)", "email: email! unique", "tags: string[]". */
    fun parseFields(text: String): List<Field> {
        val fields = mutableListOf<Field>()
        val pattern = Regex("^([A-Za-z_][\\w]*)\\s*[: ]\\s*([A-Za-z\\[\\]]+)(\\(([^)]*)\\))?\\s*(!|\\?)?\\s*(unique)?$", RegexOption.IGNORE_CASE)
        for (raw in text.split(Regex("[\\n,]+")).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }) {
            val m = pattern.find(raw) ?: throw ToolInputError("Cannot read the field \"$raw\". Use name: type, e.g. \"price: number!\" or \"status: enum(draft|published)\".")
            val g = m.groups
            val typeKey = g[2]!!.value.lowercase()
            val type = if (typeKey == "enum") "enum" else TYPE_ALIASES[typeKey]
                ?: throw ToolInputError("Unknown type \"${g[2]!!.value}\" for ${g[1]!!.value}. Use string, text, int, number, boolean, date, email, url, uuid, enum(a|b), string[], number[] or json.")
            val values = if (type == "enum") (g[4]?.value ?: "").split(Regex("[|/]")).map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
            if (type == "enum" && values.isEmpty()) throw ToolInputError("${g[1]!!.value}: list the enum values, e.g. enum(draft|published).")
            if (g[1]!!.value in listOf("id", "createdAt", "updatedAt", "_id")) continue
            fields += Field(g[1]!!.value, type, g[5]?.value == "!", g[6] != null, values)
        }
        if (fields.isEmpty()) throw ToolInputError("Add at least one field, e.g. \"name: string!\".")
        return fields
    }

    private fun quoted(values: List<String>) = values.joinToString(", ") { Js.quote(it) }

    private fun tsType(f: Field) = when (f.type) {
        "string", "text", "email", "url", "uuid" -> "string"
        "int", "number" -> "number"
        "boolean" -> "boolean"
        "date" -> "Date"
        "enum" -> f.values.joinToString(" | ") { Js.quote(it) }
        "string[]" -> "string[]"
        "number[]" -> "number[]"
        else -> "Record<string, unknown>"
    }

    private fun zodOf(f: Field): String {
        val base = when (f.type) {
            "string" -> "z.string().trim().min(1).max(255)"
            "text" -> "z.string().max(10000)"
            "int" -> "z.number().int()"
            "number" -> "z.number()"
            "boolean" -> "z.boolean()"
            "date" -> "z.coerce.date()"
            "email" -> "z.string().email()"
            "url" -> "z.string().url()"
            "uuid" -> "z.string().uuid()"
            "enum" -> "z.enum([${quoted(f.values)}])"
            "string[]" -> "z.array(z.string())"
            "number[]" -> "z.array(z.number())"
            else -> "z.record(z.string(), z.unknown())"
        }
        return if (f.required) base else "$base.optional()"
    }

    private fun prismaOf(f: Field): String {
        val t = when (f.type) {
            "int" -> "Int"; "number" -> "Float"; "boolean" -> "Boolean"; "date" -> "DateTime"; "string[]" -> "String[]"; "number[]" -> "Float[]"; "json" -> "Json"
            else -> "String"
        }
        val list = t.endsWith("[]")
        return "  ${f.name.padEnd(12)} $t${if (f.required || list) "" else "?"}${if (f.unique) " @unique" else ""}${if (f.type == "text") " @db.Text" else ""}${if (f.type == "enum") " // ${f.values.joinToString(" | ")}" else ""}"
    }

    private fun mongooseOf(f: Field): String {
        val t = when (f.type) {
            "int", "number" -> "Number"; "boolean" -> "Boolean"; "date" -> "Date"; "string[]" -> "[String]"; "number[]" -> "[Number]"; "json" -> "Schema.Types.Mixed"
            else -> "String"
        }
        val opts = mutableListOf("type: $t")
        if (f.required) opts += "required: true"
        if (f.unique) opts += "unique: true"
        if (f.type == "enum") opts += "enum: ${Js.stringify(f.values)}"
        if (f.type == "email") opts += listOf("lowercase: true", "trim: true")
        if (f.type in listOf("string", "text")) opts += "trim: true"
        return "    ${f.name}: { ${opts.joinToString(", ")} },"
    }

    private fun validatorOf(f: Field): Pair<List<String>, List<String>> {
        val d = mutableListOf<String>()
        if (!f.required) d += "@IsOptional()"
        when (f.type) {
            "string" -> d += listOf("@IsString()", "@MaxLength(255)")
            "text" -> d += "@IsString()"
            "int" -> d += "@IsInt()"
            "number" -> d += "@IsNumber()"
            "boolean" -> d += "@IsBoolean()"
            "date" -> d += "@IsDateString()"
            "email" -> d += "@IsEmail()"
            "url" -> d += "@IsUrl()"
            "uuid" -> d += "@IsUUID()"
            "enum" -> d += "@IsIn(${Js.stringify(f.values)})"
            "string[]" -> d += listOf("@IsArray()", "@IsString({ each: true })")
            "number[]" -> d += listOf("@IsArray()", "@IsNumber({}, { each: true })")
            "json" -> d += "@IsObject()"
        }
        return d to d.map { Regex("^@(\\w+)").find(it)!!.groupValues[1] }
    }

    private fun jsonSchemaOf(f: Field): String = Js.stringify(
        when (f.type) {
            "string" -> linkedMapOf("type" to "string", "minLength" to 1, "maxLength" to 255)
            "text" -> linkedMapOf("type" to "string")
            "int" -> linkedMapOf("type" to "integer")
            "number" -> linkedMapOf("type" to "number")
            "boolean" -> linkedMapOf("type" to "boolean")
            "date" -> linkedMapOf("type" to "string", "format" to "date-time")
            "email" -> linkedMapOf("type" to "string", "format" to "email")
            "url" -> linkedMapOf("type" to "string", "format" to "uri")
            "uuid" -> linkedMapOf("type" to "string", "format" to "uuid")
            "enum" -> linkedMapOf("type" to "string", "enum" to f.values)
            "string[]" -> linkedMapOf("type" to "array", "items" to mapOf("type" to "string"))
            "number[]" -> linkedMapOf("type" to "array", "items" to mapOf("type" to "number"))
            else -> linkedMapOf("type" to "object")
        },
    )

    // ---------------------------------------------------------------------------
    // Node: Express, NestJS, Next.js, Fastify
    // ---------------------------------------------------------------------------

    fun scaffoldResource(resource: String, fields: List<Field>, framework: String, db: String): Scaffold {
        if (!resource.trim().matches(Regex("^[A-Za-z][A-Za-z0-9 _-]*$"))) throw ToolInputError("The resource name should be a word like product or blogPost.")
        val R = pascalOf(resource)
        val r = camelOf(resource)
        val plural = pluralOf(kebabOf(resource))
        val rs = camelOf(pluralOf(r))
        if (framework == "nestjs") return nest(R, r, rs, plural, fields, db)
        val dir = if (framework == "next") "lib" else "src/$plural"
        val files = mutableListOf<GeneratedFile>()
        val steps = mutableListOf<String>()
        val idType = "string"
        val zodFields = fields.joinToString("\n") { f -> "  ${f.name}: ${zodOf(f)}," }
        val tsFields = fields.joinToString("\n") { f -> "  ${f.name}${if (f.required) "" else "?"}: ${tsType(f)};" }
        files += GeneratedFile(
            "$dir/${kebabOf(resource)}.schema.ts",
            """import { z } from "zod";

export const create${R}Schema = z.object({
$zodFields
});

export const update${R}Schema = create${R}Schema.partial();

export const list${R}QuerySchema = z.object({
  page: z.coerce.number().int().min(1).default(1),
  limit: z.coerce.number().int().min(1).max(100).default(20),
});

export type Create${R}Input = z.infer<typeof create${R}Schema>;
export type Update${R}Input = z.infer<typeof update${R}Schema>;

export interface $R {
  id: $idType;
$tsFields
  createdAt: Date;
  updatedAt: Date;
}
""",
            "typescript",
        )
        files += repository(R, r, rs, dir, fields, db, resource)
        steps += "npm install zod${when (db) { "prisma" -> " @prisma/client && npm install -D prisma"; "mongoose" -> " mongoose"; else -> "" }}"
        if (db == "prisma") steps += "Add the model to prisma/schema.prisma, then: npx prisma migrate dev --name add_$r"
        if (db == "mongoose") steps += "Connect once at startup: await mongoose.connect(process.env.MONGODB_URI!)"
        val repo = "./${kebabOf(resource)}.repository"
        val schema = "./${kebabOf(resource)}.schema"
        val Rs = pascalOf(rs)
        if (framework == "express") {
            steps.add(0, "npm install express && npm install -D @types/express")
            steps += "Mount the router: app.use(express.json()); app.use(\"/api/$plural\", ${rs}Router);"
            files += GeneratedFile(
                "$dir/${kebabOf(resource)}.routes.ts",
                """import { NextFunction, Request, Response, Router } from "express";
import { ZodError } from "zod";
import { create${R}Schema, list${R}QuerySchema, update${R}Schema } from "$schema";
import * as repo from "$repo";

export const ${rs}Router = Router();

// Express 4 does not catch rejected promises; this forwards them to the error handler.
const handle = (fn: (req: Request, res: Response) => Promise<unknown>) =>
  (req: Request, res: Response, next: NextFunction) => fn(req, res).catch(next);

${rs}Router.get("/", handle(async (req, res) => {
  const { page, limit } = list${R}QuerySchema.parse(req.query);
  const [items, total] = await Promise.all([repo.list$Rs((page - 1) * limit, limit), repo.count$Rs()]);
  res.json({ items, page, limit, total, pages: Math.ceil(total / limit) });
}));

${rs}Router.get("/:id", handle(async (req, res) => {
  const item = await repo.get$R(req.params.id);
  if (!item) return res.status(404).json({ error: "$R not found" });
  res.json(item);
}));

${rs}Router.post("/", handle(async (req, res) => {
  const input = create${R}Schema.parse(req.body);
  res.status(201).json(await repo.create$R(input));
}));

${rs}Router.patch("/:id", handle(async (req, res) => {
  const input = update${R}Schema.parse(req.body);
  const item = await repo.update$R(req.params.id, input);
  if (!item) return res.status(404).json({ error: "$R not found" });
  res.json(item);
}));

${rs}Router.delete("/:id", handle(async (req, res) => {
  const deleted = await repo.delete$R(req.params.id);
  if (!deleted) return res.status(404).json({ error: "$R not found" });
  res.status(204).end();
}));

// Validation errors become 400s; everything else is a 500 without internals.
${rs}Router.use((err: unknown, _req: Request, res: Response, next: NextFunction) => {
  if (err instanceof ZodError) return res.status(400).json({ error: "Validation failed", issues: err.issues });
  next(err);
});
""",
                "typescript",
            )
        } else if (framework == "fastify") {
            steps.add(0, "npm install fastify")
            steps += "Register the plugin: app.register(${rs}Routes, { prefix: \"/api/$plural\" });"
            val props = fields.joinToString("\n") { f -> "    ${f.name}: ${jsonSchemaOf(f)}," }
            val requiredNames = Js.stringify(fields.filter { it.required }.map { it.name })
            files += GeneratedFile(
                "$dir/${kebabOf(resource)}.routes.ts",
                """import { FastifyInstance } from "fastify";
import { Create${R}Input, Update${R}Input } from "$schema";
import * as repo from "$repo";

// Fastify validates bodies with JSON Schema (Ajv) before the handler runs.
const properties = {
$props
};
const createBody = { type: "object", properties, required: $requiredNames, additionalProperties: false } as const;
const updateBody = { type: "object", properties, additionalProperties: false } as const;
const idParams = { type: "object", properties: { id: { type: "string" } }, required: ["id"] } as const;
const listQuery = { type: "object", properties: { page: { type: "integer", minimum: 1, default: 1 }, limit: { type: "integer", minimum: 1, maximum: 100, default: 20 } } } as const;

export async function ${rs}Routes(app: FastifyInstance) {
  app.get<{ Querystring: { page: number; limit: number } }>("/", { schema: { querystring: listQuery } }, async req => {
    const { page, limit } = req.query;
    const [items, total] = await Promise.all([repo.list$Rs((page - 1) * limit, limit), repo.count$Rs()]);
    return { items, page, limit, total, pages: Math.ceil(total / limit) };
  });

  app.get<{ Params: { id: string } }>("/:id", { schema: { params: idParams } }, async (req, reply) => {
    const item = await repo.get$R(req.params.id);
    return item ?? reply.code(404).send({ error: "$R not found" });
  });

  app.post<{ Body: Create${R}Input }>("/", { schema: { body: createBody } }, async (req, reply) => {
    return reply.code(201).send(await repo.create$R(req.body));
  });

  app.patch<{ Params: { id: string }; Body: Update${R}Input }>("/:id", { schema: { params: idParams, body: updateBody } }, async (req, reply) => {
    const item = await repo.update$R(req.params.id, req.body);
    return item ?? reply.code(404).send({ error: "$R not found" });
  });

  app.delete<{ Params: { id: string } }>("/:id", { schema: { params: idParams } }, async (req, reply) => {
    const deleted = await repo.delete$R(req.params.id);
    return deleted ? reply.code(204).send() : reply.code(404).send({ error: "$R not found" });
  });
}
""",
                "typescript",
            )
        } else {
            val libImport = "@/lib/${kebabOf(resource)}"
            files += GeneratedFile(
                "app/api/$plural/route.ts",
                """import { NextRequest, NextResponse } from "next/server";
import { ZodError } from "zod";
import { create${R}Schema, list${R}QuerySchema } from "$libImport.schema";
import * as repo from "$libImport.repository";

export async function GET(request: NextRequest) {
  const { page, limit } = list${R}QuerySchema.parse(Object.fromEntries(request.nextUrl.searchParams));
  const [items, total] = await Promise.all([repo.list$Rs((page - 1) * limit, limit), repo.count$Rs()]);
  return NextResponse.json({ items, page, limit, total, pages: Math.ceil(total / limit) });
}

export async function POST(request: NextRequest) {
  try {
    const input = create${R}Schema.parse(await request.json());
    return NextResponse.json(await repo.create$R(input), { status: 201 });
  } catch (error) {
    if (error instanceof ZodError) return NextResponse.json({ error: "Validation failed", issues: error.issues }, { status: 400 });
    if (error instanceof SyntaxError) return NextResponse.json({ error: "Body must be JSON" }, { status: 400 });
    throw error;
  }
}
""",
                "typescript",
            )
            files += GeneratedFile(
                "app/api/$plural/[id]/route.ts",
                """import { NextRequest, NextResponse } from "next/server";
import { ZodError } from "zod";
import { update${R}Schema } from "$libImport.schema";
import * as repo from "$libImport.repository";

// Next.js 15+: route params are a Promise.
type Context = { params: Promise<{ id: string }> };

export async function GET(_request: NextRequest, { params }: Context) {
  const { id } = await params;
  const item = await repo.get$R(id);
  return item ? NextResponse.json(item) : NextResponse.json({ error: "$R not found" }, { status: 404 });
}

export async function PATCH(request: NextRequest, { params }: Context) {
  const { id } = await params;
  try {
    const item = await repo.update$R(id, update${R}Schema.parse(await request.json()));
    return item ? NextResponse.json(item) : NextResponse.json({ error: "$R not found" }, { status: 404 });
  } catch (error) {
    if (error instanceof ZodError) return NextResponse.json({ error: "Validation failed", issues: error.issues }, { status: 400 });
    throw error;
  }
}

export async function DELETE(_request: NextRequest, { params }: Context) {
  const { id } = await params;
  const deleted = await repo.delete$R(id);
  return deleted ? new NextResponse(null, { status: 204 }) : NextResponse.json({ error: "$R not found" }, { status: 404 });
}
""",
                "typescript",
            )
        }
        return Scaffold(files, steps)
    }

    private fun prismaModel(R: String, fields: List<Field>) = """
model $R {
  id           String   @id @default(uuid())
${fields.joinToString("\n") { prismaOf(it) }}
  createdAt    DateTime @default(now())
  updatedAt    DateTime @updatedAt
}
"""

    private fun repository(R: String, r: String, rs: String, dir: String, fields: List<Field>, db: String, resource: String): List<GeneratedFile> {
        val Rs = pascalOf(rs)
        val header = "import { Create${R}Input, $R, Update${R}Input } from \"./${kebabOf(resource)}.schema\";\n"
        val files = mutableListOf<GeneratedFile>()
        when (db) {
            "memory" -> files += GeneratedFile(
                "$dir/${kebabOf(resource)}.repository.ts",
                """import { randomUUID } from "node:crypto";
$header
// In-memory store for prototypes and tests; swap for a database before production.
const store = new Map<string, $R>();

export async function list$Rs(skip: number, take: number): Promise<$R[]> {
  return [...store.values()].sort((a, b) => b.createdAt.getTime() - a.createdAt.getTime()).slice(skip, skip + take);
}

export async function count$Rs(): Promise<number> {
  return store.size;
}

export async function get$R(id: string): Promise<$R | undefined> {
  return store.get(id);
}

export async function create$R(input: Create${R}Input): Promise<$R> {
  const now = new Date();
  const item: $R = { id: randomUUID(), ...input, createdAt: now, updatedAt: now };
  store.set(item.id, item);
  return item;
}

export async function update$R(id: string, input: Update${R}Input): Promise<$R | undefined> {
  const existing = store.get(id);
  if (!existing) return undefined;
  const item: $R = { ...existing, ...input, updatedAt: new Date() };
  store.set(id, item);
  return item;
}

export async function delete$R(id: string): Promise<boolean> {
  return store.delete(id);
}
""",
                "typescript",
            )
            "prisma" -> {
                files += GeneratedFile(
                    "${if (dir.startsWith("lib")) "lib" else "src"}/prisma.ts",
                    """import { PrismaClient } from "@prisma/client";

// One client per process; reuse it across hot reloads in development.
const globalForPrisma = globalThis as unknown as { prisma?: PrismaClient };
export const prisma = globalForPrisma.prisma ?? new PrismaClient();
if (process.env.NODE_ENV !== "production") globalForPrisma.prisma = prisma;
""",
                    "typescript",
                )
                val prismaPath = if (dir.startsWith("lib")) "./prisma" else "../prisma"
                files += GeneratedFile(
                    "$dir/${kebabOf(resource)}.repository.ts",
                    """import { Prisma } from "@prisma/client";
import { prisma } from "$prismaPath";
import { Create${R}Input, Update${R}Input } from "./${kebabOf(resource)}.schema";

export function list$Rs(skip: number, take: number) {
  return prisma.$r.findMany({ skip, take, orderBy: { createdAt: "desc" } });
}

export function count$Rs() {
  return prisma.$r.count();
}

export function get$R(id: string) {
  return prisma.$r.findUnique({ where: { id } });
}

export function create$R(input: Create${R}Input) {
  return prisma.$r.create({ data: input as Prisma.${R}CreateInput });
}

export async function update$R(id: string, input: Update${R}Input) {
  try {
    return await prisma.$r.update({ where: { id }, data: input as Prisma.${R}UpdateInput });
  } catch (error) {
    // P2025: record to update not found
    if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === "P2025") return null;
    throw error;
  }
}

export async function delete$R(id: string) {
  const { count } = await prisma.$r.deleteMany({ where: { id } });
  return count > 0;
}
""",
                    "typescript",
                )
                files += GeneratedFile("prisma/schema.prisma", prismaModel(R, fields), "prisma", "append")
            }
            else -> {
                files += GeneratedFile(
                    "$dir/${kebabOf(resource)}.model.ts",
                    """import { InferSchemaType, Schema, model, models, Model } from "mongoose";

const ${r}Schema = new Schema(
  {
${fields.joinToString("\n") { mongooseOf(it) }}
  },
  {
    timestamps: true,
    // Expose "id" instead of "_id" and drop "__v" in JSON responses.
    toJSON: { virtuals: true, versionKey: false, transform: (_doc, ret: Record<string, unknown>) => { delete ret._id; return ret; } },
  },
);

export type ${R}Document = InferSchemaType<typeof ${r}Schema>;
// models.$R avoids OverwriteModelError on hot reload (Next.js, nodemon).
export const ${R}Model = (models.$R as Model<${R}Document>) ?? model("$R", ${r}Schema);
""",
                    "typescript",
                )
                files += GeneratedFile(
                    "$dir/${kebabOf(resource)}.repository.ts",
                    """import { isValidObjectId } from "mongoose";
import { ${R}Model } from "./${kebabOf(resource)}.model";
import { Create${R}Input, Update${R}Input } from "./${kebabOf(resource)}.schema";

export function list$Rs(skip: number, take: number) {
  return ${R}Model.find().sort({ createdAt: -1 }).skip(skip).limit(take);
}

export function count$Rs() {
  return ${R}Model.countDocuments();
}

export async function get$R(id: string) {
  // An invalid ObjectId would throw a CastError; treat it as "not found".
  return isValidObjectId(id) ? ${R}Model.findById(id) : null;
}

export function create$R(input: Create${R}Input) {
  return ${R}Model.create(input);
}

export async function update$R(id: string, input: Update${R}Input) {
  return isValidObjectId(id) ? ${R}Model.findByIdAndUpdate(id, input, { new: true, runValidators: true }) : null;
}

export async function delete$R(id: string) {
  if (!isValidObjectId(id)) return false;
  return (await ${R}Model.findByIdAndDelete(id)) !== null;
}
""",
                    "typescript",
                )
            }
        }
        return files
    }

    private fun nest(R: String, r: String, rs: String, plural: String, fields: List<Field>, db: String): Scaffold {
        val dir = "src/$plural"
        val imports = LinkedHashSet<String>()
        val dtoFields = fields.map { f ->
            val (decorators, names) = validatorOf(f)
            imports += names
            "${decorators.joinToString("\n") { "  $it" }}\n  ${f.name}${if (f.required) "!" else "?"}: ${if (f.type == "date") "string" else tsType(f)};"
        }
        val kr = kebabOf(r)
        val files = mutableListOf(
            GeneratedFile("$dir/dto/create-$kr.dto.ts", "import { ${imports.sorted().joinToString(", ")} } from \"class-validator\";\n\nexport class Create${R}Dto {\n${dtoFields.joinToString("\n\n")}\n}\n", "typescript"),
            GeneratedFile("$dir/dto/update-$kr.dto.ts", "import { PartialType } from \"@nestjs/mapped-types\";\nimport { Create${R}Dto } from \"./create-$kr.dto\";\n\nexport class Update${R}Dto extends PartialType(Create${R}Dto) {}\n", "typescript"),
        )
        val Rs = pascalOf(rs)
        val serviceBody: String
        var moduleImports = ""
        var moduleImportList = ""
        var providers = "${Rs}Service"
        val steps = mutableListOf(
            "npm install class-validator class-transformer @nestjs/mapped-types",
            "Enable validation in main.ts: app.useGlobalPipes(new ValidationPipe({ whitelist: true, forbidNonWhitelisted: true, transform: true }));",
            "Add ${Rs}Module to the imports of AppModule.",
        )
        if (db == "mongoose") {
            steps.add(0, "npm install @nestjs/mongoose mongoose")
            val props = fields.joinToString("\n\n") { f ->
                val options = LinkedHashMap<String, Any?>()
                if (f.required) options["required"] = true
                if (f.unique) options["unique"] = true
                if (f.type == "enum") options["enum"] = f.values
                if (f.type.endsWith("[]")) options["type"] = listOf(if (f.type == "string[]") "String" else "Number")
                "  @Prop(${Js.stringify(options).replace(Regex("\"(String|Number)\""), "$1")})\n  ${f.name}${if (f.required) "!" else "?"}: ${tsType(f)};"
            }
            files += GeneratedFile(
                "$dir/schemas/$kr.schema.ts",
                """import { Prop, Schema, SchemaFactory } from "@nestjs/mongoose";
import { HydratedDocument } from "mongoose";

export type ${R}Document = HydratedDocument<$R>;

@Schema({ timestamps: true })
export class $R {
$props
}

export const ${R}Schema = SchemaFactory.createForClass($R);
""",
                "typescript",
            )
            moduleImports = "import { MongooseModule } from \"@nestjs/mongoose\";\nimport { $R, ${R}Schema } from \"./schemas/$kr.schema\";\n"
            moduleImportList = "\n  imports: [MongooseModule.forFeature([{ name: $R.name, schema: ${R}Schema }])],"
            serviceBody = """import { Injectable, NotFoundException } from "@nestjs/common";
import { InjectModel } from "@nestjs/mongoose";
import { isValidObjectId, Model } from "mongoose";
import { Create${R}Dto } from "./dto/create-$kr.dto";
import { Update${R}Dto } from "./dto/update-$kr.dto";
import { $R, ${R}Document } from "./schemas/$kr.schema";

@Injectable()
export class ${Rs}Service {
  constructor(@InjectModel($R.name) private readonly model: Model<${R}Document>) {}

  async findAll(page: number, limit: number) {
    const [items, total] = await Promise.all([
      this.model.find().sort({ createdAt: -1 }).skip((page - 1) * limit).limit(limit).exec(),
      this.model.countDocuments().exec(),
    ]);
    return { items, page, limit, total, pages: Math.ceil(total / limit) };
  }

  async findOne(id: string) {
    const item = isValidObjectId(id) ? await this.model.findById(id).exec() : null;
    if (!item) throw new NotFoundException("$R not found");
    return item;
  }

  create(dto: Create${R}Dto) {
    return this.model.create(dto);
  }

  async update(id: string, dto: Update${R}Dto) {
    const item = isValidObjectId(id) ? await this.model.findByIdAndUpdate(id, dto, { new: true, runValidators: true }).exec() : null;
    if (!item) throw new NotFoundException("$R not found");
    return item;
  }

  async remove(id: string) {
    const item = isValidObjectId(id) ? await this.model.findByIdAndDelete(id).exec() : null;
    if (!item) throw new NotFoundException("$R not found");
  }
}
"""
        } else if (db == "prisma") {
            steps.add(0, "npm install @prisma/client && npm install -D prisma")
            steps += "npx prisma migrate dev --name add_$r"
            files += GeneratedFile(
                "src/prisma/prisma.service.ts",
                """import { Injectable, OnModuleInit } from "@nestjs/common";
import { PrismaClient } from "@prisma/client";

@Injectable()
export class PrismaService extends PrismaClient implements OnModuleInit {
  async onModuleInit() {
    await this.${D}connect();
  }
}
""",
                "typescript",
            )
            files += GeneratedFile("prisma/schema.prisma", prismaModel(R, fields), "prisma", "append")
            moduleImports = "import { PrismaService } from \"../prisma/prisma.service\";\n"
            providers = "${Rs}Service, PrismaService"
            serviceBody = """import { Injectable, NotFoundException } from "@nestjs/common";
import { Prisma } from "@prisma/client";
import { PrismaService } from "../prisma/prisma.service";
import { Create${R}Dto } from "./dto/create-$kr.dto";
import { Update${R}Dto } from "./dto/update-$kr.dto";

@Injectable()
export class ${Rs}Service {
  constructor(private readonly prisma: PrismaService) {}

  async findAll(page: number, limit: number) {
    const [items, total] = await this.prisma.${D}transaction([
      this.prisma.$r.findMany({ skip: (page - 1) * limit, take: limit, orderBy: { createdAt: "desc" } }),
      this.prisma.$r.count(),
    ]);
    return { items, page, limit, total, pages: Math.ceil(total / limit) };
  }

  async findOne(id: string) {
    const item = await this.prisma.$r.findUnique({ where: { id } });
    if (!item) throw new NotFoundException("$R not found");
    return item;
  }

  create(dto: Create${R}Dto) {
    return this.prisma.$r.create({ data: dto as Prisma.${R}CreateInput });
  }

  async update(id: string, dto: Update${R}Dto) {
    await this.findOne(id);
    return this.prisma.$r.update({ where: { id }, data: dto as Prisma.${R}UpdateInput });
  }

  async remove(id: string) {
    await this.findOne(id);
    await this.prisma.$r.delete({ where: { id } });
  }
}
"""
        } else {
            serviceBody = """import { Injectable, NotFoundException } from "@nestjs/common";
import { randomUUID } from "node:crypto";
import { Create${R}Dto } from "./dto/create-$kr.dto";
import { Update${R}Dto } from "./dto/update-$kr.dto";

export type $R = Create${R}Dto & { id: string; createdAt: Date; updatedAt: Date };

// In-memory store for prototypes and tests; swap for a database before production.
@Injectable()
export class ${Rs}Service {
  private readonly store = new Map<string, $R>();

  findAll(page: number, limit: number) {
    const all = [...this.store.values()].sort((a, b) => b.createdAt.getTime() - a.createdAt.getTime());
    return { items: all.slice((page - 1) * limit, page * limit), page, limit, total: all.length, pages: Math.ceil(all.length / limit) };
  }

  findOne(id: string) {
    const item = this.store.get(id);
    if (!item) throw new NotFoundException("$R not found");
    return item;
  }

  create(dto: Create${R}Dto) {
    const now = new Date();
    const item: $R = { ...dto, id: randomUUID(), createdAt: now, updatedAt: now };
    this.store.set(item.id, item);
    return item;
  }

  update(id: string, dto: Update${R}Dto) {
    const item = { ...this.findOne(id), ...dto, updatedAt: new Date() };
    this.store.set(id, item);
    return item;
  }

  remove(id: string) {
    this.findOne(id);
    this.store.delete(id);
  }
}
"""
        }
        files += GeneratedFile("$dir/$plural.service.ts", serviceBody, "typescript")
        files += GeneratedFile(
            "$dir/$plural.controller.ts",
            """import { Body, Controller, DefaultValuePipe, Delete, Get, HttpCode, Param, ParseIntPipe, Patch, Post, Query } from "@nestjs/common";
import { Create${R}Dto } from "./dto/create-$kr.dto";
import { Update${R}Dto } from "./dto/update-$kr.dto";
import { ${Rs}Service } from "./$plural.service";

@Controller("$plural")
export class ${Rs}Controller {
  constructor(private readonly service: ${Rs}Service) {}

  @Get()
  findAll(
    @Query("page", new DefaultValuePipe(1), ParseIntPipe) page: number,
    @Query("limit", new DefaultValuePipe(20), ParseIntPipe) limit: number,
  ) {
    return this.service.findAll(Math.max(1, page), Math.min(100, Math.max(1, limit)));
  }

  @Get(":id")
  findOne(@Param("id") id: string) {
    return this.service.findOne(id);
  }

  @Post()
  create(@Body() dto: Create${R}Dto) {
    return this.service.create(dto);
  }

  @Patch(":id")
  update(@Param("id") id: string, @Body() dto: Update${R}Dto) {
    return this.service.update(id, dto);
  }

  @Delete(":id")
  @HttpCode(204)
  remove(@Param("id") id: string) {
    return this.service.remove(id);
  }
}
""",
            "typescript",
        )
        files += GeneratedFile(
            "$dir/$plural.module.ts",
            """import { Module } from "@nestjs/common";
${moduleImports}import { ${Rs}Controller } from "./$plural.controller";
import { ${Rs}Service } from "./$plural.service";

@Module({$moduleImportList
  controllers: [${Rs}Controller],
  providers: [$providers],
})
export class ${Rs}Module {}
""",
            "typescript",
        )
        return Scaffold(files, steps)
    }

    // ---------------------------------------------------------------------------
    // Backend: Spring Boot, ASP.NET Core, FastAPI (web-scaffold-backend.ts)
    // ---------------------------------------------------------------------------

    private fun snake(name: String) = words(name).joinToString("_").ifEmpty { "item" }
    private fun upperFirst(name: String) = name[0].uppercase() + name.substring(1)
    private fun indent(text: String, spaces: Int) = text.split("\n").joinToString("\n") { if (it.isNotEmpty()) " ".repeat(spaces) + it else it }
    private fun enumPattern(f: Field) = f.values.joinToString("|") { it.replace(Regex("[.*+?^${D}{}()|\\[\\]\\\\]")) { m -> "\\" + m.value } }

    private fun javaType(f: Field) = when (f.type) {
        "int" -> "Integer"; "number" -> "BigDecimal"; "boolean" -> "Boolean"; "date" -> "Instant"; "uuid" -> "UUID"
        "string[]" -> "List<String>"; "number[]" -> "List<BigDecimal>"; "json" -> "Map<String, Object>"
        else -> "String"
    }

    private fun javaConstraints(f: Field, create: Boolean): List<String> {
        val c = mutableListOf<String>()
        val text = f.type in listOf("string", "email", "url", "text", "enum")
        if (create && f.required) c += if (text) "@NotBlank" else if (f.type.endsWith("[]")) "@NotEmpty" else "@NotNull"
        if (f.type == "string") c += if (create) "@Size(max = 255)" else "@Size(min = 1, max = 255)"
        if (f.type == "text") c += "@Size(max = 10000)"
        if (f.type == "email") c += listOf("@Email", "@Size(max = 255)")
        if (f.type == "url") c += listOf("@URL", "@Size(max = 2048)")
        if (f.type == "enum") c += "@Pattern(regexp = ${Js.quote(enumPattern(f))}, message = ${Js.quote("must be one of: ${f.values.joinToString(", ")}")})"
        if (f.type == "int" || f.type == "number") c += if (f.type == "int") "@Min(0)" else "@DecimalMin(\"0\")"
        return c
    }

    private fun javaColumn(f: Field): String {
        if (f.type == "string[]" || f.type == "number[]") return "@ElementCollection"
        if (f.type == "json") return "@JdbcTypeCode(SqlTypes.JSON)\n@Column(name = \"${snake(f.name)}\")"
        val attrs = mutableListOf("name = \"${snake(f.name)}\"")
        if (f.required) attrs += "nullable = false"
        if (f.unique) attrs += "unique = true"
        if (f.type == "text") attrs += "length = 10000"
        else if (f.type == "url") attrs += "length = 2048"
        else if (f.type in listOf("string", "email", "enum")) attrs += "length = 255"
        if (f.type == "number") attrs += listOf("precision = 19", "scale = 4")
        return "@Column(${attrs.joinToString(", ")})"
    }

    fun springResource(resource: String, fields: List<Field>, basePackage: String, db: String): Scaffold {
        val R = pascalOf(resource)
        val r = camelOf(resource)
        val plural = pluralOf(r)
        val featurePackage = "$basePackage.${plural.lowercase()}"
        val webPackage = "$basePackage.web"
        val dir = "src/main/java/${featurePackage.replace(".", "/")}"
        val webDir = "src/main/java/${webPackage.replace(".", "/")}"
        fun imports(types: List<String>): String {
            val all = types.joinToString(" ")
            return listOfNotNull(
                "import java.math.BigDecimal;".takeIf { all.contains("BigDecimal") },
                "import java.time.Instant;".takeIf { all.contains("Instant") },
                "import java.util.List;".takeIf { all.contains("List<") },
                "import java.util.ArrayList;".takeIf { all.contains("ArrayList") },
                "import java.util.Map;".takeIf { all.contains("Map<") },
                "import java.util.UUID;".takeIf { all.contains("UUID") },
            ).joinToString("\n")
        }
        val types = fields.map(::javaType)
        val hasList = fields.any { it.type.endsWith("[]") }
        val entityFields = fields.joinToString("\n\n") { f -> "${javaColumn(f)}\nprivate ${javaType(f)} ${f.name}${if (f.type.endsWith("[]")) " = new ArrayList<>()" else ""};" }
        val accessorList = listOf("id" to "UUID") + fields.map { it.name to javaType(it) } + listOf("createdAt" to "Instant", "updatedAt" to "Instant")
        val accessors = accessorList.joinToString("\n\n") { (name, type) ->
            "public $type get${upperFirst(name)}() { return $name; }" +
                if (name == "id" || name.endsWith("At")) "" else "\npublic void set${upperFirst(name)}($type $name) { this.$name = $name; }"
        }
        val entity = """package $featurePackage;

import jakarta.persistence.*;
${if (fields.any { it.type == "json" }) "import org.hibernate.annotations.JdbcTypeCode;\nimport org.hibernate.type.SqlTypes;\n" else ""}${imports(types + listOf("UUID", "Instant") + if (hasList) listOf("ArrayList") else emptyList())}

@Entity
@Table(name = "${snake(plural)}")
public class $R {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

${indent(entityFields, 4)}

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

${indent(accessors, 4)}
}
"""
        fun record(name: String, create: Boolean) = "public record $name(\n" +
            fields.joinToString(",\n") { f -> "        ${(javaConstraints(f, create) + javaType(f).replace(Regex("^List<String>$"), "List<@NotBlank String>")).joinToString(" ")} ${f.name}" } +
            "\n) {}"
        val validationImports = "import jakarta.validation.constraints.*;" + if (fields.any { it.type == "url" }) "\nimport org.hibernate.validator.constraints.URL;" else ""
        val dtos = listOf(
            GeneratedFile("$dir/Create${R}Request.java", "package $featurePackage;\n\n$validationImports\n${imports(types)}\n\n${record("Create${R}Request", true)}\n", "java"),
            GeneratedFile("$dir/Update${R}Request.java", "package $featurePackage;\n\n$validationImports\n${imports(types)}\n\n/** Every field is optional: only the fields sent are changed. */\n${record("Update${R}Request", false)}\n", "java"),
            GeneratedFile(
                "$dir/${R}Response.java",
                """package $featurePackage;

${imports(types + listOf("UUID", "Instant"))}

public record ${R}Response(
        UUID id,
${fields.joinToString("\n") { f -> "        ${javaType(f)} ${f.name}," }}
        Instant createdAt,
        Instant updatedAt
) {
    static ${R}Response from($R $r) {
        return new ${R}Response(
                $r.getId(),
${fields.joinToString("\n") { f -> "                " + (if (f.type.endsWith("[]")) "List.copyOf($r.get${upperFirst(f.name)}())" else "$r.get${upperFirst(f.name)}()") + "," }}
                $r.getCreatedAt(),
                $r.getUpdatedAt());
    }
}
""",
                "java",
            ),
        )
        fun copy(from: String, target: String, partial: Boolean) = fields.joinToString("\n") { f ->
            val value = if (f.type.endsWith("[]")) "new ArrayList<>($from.${f.name}())" else "$from.${f.name}()"
            val set = "$target.set${upperFirst(f.name)}($value);"
            when {
                partial -> "if ($from.${f.name}() != null) $set"
                f.type.endsWith("[]") -> "$target.set${upperFirst(f.name)}($from.${f.name}() == null ? new ArrayList<>() : $value);"
                else -> set
            }
        }
        val service = """package $featurePackage;

import $webPackage.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

${if (hasList) "import java.util.ArrayList;\n" else ""}import java.util.UUID;

@Service
@Transactional
public class ${R}Service {

    private final ${R}Repository repository;

    public ${R}Service(${R}Repository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public PageResponse<${R}Response> list(int page, int size) {
        Page<$R> result = repository.findAll(PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return new PageResponse<>(result.map(${R}Response::from).getContent(), page, size, result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public ${R}Response get(UUID id) {
        return ${R}Response.from(find(id));
    }

    public ${R}Response create(Create${R}Request request) {
        $R $r = new $R();
${indent(copy("request", r, false), 8)}
        return ${R}Response.from(repository.saveAndFlush($r));
    }

    public ${R}Response update(UUID id, Update${R}Request request) {
        $R $r = find(id);
${indent(copy("request", r, true), 8)}
        return ${R}Response.from(repository.saveAndFlush($r));
    }

    public void delete(UUID id) {
        repository.delete(find(id));
    }

    private $R find(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "$R not found"));
    }
}
"""
        val route = "/api/${kebabOf(plural)}"
        val controller = """package $featurePackage;

import $webPackage.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.UUID;

@RestController
@RequestMapping("$route")
public class ${R}Controller {

    private final ${R}Service service;

    public ${R}Controller(${R}Service service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<${R}Response> list(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return service.list(Math.max(page, 1), Math.min(Math.max(size, 1), 100));
    }

    @GetMapping("/{id}")
    public ${R}Response get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<${R}Response> create(@Valid @RequestBody Create${R}Request request, UriComponentsBuilder uri) {
        ${R}Response created = service.create(request);
        return ResponseEntity.created(uri.path("$route/{id}").buildAndExpand(created.id()).toUri()).body(created);
    }

    @PatchMapping("/{id}")
    public ${R}Response update(@PathVariable UUID id, @Valid @RequestBody Update${R}Request request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
"""
        val files = listOf(GeneratedFile("$dir/$R.java", entity, "java")) +
            GeneratedFile("$dir/${R}Repository.java", "package $featurePackage;\n\nimport org.springframework.data.jpa.repository.JpaRepository;\n\nimport java.util.UUID;\n\npublic interface ${R}Repository extends JpaRepository<$R, UUID> {\n}\n", "java") +
            dtos +
            GeneratedFile("$dir/${R}Service.java", service, "java") +
            GeneratedFile("$dir/${R}Controller.java", controller, "java") +
            GeneratedFile("$webDir/PageResponse.java", "package $webPackage;\n\nimport java.util.List;\n\npublic record PageResponse<T>(List<T> items, int page, int size, long total, int pages) {\n}\n", "java") +
            GeneratedFile(
                "$webDir/ApiExceptionHandler.java",
                """package $webPackage;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** Turns validation errors into 400 and unique-constraint violations into 409, as RFC 9457 problem details. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validationFailed(MethodArgumentNotValidException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail conflict(DataIntegrityViolationException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "A record with the same unique value already exists.");
    }
}
""",
                "java",
            )
        val driver = when (db) {
            "mysql" -> listOf("com.mysql:mysql-connector-j", "jdbc:mysql://localhost:3306/app")
            "sqlserver" -> listOf("com.microsoft.sqlserver:mssql-jdbc", "jdbc:sqlserver://localhost:1433;databaseName=app;encrypt=false")
            "sqlite" -> listOf("com.h2database:h2", "jdbc:h2:file:./data/app")
            else -> listOf("org.postgresql:postgresql", "jdbc:postgresql://localhost:5432/app")
        }
        val steps = listOf(
            "Spring Boot 3 (Java 17+). Dependencies: spring-boot-starter-web, spring-boot-starter-validation, spring-boot-starter-data-jpa and ${driver[0]} (runtime).",
            "application.properties: spring.datasource.url=${driver[1]}${if (db == "sqlite") "" else " plus spring.datasource.username / password"}; for development spring.jpa.hibernate.ddl-auto=update (use Flyway or Liquibase migrations in production).",
            "$basePackage must be your @SpringBootApplication package (or a parent of it) so the controller and entity are scanned.",
            "Try it: curl -X POST localhost:8080$route -H 'Content-Type: application/json' -d '{…}'",
        )
        return Scaffold(files, steps)
    }

    private fun csType(f: Field, nullable: Boolean): String {
        val base = when (f.type) {
            "int" -> "int"; "number" -> "decimal"; "boolean" -> "bool"; "date" -> "DateTime"; "uuid" -> "Guid"
            "string[]" -> "List<string>"; "number[]" -> "List<decimal>"
            else -> "string"
        }
        return if (nullable) "$base?" else base
    }

    private fun csAttributes(f: Field, create: Boolean): List<String> {
        val a = mutableListOf<String>()
        if (create && f.required) a += "Required"
        if (f.type == "string") a += "StringLength(255, MinimumLength = 1)"
        if (f.type == "text") a += "StringLength(10000)"
        if (f.type == "email") a += listOf("EmailAddress", "StringLength(255)")
        if (f.type == "url") a += listOf("Url", "StringLength(2048)")
        if (f.type == "enum") a += "RegularExpression(${Js.quote("^(${enumPattern(f)})$D")}, ErrorMessage = ${Js.quote("Must be one of: ${f.values.joinToString(", ")}")})"
        if (f.type == "int") a += "Range(0, int.MaxValue)"
        if (f.type == "number") a += "Range(typeof(decimal), \"0\", \"79228162514264337593543950335\")"
        if (f.type == "json") a += "StringLength(100000)"
        return a
    }

    fun aspnetResource(resource: String, fields: List<Field>, rootNamespace: String, db: String): Scaffold {
        val R = pascalOf(resource)
        val plural = pluralOf(camelOf(resource))
        val Rs = pascalOf(plural)
        fun P(f: Field) = upperFirst(f.name)
        fun valueType(f: Field) = f.type in listOf("int", "number", "boolean", "date", "uuid")
        val entityProps = fields.joinToString("\n") { f ->
            val nullable = !f.required && !f.type.endsWith("[]")
            val init = if (f.type.endsWith("[]")) " = [];" else if (!nullable && !valueType(f)) " = \"\";" else ""
            "public ${csType(f, nullable)} ${P(f)} { get; set; }$init"
        }
        val entity = """namespace $rootNamespace.Models;

public class $R
{
    public Guid Id { get; set; }
${indent(entityProps, 4)}
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
}
"""
        fun requestProps(create: Boolean) = fields.joinToString("\n\n") { f ->
            val attrs = csAttributes(f, create)
            // Value types are nullable on requests so a missing field fails [Required] instead of becoming 0.
            "${if (attrs.isNotEmpty()) "[${attrs.joinToString(", ")}]\n" else ""}public ${csType(f, true)} ${P(f)} { get; init; }"
        }
        val dtos = """using System.ComponentModel.DataAnnotations;
using $rootNamespace.Models;

namespace $rootNamespace.Dtos;

public class Create${R}Request
{
${indent(requestProps(true), 4)}
}

/// <summary>Every field is optional: only the fields sent are changed.</summary>
public class Update${R}Request
{
${indent(requestProps(false), 4)}
}

public record ${R}Response(
    Guid Id,
${fields.joinToString("\n") { f -> "    ${csType(f, !f.required && !f.type.endsWith("[]"))} ${P(f)}," }}
    DateTime CreatedAt,
    DateTime UpdatedAt)
{
    public static ${R}Response From($R entity) => new(
        entity.Id,
${fields.joinToString("\n") { f -> "        entity.${P(f)}," }}
        entity.CreatedAt,
        entity.UpdatedAt);
}

public record PagedResponse<T>(IReadOnlyList<T> Items, int Page, int PageSize, int Total, int Pages);
"""
        fun assign(partial: Boolean) = fields.joinToString("\n") { f ->
            when {
                partial && f.type.endsWith("[]") -> "if (request.${P(f)} is not null) entity.${P(f)} = request.${P(f)};"
                partial -> "if (request.${P(f)} is not null) entity.${P(f)} = request.${P(f)}${if (valueType(f)) ".Value" else ""};"
                f.type.endsWith("[]") -> "${P(f)} = request.${P(f)} ?? [],"
                f.required -> "${P(f)} = request.${P(f)}${if (valueType(f)) ".GetValueOrDefault()" else "!"},"
                else -> "${P(f)} = request.${P(f)},"
            }
        }
        val controller = """using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using $rootNamespace.Data;
using $rootNamespace.Dtos;
using $rootNamespace.Models;

namespace $rootNamespace.Controllers;

[ApiController]
[Route("api/${kebabOf(plural)}")]
public class ${Rs}Controller(AppDbContext db) : ControllerBase
{
    [HttpGet]
    public async Task<ActionResult<PagedResponse<${R}Response>>> List([FromQuery] int page = 1, [FromQuery] int pageSize = 20, CancellationToken ct = default)
    {
        page = Math.Max(page, 1);
        pageSize = Math.Clamp(pageSize, 1, 100);
        var query = db.$Rs.AsNoTracking().OrderByDescending(x => x.CreatedAt);
        var total = await query.CountAsync(ct);
        var items = await query.Skip((page - 1) * pageSize).Take(pageSize).ToListAsync(ct);
        return new PagedResponse<${R}Response>(items.Select(${R}Response.From).ToList(), page, pageSize, total, (int)Math.Ceiling(total / (double)pageSize));
    }

    [HttpGet("{id:guid}")]
    public async Task<ActionResult<${R}Response>> Get(Guid id, CancellationToken ct)
    {
        var entity = await db.$Rs.AsNoTracking().FirstOrDefaultAsync(x => x.Id == id, ct);
        return entity is null ? NotFound() : ${R}Response.From(entity);
    }

    [HttpPost]
    public async Task<ActionResult<${R}Response>> Create(Create${R}Request request, CancellationToken ct)
    {
        var now = DateTime.UtcNow;
        var entity = new $R
        {
            Id = Guid.NewGuid(),
${indent(assign(false), 12)}
            CreatedAt = now,
            UpdatedAt = now
        };
        db.$Rs.Add(entity);
        if (!await TrySaveAsync(ct)) return Conflict(Problem409());
        return CreatedAtAction(nameof(Get), new { id = entity.Id }, ${R}Response.From(entity));
    }

    [HttpPatch("{id:guid}")]
    public async Task<ActionResult<${R}Response>> Update(Guid id, Update${R}Request request, CancellationToken ct)
    {
        var entity = await db.$Rs.FirstOrDefaultAsync(x => x.Id == id, ct);
        if (entity is null) return NotFound();
${indent(assign(true), 8)}
        entity.UpdatedAt = DateTime.UtcNow;
        if (!await TrySaveAsync(ct)) return Conflict(Problem409());
        return ${R}Response.From(entity);
    }

    [HttpDelete("{id:guid}")]
    public async Task<IActionResult> Delete(Guid id, CancellationToken ct)
    {
        var deleted = await db.$Rs.Where(x => x.Id == id).ExecuteDeleteAsync(ct);
        return deleted == 0 ? NotFound() : NoContent();
    }

    private async Task<bool> TrySaveAsync(CancellationToken ct)
    {
        try
        {
            await db.SaveChangesAsync(ct);
            return true;
        }
        catch (DbUpdateException)
        {
            // A unique index was violated (or another constraint): report a conflict, not a 500.
            return false;
        }
    }

    private static ProblemDetails Problem409() => new() { Status = StatusCodes.Status409Conflict, Title = "A ${R.lowercase()} with the same unique value already exists." };
}
"""
        val modelConfig = fields.filter { it.type == "string" || it.type == "email" || it.type == "enum" }.map { "entity.Property(x => x.${P(it)}).HasMaxLength(255);" } +
            fields.filter { it.type == "url" }.map { "entity.Property(x => x.${P(it)}).HasMaxLength(2048);" } +
            fields.filter { it.type == "number" }.map { "entity.Property(x => x.${P(it)}).HasPrecision(19, 4);" } +
            fields.filter { it.unique }.map { "entity.HasIndex(x => x.${P(it)}).IsUnique();" } +
            "entity.HasIndex(x => x.CreatedAt);"
        val dbContext = """using Microsoft.EntityFrameworkCore;
using $rootNamespace.Models;

namespace $rootNamespace.Data;

public class AppDbContext(DbContextOptions<AppDbContext> options) : DbContext(options)
{
    public DbSet<$R> $Rs => Set<$R>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        modelBuilder.Entity<$R>(entity =>
        {
${indent(modelConfig.joinToString("\n"), 12)}
        });
    }
}
"""
        val provider = when (db) {
            "mysql" -> listOf("Pomelo.EntityFrameworkCore.MySql", "UseMySql", "Server=localhost;Database=app;User=app;Password=…")
            "sqlserver" -> listOf("Microsoft.EntityFrameworkCore.SqlServer", "UseSqlServer", "Server=localhost;Database=app;Trusted_Connection=True;TrustServerCertificate=True")
            "sqlite" -> listOf("Microsoft.EntityFrameworkCore.Sqlite", "UseSqlite", "Data Source=app.db")
            else -> listOf("Npgsql.EntityFrameworkCore.PostgreSQL", "UseNpgsql", "Host=localhost;Database=app;Username=app;Password=…")
        }
        val use = if (db == "mysql") "${provider[1]}(cs, ServerVersion.AutoDetect(cs))" else "${provider[1]}(cs)"
        return Scaffold(
            listOf(
                GeneratedFile("Models/$R.cs", entity, "csharp"),
                GeneratedFile("Dtos/${R}Dtos.cs", dtos, "csharp"),
                GeneratedFile("Controllers/${Rs}Controller.cs", controller, "csharp"),
                GeneratedFile("Data/AppDbContext.cs", dbContext, "csharp"),
            ),
            listOf(
                ".NET 8 or later. dotnet add package ${provider[0]} and dotnet add package Microsoft.EntityFrameworkCore.Design (for migrations).",
                "Program.cs: var cs = builder.Configuration.GetConnectionString(\"Default\"); builder.Services.AddDbContext<AppDbContext>(o => o.$use); builder.Services.AddControllers(); … app.MapControllers();",
                "appsettings.json: \"ConnectionStrings\": { \"Default\": \"${provider[2]}\" }",
                "dotnet ef migrations add Add$Rs && dotnet ef database update (dotnet tool install --global dotnet-ef once).",
                "If you already have a DbContext, add the DbSet and the OnModelCreating lines to it instead of using Data/AppDbContext.cs.",
            ),
        )
    }

    private fun pyType(f: Field) = when (f.type) {
        "int" -> "int"; "number" -> "float"; "boolean" -> "bool"; "date" -> "datetime"; "email" -> "EmailStr"; "uuid" -> "uuid.UUID"
        "enum" -> "Literal[${quoted(f.values)}]"; "string[]" -> "list[str]"; "number[]" -> "list[float]"; "json" -> "dict[str, Any]"
        else -> "str"
    }

    private fun pyField(f: Field, optional: Boolean): String {
        val args = mutableListOf<String>()
        if (f.type == "string") args += listOf("min_length=1", "max_length=255")
        if (f.type == "text") args += "max_length=10000"
        if (f.type == "url") args += listOf("pattern=r\"^https?://\"", "max_length=2048")
        if (f.type == "int" || f.type == "number") args += "ge=0"
        val type = if (optional) "Optional[${pyType(f)}]" else pyType(f)
        if (optional) return "${snake(f.name)}: $type = ${if (args.isNotEmpty()) "Field(default=None, ${args.joinToString(", ")})" else "None"}"
        return "${snake(f.name)}: $type${if (args.isNotEmpty()) " = Field(${args.joinToString(", ")})" else ""}"
    }

    private fun sqlType(f: Field) = when (f.type) {
        "string", "email" -> "String(255)"; "text" -> "Text"; "int" -> "Integer"; "number" -> "Float"; "boolean" -> "Boolean"
        "date" -> "DateTime(timezone=True)"; "url" -> "String(2048)"; "uuid" -> "Uuid"; "enum" -> "String(50)"
        else -> "JSON"
    }

    private fun sqlColumn(f: Field): String {
        val base = pyType(f).replace("EmailStr", "str").replace(Regex("^Literal\\[.*\\]$"), "str")
        val mapped = if (f.required) base else "Optional[$base]"
        return "${snake(f.name)}: Mapped[$mapped] = mapped_column(${sqlType(f)}${if (f.unique) ", unique=True" else ""}${if (f.required) ", nullable=False" else ""})"
    }

    fun fastapiResource(resource: String, fields: List<Field>, db: String): Scaffold {
        val R = pascalOf(resource)
        val plural = snake(pluralOf(camelOf(resource)))
        val r = snake(resource)
        val pkg = "app/$plural"
        val usesAny = fields.any { it.type == "json" }
        val typing = (listOf("Optional") + (if (usesAny) listOf("Any") else emptyList()) + (if (fields.any { it.type == "enum" }) listOf("Literal") else emptyList())).joinToString(", ")
        val sqlBase = { f: Field ->
            when (f.type) {
                "string", "email", "url", "enum" -> "String"; "text" -> "Text"; "int" -> "Integer"; "number" -> "Float"; "boolean" -> "Boolean"
                "date" -> "DateTime"; "uuid" -> "Uuid"; else -> "JSON"
            }
        }
        val sqlTypes = (listOf("DateTime", "Uuid") + fields.map(sqlBase)).distinct().sorted()
        val models = """import uuid
from datetime import datetime, timezone
from typing import ${(listOf("Optional") + if (usesAny) listOf("Any") else emptyList()).joinToString(", ")}

from sqlalchemy import ${sqlTypes.joinToString(", ")}
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


class $R(Base):
    __tablename__ = "$plural"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
${fields.joinToString("\n") { "    ${sqlColumn(it)}" }}
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, nullable=False, index=True)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, onupdate=utcnow, nullable=False)
"""
        val readFields = fields.joinToString("\n") { f -> "    ${snake(f.name)}: ${if (f.required) pyType(f).replace("EmailStr", "str") else "Optional[${pyType(f).replace("EmailStr", "str")}] = None"}" }
        val schemas = """import uuid
from datetime import datetime
from typing import $typing

from pydantic import BaseModel, ConfigDict, ${if (fields.any { it.type == "email" }) "EmailStr, " else ""}Field


class ${R}Create(BaseModel):
    model_config = ConfigDict(extra="forbid")

${fields.joinToString("\n") { "    ${pyField(it, !it.required)}" }}


class ${R}Update(BaseModel):
    ${TQ}Every field is optional: only the fields sent are changed.${TQ}

    model_config = ConfigDict(extra="forbid")

${fields.joinToString("\n") { "    ${pyField(it, true)}" }}


class ${R}Read(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
$readFields
    created_at: datetime
    updated_at: datetime


class ${R}Page(BaseModel):
    items: list[${R}Read]
    page: int
    limit: int
    total: int
    pages: int
"""
        val router = """import math
import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.database import get_db
from app.$plural.models import $R
from app.$plural.schemas import ${R}Create, ${R}Page, ${R}Read, ${R}Update

router = APIRouter(prefix="/${kebabOf(pluralOf(camelOf(resource)))}", tags=["$plural"])
DbSession = Annotated[Session, Depends(get_db)]


def _get_or_404(db: Session, item_id: uuid.UUID) -> $R:
    item = db.get($R, item_id)
    if item is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="$R not found")
    return item


def _commit(db: Session) -> None:
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="A ${r.replace("_", " ")} with the same unique value already exists.")


@router.get("", response_model=${R}Page)
def list_$plural(db: DbSession, page: int = Query(1, ge=1), limit: int = Query(20, ge=1, le=100)) -> ${R}Page:
    total = db.scalar(select(func.count()).select_from($R)) or 0
    items = db.scalars(select($R).order_by($R.created_at.desc()).offset((page - 1) * limit).limit(limit)).all()
    return ${R}Page(items=[${R}Read.model_validate(item) for item in items], page=page, limit=limit, total=total, pages=math.ceil(total / limit))


@router.get("/{item_id}", response_model=${R}Read)
def get_$r(item_id: uuid.UUID, db: DbSession) -> $R:
    return _get_or_404(db, item_id)


@router.post("", response_model=${R}Read, status_code=status.HTTP_201_CREATED)
def create_$r(payload: ${R}Create, db: DbSession) -> $R:
    item = $R(**payload.model_dump())
    db.add(item)
    _commit(db)
    db.refresh(item)
    return item


@router.patch("/{item_id}", response_model=${R}Read)
def update_$r(item_id: uuid.UUID, payload: ${R}Update, db: DbSession) -> $R:
    item = _get_or_404(db, item_id)
    for key, value in payload.model_dump(exclude_unset=True).items():
        setattr(item, key, value)
    _commit(db)
    db.refresh(item)
    return item


@router.delete("/{item_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_$r(item_id: uuid.UUID, db: DbSession) -> None:
    db.delete(_get_or_404(db, item_id))
    _commit(db)
"""
        val url = when (db) {
            "mysql" -> listOf("mysql+pymysql://app:password@localhost:3306/app", "pymysql")
            "sqlserver" -> listOf("mssql+pyodbc://app:password@localhost/app?driver=ODBC+Driver+18+for+SQL+Server", "pyodbc")
            "sqlite" -> listOf("sqlite:///./app.db", "")
            else -> listOf("postgresql+psycopg://app:password@localhost:5432/app", "psycopg[binary]")
        }
        val database = """import os
from collections.abc import Generator

from sqlalchemy import create_engine
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker

DATABASE_URL = os.getenv("DATABASE_URL", "${url[0]}")

engine = create_engine(DATABASE_URL, connect_args={"check_same_thread": False} if DATABASE_URL.startswith("sqlite") else {})
SessionLocal = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)


class Base(DeclarativeBase):
    pass


def get_db() -> Generator[Session, None, None]:
    with SessionLocal() as session:
        yield session
"""
        return Scaffold(
            listOf(
                GeneratedFile("app/__init__.py", "", "python"),
                GeneratedFile("app/database.py", database, "python"),
                GeneratedFile("$pkg/__init__.py", "", "python"),
                GeneratedFile("$pkg/models.py", models, "python"),
                GeneratedFile("$pkg/schemas.py", schemas, "python"),
                GeneratedFile("$pkg/router.py", router, "python"),
            ),
            listOf(
                "pip install \"fastapi[standard]\" \"sqlalchemy>=2\"${if (fields.any { it.type == "email" }) " email-validator" else ""}${if (url[1].isNotEmpty()) " \"${url[1]}\"" else ""}",
                "app/main.py: from app.$plural.router import router as ${plural}_router; app.include_router(${plural}_router, prefix=\"/api\"). For development call Base.metadata.create_all(bind=engine) at startup; use Alembic migrations in production.",
                "Set DATABASE_URL (default ${url[0]}). Run: fastapi dev app/main.py, then open http://127.0.0.1:8000/docs.",
            ),
        )
    }
}
