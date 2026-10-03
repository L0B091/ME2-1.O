import test from "node:test";
import assert from "node:assert/strict";
import storage from "../utils/jsonStorage.js";
import historialIniciativas from "../memoria/historialIniciativas.js";
import { evaluarAutonomia } from "../orquestador/orquestadorNotificaciones.js";
import orquestador from "../orquestador/orquestadorChat.js";

const H = 3600e3;
const local = (iso) => Date.parse(`${iso}-03:00`);

test("prefetch: iniciativa generada por el LLM para entrega diferida, sin registrar rotación", async () => {
  const u = `test-prefetch-${process.pid}-${Date.now()}`;
  storage.writeUserData("memoria_llm", u, { userId: u, gustos: [], disgustos: [], hechos: ["mi perro se llama Toby (dicho el 2026-10-01)"] });
  const entregar = local("2026-10-03T11:00:00");
  const r = await evaluarAutonomia({
    userId: u, memoriaLocal: { recentConversation: [], persistentMemories: [], importantMemories: [] }, registro: [],
    perfilRitmo: { zonaHoraria: "America/Argentina/Buenos_Aires", observaciones: [], ultimaInteraccion: entregar - 30 * H },
    disponibilidad: { enPrimerPlano: false, notificacionesHabilitadas: true }, eventos: []
  }, {
    ahora: entregar, prefetch: true, llmConfigurado: true, newsConfigurado: false, climaConfigurado: false,
    tendencias: { juegosEnTendencia: async () => [], peliculasEnTendencia: async () => [] }, calendarioCompania: async () => [],
    generar: async () => ({ used: true, respuesta: "texto del LLM" })
  });
  assert.equal(r.decision, "INICIAR", JSON.stringify(r));
  assert.equal(r.iniciativa.diferida, true);
  assert.equal(r.iniciativa.entregarDesde, entregar);
  assert.equal(r.iniciativa.mensaje, "texto del LLM");
  assert.ok(r.iniciativa.expiresAt > entregar);
  assert.equal(historialIniciativas.obtener(u).usadas.length, 0);
  ["iniciativas_fuentes", "memoria_llm"].forEach(ns => storage.writeUserData(ns, u, {}));
});

test("chat con memoria local primaria: la alarma se crea/cancela en el teléfono (acción local)", async () => {
  const contexto = { userId: `test-local-${process.pid}`, memoriaLocal: { source: "android_local_primary", recentConversation: [] } };
  const crear = await orquestador("despertame a las 7:30", contexto);
  assert.equal(crear.acciones.alarma.accion, "crear_local");
  assert.equal(crear.acciones.alarma.hora, "07:30");
  const cancelar = await orquestador("cancelá la alarma", contexto);
  assert.equal(cancelar.acciones.alarma.accion, "cancelar_local");
});
