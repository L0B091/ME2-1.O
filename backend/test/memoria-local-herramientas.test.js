import test from "node:test";
import assert from "node:assert/strict";
import crypto from "node:crypto";
import orquestadorChat, { _setGeocodificar, normalizarUbicacion } from "../orquestador/orquestadorChat.js";
import { obtenerHerramientas } from "../orquestador/contextoLLM.js";
import memoriaConversacional from "../memoria/memoriaConversacional.js";
import { evaluarAutonomia } from "../orquestador/orquestadorNotificaciones.js";

process.env.ME2_DEV_DEFAULT_LOCATION = "false";
const memoriaAndroid = extra => ({ source: "android_local_primary", recentConversation: [], persistentMemories: [], importantMemories: [], ...extra });

test("A5: Android recibe gustos y ubicación geocodificada para su memoria local (sin persistir en el servidor)", async () => {
  _setGeocodificar(async nombre => ({ ciudad: nombre, lat: -32.95, lon: -60.66, zonaHoraria: "America/Argentina/Cordoba" }));
  const userId = `a5-${crypto.randomBytes(4).toString("hex")}`;
  try {
    const r = await orquestadorChat("Vivo en Rosario y me gusta el ajedrez", { userId, memoriaLocal: memoriaAndroid() });
    assert.deepEqual(r.memoriaLocalDelta.gustos, ["ajedrez"]);
    assert.equal(r.memoriaLocalDelta.ubicacion.ciudad, "Rosario");
    assert.equal(r.memoriaLocalDelta.ubicacion.lat, -32.95);
    assert.match(r.debug.contexto, /Gustos del usuario: ajedrez/);
    assert.doesNotMatch(r.debug.contexto, /ubicación del usuario es desconocida/);
    assert.equal(memoriaConversacional.obtener(userId).gustos.length, 0, "el servidor no guarda la memoria de Android");
  } finally {
    _setGeocodificar(null);
  }
});

test("A5: gustos y ubicación guardados en el teléfono habilitan clima y noticias en turnos siguientes", async () => {
  const memoria = { gustos: ["astronomía"], ubicacion: normalizarUbicacion({ ciudad: "Rosario", lat: -32.95, lon: -60.66 }) };
  // La ubicación de la memoria local llega a las herramientas (sin red: se observa el pedido).
  const h = await obtenerHerramientas("u", { memoria, zonaHoraria: "America/Argentina/Buenos_Aires" }).catch(() => null);
  assert.ok(h);
  assert.equal(h.ubicacion.ciudad, "Rosario");
  assert.notEqual(h.noticias?.motivo, "sin_intereses_registrados");
});

test("A5: lat/lon nulos del cliente no se interpretan como 0,0", async () => {
  const h = await obtenerHerramientas("u", { lat: null, lon: null, memoria: {} });
  assert.equal(h.ubicacion, null);
});

test("A5: normalizarUbicacion exige coordenadas válidas", () => {
  assert.equal(normalizarUbicacion({ ciudad: "X" }), null);
  assert.equal(normalizarUbicacion({ lat: 200, lon: 0 }), null);
  assert.deepEqual(normalizarUbicacion({ ciudad: "Rosario", lat: "-32.9", lon: "-60.6", zonaHoraria: "America/Argentina/Cordoba" }),
    { ciudad: "Rosario", lat: -32.9, lon: -60.6, zonaHoraria: "America/Argentina/Cordoba" });
});

test("A5: las iniciativas usan los gustos enviados por Android (memoriaLocal.gustos)", async () => {
  const ahora = Date.parse("2026-09-12T15:00:00Z");
  const result = await evaluarAutonomia({
    userId: `a5i-${crypto.randomBytes(4).toString("hex")}`, ahora,
    memoriaLocal: { recentConversation: [], persistentMemories: [], importantMemories: [], gustos: ["ajedrez"] },
    registro: [], perfilRitmo: { zonaHoraria: "UTC", observaciones: [] },
    disponibilidad: { enPrimerPlano: false, notificacionesHabilitadas: true }, eventos: []
  }, {
    ahora, llmConfigurado: true, newsConfigurado: true, climaConfigurado: false,
    noticias: async () => [{ titulo: "Torneo mundial de ajedrez en Buenos Aires", descripcion: "", link: "https://example.org/ajedrez", fecha: new Date(ahora - 3600e3).toISOString() }],
    generar: async iniciativa => ({ used: true, respuesta: `Novedad: ${iniciativa.referenciaEvento}` })
  });
  assert.equal(result.decision, "INICIAR");
  assert.equal(result.iniciativa.mensaje, "Novedad: https://example.org/ajedrez");
});
