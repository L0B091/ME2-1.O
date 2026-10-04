import test from "node:test";
import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import login, { hashToken } from "../auth/login.js";
import { sincronizarPerfil, validarClaimsGoogle } from "../auth/googleAuth.js";
import storage from "../utils/jsonStorage.js";
import selectorMedia from "../modulos/media/selectorMedia.js";
import { validarCuerpoChat, redactar, origenPermitido, origenesPermitidos, instalarRedaccionLogs } from "../seguridad/http.js";

process.env.DOLPHIN_BASE_URL = "";
process.env.OPENROUTER_API_KEY = "";
delete process.env.ME2_ALLOW_ANONYMOUS;
const { default: app } = await import("../server.js");

function usuarioGoogle(nombre) {
  const u = sincronizarPerfil({ sub: `sub-${crypto.randomBytes(6).toString("hex")}`, email: `${nombre}_${crypto.randomBytes(4).toString("hex")}@example.com`, email_verified: true, name: nombre });
  return { id: u.id, email: u.email, token: login.iniciarSesionParaUsuario(u.email).token };
}
async function conServidor(fn) {
  const server = app.listen(0);
  try { return await fn(`http://127.0.0.1:${server.address().port}`); } finally { server.close(); }
}
const req = (url, p, { method = "GET", token, body, headers = {} } = {}) => fetch(`${url}${p}`, {
  method, headers: { ...(body !== undefined ? { "Content-Type": "application/json" } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}), ...headers },
  body: body === undefined ? undefined : (typeof body === "string" ? body : JSON.stringify(body))
});

test("auth por defecto: todo endpoint no público exige token; solo la whitelist es pública", async () => {
  await conServidor(async url => {
    const protegidos = [
      ["GET", "/api/clima?lat=1&lon=1"], ["GET", "/api/noticias"], ["GET", "/api/premium/x"], ["GET", "/api/alarmas/x"],
      ["GET", "/api/media/normal/x"], ["PUT", "/api/premium/x/backup"], ["GET", "/api/bitacora/me"], ["GET", "/api/auth/me"],
      ["POST", "/api/mercadopago/checkout"], ["POST", "/api/mercadopago/mock/pagar"], ["GET", "/api/memoria/fiscal/x"],
      ["GET", "/api/ruta-que-no-existe"], ["POST", "/api/dev/verificacion-edad"]
    ];
    for (const [method, p] of protegidos) {
      assert.equal((await req(url, p, { method, body: method === "GET" ? undefined : {} })).status, 401, `${method} ${p}`);
    }
    for (const [method, p] of [["GET", "/health"], ["GET", "/api/hora"], ["GET", "/api/mercadopago/plan"], ["GET", "/"]]) {
      assert.equal((await req(url, p, { method })).status, 200, `${method} ${p}`);
    }
    assert.equal((await req(url, "/api/auth/google", { method: "POST", body: {} })).status, 400, "público pero valida idToken");
    assert.equal((await req(url, "/api/auth/login", { method: "POST", body: { email: "a@b.c", password: "x" } })).status, 404, "login local apagado");
  });
});

test("headers estilo helmet y /health mínimo", async () => {
  await conServidor(async url => {
    const r = await req(url, "/health");
    assert.equal(r.headers.get("x-content-type-options"), "nosniff");
    assert.equal(r.headers.get("x-frame-options"), "DENY");
    assert.equal(r.headers.get("referrer-policy"), "no-referrer");
    assert.match(r.headers.get("content-security-policy"), /default-src 'none'/);
    assert.equal(r.headers.get("cache-control"), "no-store");
    assert.equal(r.headers.get("x-powered-by"), null);
    const body = await r.json();
    assert.deepEqual(Object.keys(body).sort(), ["estado", "ok", "timestamp"]);
  });
});

test("CORS estricto: origen ajeno 403; en producción sin configurar no se permite ningún origen de navegador", async () => {
  await conServidor(async url => {
    assert.equal((await req(url, "/health", { headers: { Origin: "https://evil.example" } })).status, 403);
    const ok = await req(url, "/health", { headers: { Origin: "http://localhost:5173" } });
    assert.equal(ok.status, 200);
    assert.equal(ok.headers.get("access-control-allow-origin"), "http://localhost:5173");
  });
  const prev = { NODE_ENV: process.env.NODE_ENV, CORS: process.env.CORS_ALLOWED_ORIGINS };
  try {
    process.env.NODE_ENV = "production"; delete process.env.CORS_ALLOWED_ORIGINS;
    assert.equal(origenPermitido("http://localhost:5173", origenesPermitidos()), false);
    assert.equal(origenPermitido(undefined, origenesPermitidos()), true, "app nativa sin Origin");
    process.env.CORS_ALLOWED_ORIGINS = "https://me2.app";
    assert.equal(origenPermitido("https://me2.app", origenesPermitidos()), true);
    assert.equal(origenPermitido("https://me2.app.evil.com", origenesPermitidos()), false);
    assert.equal(origenPermitido("https://me2.app:444", origenesPermitidos()), false);
  } finally {
    process.env.NODE_ENV = prev.NODE_ENV;
    if (prev.CORS === undefined) delete process.env.CORS_ALLOWED_ORIGINS; else process.env.CORS_ALLOWED_ORIGINS = prev.CORS;
  }
});

test("validación y límites: cuerpo grande 413, mensaje largo 413, contexto solo con campos admitidos", async () => {
  const u = usuarioGoogle("limites");
  await conServidor(async url => {
    assert.equal((await req(url, "/chat", { method: "POST", token: u.token, body: { mensaje: "x".repeat(4001) } })).status, 413);
    assert.equal((await req(url, "/chat", { method: "POST", token: u.token, body: { mensaje: "hola", contexto: { relleno: "x".repeat(200 * 1024) } } })).status, 413);
    assert.equal((await req(url, "/chat", { method: "POST", token: u.token, body: "{malformado" })).status, 400);
  });
  const v = validarCuerpoChat({ mensaje: "hola", contexto: { userId: "otro", premium: { premiumActivo: true }, adultMode: { unlocked: true }, lat: "999", lon: "-58.4", zonaHoraria: "America/Argentina/Buenos_Aires", memoriaLocal: { recentConversation: Array(100).fill({ role: "user", text: "a" }) } } });
  assert.equal(v.error, undefined);
  assert.deepEqual(Object.keys(v.contexto).sort(), ["lon", "memoriaLocal", "zonaHoraria"]);
  assert.equal(v.contexto.memoriaLocal.recentConversation.length, 60);
  assert.equal(validarCuerpoChat({ mensaje: "hola", contexto: [] }).error, "Contexto inválido");
});

test("path traversal: medios y almacenamiento no salen de su raíz", async () => {
  assert.equal(selectorMedia.archivoDe("normal", "../../server"), null);
  assert.equal(selectorMedia.archivoDe("../..", "server"), null);
  const raiz = fs.mkdtempSync(path.join(process.cwd(), "data", "tmp-media-"));
  try {
    fs.mkdirSync(path.join(raiz, "normal"));
    fs.writeFileSync(path.join(raiz, "normal", "manifest.json"), JSON.stringify({ items: [
      { id: "malo", tipo: "clip", archivo: "../../package.json" }, { id: "malo2", tipo: "clip", archivo: "..\\\\x.mp4" }, { id: "bueno", tipo: "clip", archivo: "ok.mp4" }
    ] }));
    fs.writeFileSync(path.join(raiz, "normal", "ok.mp4"), "x");
    assert.equal(selectorMedia.archivoDe("normal", "malo", raiz), null);
    assert.equal(selectorMedia.archivoDe("normal", "malo2", raiz), null);
    assert.ok(selectorMedia.archivoDe("normal", "bueno", raiz)?.ruta.endsWith("ok.mp4"));
  } finally { fs.rmSync(raiz, { recursive: true, force: true }); }
  assert.equal(storage.sanitizeId("../../etc/passwd"), "______etc_passwd");
  const u = usuarioGoogle("traversal");
  await conServidor(async url => {
    assert.equal((await req(url, "/api/media/..%2f..%2fserver.js/x", { token: u.token })).status, 404);
    assert.equal((await req(url, "/api/media/normal/..%2f..%2fserver", { token: u.token })).status, 404);
  });
});

test("B4: los tokens de sesión se guardan hasheados (nunca en claro) y se migran los viejos", () => {
  const u = usuarioGoogle("hash");
  const archivo = path.join(storage.DATA_DIR, "sesiones_auth", "tokens.json");
  const disco = fs.readFileSync(archivo, "utf8");
  assert.ok(!disco.includes(u.token), "el token no está en disco");
  assert.ok(disco.includes(hashToken(u.token)));
  assert.equal(login.validarToken(u.token)?.userId, u.id);
  assert.equal(login.validarToken(u.token)?.token, undefined, "no se devuelve el token");
  assert.equal(login.validarToken(hashToken(u.token)), null, "el hash no sirve como token");
  assert.equal(login.cerrarSesion(u.token).ok, true);
  assert.equal(login.validarToken(u.token), null);
});

test("Google: iss, aud, exp y email_verified se verifican explícitamente", () => {
  const ahora = 1_800_000_000;
  const ok = { iss: "https://accounts.google.com", aud: "cliente.apps", exp: ahora + 600, sub: "1", email: "a@b.com", email_verified: true };
  assert.equal(validarClaimsGoogle(ok, ["cliente.apps"], ahora), true);
  assert.throws(() => validarClaimsGoogle({ ...ok, iss: "https://evil.com" }, ["cliente.apps"], ahora), /Emisor/);
  assert.throws(() => validarClaimsGoogle({ ...ok, aud: "otro" }, ["cliente.apps"], ahora), /Audiencia/);
  assert.throws(() => validarClaimsGoogle({ ...ok, exp: ahora - 3600 }, ["cliente.apps"], ahora), /vencido/);
  assert.throws(() => validarClaimsGoogle({ ...ok, email_verified: false }, ["cliente.apps"], ahora), /verificado/);
  assert.throws(() => validarClaimsGoogle({ ...ok, sub: "" }, ["cliente.apps"], ahora), /inválido/);
});

test("logs sin secretos ni PII", () => {
  process.env.TEST_FAKE_API_KEY = "supersecreto-123456";
  const out = redactar("user ana.perez@example.com Authorization: Bearer abc.def.ghi key=supersecreto-123456 token=" + "a".repeat(64));
  assert.ok(!out.includes("ana.perez@example.com"));
  assert.ok(!out.includes("abc.def.ghi"));
  assert.ok(!out.includes("supersecreto-123456"));
  assert.ok(!out.includes("a".repeat(64)));
  const lineas = [];
  const consola = { log: (...a) => lineas.push(a.join(" ")), info() {}, warn() {}, error: (...a) => lineas.push(a.join(" ")), debug() {} };
  instalarRedaccionLogs(consola);
  consola.error("fallo para", "x@y.com", new Error("token " + "f".repeat(40)));
  assert.ok(!lineas.join("\n").includes("x@y.com"));
  assert.ok(!lineas.join("\n").includes("f".repeat(40)));
  delete process.env.TEST_FAKE_API_KEY;
});

test("bitácora: solo campos de perfil, acotados; premium no se puede escribir desde el cliente", async () => {
  const u = usuarioGoogle("bitacora");
  await conServidor(async url => {
    const r = await req(url, "/api/bitacora/me", { method: "PATCH", token: u.token, body: {
      userName: "N".repeat(500), leyenda: "L".repeat(1000), fotoPerfil: "javascript:alert(1)", premiumUntil: Date.now() + 1e10, premiumActivo: true
    } });
    assert.equal(r.status, 200);
    const me = await (await req(url, "/api/auth/me", { token: u.token })).json();
    assert.equal(me.data.perfil.displayName.length, 60);
    assert.equal(me.data.perfil.leyenda.length, 280);
    assert.equal(me.data.perfil.photoUrl, null);
    assert.ok(!me.data.perfil.premiumUntil || me.data.perfil.premiumUntil < Date.now() + 1e9);
    assert.equal(me.data.token, undefined);
    const b = await (await req(url, "/api/bitacora/me", { token: u.token })).json();
    assert.equal(typeof b.data.nivelEnlace, "number");
    assert.equal(b.data.enlacePsicologico, undefined, "I7: sin nomenclatura psicológica");
    const p = await (await req(url, `/api/premium/${u.id}`, { token: u.token })).json();
    assert.equal(p.data.premiumActivo, false);
  });
});

test("edad bajo demanda: /api/auth/google/edad exige token, valida el código y canjea → fecha → estado (sin romper sin secret)", async () => {
  const u = usuarioGoogle("edad");
  const realFetch = globalThis.fetch;
  const secretPrevio = process.env.GOOGLE_CLIENT_SECRET;
  const idPrevio = process.env.GOOGLE_CLIENT_ID;
  try {
    await conServidor(async url => {
      assert.equal((await req(url, "/api/auth/google/edad", { method: "POST", body: { serverAuthCode: "c" } })).status, 401);
      assert.equal((await req(url, "/api/auth/google/edad", { method: "POST", token: u.token, body: {} })).status, 400);
      delete process.env.GOOGLE_CLIENT_SECRET;
      const sinSecret = await (await req(url, "/api/auth/google/edad", { method: "POST", token: u.token, body: { serverAuthCode: "c" } })).json();
      assert.deepEqual(sinSecret.data, { estado: "sin_dato", sincronizado: false, motivo: "adaptador_no_configurado" });
      process.env.GOOGLE_CLIENT_ID = "web.apps.googleusercontent.com";
      process.env.GOOGLE_CLIENT_SECRET = "s";
      globalThis.fetch = async (destino, opciones) => {
        const d = String(destino);
        if (d.startsWith("https://oauth2.googleapis.com/token")) return new Response(JSON.stringify({ access_token: "at" }), { status: 200 });
        if (d.startsWith("https://people.googleapis.com/")) return new Response(JSON.stringify({ birthdays: [{ date: { year: 1990, month: 1, day: 15 } }] }), { status: 200 });
        return realFetch(destino, opciones);
      };
      const ok = await (await req(url, "/api/auth/google/edad", { method: "POST", token: u.token, body: { serverAuthCode: "c" } })).json();
      assert.equal(ok.data.estado, "mayor");
      globalThis.fetch = async (destino, opciones) => String(destino).startsWith("https://oauth2.googleapis.com/")
        ? new Response("{}", { status: 400 }) : realFetch(destino, opciones);
      assert.equal((await req(url, "/api/auth/google/edad", { method: "POST", token: u.token, body: { serverAuthCode: "c" } })).status, 502);
    });
  } finally {
    globalThis.fetch = realFetch;
    if (secretPrevio === undefined) delete process.env.GOOGLE_CLIENT_SECRET; else process.env.GOOGLE_CLIENT_SECRET = secretPrevio;
    if (idPrevio === undefined) delete process.env.GOOGLE_CLIENT_ID; else process.env.GOOGLE_CLIENT_ID = idPrevio;
  }
});
