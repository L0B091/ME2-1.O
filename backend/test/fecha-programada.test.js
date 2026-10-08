import test from "node:test";
import assert from "node:assert/strict";
import dolphinClient from "../llm/dolphinClient.js";
import orquestadorChat from "../orquestador/orquestadorChat.js";
import fechaProgramada, { describirProgramado, contieneFechaHora, epochDeFechaHora } from "../utils/fechaProgramada.js";

const tz = "America/Argentina/Buenos_Aires";
const memoriaLocal = () => ({ source: "android_local_primary", recentConversation: [], calendario: [] });
const chatOriginal = dolphinClient.chat;

// LLM simulado: devuelve las respuestas en orden y guarda lo que recibió.
function llmFalso(...respuestas) {
  const llamadas = [];
  dolphinClient.chat = async (mensajes) => {
    llamadas.push(mensajes);
    const r = respuestas[Math.min(llamadas.length - 1, respuestas.length - 1)];
    return { used: true, respuesta: typeof r === "function" ? r(mensajes) : r };
  };
  return llamadas;
}
const contexto = (m) => m[0].content;
const uid = (s) => `fecha-${s}-${process.pid}-${Date.now()}`;

test.after(() => { dolphinClient.chat = chatOriginal; });

test("fecha programada: formato es-AR con hoy/mañana, zona ME2 y validación tolerante (ceros, ':' o '.')", () => {
  const ahora = Date.parse("2026-10-08T17:22:36Z"); // jueves 14:22 BA
  assert.equal(describirProgramado(Date.parse("2026-10-08T17:32:00Z"), ahora).texto, "hoy jueves 8/10 a las 14:32");
  assert.equal(describirProgramado(Date.parse("2026-10-09T03:30:00Z"), ahora).texto, "mañana viernes 9/10 a las 00:30");
  assert.equal(describirProgramado(Date.parse("2026-10-12T12:00:00Z"), ahora).texto, "lunes 12/10 a las 09:00");
  assert.equal(describirProgramado(epochDeFechaHora("2026-10-15", "20:00"), ahora).texto, "jueves 15/10 a las 20:00");
  const d = describirProgramado(Date.parse("2026-10-09T12:05:00Z"), ahora); // 09:05
  for (const ok of ["mañana viernes 9/10 a las 09:05", "el 09/10 a las 9:05", "viernes 9 / 10, 09.05 hs"]) assert.ok(contieneFechaHora(ok, d), ok);
  for (const no of ["mañana a las 09:05", "viernes 9/10", "el 19/10 a las 09:05", "9/10 a las 19:05", "9/10 a las 09:50"]) assert.ok(!contieneFechaHora(no, d), no);
});

test("alarma: el orquestador pasa la fecha/hora exacta como dato obligatorio y acepta la respuesta que la incluye (sin reintento)", async () => {
  const llamadas = llmFalso((m) => {
    const t = /«([^»]+)»/.exec(contexto(m))[1];
    return `¡Listo! Te despierto ${t}.`;
  });
  const antes = Date.now();
  const r = await orquestadorChat("poneme una alarma en 2 minutos", { userId: uid("a1"), memoriaLocal: memoriaLocal(), zonaHoraria: tz });
  const epoch = r.acciones.alarma.disparoEpochMs;
  assert.equal(r.acciones.alarma.accion, "crear_local");
  assert.ok(epoch - antes >= 60e3 && epoch - antes <= 180e3, "≈ ahora + 2 min con el reloj del servidor");
  const esperado = describirProgramado(epoch, antes);
  assert.equal(esperado.relativo === "hoy" || esperado.relativo === "mañana", true);
  assert.match(contexto(llamadas[0]), new RegExp(`Alarma CREADA en el teléfono para el ${esperado.texto}`));
  assert.match(contexto(llamadas[0]), new RegExp(`DATO OBLIGATORIO.*«${esperado.texto}»`));
  assert.equal(llamadas.length, 1);
  assert.equal(r.respuesta, `¡Listo! Te despierto ${esperado.texto}.`);
  assert.equal(r.debug.llm.fechaIncluida, true);
  assert.deepEqual(r.debug.llm.fechaProgramada, [esperado.texto]);
});

test("alarma: si el LLM omite la fecha, se regenera UNA vez con instrucción estricta y se usa la que la incluye", async () => {
  const antes = { ...fechaProgramada.ESTADISTICAS_FECHA };
  const llamadas = llmFalso("Dale, ahí te la puse 😊", (m) => {
    const t = /«([^»]+)»/.exec(m.at(-1).content)[1];
    return `Perdón: quedó para ${t}.`;
  });
  const r = await orquestadorChat("poneme una alarma a las 7:15", { userId: uid("a2"), memoriaLocal: memoriaLocal(), zonaHoraria: tz });
  const d = describirProgramado(r.acciones.alarma.disparoEpochMs);
  assert.equal(d.hhmm, "07:15");
  assert.equal(llamadas.length, 2);
  const estricta = llamadas[1].at(-1);
  assert.equal(estricta.role, "system");
  assert.match(estricta.content, new RegExp(`TEXTUALMENTE «${d.texto}»`));
  assert.deepEqual(llamadas[1].at(-2), { role: "assistant", content: "Dale, ahí te la puse 😊" });
  assert.equal(r.respuesta, `Perdón: quedó para ${d.texto}.`);
  assert.equal(fechaProgramada.ESTADISTICAS_FECHA.regeneradas, antes.regeneradas + 1);
  assert.equal(fechaProgramada.ESTADISTICAS_FECHA.faltanteTrasReintento, antes.faltanteTrasReintento);
});

test("alarma: si tras el reintento sigue faltando, NO se agrega texto enlatado; queda registrado", async () => {
  const antes = { ...fechaProgramada.ESTADISTICAS_FECHA };
  const llamadas = llmFalso("Listo, alarma puesta.", "Listo, ya está.");
  const r = await orquestadorChat("despertame a las 6", { userId: uid("a3"), memoriaLocal: memoriaLocal(), zonaHoraria: tz });
  assert.equal(llamadas.length, 2, "un solo reintento");
  assert.equal(r.respuesta, "Listo, alarma puesta.");
  assert.equal(r.debug.llm.fechaIncluida, false);
  assert.equal(fechaProgramada.ESTADISTICAS_FECHA.faltanteTrasReintento, antes.faltanteTrasReintento + 1);
});

test("evento de agenda: la fecha/hora exacta va como dato obligatorio y se valida (crear_local)", async () => {
  const llamadas = llmFalso("Anotado 👍", (m) => `Anotado para ${/«([^»]+)»/.exec(m.at(-1).content)[1]}.`);
  const r = await orquestadorChat("agendá cena con Ana mañana a las 20", { userId: uid("e1"), memoriaLocal: memoriaLocal(), zonaHoraria: tz });
  const ev = r.acciones.evento.evento;
  const d = describirProgramado(epochDeFechaHora(ev.fecha, ev.hora));
  assert.equal(d.relativo, "mañana");
  assert.match(d.texto, /^mañana \S+ \d{1,2}\/\d{1,2} a las 20:00$/);
  assert.match(contexto(llamadas[0]), /Evento AGENDADO en el calendario del teléfono: .*cena con Ana/);
  assert.match(contexto(llamadas[0]), new RegExp(`DATO OBLIGATORIO.*«${d.texto}»`));
  assert.equal(llamadas.length, 2);
  assert.equal(r.respuesta, `Anotado para ${d.texto}.`);
  assert.equal(r.debug.llm.fechaIncluida, true);
});

test("sin alarma ni evento creado no hay validación ni reintento", async () => {
  const llamadas = llmFalso("hola!");
  const r = await orquestadorChat("hola, ¿cómo va?", { userId: uid("n"), memoriaLocal: memoriaLocal(), zonaHoraria: tz });
  assert.equal(llamadas.length, 1);
  assert.equal(r.debug.llm.fechaProgramada, undefined);
});

// Falla real (moto g04s, 8/10 17:31): "DESPIERTAME EN 3 MINUTOS" no se detectaba (solo el voseo "despertame"), no se
// creó la alarma y el LLM igual respondió "He programado una alarma ... a las **17:34**".
test("falla real: 'DESPIERTAME EN 3 MINUTOS' crea la alarma local con su instante y la respuesta lleva fecha y hora", async () => {
  const llamadas = llmFalso("¡Listo! He programado una alarma para dentro de **3 minutos**, es decir, a las **17:34**. 🚨",
    (m) => `¡Listo! Te despierto ${/«([^»]+)»/.exec(m.at(-1).content)[1]}. 🚨`);
  const antes = Date.now();
  const r = await orquestadorChat("DESPIERTAME EN 3 MINUTOS", { userId: uid("real"), memoriaLocal: memoriaLocal(), zonaHoraria: tz });
  const a = r.acciones.alarma;
  assert.equal(a.accion, "crear_local");
  assert.equal(a.titulo, "Hora de despertar");
  assert.ok(a.disparoEpochMs - antes >= 2 * 60e3 && a.disparoEpochMs - antes <= 4 * 60e3, "≈ ahora + 3 min (redondeado al minuto)");
  assert.ok(Array.isArray(a.dispatchPlan) && a.dispatchPlan.length === 3);
  const d = describirProgramado(a.disparoEpochMs, antes);
  assert.equal(a.hora, d.hhmm);
  assert.equal(llamadas.length, 2, "la primera respuesta no traía la fecha → un reintento");
  assert.equal(r.respuesta, `¡Listo! Te despierto ${d.texto}. 🚨`);
  assert.doesNotMatch(r.respuesta, /\*/);
});

test("variantes de pedido de alarma (voseo/tuteo, mayúsculas, tildes) se detectan; relatos no", async () => {
  const { detectarAlarma } = await import("../modulos/detectorAlarmas.js");
  const ahora = Date.parse("2026-10-08T20:31:50Z"); // 17:31:50 BA
  for (const t of ["DESPIERTAME EN 3 MINUTOS", "despiértame en 3 minutos", "Despertame en 3 minutos", "¿me despertás en 3 minutos",
    "quiero que me despiertes en 3 minutos", "levántame en 3 minutos", "ponme una alarma en 3 minutos", "programa una alarma en 3 minutos"]) {
    const r = detectarAlarma(t, { ahora });
    assert.equal(r?.accion, "crear", t);
    assert.equal(r.hora, "17:35", t);
    assert.equal(r.epochMs, Date.parse("2026-10-08T20:35:00Z"), t);
  }
  assert.equal(detectarAlarma("despiértame a las 7", { ahora }).hora, "07:00");
  for (const t of ["estoy despierto desde las 6", "me desperté a las 7", "el despertador sonó a las 7", "mañana a las 7 tengo turno"]) {
    assert.equal(detectarAlarma(t, { ahora }), null, t);
  }
});

test("si NO se creó alarma, el avatar no puede decir que la programó: se regenera una vez", async () => {
  const llamadas = llmFalso("¡Listo! He programado una alarma para dentro de un rato.", "No llegué a entender la hora, ¿a qué hora te despierto?");
  const r = await orquestadorChat("poneme una alarma en un ratito", { userId: uid("inv"), memoriaLocal: memoriaLocal(), zonaHoraria: tz });
  assert.notEqual(r.acciones.alarma?.accion, "crear_local");
  assert.match(contexto(llamadas[0]), /no se entendió la hora; no se creó ninguna alarma/);
  assert.equal(llamadas.length, 2);
  assert.match(llamadas[1].at(-1).content, /NO se creó ninguna alarma/);
  assert.equal(r.respuesta, "No llegué a entender la hora, ¿a qué hora te despierto?");
  assert.equal(r.debug.llm.alarmaInventada, false);
  // Consultar una alarma existente no dispara la validación.
  const q = llmFalso("Sí, tu alarma está puesta para las 7.");
  await orquestadorChat("¿sigue puesta la alarma?", { userId: uid("inv2"), memoriaLocal: memoriaLocal(), zonaHoraria: tz });
  assert.equal(q.length, 1);
});

test("markdown del LLM se quita (el chat muestra texto plano)", async () => {
  const { textoPlano } = await import("../utils/textoPlano.js");
  assert.equal(textoPlano("a las **17:34** y __hoy__, *suspira*, `x`"), "a las 17:34 y hoy, suspira, x");
  assert.equal(textoPlano("# Hola\n[link](https://a.com)"), "Hola\nlink: https://a.com");
  assert.equal(textoPlano("2*3*4 = 24 y snake_case"), "2*3*4 = 24 y snake_case");
  llmFalso("Hola **vos** 😊");
  const r = await orquestadorChat("hola", { userId: uid("md"), memoriaLocal: memoriaLocal(), zonaHoraria: tz });
  assert.equal(r.respuesta, "Hola vos 😊");
});

test("diagnóstico del despertador del teléfono: se acepta solo como líneas cortas (van al log, no al LLM)", async () => {
  const { validarCuerpoChat } = await import("../seguridad/http.js");
  const v = validarCuerpoChat({ mensaje: "hola", contexto: { diagAlarmas: ["08/10 17:34:00 disparo abc intento=1\u0000x", 5, "y".repeat(500), ...Array(60).fill("z")] } });
  assert.equal(v.contexto.diagAlarmas.length, 40);
  assert.ok(v.contexto.diagAlarmas.every(l => typeof l === "string" && l.length <= 260 && !/\u0000/.test(l)));
  assert.equal(validarCuerpoChat({ mensaje: "hola", contexto: { diagAlarmas: "no" } }).contexto.diagAlarmas, undefined);
});
