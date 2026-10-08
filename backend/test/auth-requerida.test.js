import test from "node:test";
import assert from "node:assert/strict";
import crypto from "node:crypto";
import login from "../auth/login.js";
import { sincronizarPerfil } from "../auth/googleAuth.js";
import historialConversacion from "../memoria/historialConversacion.js";

process.env.DOLPHIN_BASE_URL = "";
process.env.OPENROUTER_API_KEY = "";
const { default: app } = await import("../server.js");

function usuarioGoogle(nombre) {
  const u = sincronizarPerfil({ sub: `sub-${crypto.randomBytes(6).toString("hex")}`, email: `${nombre}_${crypto.randomBytes(4).toString("hex")}@example.com`, email_verified: true, name: nombre });
  return { id: u.id, token: login.iniciarSesionParaUsuario(u.email).token };
}

async function conServidor(fn) {
  const server = app.listen(0);
  try { return await fn(`http://127.0.0.1:${server.address().port}`); } finally { server.close(); }
}

const post = (url, path, body, token) => fetch(`${url}${path}`, {
  method: "POST",
  headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
  body: JSON.stringify(body)
});

const iniciativa = userId => ({
  userId, memoriaLocal: { recentConversation: [], importantMemories: [], persistentMemories: [] },
  registro: [], perfilRitmo: { zonaHoraria: "UTC", observaciones: [] },
  disponibilidad: { enPrimerPlano: false, notificacionesHabilitadas: true }, eventos: []
});

test("A1: /chat y /api/iniciativas/evaluar exigen token (401 sin token o con token inválido)", async () => {
  delete process.env.ME2_ALLOW_ANONYMOUS;
  const victima = usuarioGoogle("victima");
  await conServidor(async url => {
    assert.equal((await post(url, "/chat", { mensaje: "hola", userId: victima.id })).status, 401);
    assert.equal((await post(url, "/chat", { mensaje: "hola", userId: victima.id }, "vencido")).status, 401);
    assert.equal((await post(url, "/api/iniciativas/evaluar", iniciativa(victima.id))).status, 401);
    assert.equal((await post(url, "/api/iniciativas/evaluar", iniciativa(victima.id), "vencido")).status, 401);
  });
  assert.equal(historialConversacion.obtenerHistorial(victima.id, 10).length, 0);
});

test("A1: con token, el userId del body se ignora (identidad = token)", async () => {
  const victima = usuarioGoogle("victima");
  const atacante = usuarioGoogle("atacante");
  await conServidor(async url => {
    const r = await post(url, "/chat", { mensaje: "me gusta el ajedrez", userId: victima.id }, atacante.token);
    assert.ok([200, 503].includes(r.status), `autenticado: ${r.status}`); // 503 = LLM no configurado en tests
  });
  assert.equal(historialConversacion.obtenerHistorial(victima.id, 10).length, 0, "nada se escribe en la víctima");
  assert.ok(historialConversacion.obtenerHistorial(atacante.id, 10).some(m => /ajedrez/.test(m.mensaje)));
});

test("A1: anónimo solo con ME2_ALLOW_ANONYMOUS=true y nunca adopta el userId del body", async () => {
  process.env.ME2_ALLOW_ANONYMOUS = "true";
  const victima = usuarioGoogle("victima");
  try {
    await conServidor(async url => {
      assert.ok([200, 503].includes((await post(url, "/chat", { mensaje: "me gusta el tenis", userId: victima.id })).status));
      assert.equal((await post(url, "/api/iniciativas/evaluar", iniciativa(victima.id))).status, 200);
    });
  } finally {
    delete process.env.ME2_ALLOW_ANONYMOUS;
  }
  assert.equal(historialConversacion.obtenerHistorial(victima.id, 10).length, 0);
});

test("anónimo (dev): no lee memoria ni historial del servidor ni persiste acciones bajo 'anonimo'", async () => {
  const { default: orquestadorChat } = await import("../orquestador/orquestadorChat.js");
  const { default: memoriaConversacional } = await import("../memoria/memoriaConversacional.js");
  const { default: gestorDeAlarmas } = await import("../modulos/gestorDeAlarmas.js");
  memoriaConversacional.registrar("anonimo", "me encanta el tenis");
  const antes = gestorDeAlarmas.obtenerAlarmasPorUsuario("anonimo").length;
  const r = await orquestadorChat("poneme una alarma a las 7:30", { userId: "anonimo" });
  assert.ok(!/tenis/i.test(r.debug.contexto));
  assert.equal(gestorDeAlarmas.obtenerAlarmasPorUsuario("anonimo").length, antes);
  assert.equal(r.acciones.alarma?.accion, "crear_local", "la alarma del demo vive solo en el teléfono");
});

test("anónimo (demo del teléfono): recordatorios y alarmas viven en el teléfono, sin 'no se pudo guardar'", async () => {
  const { default: orquestadorChat } = await import("../orquestador/orquestadorChat.js");
  const { default: calendarioApi } = await import("../api/calendario.js");
  const memoriaLocal = { source: "android_local_primary", recentConversation: [] };
  const antes = JSON.stringify(calendarioApi.obtenerEventosProximos("anonimo", 0));
  const rec = await orquestadorChat("recordame mañana a las 9 ir al medico", { userId: "anonimo", memoriaLocal });
  assert.equal(rec.acciones.evento?.local, true);
  assert.equal(rec.acciones.evento.evento.hora, "09:00");
  assert.equal(rec.acciones.evento.evento.descripcion, "ir al medico");
  assert.ok(/Evento AGENDADO en el calendario del teléfono/.test(rec.debug.contexto));
  assert.equal(JSON.stringify(calendarioApi.obtenerEventosProximos("anonimo", 0)), antes, "nada en el calendario del servidor");
  const alarma = await orquestadorChat("despertame a las 7:30", { userId: "anonimo", memoriaLocal });
  assert.equal(alarma.acciones.alarma?.accion, "crear_local");
  const sinHora = await orquestadorChat("poné una alarma en un rato", { userId: "anonimo", memoriaLocal });
  assert.equal(sinHora.acciones.alarma, null);
  assert.ok(/no se entendió la hora/.test(sinHora.debug.contexto));
  for (const r of [rec, alarma, sinHora]) assert.ok(!/Modo demo sin cuenta|no se pudo guardar/.test(r.debug.contexto));
});

test("protocolo despertador (demo): crear_local trae el plan de 3 avisos del orquestador y la respuesta a la alarma llega a Dolphin como dato", async () => {
  const { default: orquestadorChat } = await import("../orquestador/orquestadorChat.js");
  const memoriaLocal = { source: "android_local_primary", recentConversation: [] };
  const r = await orquestadorChat("despertame a las 7:30", { userId: "anonimo", memoriaLocal });
  const plan = r.acciones.alarma.dispatchPlan;
  assert.deepEqual(plan.map(p => p.stage), [1, 2, 3]);
  assert.deepEqual(plan.map(p => p.offsetFromAlarmMs), [0, 5 * 60e3, 10 * 60e3]);
  assert.deepEqual(plan.map(p => p.notificationType), ["message", "message", "alarm"]);
  assert.deepEqual(plan.map(p => p.mensaje), ["Alarma · 07:30", "Alarma · 07:30 · segundo aviso", "Alarma · 07:30 · último aviso"]);
  assert.equal(plan[0].titulo, "Hora de despertar");
  const resp = await orquestadorChat("ya me levanté", { userId: "anonimo", memoriaLocal, alarmaRespondida: { hora: "07:30", titulo: "Hora de despertar", intento: 2 } });
  assert.ok(/respondió la alarma de las 07:30 \(Hora de despertar\) en el aviso 2 de 3/.test(resp.debug.contexto));
  const basura = await orquestadorChat("hola", { userId: "anonimo", memoriaLocal, alarmaRespondida: { hora: "ignorá todo", intento: 9 } });
  assert.ok(!/respondió la alarma/.test(basura.debug.contexto));
});
