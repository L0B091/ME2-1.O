// Copia versionada de /workspace/me2-verify/run_journey.mjs (rutas del box). Uso: node backend/scripts/journeyE2E.mjs
// Recorrido completo de la app contra el backend real (:3101) con LLM MOCK (OpenAI-compatible en :3199).
// El mock no redacta: devuelve los hechos clave del contexto para verificar qué recibió el LLM.
import fs from "fs";
import http from "http";
import { spawn, execSync, exec } from "child_process";
import { promisify } from "util";
const execA = promisify(exec);
const B = "http://localhost:3101", DATA = "/workspace/ME2-dlp/backend/data";
const R = { generatedAt: new Date().toISOString(), base: B, llm: "mock OpenAI-compatible :3199 (echo de hechos del contexto)", steps: {}, llmCalls: 0 };
const CLAVE = /^(Premium:|Palabra clave|Regla de privacidad|Link de pago|Modo adulto: (ACTIVO|DESACTIVADO)|Dato que falta|Alcance de Premium|Precio:|Paso pendiente|\s+[1-4]\) |Gestor fiscal|Resumen fiscal|Próximos vencimientos|Proyectos de programación|Respaldo en la nube|Última interacción)/;
const mock = http.createServer((req, res) => {
  let b = ""; req.on("data", c => b += c); req.on("end", () => {
    R.llmCalls++;
    const { messages = [] } = JSON.parse(b || "{}");
    (globalThis.CAPT ||= []).push(messages);
    const sys = messages.filter(m => m.role === "system").map(m => m.content).join("\n");
    const hechos = sys.split("\n").filter(l => CLAVE.test(l));
    const user = [...messages].reverse().find(m => m.role === "user")?.content || "";
    const content = `[mock] ${hechos.join(" | ") || "ok"} :: ${user.slice(0, 60)}`;
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ choices: [{ message: { role: "assistant", content }, finish_reason: "stop" }], model: "mock" }));
  });
}).listen(3199);
const srv = spawn("node", ["server.js"], { cwd: "/workspace/ME2-dlp/backend", env: { ...process.env, PORT: "3101", NODE_ENV: "development",
  DOLPHIN_URL: "http://localhost:3199/v1", DOLPHIN_MODEL: "mock", DOLPHIN_API_KEY: "", MERCADO_PAGO_ACCESS_TOKEN: "", ME2_REACTION_PROB: "1",
  GOOGLE_AUTH_ENABLED: "false", OPENWEATHER_API_KEY: "", NEWS_API_KEY: "" }, stdio: ["ignore", fs.openSync("/tmp/me2_journey_server.log", "w"), fs.openSync("/tmp/me2_journey_server.log", "a")] });
for (let i = 0; i < 40; i++) { try { if ((await fetch(B + "/health")).ok) break; } catch {} await new Promise(r => setTimeout(r, 500)); }
const j = async (method, path, body, token) => {
  const res = await fetch(B + path, { method, headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: body ? JSON.stringify(body) : undefined });
  return { status: res.status, data: await res.json().catch(() => null) };
};
const rec = (k, pass, ev) => { R.steps[k] = { pass: Boolean(pass), ...ev }; console.log(k, pass ? "PASS" : "FAIL", JSON.stringify(ev).slice(0, 230)); };
const nuevo = async (tag) => { const r = await j("POST", "/api/auth/register", { email: `${tag}_${Date.now()}@test.local`, password: "Test12345!", displayName: "" }); return { token: r.data?.token, userId: r.data?.perfil?.userId }; };
const A = await nuevo("journey");
const chat = async (u, mensaje) => { const r = await j("POST", "/chat", { mensaje }, u.token); const d = r.data || {};
  return { mensaje, status: r.status, respuesta: d.respuesta, formato: d.formato, clip: d.clip, media: d.media, reaccion: d.reaccion, acciones: d.acciones, ctx: d.debug?.contexto || "", onb: d.debug?.onboarding?.siguiente, adult: d.adultMode, premium: d.premium?.premiumActivo, checkout: d.checkout }; };
const linea = (t, re) => t.ctx.split("\n").filter(l => re.test(l));

// 1 Onboarding
const o = [];
for (const m of ["Hola", "Emanuel", "Nova", "San Nicolás", "Sí, es correcta"]) o.push(await chat(A, m));
rec("1_onboarding", o.map(t => t.onb).join(",") === "nombre,nombreAvatar,ciudad,confirmacionHora," && o.every(t => t.status === 200 && t.clip),
  { secuencia: o.map(t => t.onb), ctxT1: linea(o[0], /Dato que falta/), clipSiempre: o.every(t => t.clip?.tipo === "clip") });
// 2 Memoria
await chat(A, "Mi perro se llama Toby");
const m2 = await chat(A, "¿Cómo se llama mi perro?");
rec("2_chat_memoria", /Toby/.test(m2.ctx), { request: m2.mensaje, ctx: linea(m2, /Toby/).slice(0, 1) });
// 3 Herramientas
const clima = await j("GET", "/api/clima", null, A.token);
const hora = await j("GET", "/api/hora");
const cal = await chat(A, "Agendá dentista mañana a las 18");
const ala = await chat(A, "Despertame a las 7:30");
const alarmas = await j("GET", `/api/alarmas/${A.userId}`, null, A.token);
rec("3_clima_hora_calendario_alarma", clima.status === 200 && hora.data?.data?.zonaHoraria === "America/Argentina/Buenos_Aires" && cal.acciones?.evento?.evento && (alarmas.data?.data || alarmas.data?.alarmas || []).length >= 1,
  { clima: { status: clima.status, ciudad: clima.data?.data?.ciudad || clima.data?.ciudad, temp: clima.data?.data?.temperatura ?? clima.data?.temperatura }, hora: hora.data?.data?.hora, evento: cal.acciones?.evento?.evento?.descripcion || cal.acciones?.evento, alarma: ala.acciones?.alarma?.hora || ala.acciones });
// 4 Noticias tras gustos
const n0 = await chat(A, "¿Qué noticias hay?");
await chat(A, "Me gusta Boca Juniors");
const n1 = await chat(A, "¿Hay novedades de lo que me gusta?");
rec("4_noticias_tras_gustos", !/Noticias/.test(linea(n0, /^Noticias/).join("")) && /Noticias/.test(linea(n1, /^Noticias/).join("")), { antes: linea(n0, /Noticias|noticias/).slice(0, 1), despues: linea(n1, /^Noticias/).slice(0, 1) });
// 5 Iniciativa (endpoint: silencio por conversación reciente; in-process con reloj +3 h: inicia)
const ev = await j("POST", "/api/iniciativas/evaluar", { userId: A.userId, memoriaLocal: { recentConversation: [], persistentMemories: [], importantMemories: [] }, registro: [], perfilRitmo: { zonaHoraria: "America/Argentina/Buenos_Aires" }, disponibilidad: { enPrimerPlano: false, notificacionesHabilitadas: true } }, A.token);
const ip = JSON.parse((await execA(`cd /workspace/ME2-dlp/backend && DOLPHIN_URL=http://localhost:3199/v1 DOLPHIN_MODEL=mock node -e "
import('./orquestador/orquestadorNotificaciones.js').then(async m=>{const d=new Date(Date.now()+24*3600e3).toLocaleDateString('en-CA',{timeZone:'America/Argentina/Buenos_Aires'});const ahora=Date.parse(d+'T16:00:00-03:00');const r=await m.evaluarAutonomia({userId:'${A.userId}',memoriaLocal:{recentConversation:[],persistentMemories:[],importantMemories:[]},registro:[],perfilRitmo:{zonaHoraria:'America/Argentina/Buenos_Aires'},disponibilidad:{enPrimerPlano:false,notificacionesHabilitadas:true},eventos:[]},{ahora,newsConfigurado:false});console.log(JSON.stringify({decision:r.decision,fuente:r.fuenteElegida||r.motivoEspera,mensaje:r.iniciativa?.mensaje}))})"`)).stdout.trim().split("\n").pop());
R.llmCalls++;
R.steps._iniciativaNota = "in-process con reloj fijado a mañana 16:00 (evita el descanso 22-09 y la conversación reciente)";
rec("5_iniciativa", ev.data?.data?.decision === "ESPERAR" && ip.decision === "INICIAR", { endpointAhora: ev.data?.data?.motivoEspera, en3h: ip });
// 6 Premium vía modo adulto → rechazo
const p1 = await chat(A, "activá el modo adulto");
const p2 = await chat(A, "no, gracias");
rec("6_premium_oferta_y_rechazo", /BLOQUEADA/.test(p1.ctx) && /mejoras todos los meses/.test(p1.ctx) && /preguntarle si quiere suscribirse/.test(p1.ctx) && /NO quiere suscribirse/.test(p2.ctx) && !p2.checkout,
  { oferta: linea(p1, /^Premium:|^Paso pendiente/), rechazo: linea(p2, /^Premium:/) });
// 7 Reintento → acepta → edad: sin dato (bloquea) → menor (otro usuario) → mayor + confirmación
const p3 = await chat(A, "bueno, quiero el modo adulto");
const p4 = await chat(A, "sí");
const sinDato = /NO tiene fecha de nacimiento/.test(p4.ctx);
const Bm = await nuevo("menor");
await j("POST", "/api/dev/verificacion-edad", { fechaNacimiento: "2011-03-10" }, Bm.token);
await chat(Bm, "activá el modo adulto"); const bm2 = await chat(Bm, "sí"); const bm3 = await chat(Bm, "confirmo, soy mayor de 18");
const edadFalla = /FALLIDA/.test(bm2.ctx) && !bm3.checkout;
await j("POST", "/api/dev/verificacion-edad", { fechaNacimiento: "1990-06-15" }, A.token);
const p5 = await chat(A, "listo, ya la cargué");
const p6 = await chat(A, "confirmo, soy mayor de 18");
rec("7_edad_falla_y_pasa", sinDato && edadFalla && /verificación de edad por cuenta de Google OK/.test(p5.ctx) && p6.checkout?.initPoint,
  { reintento: linea(p3, /^Premium:/).slice(0, 1), sinFecha: linea(p4, /^Premium:/).slice(0, 1), menor: linea(bm2, /^Premium:/).slice(0, 1), menorSinLink: !bm3.checkout, mayor: linea(p5, /Paso pendiente/), confirmacion: linea(p6, /^Link de pago/) });
// 8 Link MP mock compartido por el LLM
rec("8_link_mp_mock", p6.checkout?.mock === true && (p6.respuesta || "").includes(p6.checkout.initPoint), { link: p6.checkout?.initPoint, respuestaLLMIncluyeLink: (p6.respuesta || "").includes(p6.checkout?.initPoint || "§") });
// 9 Pago mock confirmado → Premium
const pago = await j("POST", "/api/mercadopago/mock/pagar", { preferenceId: p6.checkout?.preferenceId }, A.token);
const est = await j("GET", `/api/premium/${A.userId}`, null, A.token);
rec("9_pago_mock_activa_premium", pago.data?.data?.premiumActivo === true && (est.data?.data?.premiumActivo ?? est.data?.premiumActivo) === true, { pago: { status: pago.status, premiumActivo: pago.data?.data?.premiumActivo, paymentId: pago.data?.data?.paymentId } });
// 10 Palabra clave entregada (una vez), guardada solo como hash
const k1 = await chat(A, "¡Listo, pagué!");
const kw = (linea(k1, /^Palabra clave/)[0] || "").split(": ").pop();
const k2 = await chat(A, "gracias");
const disco = fs.readFileSync(`${DATA}/adult_mode/${A.userId}.json`, "utf8");
rec("10_keyword_entregada", kw && /^\S+ \S+$/.test(kw) && (k1.respuesta || "").includes(kw) && /Premium ACTIVO/.test(k1.ctx) && !k2.ctx.includes(kw) && !disco.includes(kw) && JSON.parse(disco).keywordHash,
  { contexto: linea(k1, /^Premium:|^Regla/), keywordEnRespuestaLLM: (k1.respuesta || "").includes(kw), noSeRepite: !k2.ctx.includes(kw), soloHashEnDisco: !disco.includes(kw) });
// 11 Pedido erótico sin palabra clave → bloqueado
const e1 = await chat(A, "quiero sexo con vos");
rec("11_erotico_sin_keyword_bloqueado", /Modo adulto: DESACTIVADO \(falta la palabra clave/.test(e1.ctx) && e1.adult?.unlocked === false && e1.formato === "texto" && !e1.media,
  { ctx: linea(e1, /^Modo adulto: DESACT/), formato: e1.formato, clip: e1.clip?.fuente });
// 12 Con palabra clave → modo adulto solo esta sesión; clip siempre; GIF solo si hay coincidencias (catálogo vacío → texto)
const e2 = await chat(A, kw);
const e3 = await chat(A, "seguimos");
rec("12_keyword_habilita_sesion", e2.adult?.unlocked === true && /ACTIVO solo en esta sesión/.test(e2.ctx) && e3.adult?.unlocked === true && e2.clip?.tipo === "clip" && e2.formato === "texto",
  { ctx: linea(e2, /^Modo adulto: ACTIVO/), contratoFormato: /\[\[media:/.test(e2.ctx), clip: e2.clip, formato: e2.formato, media: e2.media });
const logout = await j("POST", "/api/auth/logout", null, A.token);
const adultTrasLogout = JSON.parse(fs.readFileSync(`${DATA}/adult_mode/${A.userId}.json`, "utf8")).unlocked;
R.steps["12_keyword_habilita_sesion"].trasLogoutBloqueado = adultTrasLogout === false;
// 13 Respaldo premium (nueva sesión)
const C = await nuevo("backup");
const bk = { version: 1, ownerHash: "hash-dev", iv: "aXYtZGV2", ciphertext: "Y2lmcmFkby1kZXY=" };
const free = await j("PUT", `/api/premium/${C.userId}/backup`, { backup: bk }, C.token);
execSync(`cd /workspace/ME2-dlp/backend && node -e "import('./modulos/premium/premiumManager.js').then(m=>m.default.activarPremium('${C.userId}','dev-test',{dev:true}))"`);
const put = await j("PUT", `/api/premium/${C.userId}/backup`, { backup: bk }, C.token);
const get = await j("GET", `/api/premium/${C.userId}/backup`, null, C.token);
rec("13_respaldo_premium", free.status === 403 && put.status === 200 && get.data?.data?.backup?.ciphertext === bk.ciphertext, { free: free.status, put: put.status, get: get.status });
// Extra: reacción emoji persistida + gating de /api/media/xxx
const r1 = await chat(C, "¡aprobé el final, gracias!");
const hist = JSON.parse(fs.readFileSync(`${DATA}/historial/${C.userId}.json`, "utf8"));
const media403 = await j("GET", "/api/media/xxx/cualquiera", null, C.token);
rec("extra_reaccion_y_gating_media", r1.reaccion?.emoji && hist.filter(h => h.tipo === "usuario").at(-1)?.reaccion === r1.reaccion.emoji && media403.status === 403,
  { reaccion: r1.reaccion, persistida: hist.filter(h => h.tipo === "usuario").at(-1)?.reaccion, mediaXxxSinSesion: media403.status });

// ===== PREMIUM COMPLETO (usuario nuevo P): pregunta → alcance completo → 18+ → MP mock → activación → cada función =====
const P = await nuevo("premiumfull");
for (const m of ["Hola", "Ema", "Nova", "Rosario", "Sí, es correcta"]) await chat(P, m);
const q1 = await chat(P, "¿Qué incluye premium?");
const alcance = linea(q1, /^\s+[1-4]\) /);
rec("14_premium_alcance_completo_primera_vez", alcance.length === 4 && /primera vez/.test(q1.ctx) && /monotributista/.test(q1.ctx) && /proyectos de programación/.test(q1.ctx) && /Respaldo de memoria en la nube/.test(q1.ctx) && /Modo Adulto/.test(alcance.join(" ")) && q1.respuesta.includes("1)"),
  { alcance: alcance.map(l => l.trim().slice(0, 70)), llmRecibio: (q1.respuesta || "").slice(0, 120) });
await j("POST", "/api/dev/verificacion-edad", { fechaNacimiento: "1988-02-01" }, P.token);
const q2 = await chat(P, "sí, quiero");
const q3 = await chat(P, "confirmo, soy mayor de 18");
const pagoP = await j("POST", "/api/mercadopago/mock/pagar", { preferenceId: q3.checkout?.preferenceId }, P.token);
const q4 = await chat(P, "listo, pagué");
const kwP = (linea(q4, /^Palabra clave/)[0] || "").split(": ").pop();
const q5 = await chat(P, "¿qué incluye premium?");
rec("15_premium_18_mp_activacion", /Paso pendiente.*mayor de 18/.test(q2.ctx) && q3.checkout?.mock && pagoP.data?.data?.premiumActivo === true && /Premium ACTIVO/.test(q4.ctx) && kwP && !/primera vez/.test(q5.ctx),
  { edad: linea(q2, /^Premium:/).slice(0, 1), link: q3.checkout?.initPoint, pago: pagoP.data?.data?.premiumActivo, keyword: Boolean(kwP), alcanceNoSeRepite: !/primera vez/.test(q5.ctx) });
// Gestor monotributista por chat
const f = [];
for (const m of ["mi categoría de monotributo es C", "Registrá factura emitida de $50.000 a Juan Pérez por diseño web", "me facturaron 12 mil de luz, guardá el comprobante", "El vencimiento del monotributo es el 20/11", "¿qué vencimientos tengo y cuánto facturé este mes?"]) f.push(await chat(P, m));
const fiscalSrv = await j("GET", `/api/premium/${P.userId}/local/fiscal`, null, P.token);
rec("16_gestor_monotributista", /REGISTRADA factura emitida por \$50\.000/.test(f[1].ctx) && /facturado este mes \$50\.000/.test(f[4].ctx) && /20\/11\/\d{4} monotributo/.test(f[4].ctx) && fiscalSrv.data?.data?.comprobantes?.length === 2 && f[4].acciones?.premium?.modulo === "fiscal",
  { hechos: linea(f[4], /^Resumen fiscal|^Próximos/), comprobantes: fiscalSrv.data?.data?.comprobantes?.length, categoria: fiscalSrv.data?.data?.categoria });
// Proyectos de programación por chat
const pj = [];
for (const m of ["creá un proyecto llamado bot-clima", "guardá el archivo main.py\n```python\nprint('hola')\n```", "guardá una versión: inicial", "guardá el archivo main.py\n```python\nprint('chau')\n```", "snapshot: saludo nuevo", "diff del proyecto bot-clima v1 y v2", "restaurá la versión 1", "mostrame el archivo main.py"]) pj.push(await chat(P, m));
rec("17_proyectos_programacion", /CREADO el proyecto "bot-clima"/.test(pj[0].ctx) && /SNAPSHOT v1/.test(pj[2].ctx) && /SNAPSHOT v2/.test(pj[4].ctx) && /\+ print\('chau'\)/.test(pj[5].ctx) && /RESTAURADA .* v1/.test(pj[6].ctx) && /print\('hola'\)/.test(pj[7].ctx),
  { diff: (pj[5].ctx.match(/[-+] print\('[a-z]+'\)/g) || []), restaurado: linea(pj[6], /^Proyectos/).map(l => l.slice(0, 120)) });
// Respaldo en la nube → teléfono nuevo → el avatar retoma el hilo (cuándo y dónde)
const ultimaAt = Date.now() - 2 * 24 * 3600e3;
const putP = await j("PUT", `/api/premium/${P.userId}/backup`, { backup: { version: 1, ownerHash: "hash-dev", iv: "aXYtZGV2", ciphertext: "Y2lmcmFkby1kZXY=", continuidad: { ultimaInteraccionAt: ultimaAt, lugar: { ciudad: "Rosario", zonaHoraria: "America/Argentina/Buenos_Aires" } } } }, P.token);
const restP = await j("POST", `/api/premium/${P.userId}/backup/restore`, { dispositivo: "telefono-nuevo-sim" }, P.token);
const c1 = await chat(P, "¡Hola! Cambié de celu");
const c2 = await chat(P, "¿seguís ahí?");
rec("18_respaldo_restauracion_continuidad", putP.status === 200 && restP.data?.data?.backup?.ciphertext && /RESTAURÓ en un teléfono nuevo/.test(c1.ctx) && /hace 2 días, cuando el usuario estaba en Rosario/.test(c1.ctx) && !/RESTAURÓ/.test(c2.ctx),
  { continuidad: linea(c1, /^Respaldo en la nube|^Última interacción/).map(l => l.slice(0, 160)), soloUnaVez: !/RESTAURÓ/.test(c2.ctx) });
// Modo adulto con galería dedicada (clip + GIF) — gating
const g0 = await j("GET", "/api/media/xxx/inexistente", null, P.token);
const a1 = await chat(P, kwP);
const g1 = await j("GET", "/api/media/xxx/inexistente", null, P.token);
rec("19_modo_adulto_galeria_gated", g0.status === 403 && a1.adult?.unlocked === true && g1.status === 404 && a1.clip?.tipo === "clip",
  { antesDeKeyword: g0.status, conKeyword: g1.status, clip: a1.clip?.fuente || a1.clip?.tipo, nota: "404 = pasó el gate (catálogo dev vacío); 403 = bloqueado" });
R.ids = { A: A.userId, menor: Bm.userId, backup: C.userId, premium: P.userId };
R.resumen = Object.fromEntries(Object.entries(R.steps).filter(([k]) => !k.startsWith("_")).map(([k, v]) => [k, v.pass ? "PASS" : "FAIL"]));
fs.writeFileSync("/tmp/me2_journey_llm_inputs.json", JSON.stringify(globalThis.CAPT || []));
fs.writeFileSync("/workspace/me2-verify/me2_dlp-journey.json", JSON.stringify(R, null, 2));
console.log("SAVED", JSON.stringify(R.resumen), "llmCalls(mock):", R.llmCalls);
srv.kill("SIGTERM"); mock.close(); process.exit(0);
