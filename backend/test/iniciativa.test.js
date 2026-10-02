import test from "node:test";
import assert from "node:assert/strict";
import { evaluarIniciativa, POLITICA_INICIATIVA, interesesDe } from "../comportamiento/iniciativaConversacional.js";
import { evaluarPerfilRitmo } from "../modulos/interaccion/perfilRitmoUsuario.js";
import { evaluarAutonomia, validarSolicitudIniciativa } from "../orquestador/orquestadorNotificaciones.js";
import { generarIniciativaLLM } from "../orquestador/orquestadorNotificaciones.js";

const HORA = 3600000;
const ahora = Date.parse("2026-09-12T15:00:00Z");
function solicitud() {
  return {
    userId: "local-test", ahora,
    memoriaLocal: {
      recentConversation: [{ role: "user", text: "Me interesa astronomia espacial, recordame retomar el proyecto", timestamp: ahora - 2 * HORA }],
      persistentMemories: [{ text: "Me gusta astronomia espacial", timestamp: ahora - 10 * HORA }],
      importantMemories: []
    },
    registro: [], perfilRitmo: { zonaHoraria: "UTC", observaciones: [] },
    disponibilidad: { enPrimerPlano: false, notificacionesHabilitadas: true }, eventos: []
  };
}

function evento(categoria = "NOTICIA", extra = {}) {
  return {
    categoria, motivo: "Un hecho real relacionado con un interes",
    fuente: "test-source", id: `event-${categoria}`, referenciaEvento: `event-${categoria}`,
    timestamp: ahora - HORA, expiresAt: ahora + HORA,
    contexto: { evidencia: "Avance en astronomia espacial" }, ...extra
  };
}

function entrega(categoria, prioridad, cuando = ahora - 2 * HORA, estado = "ENVIADA") {
  return {
    id: "old", referenciaEvento: "old", fuente: "test-source", categoria, prioridad,
    timestamp: cuando, enviada: cuando, entregada: cuando, expiresAt: cuando + 24 * HORA,
    estado, abierta: estado === "ABIERTA" ? cuando : null, respondida: null
  };
}

test("selects one highest-priority relevant event, not caller-supplied priority", () => {
  const input = solicitud();
  input.eventos = [evento("NOTICIA", { prioridad: 9000 }), evento("TRANSITO")];
  const result = evaluarIniciativa(input);
  assert.equal(result.decision, "INICIAR");
  assert.equal(result.iniciativa.categoria, "TRANSITO");
  assert.equal(result.iniciativa.prioridad, POLITICA_INICIATIVA.prioridades.TRANSITO);
  assert.ok(result.iniciativa.contexto.interesesRelacionados.length >= 2);
});

test("unrelated events, expired events and future news never initiate", () => {
  const input = solicitud();
  input.memoriaLocal.recentConversation = [];
  input.eventos = [
    evento("NOTICIA", { contexto: { evidencia: "Deportes de verano" } }),
    evento("TRANSITO", { expiresAt: ahora - 1 }),
    evento("EVENTO", { timestamp: ahora + HORA })
  ];
  assert.equal(evaluarIniciativa(input).decision, "ESPERAR");
});

test("deduplication uses stable source references, including changed headlines", () => {
  const input = solicitud();
  input.memoriaLocal.recentConversation = [];
  input.eventos = [evento()];
  const first = evaluarIniciativa(input).iniciativa;
  input.registro = [{ ...first, estado: "IGNORADA", entregada: ahora - 30 * HORA }];
  input.eventos[0].contexto.evidencia += " nuevo detalle";
  assert.equal(evaluarIniciativa(input).motivoEspera, "duplicada");
});

test("normal initiatives respect sleep across midnight and timezone", () => {
  for (const timestamp of ["2026-09-12T23:00:00Z", "2026-09-13T05:00:00Z"]) {
    const input = solicitud();
    input.ahora = Date.parse(timestamp);
    input.perfilRitmo.configurado = { dormir: "23:00", despertar: "07:00" };
    assert.equal(evaluarIniciativa(input).motivoEspera, "descanso_probable");
  }
  const input = solicitud();
  input.ahora = Date.parse("2026-09-12T10:00:00Z");
  input.perfilRitmo.zonaHoraria = "America/Argentina/Buenos_Aires";
  assert.equal(evaluarIniciativa(input).motivoEspera, "descanso_probable");
  input.perfilRitmo.configurado = { dormir: "23:00", despertar: "07:00" };
  assert.notEqual(evaluarIniciativa(input).motivoEspera, "descanso_probable");
});

test("day-specific overnight schedule uses previous day after midnight", () => {
  const profile = {
    zonaHoraria: "UTC", configurado: {
      dormir: "23:00", despertar: "07:00",
      dias: { "5": { dormir: "22:00", despertar: "10:00" }, "6": { dormir: "23:00", despertar: "06:00" } }
    }
  };
  assert.equal(evaluarPerfilRitmo(profile, Date.parse("2026-09-12T09:00:00Z"), POLITICA_INICIATIVA).dormido, true);
});

test("one observation never establishes sleep; seven dense days may learn reversibly", () => {
  assert.equal(evaluarPerfilRitmo({ zonaHoraria: "UTC", observaciones: [ahora] }, ahora, POLITICA_INICIATIVA).origen, "provisional");
  const observaciones = [];
  for (let day = 1; day <= 7; day++) {
    for (const hour of [6, 10, 15, 21]) observaciones.push(Date.parse(`2026-09-${String(day).padStart(2, "0")}T${hour.toString().padStart(2, "0")}:00:00Z`));
  }
  const profile = { zonaHoraria: "UTC", observaciones };
  assert.equal(evaluarPerfilRitmo(profile, ahora, POLITICA_INICIATIVA).origen, "aprendido");
  assert.equal(evaluarPerfilRitmo(profile, ahora + 40 * 24 * HORA, POLITICA_INICIATIVA).origen, "provisional");
});

test("active app, denied permission and recent interaction prevent initiatives", () => {
  const input = solicitud();
  input.disponibilidad.enPrimerPlano = true;
  assert.equal(evaluarIniciativa(input).motivoEspera, "en_primer_plano");
  input.disponibilidad.enPrimerPlano = false;
  input.disponibilidad.notificacionesHabilitadas = false;
  assert.equal(evaluarIniciativa(input).motivoEspera, "notificaciones_denegadas");
  input.disponibilidad.notificacionesHabilitadas = true;
  input.perfilRitmo.observaciones = [ahora - 1000];
  assert.equal(evaluarIniciativa(input).motivoEspera, "conversacion_reciente");
  input.perfilRitmo.observaciones = [];
  input.perfilRitmo.ultimaInteraccion = ahora - 1000;
  assert.equal(evaluarIniciativa(input).motivoEspera, "conversacion_reciente");
});

test("cooldown does not block higher priority but separates all notifications", () => {
  const input = solicitud();
  input.eventos = [evento("TRANSITO")];
  input.registro = [entrega("SOCIAL", 20)];
  assert.equal(evaluarIniciativa(input).iniciativa.categoria, "TRANSITO");
  input.registro[0].entregada = ahora - 1000;
  assert.equal(evaluarIniciativa(input).motivoEspera, "separacion_minima");
});

test("repeated ignored notifications reduce frequency irrespective of category", () => {
  const input = solicitud();
  input.eventos = [evento("TRANSITO")];
  input.registro = [
    entrega("SOCIAL", 20, ahora - 6 * HORA, "IGNORADA"),
    entrega("CURIOSIDAD", 30, ahora - 10 * HORA, "IGNORADA")
  ];
  assert.equal(evaluarIniciativa(input).motivoEspera, "ignorada_repetida");
});

test("negative response suppresses new normal initiatives even at higher priority", () => {
  const input = solicitud();
  input.eventos = [evento("TRANSITO")];
  input.registro = [{ ...entrega("SOCIAL", 20), estado: "RESPONDIDA", respondida: ahora - 2 * HORA, tipoRespuesta: "negativa" }];
  assert.equal(evaluarIniciativa(input).motivoEspera, "respuesta_negativa");
  input.registro[0].tipoRespuesta = "positiva";
  assert.equal(evaluarIniciativa(input).decision, "INICIAR");
});

test("daily budget and category cooldown apply at exact thresholds, without lower-priority starvation", () => {
  const input = solicitud();
  input.memoriaLocal.recentConversation = [];
  input.eventos = [evento("NOTICIA")];
  input.registro = [entrega("NOTICIA", 70, ahora - 23 * HORA)];
  assert.equal(evaluarIniciativa(input).motivoEspera, "cooldown_categoria");
  input.registro[0].entregada = ahora - 24 * HORA;
  assert.equal(evaluarIniciativa(input).decision, "INICIAR");
  input.registro = [6, 10, 14].map(hours => entrega("EVENTO", 90, ahora - hours * HORA, "RESPONDIDA"));
  assert.equal(evaluarIniciativa(input).motivoEspera, "limite_diario");
  input.registro.forEach(item => { item.prioridad = 20; item.categoria = "SOCIAL"; });
  assert.equal(evaluarIniciativa(input).decision, "INICIAR");
});

test("explicit alarms defer normal initiatives without duplicating native delivery", () => {
  const input = solicitud();
  input.eventos = [evento("ALARMA", {
    timestamp: ahora + 60000,
    contexto: { programadoPorUsuario: true, evidencia: "Despertador" }
  }), evento()];
  assert.equal(evaluarIniciativa(input).motivoEspera, "alarma_nativa_prioritaria");
});

test("curiosity requires context and enough time; no fabricated memories", () => {
  const input = solicitud();
  input.memoriaLocal.recentConversation[0].text = "Me interesa astronomia espacial";
  assert.equal(evaluarIniciativa(input).motivoEspera, "espontanea_prematura");
  input.memoriaLocal.recentConversation[0].timestamp = ahora - 30 * HORA;
  assert.equal(evaluarIniciativa(input).iniciativa.categoria, "CURIOSIDAD");
  input.memoriaLocal.recentConversation = [];
  assert.equal(evaluarIniciativa(input).decision, "ESPERAR");
});

test("validation rejects malformed timestamps, states, schedules and sources", () => {
  for (const mutate of [
    input => { input.perfilRitmo.zonaHoraria = "Invalid/Timezone"; },
    input => { input.perfilRitmo.configurado = { dormir: "25:00", despertar: "07:00" }; },
    input => { input.memoriaLocal.recentConversation[0].timestamp = "yesterday"; },
    input => { input.registro = [{ ...entrega("SOCIAL", 20), estado: "BOGUS" }]; },
    input => { input.eventos = [evento("NOTICIA", { fuente: "continuidad" })]; }
  ]) {
    const input = solicitud();
    mutate(input);
    assert.throws(() => validarSolicitudIniciativa(input), { status: 400 });
  }
});

test("unconfigured LLM does not call remote providers", async () => {
  const result = await evaluarAutonomia(solicitud(), {
    ahora, llmConfigurado: false, newsConfigurado: true,
    generar() { assert.fail("Must not call LLM"); },
    noticias() { assert.fail("Must not call News"); }
  });
  assert.equal(result.motivoEspera, "llm_no_configurado");
});

test("beta evaluation never reads calendar events, including for authenticated identities", async () => {
  for (const authUserId of [undefined, "authenticated-test"]) {
    let calendarReads = 0;
    const result = await evaluarAutonomia(solicitud(), {
      ahora, authUserId, llmConfigurado: true, newsConfigurado: false,
      calendario() {
        calendarReads++;
        return [evento("EVENTO", { fuente: "calendario" })];
      },
      generar: async iniciativa => {
        assert.equal(iniciativa.fuente, "continuidad");
        return { used: true, respuesta: "Retomamos tu proyecto?" };
      }
    });
    assert.equal(calendarReads, 0);
    assert.equal(result.decision, "INICIAR");
    assert.equal(result.iniciativa.fuente, "continuidad");
    assert.deepEqual(result.fallosFuentes, []);
  }
});

test("News failure preserves available context, LLM failure never fabricates message", async () => {
  const result = await evaluarAutonomia(solicitud(), {
    ahora, llmConfigurado: true, newsConfigurado: true,
    noticias: async () => { throw new Error("offline"); },
    generar: async () => ({ used: true, respuesta: "Podemos retomar tu proyecto de astronomia." })
  });
  assert.equal(result.decision, "INICIAR");
  assert.deepEqual(result.fallosFuentes, ["noticias_no_disponible"]);
  const failed = await evaluarAutonomia(solicitud(), {
    ahora, llmConfigurado: true, newsConfigurado: false,
    generar: async () => { throw new Error("offline"); }
  });
  assert.equal(failed.motivoEspera, "llm_no_disponible");
  assert.equal(failed.iniciativa, undefined);
});

test("real relevant News generates contextual initiative and explicit reference", async () => {
  const result = await evaluarAutonomia(solicitud(), {
    ahora, llmConfigurado: true, newsConfigurado: true,
    noticias: async () => [{
      titulo: "Avance en astronomia espacial", descripcion: "Nuevo observatorio",
      link: "https://example.org/story", fecha: new Date(ahora - HORA).toISOString()
    }],
    generar: async iniciativa => {
      assert.equal(iniciativa.categoria, "NOTICIA");
      assert.equal(iniciativa.referenciaEvento, "https://example.org/story");
      return { used: true, respuesta: "Una novedad sobre tu interes por la astronomia." };
    }
  });

  assert.equal(result.iniciativa.mensaje, "Una novedad sobre tu interes por la astronomia.");
});

test("sleep starting while the LLM responds cancels delivery", async t => {
  let clock = Date.parse("2026-09-12T21:59:59Z");
  t.mock.method(Date, "now", () => clock);
  const result = await evaluarAutonomia(solicitud(), {
    llmConfigurado: true, newsConfigurado: false,
    generar: async () => {
      clock += 2000;
      return { used: true, respuesta: "Retomamos tu proyecto?" };
    }
  });
  assert.equal(result.motivoEspera, "descanso_probable");
  assert.equal(result.iniciativa, undefined);
});

test("interest extraction excludes generic conversation words", () => {
  const words = interesesDe({ persistentMemories: [{ text: "Me gusta hablar sobre astronomia espacial" }] });
  assert.deepEqual(words, ["astronomia", "espacial"]);
});

test("initiative generator sends factual context to the Dolphin client (no persona blocks)", async () => {
  const originalFetch = globalThis.fetch;
  const env = { url: process.env.DOLPHIN_URL, model: process.env.DOLPHIN_MODEL };
  process.env.DOLPHIN_URL = "http://dolphin.test/v1";
  process.env.DOLPHIN_MODEL = "dolphin-test";
  let request;
  globalThis.fetch = async (url, options) => {
    if (String(url).includes("dolphin.test")) {
      request = JSON.parse(options.body);
      return { ok: true, text: async () => JSON.stringify({ choices: [{ message: { content: "Retomamos el proyecto?" } }] }) };
    }
    throw new Error("offline");
  };
  try {
    const result = await generarIniciativaLLM(
      evaluarIniciativa(solicitud()).iniciativa, solicitud().memoriaLocal, null
    );
    assert.equal(result.used, true);
    assert.equal(result.respuesta, "Retomamos el proyecto?");
    assert.equal(request.model, "dolphin-test");
    assert.ok(request.messages.at(-1).content.includes("Motivo de la iniciativa"));
    assert.ok(!request.messages.some(item => item.content.includes("Identidad base:")));
  } finally {
    globalThis.fetch = originalFetch;
    for (const [k, v] of [["DOLPHIN_URL", env.url], ["DOLPHIN_MODEL", env.model]]) {
      if (v === undefined) delete process.env[k]; else process.env[k] = v;
    }
  }
});
