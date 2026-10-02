import test from "node:test";
import assert from "node:assert/strict";
import storage from "../utils/jsonStorage.js";
import historialIniciativas from "../memoria/historialIniciativas.js";
import memoriaConversacional from "../memoria/memoriaConversacional.js";
import estadoEmocional from "../memoria/estadoEmocional.js";
import { evaluarAutonomia, suenoElegible, recuerdoRelevante, fuentesCompania, aplicarRotacion } from "../orquestador/orquestadorNotificaciones.js";

const H = 3600e3, D = 24 * H;
// Hora local Buenos Aires (UTC-3)
const local = (iso) => Date.parse(`${iso}-03:00`);
const NS = ["iniciativas_fuentes", "memoria_llm", "estado_emocional", "continuidad"];
let n = 0;
function usuario(memoria = {}) {
  const u = `test-fuentes-${process.pid}-${Date.now()}-${n++}`;
  if (Object.keys(memoria).length) storage.writeUserData("memoria_llm", u, { userId: u, gustos: [], disgustos: [], hechos: [], ...memoria });
  return u;
}
const limpiar = u => NS.forEach(ns => storage.writeUserData(ns, u, {}));
const sinRed = { juegosEnTendencia: async () => [{ nombre: "Juego Real X" }], peliculasEnTendencia: async () => [{ titulo: "Peli Terror", genero: "Terror" }, { titulo: "Peli Comedia", genero: "Comedia" }] };

function solicitud(userId, ahora) {
  return {
    userId, ahora, memoriaLocal: { recentConversation: [], persistentMemories: [], importantMemories: [] },
    registro: [], perfilRitmo: { zonaHoraria: "America/Argentina/Buenos_Aires", observaciones: [], ultimaInteraccion: ahora - 30 * H },
    disponibilidad: { enPrimerPlano: false, notificacionesHabilitadas: true }, eventos: []
  };
}
const opts = (ahora, extra = {}) => ({
  ahora, llmConfigurado: true, newsConfigurado: false, climaConfigurado: false, tendencias: sinRed,
  calendarioCompania: async () => [], generar: async () => ({ used: true, respuesta: "ok" }), ...extra
});

test("sueño: solo de mañana, cooldown ≥3 días, nunca dos veces seguidas, después de la alarma", () => {
  const u = usuario();
  const lunes8 = local("2026-10-05T08:00:00");
  assert.equal(suenoElegible(u, {}, lunes8).elegible, true);
  assert.equal(suenoElegible(u, {}, local("2026-10-05T15:00:00")).motivo, "fuera_de_ventana");
  assert.equal(suenoElegible(u, {}, local("2026-10-05T07:00:00"), [{ hora: "07:30" }]).motivo, "antes_de_alarma");
  assert.match(suenoElegible(u, {}, lunes8, [{ hora: "07:30" }]).evidencia, /alarma de las 07:30 ya sonó/);
  historialIniciativas.registrar(u, { fuente: "sueno" }, lunes8);
  historialIniciativas.registrar(u, { fuente: "clima" }, lunes8 + D + H);
  assert.equal(suenoElegible(u, {}, lunes8 + D).motivo, "cooldown_dias");       // no 2 días seguidos
  assert.equal(suenoElegible(u, {}, lunes8 + 2 * D).motivo, "cooldown_dias");
  assert.equal(suenoElegible(u, {}, lunes8 + 3 * D).elegible, true);            // ≥3 días
  const u2 = usuario();
  historialIniciativas.registrar(u2, { fuente: "sueno" }, lunes8 - 5 * D);      // fuera de cooldown pero fue la última
  assert.equal(suenoElegible(u2, {}, lunes8).motivo, "repetida");
  limpiar(u2);
  limpiar(u);
});

test("sueño: respuesta del usuario actualiza estado emocional y memoria", () => {
  const u = usuario();
  memoriaConversacional.registrar(u, "Dormí re mal, me desperté mil veces.");
  estadoEmocional.registrar(u, "Dormí re mal, me desperté mil veces.");
  assert.match(memoriaConversacional.obtener(u).hechos.join(" "), /sueño: dormí re mal/);
  assert.equal(estadoEmocional.obtener(u).actual, "cansancio");
  limpiar(u);
});

test("recuerdo_relevante: solo memoria guardada, rankeada por gustos/pendientes; sin memoria no es elegible", () => {
  const ahora = local("2026-10-05T16:00:00");
  assert.equal(recuerdoRelevante(usuario(), { hechos: [] }, ahora), null);
  const hechos = ["trabajo de contador (dicho el 2026-10-01)", "mi perro se llama Toby y le encanta Boca (dicho el 2026-10-01)"];
  const r = recuerdoRelevante(null, { hechos, gustos: ["boca"] }, ahora);
  assert.equal(r.hecho, hechos[1]);
  assert.ok(r.relacion.includes("gustos"));
  assert.ok(hechos.includes(r.hecho));
});

test("compañía: elegibilidad por horario, gustos, clima y agenda", async () => {
  const u = usuario();
  const mem = { gustos: ["videojuegos", "terror"] };
  const sab15 = await fuentesCompania(u, mem, local("2026-10-03T15:00:00"), { tendencias: sinRed });
  assert.deepEqual(sab15.map(e => e.fuente).sort(), ["juego_pasatiempo", "pelicula_juntos", "salida_pareja"]);
  assert.match(sab15.find(e => e.fuente === "juego_pasatiempo").contexto.evidencia, /Juego Real X/);
  assert.match(sab15.find(e => e.fuente === "pelicula_juntos").contexto.evidencia, /Peli Terror \(Terror\)/);
  const lun11 = await fuentesCompania(u, mem, local("2026-10-05T11:00:00"), { tendencias: sinRed, calendario: async () => [{ fecha: "2026-10-10", hora: "20:00", descripcion: "cumple de Ana" }] });
  assert.deepEqual(lun11.map(e => e.fuente), ["planear_salida"]);
  assert.match(lun11[0].contexto.evidencia, /sábado 2026-10-10.*cumple de Ana \(evitar ese horario\)/);
  const ocupado = await fuentesCompania(u, mem, local("2026-10-05T11:00:00"), { calendario: async () => [{ fecha: "2026-10-10", hora: "10:00", descripcion: "a" }, { fecha: "2026-10-11", hora: "10:00", descripcion: "b" }] });
  assert.equal(ocupado.length, 0);
  const sinGustos = await fuentesCompania(u, {}, local("2026-10-03T15:00:00"), { tendencias: sinRed });
  assert.deepEqual(sinGustos.map(e => e.fuente), ["salida_pareja"]);
  const lluvia = await fuentesCompania(u, {}, local("2026-10-02T20:00:00"), { clima: { temperatura: 12, descripcion: "lluvia moderada" }, ubicacion: { ciudad: "San Nicolás" } });
  assert.match(lluvia[0].contexto.evidencia, /San Nicolás.*bajo techo/);
  const sinTendencia = await fuentesCompania(u, { gustos: ["videojuegos"] }, local("2026-10-03T15:00:00"), { tendencias: { juegosEnTendencia: async () => { throw new Error("off"); }, peliculasEnTendencia: async () => [] } });
  assert.match(sinTendencia.find(e => e.fuente === "juego_pasatiempo").contexto.evidencia, /Sin datos de tendencias/);
  historialIniciativas.registrar(u, { fuente: "salida_pareja" }, local("2026-10-03T16:00:00"));
  const otroDia = await fuentesCompania(u, mem, local("2026-10-04T15:00:00"), { tendencias: sinRed });
  assert.ok(!otroDia.some(e => e.fuente === "salida_pareja"), "cooldown de salida_pareja");
  limpiar(u);
});

test("rotación: la última fuente no se repite (salvo recordatorio)", () => {
  const u = usuario();
  historialIniciativas.registrar(u, { fuente: "clima" }, 1);
  const evs = [{ contexto: { fuenteServidor: "clima" } }, { contexto: { fuenteServidor: "sueno" } }, { fuente: "calendario", categoria: "EVENTO", contexto: {} }];
  assert.deepEqual(aplicarRotacion(u, evs).map(e => e.contexto.fuenteServidor || e.fuente), ["sueno", "calendario"]);
  limpiar(u);
});

test("evaluar en secuencia: fuentes reales rotan sin repetirse y sueño respeta cooldown", async () => {
  const u = usuario({ gustos: ["videojuegos", "terror"], hechos: ["mi perro se llama Toby (dicho el 2026-10-01)", "trabajo de contador (dicho el 2026-10-01)"] });
  const elegidas = [];
  let ahora = local("2026-10-03T09:00:00"); // sábado
  for (let i = 0; i < 8; i++) {
    const r = await evaluarAutonomia(solicitud(u, ahora), opts(ahora));
    elegidas.push({ dia: new Date(ahora).toISOString().slice(0, 13), decision: r.decision, fuente: r.fuenteElegida || r.motivoEspera });
    ahora += r.decision === "INICIAR" ? 25 * H : 4 * H; // siguiente evaluación (fuera de cooldown de categoría)
  }
  const fuentes = elegidas.filter(e => e.decision === "INICIAR").map(e => e.fuente);
  assert.ok(fuentes.length >= 5, JSON.stringify(elegidas));
  for (let i = 1; i < fuentes.length; i++) assert.notEqual(fuentes[i], fuentes[i - 1], `repetida: ${fuentes}`);
  const usos = historialIniciativas.obtener(u).usadas.filter(x => x.fuente === "sueno").map(x => x.timestamp);
  for (let i = 1; i < usos.length; i++) assert.ok(usos[i] - usos[i - 1] >= 3 * D);
  const recuerdos = historialIniciativas.obtener(u).usadas.filter(x => x.fuente === "recuerdo_relevante");
  assert.ok(recuerdos.length >= 1);
  console.log("secuencia:", JSON.stringify(elegidas));
  limpiar(u);
});
