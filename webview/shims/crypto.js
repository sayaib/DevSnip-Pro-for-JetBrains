/*
 * The subset of Node's `crypto` module the DevSnip Pro toolkit uses, for the
 * JetBrains plugin's embedded browser (JCEF). Synchronous, like Node:
 * hashes and HMAC (@noble/hashes), random values (Web Crypto), keys from
 * PEM / JWK, RSA PKCS#1 v1.5 and PSS (node-forge), ECDSA P-256/384/521 and
 * Ed25519 (@noble/curves), and X.509 certificate details.
 *
 * `npm test` in this folder checks the toolkit's results with this shim
 * against the same toolkit running on real Node.
 */
import { Buffer } from "buffer";
import forge from "node-forge";
import { sha1 } from "@noble/hashes/sha1";
import { ripemd160 } from "@noble/hashes/ripemd160";
import { sha224, sha256 } from "@noble/hashes/sha256";
import { sha384, sha512, sha512_224, sha512_256 } from "@noble/hashes/sha512";
import { sha3_224, sha3_256, sha3_384, sha3_512 } from "@noble/hashes/sha3";
import { hmac } from "@noble/hashes/hmac";
import { p256 } from "@noble/curves/p256";
import { p384 } from "@noble/curves/p384";
import { p521 } from "@noble/curves/p521";
import { ed25519 } from "@noble/curves/ed25519";

/** MD5 (not in @noble/hashes) through node-forge, with the same create/update/digest shape. */
function md5Create() {
  const md = forge.md.md5.create();
  return {
    update(data) {
      md.update(toBinary(data));
      return this;
    },
    digest: () => fromBinary(md.digest().getBytes())
  };
}
const md5 = Object.assign(data => md5Create().update(data).digest(), { create: md5Create, outputLen: 16, blockLen: 64 });

const HASHES = {
  md5, sha1, ripemd160, rmd160: ripemd160, "ripemd-160": ripemd160,
  sha224, sha256, sha384, sha512, "sha512-224": sha512_224, "sha512-256": sha512_256,
  "sha3-224": sha3_224, "sha3-256": sha3_256, "sha3-384": sha3_384, "sha3-512": sha3_512
};

function hashFn(algorithm) {
  const name = String(algorithm).toLowerCase().replace(/^rsa-/, "");
  const fn = HASHES[name];
  if (!fn) throw new Error("Digest method not supported");
  return fn;
}

function bytes(data, encoding) {
  if (typeof data === "string") return new Uint8Array(Buffer.from(data, encoding || "utf8"));
  if (data instanceof Uint8Array) return data;
  if (ArrayBuffer.isView(data)) return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
  if (data instanceof ArrayBuffer) return new Uint8Array(data);
  throw new TypeError('The "data" argument must be of type string or an instance of Buffer, TypedArray, or DataView.');
}

function output(raw, encoding) {
  const buf = Buffer.from(raw);
  return encoding ? buf.toString(encoding) : buf;
}

class Hash {
  constructor(fn, key) {
    if (key !== undefined && fn === md5) {
      // noble's HMAC needs internals the forge-backed MD5 lacks; use forge's HMAC.
      const h = forge.hmac.create();
      h.start("md5", toBinary(key));
      this.state = { update: data => h.update(toBinary(data)), digest: () => fromBinary(h.digest().getBytes()) };
      return;
    }
    this.state = key === undefined ? fn.create() : hmac.create(fn, key);
  }
  update(data, encoding) {
    this.state.update(bytes(data, encoding));
    return this;
  }
  digest(encoding) {
    return output(this.state.digest(), encoding);
  }
}

export function createHash(algorithm) {
  return new Hash(hashFn(algorithm));
}

export function createHmac(algorithm, key) {
  const k = key instanceof KeyObject ? key.secret : bytes(key);
  return new Hash(hashFn(algorithm), k);
}

export function getHashes() {
  return Object.keys(HASHES).filter(n => !["rmd160", "ripemd-160"].includes(n)).concat(["RSA-SHA256", "RSA-SHA1", "RSA-SHA512"]);
}

export function randomBytes(size, callback) {
  const out = new Uint8Array(size);
  for (let i = 0; i < size; i += 65536) globalThis.crypto.getRandomValues(out.subarray(i, Math.min(size, i + 65536)));
  const buf = Buffer.from(out);
  if (callback) {
    callback(null, buf);
    return undefined;
  }
  return buf;
}

export function randomUUID() {
  return globalThis.crypto.randomUUID();
}

export function randomInt(min, max) {
  if (max === undefined) {
    max = min;
    min = 0;
  }
  const range = max - min;
  const buf = randomBytes(6);
  return min + (buf.readUIntBE(0, 6) % range);
}

export function timingSafeEqual(a, b) {
  const x = bytes(a);
  const y = bytes(b);
  if (x.length !== y.length) throw new RangeError("Input buffers must have the same byte length");
  let diff = 0;
  for (let i = 0; i < x.length; i++) diff |= x[i] ^ y[i];
  return diff === 0;
}

export const constants = { RSA_PKCS1_PADDING: 1, RSA_NO_PADDING: 3, RSA_PKCS1_OAEP_PADDING: 4, RSA_PKCS1_PSS_PADDING: 6, RSA_PSS_SALTLEN_DIGEST: -1, RSA_PSS_SALTLEN_MAX_SIGN: -2, RSA_PSS_SALTLEN_AUTO: -2 };

// ---------------------------------------------------------------------------
// ASN.1 / DER helpers (node-forge's parser works on binary strings)
// ---------------------------------------------------------------------------

const asn1 = forge.asn1;
const toBinary = u8 => forge.util.binary.raw.encode(u8);
const fromBinary = s => Uint8Array.from(forge.util.binary.raw.decode(s));
const OID = {
  rsa: "1.2.840.113549.1.1.1",
  rsaPss: "1.2.840.113549.1.1.10",
  ec: "1.2.840.10045.2.1",
  ed25519: "1.3.101.112",
  p256: "1.2.840.10045.3.1.7",
  p384: "1.3.132.0.34",
  p521: "1.3.132.0.35",
  basicConstraints: "2.5.29.19",
  subjectAltName: "2.5.29.17"
};
const CURVES = {
  [OID.p256]: { name: "prime256v1", jwk: "P-256", curve: p256, size: 32 },
  [OID.p384]: { name: "secp384r1", jwk: "P-384", curve: p384, size: 48 },
  [OID.p521]: { name: "secp521r1", jwk: "P-521", curve: p521, size: 66 }
};
const CURVE_BY_JWK = Object.fromEntries(Object.entries(CURVES).map(([oid, c]) => [c.jwk, { ...c, oid }]));

function der(node) {
  return fromBinary(asn1.toDer(node).getBytes());
}

function parseDer(u8) {
  // decodeBitStrings: false keeps BIT STRINGs (keys) as bytes instead of parsing the DER inside them.
  return asn1.fromDer(forge.util.createBuffer(toBinary(u8)), { strict: false, parseAllBytes: false, decodeBitStrings: false });
}

function intBytes(node) {
  let v = node.value;
  while (v.length > 1 && v.charCodeAt(0) === 0) v = v.slice(1);
  return fromBinary(v);
}

const bigOf = u8 => new forge.jsbn.BigInteger(Buffer.from(u8).toString("hex") || "0", 16);
const b64u = u8 => Buffer.from(u8).toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const fromB64u = s => new Uint8Array(Buffer.from(String(s).replace(/-/g, "+").replace(/_/g, "/"), "base64"));

function pemBlocks(text) {
  const m = /-----BEGIN ([A-Z0-9 ]+)-----([\s\S]*?)-----END \1-----/.exec(String(text));
  if (!m) return null;
  return { label: m[1], der: new Uint8Array(Buffer.from(m[2].replace(/[^A-Za-z0-9+/=]/g, ""), "base64")) };
}

// ---------------------------------------------------------------------------
// Keys
// ---------------------------------------------------------------------------

export class KeyObject {
  constructor(type, keyType, material) {
    this.type = type;
    this.asymmetricKeyType = keyType;
    this.material = material;
  }

  get asymmetricKeyDetails() {
    const m = this.material;
    if (this.asymmetricKeyType === "rsa") return { modulusLength: m.n.length * 8 - leadingZeroBits(m.n[0]), publicExponent: BigInt("0x" + (Buffer.from(m.e).toString("hex") || "0")) };
    if (this.asymmetricKeyType === "ec") return { namedCurve: m.curve.name };
    return {};
  }

  get symmetricKeySize() {
    return this.type === "secret" ? this.secret.length : undefined;
  }

  export(options = {}) {
    if (options.format === "jwk") return this.jwk();
    if (this.type === "public" && options.type === "spki") {
      const d = this.material.spki || spkiDer(this);
      return options.format === "pem" ? toPem("PUBLIC KEY", d) : Buffer.from(d);
    }
    throw new Error(`Exporting ${this.type} keys as ${options.type}/${options.format} is not supported here.`);
  }

  jwk() {
    const m = this.material;
    if (this.asymmetricKeyType === "rsa") {
      const out = { kty: "RSA", n: b64u(m.n), e: b64u(m.e) };
      if (this.type === "private") Object.assign(out, { d: b64u(m.d), p: b64u(m.p), q: b64u(m.q), dp: b64u(m.dp), dq: b64u(m.dq), qi: b64u(m.qi) });
      return out;
    }
    if (this.asymmetricKeyType === "ec") {
      const pub = m.curve.curve.ProjectivePoint.fromHex(m.publicPoint).toRawBytes(false);
      const out = { kty: "EC", x: b64u(pub.slice(1, 1 + m.curve.size)), y: b64u(pub.slice(1 + m.curve.size)), crv: m.curve.jwk };
      if (this.type === "private") out.d = b64u(m.d);
      return out;
    }
    if (this.asymmetricKeyType === "ed25519") {
      const out = { crv: "Ed25519", x: b64u(m.publicKey), kty: "OKP" };
      if (this.type === "private") out.d = b64u(m.seed);
      return out;
    }
    throw new Error("Unsupported key");
  }
}

function leadingZeroBits(byte) {
  let n = 0;
  for (let mask = 0x80; mask && !(byte & mask); mask >>= 1) n++;
  return n;
}

function toPem(label, u8) {
  const b64 = Buffer.from(u8).toString("base64");
  return `-----BEGIN ${label}-----\n${b64.match(/.{1,64}/g).join("\n")}\n-----END ${label}-----\n`;
}

function rsaPublic(n, e) {
  return new KeyObject("public", "rsa", { n, e });
}

function ecPublic(curve, point) {
  curve.curve.ProjectivePoint.fromHex(point); // validates the point
  return new KeyObject("public", "ec", { curve, publicPoint: point });
}

function publicOf(key) {
  if (key.type === "public") return key;
  const m = key.material;
  if (key.asymmetricKeyType === "rsa") return rsaPublic(m.n, m.e);
  if (key.asymmetricKeyType === "ec") return new KeyObject("public", "ec", { curve: m.curve, publicPoint: m.publicPoint });
  return new KeyObject("public", "ed25519", { publicKey: m.publicKey });
}

function spkiDer(key) {
  const m = key.material;
  let algorithm;
  let bits;
  if (key.asymmetricKeyType === "rsa") {
    algorithm = asn1.create(asn1.Class.UNIVERSAL, asn1.Type.SEQUENCE, true, [asn1.create(asn1.Class.UNIVERSAL, asn1.Type.OID, false, asn1.oidToDer(OID.rsa).getBytes()), asn1.create(asn1.Class.UNIVERSAL, asn1.Type.NULL, false, "")]);
    bits = der(asn1.create(asn1.Class.UNIVERSAL, asn1.Type.SEQUENCE, true, [derInt(m.n), derInt(m.e)]));
  } else if (key.asymmetricKeyType === "ec") {
    algorithm = asn1.create(asn1.Class.UNIVERSAL, asn1.Type.SEQUENCE, true, [asn1.create(asn1.Class.UNIVERSAL, asn1.Type.OID, false, asn1.oidToDer(OID.ec).getBytes()), asn1.create(asn1.Class.UNIVERSAL, asn1.Type.OID, false, asn1.oidToDer(curveOid(m.curve)).getBytes())]);
    bits = m.curve.curve.ProjectivePoint.fromHex(m.publicPoint).toRawBytes(false);
  } else {
    algorithm = asn1.create(asn1.Class.UNIVERSAL, asn1.Type.SEQUENCE, true, [asn1.create(asn1.Class.UNIVERSAL, asn1.Type.OID, false, asn1.oidToDer(OID.ed25519).getBytes())]);
    bits = m.publicKey;
  }
  return der(asn1.create(asn1.Class.UNIVERSAL, asn1.Type.SEQUENCE, true, [algorithm, asn1.create(asn1.Class.UNIVERSAL, asn1.Type.BITSTRING, false, "\x00" + toBinary(bits))]));
}

function curveOid(curve) {
  return Object.keys(CURVES).find(oid => CURVES[oid] === curve);
}

function derInt(u8) {
  let s = toBinary(u8);
  if (s.length && s.charCodeAt(0) & 0x80) s = "\x00" + s;
  return asn1.create(asn1.Class.UNIVERSAL, asn1.Type.INTEGER, false, s || "\x00");
}

/** SubjectPublicKeyInfo DER → public KeyObject (exported again exactly as read). */
function fromSpki(u8) {
  const key = readSpki(u8);
  key.material.spki = u8;
  return key;
}

function readSpki(u8) {
  const seq = parseDer(u8);
  const oid = asn1.derToOid(seq.value[0].value[0].value);
  const bits = fromBinary(seq.value[1].value).slice(1);
  if (oid === OID.rsa || oid === OID.rsaPss) {
    const inner = parseDer(bits);
    return rsaPublic(intBytes(inner.value[0]), intBytes(inner.value[1]));
  }
  if (oid === OID.ec) {
    const params = seq.value[0].value[1];
    const curve = params.type === asn1.Type.OID ? CURVES[asn1.derToOid(params.value)] : curveFromExplicit(params);
    if (!curve) throw new Error("Unsupported elliptic curve");
    return ecPublic(curve, bits);
  }
  if (oid === OID.ed25519) return new KeyObject("public", "ed25519", { publicKey: bits });
  throw new Error("Unsupported key type");
}

/**
 * Explicit EC parameters (old OpenSSL/LibreSSL output): the curve is found by
 * its field prime (OpenSSL 3, under Node, recognises it the same way).
 */
function curveFromExplicit(params) {
  const fieldId = params.value && params.value[1];
  const prime = fieldId && fieldId.value && fieldId.value[1] ? intBytes(fieldId.value[1]) : null;
  if (!prime) return undefined;
  const hex = Buffer.from(prime).toString("hex");
  for (const known of Object.values(CURVES)) {
    if (known.curve.CURVE.p.toString(16) === hex) return known;
  }
  return undefined;
}

function rsaPrivateFromSeq(seq) {
  const v = seq.value.map(intBytes);
  return new KeyObject("private", "rsa", { n: v[1], e: v[2], d: v[3], p: v[4], q: v[5], dp: v[6], dq: v[7], qi: v[8] });
}

function ecPrivateFromSec1(seq, curveFromParams) {
  const d = fromBinary(seq.value[1].value);
  let curve = curveFromParams;
  for (const part of seq.value.slice(2)) {
    if (part.type === 0 && part.value[0]) curve = CURVES[asn1.derToOid(part.value[0].value)] ?? curve;
  }
  if (!curve) throw new Error("Unsupported elliptic curve");
  return new KeyObject("private", "ec", { curve, d, publicPoint: curve.curve.getPublicKey(d, false) });
}

/** PKCS#8 PrivateKeyInfo DER → private KeyObject. */
function fromPkcs8(u8) {
  const seq = parseDer(u8);
  const oid = asn1.derToOid(seq.value[1].value[0].value);
  const inner = fromBinary(seq.value[2].value);
  if (oid === OID.rsa || oid === OID.rsaPss) return rsaPrivateFromSeq(parseDer(inner));
  if (oid === OID.ec) return ecPrivateFromSec1(parseDer(inner), CURVES[asn1.derToOid(seq.value[1].value[1].value)]);
  if (oid === OID.ed25519) {
    const seed = fromBinary(parseDer(inner).value);
    return new KeyObject("private", "ed25519", { seed, publicKey: ed25519.getPublicKey(seed) });
  }
  throw new Error("Unsupported key type");
}

function fromPem(text, kind) {
  const block = pemBlocks(text);
  if (!block) throw new Error("error:1E08010C:DECODER routines::unsupported");
  switch (block.label) {
    case "PRIVATE KEY": return fromPkcs8(block.der);
    case "RSA PRIVATE KEY": return rsaPrivateFromSeq(parseDer(block.der));
    case "EC PRIVATE KEY": return ecPrivateFromSec1(parseDer(block.der));
    case "PUBLIC KEY": return fromSpki(block.der);
    case "RSA PUBLIC KEY": {
      const seq = parseDer(block.der);
      return rsaPublic(intBytes(seq.value[0]), intBytes(seq.value[1]));
    }
    case "CERTIFICATE":
      if (kind === "public") return new X509Certificate(text).publicKey;
      break;
    case "ENCRYPTED PRIVATE KEY":
      throw new Error("error:07880109:common libcrypto routines::interrupted or cancelled");
  }
  throw new Error("error:1E08010C:DECODER routines::unsupported");
}

function fromJwk(jwk, kind) {
  if (!jwk || typeof jwk !== "object") throw new TypeError('The "key.key" property must be of type object.');
  if (jwk.kty === "RSA") {
    if (kind === "private") {
      if (!jwk.d) throw new TypeError('The "key.d" property must be of type string.');
      const f = ["n", "e", "d", "p", "q", "dp", "dq", "qi"].map(k => fromB64u(jwk[k] ?? ""));
      return new KeyObject("private", "rsa", { n: f[0], e: f[1], d: f[2], p: f[3], q: f[4], dp: f[5], dq: f[6], qi: f[7] });
    }
    return rsaPublic(fromB64u(jwk.n), fromB64u(jwk.e));
  }
  if (jwk.kty === "EC") {
    const curve = CURVE_BY_JWK[jwk.crv];
    if (!curve) throw new TypeError("Unsupported JWK EC curve: " + jwk.crv + ".");
    const base = CURVES[curve.oid];
    const point = new Uint8Array([4, ...fromB64u(jwk.x), ...fromB64u(jwk.y)]);
    if (kind === "private") {
      if (!jwk.d) throw new TypeError('The "key.d" property must be of type string.');
      return new KeyObject("private", "ec", { curve: base, d: fromB64u(jwk.d), publicPoint: point });
    }
    return ecPublic(base, point);
  }
  if (jwk.kty === "OKP" && jwk.crv === "Ed25519") {
    if (kind === "private") {
      if (!jwk.d) throw new TypeError('The "key.d" property must be of type string.');
      const seed = fromB64u(jwk.d);
      return new KeyObject("private", "ed25519", { seed, publicKey: ed25519.getPublicKey(seed) });
    }
    return new KeyObject("public", "ed25519", { publicKey: fromB64u(jwk.x) });
  }
  throw new TypeError(`Unsupported JWK key type "${jwk.kty}".`);
}

function keyFrom(input, kind) {
  if (input instanceof KeyObject) return kind === "public" ? publicOf(input) : input;
  if (input && typeof input === "object" && !(input instanceof Uint8Array)) {
    if (input.key instanceof KeyObject) return kind === "public" ? publicOf(input.key) : input.key;
    if (input.format === "jwk") {
      const key = fromJwk(input.key, kind === "public" && input.key && input.key.d ? "private" : kind);
      return kind === "public" ? publicOf(key) : key;
    }
    return keyFrom(input.key, kind);
  }
  const text = typeof input === "string" ? input : Buffer.from(input).toString("utf8");
  const key = fromPem(text, kind);
  if (kind === "private" && key.type !== "private") throw new Error("error:1E08010C:DECODER routines::unsupported");
  return kind === "public" ? publicOf(key) : key;
}

export function createPrivateKey(input) {
  return keyFrom(input, "private");
}

export function createPublicKey(input) {
  return keyFrom(input, "public");
}

export function createSecretKey(key, encoding) {
  const k = new KeyObject("secret", undefined, {});
  k.secret = bytes(key, encoding);
  return k;
}

// ---------------------------------------------------------------------------
// Signatures
// ---------------------------------------------------------------------------

function forgeMd(algorithm) {
  const name = String(algorithm).toLowerCase().replace(/^rsa-/, "");
  const md = { sha1: forge.md.sha1, sha256: forge.md.sha256, sha384: forge.md.sha384, sha512: forge.md.sha512, md5: forge.md.md5 }[name];
  if (!md) throw new Error("Digest method not supported");
  return md;
}

function rsaForgeKey(key) {
  const m = key.material;
  if (key.type === "private") return forge.pki.setRsaPrivateKey(bigOf(m.n), bigOf(m.e), bigOf(m.d), bigOf(m.p), bigOf(m.q), bigOf(m.dp), bigOf(m.dq), bigOf(m.qi));
  return forge.pki.setRsaPublicKey(bigOf(m.n), bigOf(m.e));
}

function options(keyOrOptions) {
  if (keyOrOptions && typeof keyOrOptions === "object" && !(keyOrOptions instanceof KeyObject) && "key" in keyOrOptions) return keyOrOptions;
  return { key: keyOrOptions };
}

function pss(algorithm, saltLength) {
  const md = forgeMd(algorithm);
  return forge.pss.create({ md: md.create(), mgf: forge.mgf.mgf1.create(md.create()), saltLength });
}

export function sign(algorithm, data, keyOrOptions) {
  const o = options(keyOrOptions);
  const key = keyFrom(o.key, "private");
  const message = bytes(data);
  if (key.asymmetricKeyType === "ed25519") return Buffer.from(ed25519.sign(message, key.material.seed));
  if (key.asymmetricKeyType === "ec") {
    const digest = hashFn(algorithm || "sha256")(message);
    const signature = key.material.curve.curve.sign(digest, key.material.d, { lowS: false, prehash: false });
    return Buffer.from(o.dsaEncoding === "ieee-p1363" ? signature.toCompactRawBytes() : signature.toDERRawBytes());
  }
  const md = forgeMd(algorithm || "sha256").create();
  md.update(toBinary(message));
  const forgeKey = rsaForgeKey(key);
  const signature = o.padding === constants.RSA_PKCS1_PSS_PADDING ? forgeKey.sign(md, pss(algorithm, o.saltLength ?? md.digestLength)) : forgeKey.sign(md);
  return Buffer.from(fromBinary(signature));
}

export function verify(algorithm, data, keyOrOptions, signature) {
  const o = options(keyOrOptions);
  const key = keyFrom(o.key, "public");
  const message = bytes(data);
  const sig = bytes(signature);
  if (key.asymmetricKeyType === "ed25519") {
    try {
      return ed25519.verify(sig, message, key.material.publicKey);
    } catch {
      return false;
    }
  }
  if (key.asymmetricKeyType === "ec") {
    const digest = hashFn(algorithm || "sha256")(message);
    try {
      const curve = key.material.curve.curve;
      const parsed = o.dsaEncoding === "ieee-p1363" ? curve.Signature.fromCompact(sig) : curve.Signature.fromDER(sig);
      return curve.verify(parsed, digest, key.material.publicPoint, { lowS: false, prehash: false });
    } catch {
      return false;
    }
  }
  const md = forgeMd(algorithm || "sha256").create();
  md.update(toBinary(message));
  try {
    const forgeKey = rsaForgeKey(key);
    return o.padding === constants.RSA_PKCS1_PSS_PADDING
      ? forgeKey.verify(md.digest().getBytes(), toBinary(sig), pss(algorithm, o.saltLength ?? md.digestLength))
      : forgeKey.verify(md.digest().getBytes(), toBinary(sig));
  } catch {
    return false;
  }
}

// ---------------------------------------------------------------------------
// X.509 certificates (the fields Node's X509Certificate exposes)
// ---------------------------------------------------------------------------

const NAME_KEYS = {
  "2.5.4.3": "CN", "2.5.4.6": "C", "2.5.4.7": "L", "2.5.4.8": "ST", "2.5.4.10": "O", "2.5.4.11": "OU", "2.5.4.5": "serialNumber",
  "2.5.4.9": "street", "2.5.4.12": "title", "2.5.4.42": "GN", "2.5.4.4": "SN", "2.5.4.17": "postalCode", "2.5.4.15": "businessCategory",
  "1.2.840.113549.1.9.1": "emailAddress", "0.9.2342.19200300.100.1.25": "DC", "0.9.2342.19200300.100.1.1": "UID",
  "1.3.6.1.4.1.311.60.2.1.3": "jurisdictionC", "1.3.6.1.4.1.311.60.2.1.2": "jurisdictionST", "1.3.6.1.4.1.311.60.2.1.1": "jurisdictionL"
};
const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

function nameString(seq) {
  const parts = [];
  for (const rdn of seq.value) {
    for (const atv of rdn.value) {
      const oid = asn1.derToOid(atv.value[0].value);
      const raw = atv.value[1].value;
      const text = atv.value[1].type === asn1.Type.BMPSTRING ? Buffer.from(fromBinary(raw)).swap16().toString("utf16le") : forge.util.decodeUtf8(raw);
      parts.push(`${NAME_KEYS[oid] ?? oid}=${text}`);
    }
  }
  return parts.join("\n");
}

function asnTime(node) {
  const s = node.value;
  const utc = node.type === asn1.Type.UTCTIME;
  const year = utc ? (Number(s.slice(0, 2)) >= 50 ? 1900 : 2000) + Number(s.slice(0, 2)) : Number(s.slice(0, 4));
  const r = utc ? s.slice(2) : s.slice(4);
  const d = new Date(Date.UTC(year, Number(r.slice(0, 2)) - 1, Number(r.slice(2, 4)), Number(r.slice(4, 6)), Number(r.slice(6, 8)), Number(r.slice(8, 10) || 0)));
  const pad = n => String(n).padStart(2, "0");
  return `${MONTHS[d.getUTCMonth()]} ${String(d.getUTCDate()).padStart(2, " ")} ${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:${pad(d.getUTCSeconds())} ${d.getUTCFullYear()} GMT`;
}

function fingerprint(fn, u8) {
  return Buffer.from(fn(u8)).toString("hex").toUpperCase().match(/.{2}/g).join(":");
}

export class X509Certificate {
  constructor(input) {
    const text = typeof input === "string" ? input : Buffer.from(input).toString("latin1");
    const block = pemBlocks(text);
    const raw = block ? block.der : bytes(input);
    let cert;
    try {
      cert = parseDer(raw);
      if (!cert.value || !cert.value[0] || !cert.value[0].value) throw new Error("bad");
    } catch {
      throw new Error("error:0680007B:asn1 encoding routines::header too long");
    }
    this.raw = Buffer.from(raw);
    const tbs = cert.value[0].value;
    let i = 0;
    if (tbs[0].tagClass === asn1.Class.CONTEXT_SPECIFIC && tbs[0].type === 0) i = 1;
    this.serialNumber = Buffer.from(intBytes(tbs[i])).toString("hex").toUpperCase();
    const validity = tbs[i + 3];
    this.issuer = nameString(tbs[i + 2]);
    this.validFrom = asnTime(validity.value[0]);
    this.validTo = asnTime(validity.value[1]);
    this.subject = nameString(tbs[i + 4]);
    this.publicKey = fromSpki(der(tbs[i + 5]));
    this.fingerprint = fingerprint(sha1, raw);
    this.fingerprint256 = fingerprint(sha256, raw);
    this.fingerprint512 = fingerprint(sha512, raw);
    this.ca = false;
    this.subjectAltName = undefined;
    const extensions = tbs.find(n => n.tagClass === asn1.Class.CONTEXT_SPECIFIC && n.type === 3);
    for (const ext of extensions ? extensions.value[0].value : []) {
      const oid = asn1.derToOid(ext.value[0].value);
      const value = ext.value[ext.value.length - 1].value;
      if (oid === OID.basicConstraints) {
        const bc = parseDer(fromBinary(value));
        this.ca = !!(bc.value[0] && bc.value[0].type === asn1.Type.BOOLEAN && bc.value[0].value.charCodeAt(0));
      } else if (oid === OID.subjectAltName) {
        const names = parseDer(fromBinary(value)).value.map(gn => {
          if (gn.type === 2) return `DNS:${gn.value}`;
          if (gn.type === 1) return `email:${gn.value}`;
          if (gn.type === 6) return `URI:${gn.value}`;
          if (gn.type === 7) {
            const ip = fromBinary(gn.value);
            return `IP Address:${ip.length === 4 ? Array.from(ip).join(".") : Buffer.from(ip).toString("hex").toUpperCase().match(/.{4}/g).map(h => h.replace(/^0+(?=.)/, "")).join(":")}`;
          }
          return null;
        }).filter(Boolean);
        this.subjectAltName = names.join(", ");
      }
    }
  }

  toString() {
    return toPem("CERTIFICATE", this.raw);
  }
}

export default {
  createHash, createHmac, getHashes, randomBytes, randomUUID, randomInt, timingSafeEqual, constants,
  KeyObject, createPrivateKey, createPublicKey, createSecretKey, sign, verify, X509Certificate,
  webcrypto: globalThis.crypto
};
