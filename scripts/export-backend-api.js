#!/usr/bin/env node
/*
 * Exports the "Backend & API" toolkit tools from the DevSnip Pro VS Code
 * extension so the JetBrains port stays identical to it:
 *
 *  1. Tool metadata (titles, fields, defaults, help, examples) →
 *     src/main/resources/toolkit/backend-api.json, loaded by the plugin.
 *  2. Reference results of every tool over many inputs →
 *     src/test/resources/toolkit/golden-backend-api.json, which the Kotlin
 *     tests must reproduce exactly.
 *
 * Usage (from this repository, with the VS Code repo checked out next to it):
 *   (cd ../DevSnip-Pro && npx tsc -p . --outDir /tmp/devsnip-out --sourceMap false)
 *   NODE_PATH=../DevSnip-Pro/node_modules node scripts/export-backend-api.js /tmp/devsnip-out
 *
 * Nothing in the VS Code repository is modified.
 */
"use strict";
const fs = require("fs");
const path = require("path");

const compiled = process.argv[2];
if (!compiled) {
  console.error("Usage: node scripts/export-backend-api.js <compiled VS Code out dir>");
  process.exit(1);
}
const { ALL_TOOLS } = require(path.resolve(compiled, "toolkits/registry"));
const root = path.resolve(__dirname, "..");

// Order of the "Backend & API" section in the VS Code sidebar (REST API Client is the separate client).
const IDS = ["web.curl", "web.api-response", "web.url", "web.cors", "web.openapi", "web.graphql", "web.api-scaffold"];
const tools = IDS.map(id => {
  const tool = ALL_TOOLS.find(t => t.id === id);
  if (!tool) throw new Error(`Tool ${id} not found`);
  return tool;
});

// ---------------------------------------------------------------- metadata
const descriptors = tools.map(t => ({
  id: t.id,
  command: t.command,
  title: t.title,
  summary: t.summary,
  guide: t.guide,
  keywords: t.keywords,
  icon: t.icon,
  live: !!t.live,
  network: !!t.network,
  runLabel: t.runLabel,
  fields: t.fields,
  examples: t.examples ?? [],
  actions: t.actions ?? []
}));
write("src/main/resources/toolkit/backend-api.json", descriptors);

// ------------------------------------------------------------ golden cases
/** Values as the form sends them: defaults, overrides, and only fields whose showIf holds. */
function formValues(tool, overrides) {
  const all = {};
  for (const f of tool.fields) if (f.default !== undefined) all[f.id] = f.default;
  Object.assign(all, overrides);
  const visible = {};
  for (const f of tool.fields) {
    if (f.showIf && !f.showIf.equals.includes(all[f.showIf.field])) continue;
    if (all[f.id] !== undefined) visible[f.id] = all[f.id];
  }
  return visible;
}

const cases = [];
const ctx = { now: () => new Date("2026-10-01T10:00:00Z") };
async function add(id, overrides, label) {
  const tool = tools.find(t => t.id === id);
  const values = formValues(tool, overrides);
  let result;
  let error;
  try {
    result = JSON.parse(JSON.stringify(await tool.run(values, ctx)));
  } catch (e) {
    error = e && e.constructor && e.constructor.name === "ToolInputError" ? { input: true, message: e.message } : { input: false, message: String(e && e.message) };
  }
  cases.push({ tool: id, label: label ?? JSON.stringify(overrides).slice(0, 80), values, ...(error ? { error } : { result }) });
}

async function main() {
  // Every tool's defaults, every mode and every example.
  for (const t of tools) {
    await add(t.id, {}, "defaults");
    const mode = t.fields.find(f => f.id === "mode");
    if (mode) for (const o of mode.options) await add(t.id, { mode: o.value }, `mode ${o.value}`);
    for (const ex of t.examples ?? []) await add(t.id, ex.values, `example: ${ex.label}`);
  }

  // cURL Converter: every language over many command shapes.
  const curls = [
    "curl https://a.io",
    "curl -X POST https://a.io -d 'a=1&b=2'",
    "curl https://a.io --json '{\"x\":[1,{\"y\":null}],\"t\":true,\"f\":1.5}'",
    "curl https://a.io -F f=@x.png -u u:p",
    "curl -X DELETE https://a.io --data-raw 'raw text' -m 5",
    "curl https://a.io --data-raw '{\"price\":\"$5\"}' -H 'Content-Type: application/json'",
    "curl -sSLX PUT https://api.example.com/x --json '{\"a\":1}'",
    "curl -G https://a.io/s --data-urlencode 'q=a b&c' -d n=2",
    "curl https://a.io/up -F file=@./a.png -F name=x -H 'Content-Type: multipart/form-data; boundary=zz'",
    "curl \"https://x.io\" ^\n -H \"A: ^\"b^\"\"",
    "curl -H 'A: b' \\\n  --data $'x\\ny\\u00e9' \"https://q.io/p?z=1\"",
    "curl -k --compressed -x http://proxy:8080 -b 'sid=1' -A 'Mozilla/5.0' -e https://ref.io -H 'sec-fetch-mode: cors' -H 'X-Empty;' 'http://example.com/path?a=1&b=2' -o out.txt -v",
    "curl 'https://api.example.com/v1/x?y=1&y=2' -X PATCH -H 'Content-Type: application/vnd.api+json' -d '{\"data\":{\"type\":\"x\",\"n\":1.5e3,\"big\":12345678901234567890,\"tiny\":0.0000001,\"u\":\"\\u00e9 é 😀 \\\\ \\\"q\\\" `tick` ${x}\"}}'",
    "curl example.com/a?b=1",
    "curl -I https://a.io",
    "curl --oauth2-bearer tok https://a.io/me",
    "curl -X OPTIONS https://a.io -d 'x'",
    "curl -X POST https://a.io -H 'Content-Type: text/xml' -d '<a>1</a>'",
    "curl https://a.io --form-string 'n=@literal' -F 'doc=@/tmp/a b.pdf;type=application/pdf' -F 'meta=x;type=text/plain'",
    "curl -u admin https://a.io",
    "curl.exe https://a.io --max-time 7 -L",
    "C:\\tools\\curl.exe -X GET https://a.io",
    "curl --request=POST --url=https://a.io/x --header='X-A: 1' --data='k=v'",
    "curl https://a.io -d '[1,2,3]'",
    "curl https://a.io -d '{\"broken\": }'",
    "curl -H 'Content-Type: application/json' https://a.io -d 'not json'",
    "curl -X PURGE https://cdn.example.com/x -H 'Fastly-Key: k'",
    "curl -X PUT https://a.io -d 'x' -m 2.5",
    "curl http://10.0.2.2:8080/api -H 'Accept: text/html'",
    "curl https://a.io/file.json -H 'Accept: */*'",
    "curl https://a.io -d @data.json -H 'Content-Type: application/json'",
    "curl https://a.io --retry 3 -w '%{http_code}' --http2 -s",
    "curl 'https://a.io/q?x=caf%C3%A9&y=a+b#frag' -H 'Host: other' -H 'Content-Length: 5'",
    "curl -X POST https://a.io -d 'a' -d 'b' -d 'c=1'",
    "curl -F 'a=b' -F 'c=@\"weird name\".txt' https://a.io/u",
    "curl https://user:pw@a.io/x",
    "curl -X post https://a.io -d '{\"emptyObj\":{},\"emptyArr\":[],\"nested\":{\"deep\":[[]]}}'"
  ];
  for (const curl of curls) await add("web.curl", { curl, all: true }, `all languages: ${curl.slice(0, 60)}`);
  for (const curl of ["wget https://x", "curl", "curl -H", "curl 'unclosed", "curl \"unclosed", "curl $'unclosed", "curl -F novalue https://a.io", "curl -X GET", "curl http://[bad", "   "]) await add("web.curl", { curl }, `error: ${curl}`);

  // API Response Inspector.
  const responses = [
    "{\"items\":[{\"id\":1,\"tags\":[\"a\"],\"owner\":{\"name\":\"x\"}},{\"id\":2}],\"next_cursor\":\"abc\",\"total\":2}",
    "[{\"id\":1,\"price\":9.99,\"active\":true,\"meta\":null}]",
    "HTTP/1.1 200 OK\nContent-Type: text/plain\nLink: <https://a.io?page=2>; rel=\"next\"\nX-RateLimit-Remaining: 10\nX-RateLimit-Limit: 100\n\n{\"data\":[1,2],\"meta\":{\"page\":1,\"hasMore\":true}}",
    "HTTP/2 404\ncontent-type: application/problem+json\n\n{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404,\"detail\":\"No order 7\"}",
    "HTTP/1.1 204 No Content\nDate: Mon, 01 Jan 2026 00:00:00 GMT",
    "HTTP/1.1 500 Internal Server Error\r\nContent-Type: text/html\r\n\r\n<html>oops</html>",
    "HTTP/1.1 302 Found\nLocation: /login\n\n",
    "plain text body",
    "42",
    "{\"a\":{},\"b\":[],\"c\":[{\"x\":1}],\"d\":\"" + "y".repeat(80) + "\"}",
    "HTTP/1.1 418 I'm a teapot\nX-A: 1\n\n{\"error\":\"short and stout\"}",
    "   "
  ];
  for (const raw of responses) await add("web.api-response", { raw }, `response: ${raw.slice(0, 50)}`);

  // URL & Query String Tool.
  const urls = [
    "https://a.io/p?q=caf%C3%A9+latte&access_token=x&u=%252F",
    "postgresql://app:p@ss@db.example.com:5432/orders?sslmode=require",
    "mysql://root:p%40ss@localhost/db",
    "http://api.example.com/x?a=1&a=2&b",
    "http://localhost:3000/cb?code=abc#state",
    "https://EXAMPLE.com/Path%20With%20Spaces/",
    "https://xn--caf-dma.example/a",
    "example.com/a?b=1",
    "localhost:3000/x",
    "https://a.io/has space?x=1 2",
    "https://user:secret@a.io:8443/a/b/c?k=v",
    "ftp://files.example.com/pub",
    "https://a.io/" + "x".repeat(2100),
    "https://a.io/?key=1&sig=2&page=3",
    "not a url at all",
    "https://a.io/%E0%A4%A",
    "wss://chat.example.com/socket?token=t",
    "mailto:someone@example.com"
  ];
  for (const url of urls) await add("web.url", { mode: "parse", url }, `parse: ${url.slice(0, 60)}`);
  const builds = [
    ["https://api.example.com/v1/search", "q=coffee & cake\ncity=São Paulo\nredirect=https://app.example.com/cb?x=1\npage=2"],
    ["https://a.io/s?x=1&y=2#top", "q=a b&c\n-x\n# comment\nz: 9\nemoji=😀\nstar=*!'()"],
    ["https://a.io", ""],
    ["a.io/x", "q=1"],
    ["https://a.io", "novalue"],
    ["https://a.io:443/p?keep=1", "=bad"]
  ];
  for (const [base, params] of builds) await add("web.url", { mode: "build", base, params }, `build: ${base}`);
  const queries = [
    "filter[status]=active&filter[age][gte]=18&sort=-createdAt&fields[]=name&fields[]=email&page=2",
    "https://a.io/x?a[0]=x&a[1]=y&b=1&b=2&c&d=%E2%9C%93&e=a+b#frag",
    "a[0][n]=1&a[0][m]=2&a[1][n]=3",
    "x=%E0%A4%A&y=1",
    "?only=1",
    "dup=1&dup=2&dup=3",
    "obj[a]=1&obj=2"
  ];
  for (const query of queries) await add("web.url", { mode: "qs-json", query }, `qs-json: ${query.slice(0, 50)}`);
  const objects = [
    "{\"filter\":{\"status\":\"active\",\"age\":{\"gte\":18}},\"fields\":[\"name\",\"email\"],\"page\":2}",
    "{\"a\":[{\"b\":1},{\"b\":2}],\"n\":null,\"t\":true,\"s\":\"a b&c\",\"e\":[],\"o\":{}}",
    "{\"t\":[\"x\",\"y\"],\"m\":[1,[2,3]]}",
    "[1,2]",
    "{bad json",
    "\"str\""
  ];
  for (const json of objects) for (const arrayFormat of ["brackets", "indices", "repeat", "comma"]) await add("web.url", { mode: "json-qs", json, arrayFormat }, `json-qs ${arrayFormat}: ${json.slice(0, 40)}`);
  await add("web.url", { mode: "parse", url: "" }, "parse: empty");

  // CORS Builder & Debugger.
  const corsBuilds = [
    { origins: "*", credentials: true },
    { origins: "*", credentials: false, expose: "" },
    { origins: "https://app.example.com/\nexample.com\nhttp://staging.example.com\nhttps://*.example.com", methods: "get, post", headers: "", expose: "X-A, X-B", maxAge: 0 },
    { origins: "", credentials: true },
    { origins: "https://a.io", maxAge: 86400, credentials: false }
  ];
  for (const b of corsBuilds) await add("web.cors", { mode: "build", ...b }, `cors build: ${JSON.stringify(b).slice(0, 60)}`);
  const checks = [
    { origin: "http://localhost:3000", method: "PUT", requestHeaders: "Authorization", credentials: true, response: "Access-Control-Allow-Origin: *\nAccess-Control-Allow-Methods: GET", preflightStatus: 204 },
    { origin: "https://app.io", method: "GET", requestHeaders: "", credentials: false, response: "Access-Control-Allow-Origin: https://app.io" },
    { origin: "https://app.io", method: "POST", requestHeaders: "Content-Type: application/json, X-Trace", credentials: true, response: "HTTP/1.1 200 OK\nAccess-Control-Allow-Origin: https://app.io\nAccess-Control-Allow-Origin: https://app.io\nAccess-Control-Allow-Credentials: True\nAccess-Control-Allow-Headers: *", preflightStatus: 301 },
    { origin: "https://app.io", method: "DELETE", requestHeaders: "Authorization, X-A", credentials: false, response: "Access-Control-Allow-Origin: https://app.io\nVary: Origin\nAccess-Control-Allow-Methods: *\nAccess-Control-Allow-Headers: *", preflightStatus: 200 },
    { origin: "https://app.io", method: "POST", requestHeaders: "Content-Type: text/plain", credentials: false, response: "Access-Control-Allow-Origin: https://other.io" },
    { origin: "https://app.io", method: "patch", requestHeaders: "x-a", credentials: true, response: "Access-Control-Allow-Origin: https://app.io, https://b.io\nAccess-Control-Allow-Credentials: true\nAccess-Control-Allow-Methods: GET, PATCH\nAccess-Control-Allow-Headers: X-A", preflightStatus: 204 },
    { origin: "https://app.io", method: "GET", requestHeaders: "", credentials: false, response: "" }
  ];
  for (const c of checks) await add("web.cors", { mode: "check", ...c }, `cors check: ${c.method} ${c.response.slice(0, 40)}`);
  const corsErrors = [
    "No 'Access-Control-Allow-Origin' header is present on the requested resource.",
    "The value of the 'Access-Control-Allow-Origin' header in the response must not be the wildcard '*' when the request's credentials mode is 'include'.",
    "Redirect is not allowed for a preflight request. Request header field x-api-key is not allowed by Access-Control-Allow-Headers in preflight response.",
    "Method PATCH is not allowed by Access-Control-Allow-Methods in preflight response.",
    "The 'Access-Control-Allow-Origin' header contains multiple values 'a, b', but only one is allowed.",
    "has a value 'http://127.0.0.1:3000' that is not equal to the supplied origin.",
    "TypeError: Failed to fetch",
    "net::ERR_BLOCKED_BY_ORB",
    "Mixed Content: The page was loaded over HTTPS",
    "Permission was denied for this request to access the `unknown` address space.",
    "something unrelated"
  ];
  for (const error of corsErrors) await add("web.cors", { mode: "explain", error }, `cors explain: ${error.slice(0, 50)}`);
  await add("web.cors", { mode: "check", origin: "" }, "cors check: no origin");

  // OpenAPI / Swagger Toolkit.
  const swagger2 = "swagger: \"2.0\"\ninfo:\n  title: Pets\n  version: 1.0.0\nhost: petstore.example.com\nbasePath: /v2\nsecurityDefinitions:\n  key: { type: apiKey, in: header, name: X-Key }\npaths:\n  /pets:\n    get:\n      parameters:\n        - name: limit\n          in: query\n          type: integer\n          required: true\n        - name: X-Trace\n          in: header\n          type: string\n      responses:\n        200:\n          description: ok\n          schema:\n            type: array\n            items: { $ref: '#/definitions/Pet' }\n    post:\n      operationId: addPet\n      parameters:\n        - in: body\n          name: body\n          required: true\n          schema: { $ref: '#/definitions/Pet' }\n        - in: formData\n          name: ignored\n          type: string\n      responses:\n        201: { description: created }\n  /pets/{petId}:\n    parameters:\n      - name: petId\n        in: path\n        type: string\n    delete:\n      operationId: addPet\n      deprecated: true\n      responses:\n        204: { description: gone }\n    get:\n      summary: |\n        Find a pet\n        by id\n      responses:\n        default: { description: whatever }\ndefinitions:\n  Pet:\n    type: object\n    description: A pet */ here\n    required: [name]\n    properties:\n      name: { type: string, description: \"The name\" }\n      tag: { type: string }\n      photo: { type: string, format: binary }\n      kind: { type: [string, \"null\"] }\n      extra: { type: object, additionalProperties: { type: integer } }\n      any: { type: object, additionalProperties: true }\n      mixed: { type: object, properties: { a: { type: number } }, additionalProperties: true }\n      \"weird-key\": { const: 5 }\n      both: { oneOf: [{ type: string }, { type: integer }] }\n      matrix: { type: array, items: { anyOf: [{ type: string }, { type: boolean }] } }\n  \"2xx Error\":\n    type: string\n    enum: [a, b]\n    nullable: true\n";
  const jsonSpec = JSON.stringify({ openapi: "3.0.3", info: { title: "J", version: "2" }, paths: { "/a/{id}": { parameters: [{ $ref: "#/components/parameters/Id" }], put: { requestBody: { $ref: "#/components/requestBodies/Body" }, responses: { "200": { $ref: "#/components/responses/Ok" } } }, get: { requestBody: { content: { "text/plain": { schema: { type: "string" } } } }, responses: { "500": { description: "err" } } } }, "/b": { post: { responses: {} } }, "/c": { $ref: "#/components/pathItems/C" } }, components: { parameters: { Id: { name: "id", in: "path", schema: { type: "integer" } } }, requestBodies: { Body: { required: true, content: { "application/xml": { schema: { type: "object" } }, "application/merge-patch+json": { schema: { $ref: "#/components/schemas/Missing" } } } } }, responses: { Ok: { description: "ok", content: { "application/json": { schema: { type: "object", properties: { n: { type: "number", example: 3.5 } } } } } } } } });
  const broken = "openapi: 3.0.0\ninfo: { title: x, version: '1' }\npaths:\n  /a/{id}:\n    get:\n      responses: { '200': { description: ok } }";
  for (const spec of [swagger2, jsonSpec, broken]) for (const mode of ["endpoints", "types", "client", "curl"]) await add("web.openapi", { mode, spec }, `openapi ${mode}: ${spec.slice(0, 30)}`);
  for (const spec of ["", "info: {}", "openapi: [unclosed", "- a\n- b", "{\"openapi\": \"3.1.0\", bad}", "openapi: 3.0.0\npaths: {}\nanchors:\n  a: &x { k: 1 }\n  b: *x\nflag: yes\ndate: 2026-01-15\noct: 0o17\nhex: 0x1F\nn: ~\nkey: on"]) for (const mode of ["endpoints", "types"]) await add("web.openapi", { mode, spec }, `openapi error ${mode}: ${spec.slice(0, 30)}`);
  const samples = [
    { method: "GET", path: "/users/:userId/orders", status: "200", requestJson: "", responseJson: "[{\"id\":1,\"total\":9.5,\"when\":\"2026-01-15T09:30:00Z\",\"email\":\"a@b.io\",\"site\":\"https://x.io\",\"tags\":[\"a\"],\"n\":null},{\"id\":2,\"total\":3,\"extra\":true}]" },
    { method: "PUT", path: "/items/{id}", status: "", requestJson: "{\"name\":\"x\",\"nested\":{\"deep\":[1,2.5]},\"mixed\":[1,\"a\",{\"k\":1}],\"day\":\"2026-01-15\",\"uuid\":\"3fa85f64-5717-4562-b3fc-2c963f66afa6\"}", responseJson: "" },
    { method: "DELETE", path: "/x", status: "204", requestJson: "", responseJson: "" },
    { method: "POST", path: "/bad", status: "201", requestJson: "{bad", responseJson: "{}" },
    { method: "POST", path: "/empty-array", status: "200", requestJson: "", responseJson: "[]" },
    { method: "PATCH", path: "/s", status: "200", requestJson: "\"just a string\"", responseJson: "{\"multi\":\"line\\nvalue\",\"quote\":\"he said \\\"hi\\\": ok\",\"hash\":\"a #b\",\"num\":\"123\",\"bool\":\"true\",\"lead\":\"-x\",\"empty\":\"\",\"colon\":\"a: b\",\"star\":\"*x\",\"pct\":\"%p\",\"brace\":\"{x}\",\"sp\":\" pad \",\"null\":\"null\",\"tilde\":\"~\",\"float\":\"1.5e3\",\"date\":\"2026-01-15\",\"url\":\"https://a.io/x?y=1#z\",\"unicode\":\"é😀\"}" }
  ];
  for (const s of samples) await add("web.openapi", { mode: "from-sample", ...s }, `from-sample: ${s.method} ${s.path}`);

  // GraphQL Formatter & Types.
  const queries2 = [
    "query Q($id: ID!, $n: Int = 5) { user(id: $id) { id ...F posts(first: $n, where: {a: 1, b: [1, 2]}) { title } } } fragment F on User { name }",
    "{ me { id } }",
    "mutation Create($input: CreatePostInput!, $draft: Boolean = true, $title: String = \"Hello\") { createPost(input: $input, draft: $draft) @include(if: true) { id ... on Post { title } } }",
    "query A { a } query B { b }",
    "query { a } { b }",
    "# comment\nquery Q { a # trailing\n b }",
    "subscription OnEvent($ids: [ID!]!) { event(ids: $ids) { id payload } }",
    "query Q { ...Missing a }",
    "query Q { a } fragment Unused on X { b }",
    "query Q($f: Float = 1.5, $l: [String] = [\"a\"]) { search(text: \"\\\"quoted\\\"\", limit: -3, filter: {nested: {deep: [{x: 1}, {y: $f}]}}) { __typename } }",
    "query Q { a(x: \"\"\"block\nstring\"\"\") { b } }",
    "{ a { b }",
    "query Q { a(x: \"unterminated) }",
    "query Q { a } ]",
    "query Q { a(x: 1) % }"
  ];
  for (const query of queries2) await add("web.graphql", { mode: "query", query }, `graphql: ${query.slice(0, 50)}`);
  await add("web.graphql", { mode: "query", query: "{ a }", endpoint: "http://localhost:4000/gql" }, "graphql: endpoint");
  const sdls = [
    "schema { query: Query }\ndirective @auth(role: String) on FIELD_DEFINITION | OBJECT\ntype Query { users(first: Int = 10, after: String): [User!]! @auth(role: \"admin\") node(id: ID!): Node }\n\"\"\"\nMultiline\ndescription */\n\"\"\"\ntype User implements Node & Entity @key(fields: \"id\") { id: ID! \"the name\" name: String matrix: [[Int]] }\ninterface Node { id: ID! }\nextend type User { extra: Boolean! }\nenum Color @flag { RED \"desc\" GREEN @deprecated(reason: \"x\") BLUE }\ninput Filter { color: Color = RED tags: [String!] required: Int! }\nunion U = | A | B\nscalar JSON @specifiedBy(url: \"https://x.io\")",
    "type Empty",
    "# only a comment",
    "type Broken { a: String"
  ];
  for (const sdl of sdls) await add("web.graphql", { mode: "sdl", sdl }, `sdl: ${sdl.slice(0, 40)}`);

  // API Resource Scaffolder: every framework, database and field type.
  const allFields = "title: string!\nslug: string! unique\nbody: text\nviews: int\nprice: number!\nactive: boolean!\npublishedAt: date\ncontact: email unique\nsite: url\nref: uuid!\nstatus: enum(draft|live|a.b)!\ntags: string[]\nscores: number[]!\nmeta: json";
  for (const framework of ["express", "nestjs", "next", "fastify"]) for (const db of ["prisma", "mongoose", "memory"]) await add("web.api-scaffold", { resource: "blogPost", framework, db, fields: allFields }, `scaffold ${framework}/${db}`);
  for (const framework of ["spring", "aspnet", "fastapi"]) for (const sqlDb of ["postgres", "mysql", "sqlserver", "sqlite"]) await add("web.api-scaffold", { resource: "blog-entry", framework, sqlDb, fields: allFields }, `scaffold ${framework}/${sqlDb}`);
  for (const framework of ["express", "spring", "aspnet", "fastapi"]) await add("web.api-scaffold", { resource: "category", framework, fields: "name: str!, parent: uuid, note: text?, kind: enum(a/b) unique, count integer!, id: int, createdAt: date, ratio: float, at: timestamp, x: object, list: int[], t: tags, b: bool, l: uri, d: decimal, dbl: double, s2: datetime" }, `scaffold aliases ${framework}`);
  const scaffoldErrors = [
    { fields: "price number?? weird" },
    { fields: "x: weird" },
    { fields: "s: enum()" },
    { fields: "# only comment\n\n" },
    { resource: "1bad" },
    { resource: "" },
    { framework: "spring", packageName: "Com.Example" },
    { framework: "aspnet", namespace: "1Bad" },
    { framework: "spring", packageName: "com.example.shop" , resource: "box", fields: "w: number!\nh: number" },
    { framework: "aspnet", namespace: "Shop.Api", resource: "user profile", fields: "email: email! unique" },
    { framework: "fastapi", resource: "BigThing", fields: "label: string\nconfig: json!" }
  ];
  for (const s of scaffoldErrors) await add("web.api-scaffold", s, `scaffold edge: ${JSON.stringify(s).slice(0, 60)}`);

  write("src/test/resources/toolkit/golden-backend-api.json", cases);
  console.log(`Exported ${descriptors.length} tools and ${cases.length} reference cases (${cases.filter(c => c.error).length} errors).`);
}

function write(rel, data) {
  const file = path.join(root, rel);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(data, null, 1) + "\n");
}

main().catch(e => {
  console.error(e);
  process.exit(1);
});
