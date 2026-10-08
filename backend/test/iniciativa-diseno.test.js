import test from "node:test";
import assert from "node:assert/strict";
import storage from "../utils/jsonStorage.js";
import { evaluarAutonomia, suenoElegible, hechosDelTelefono } from "../orquestador/orquestadorNotificaciones.js";
import orquestadorChat from "../orquestador/orquestadorChat.js";
import protocoloDespertador from "../modulos/protocoloDespertador.js";

const H = 3600e3;
const local = (iso) => Date.parse(`${iso}-03:00`);
let n = 0;
const usuario = () => `test-diseno-${process.pid}-${Date.now()}-${n++}`;
const limpiar = u => ["iniciativas_fuentes", "memoria_llm", "estado_emocional", "continuidad"].forEach(ns => storage.writeUserData(ns, u, {}));

function solicitud(userId, ahora, memoriaLocal = {}, eventos = []) {
  return {
    userId, ahora, memoriaLocal: { recentConversation: [], persistentMemories: [], importantMemories: [], ...memoriaLocal },
    registro: [], perfilRitmo: { zonaHoraria: "America/Argentina/Buenos_Aires", observaciones: [], ultimaInteraccion: ahora - 30 * H },
    disponibilidad: { enPrimerPlano: false, notificacionesHabilitadas: true }, eventos
  };
}
const opts = (ahora, generar) => ({
  ahora, llmConfigurado: true, newsConfigurado: false, climaConfigurado: false, companiaConfigurada: false,
  calendario: async () => [], generar
});

test("iniciativa/recuerdo: con memoria en el teléfono, el recuerdo citado es una frase REAL del usuario (y sin memorias no hay recuerdo)", async () => {
  const ahora = local("2026-10-08T16:00:00");
  const frase = "Me gusta ir a pescar al río con mi viejo";
  const nota = { category: "general", text: frase, importance: 2, timestamp: ahora - 2 * 24 * H };
  assert.deepEqual(hechosDelTelefono({ persistentMemories: [nota] }), [`${frase} (dicho el 2026-10-06)`]);
  assert.deepEqual(hechosDelTelefono({ persistentMemories: [{ text: "sin fecha" }] }), [], "sin timestamp no es un recuerdo verificable");

  const u = usuario();
  let pedido = null;
  const r = await evaluarAutonomia(solicitud(u, ahora, { persistentMemories: [nota] }), opts(ahora, async (ini) => { pedido = ini; return { used: true, respuesta: "¿Fuiste a pescar?" }; }));
  assert.equal(r.decision, "INICIAR");
  assert.equal(r.fuenteElegida, "recuerdo_relevante");
  assert.match(pedido.contexto.evidencia, /pescar al río con mi viejo/);
  limpiar(u);

  const u2 = usuario();
  const r2 = await evaluarAutonomia(solicitud(u2, ahora), opts(ahora, async () => ({ used: true, respuesta: "x" })));
  assert.notEqual(r2.fuenteElegida, "recuerdo_relevante");
  limpiar(u2);
});

test("iniciativa/sueño: zona fija de ME2 y alarma matinal del TELÉFONO (evento ALARMA) respetada", () => {
  const u = usuario();
  const alarma0800 = [{ hora: "08:00" }];
  assert.equal(suenoElegible(u, {}, local("2026-10-08T07:30:00"), alarma0800).motivo, "antes_de_alarma");
  assert.equal(suenoElegible(u, {}, local("2026-10-08T08:30:00"), alarma0800).elegible, true);
  // La ubicación con otra zona no cambia "la mañana" de ME2.
  assert.equal(suenoElegible(u, { ubicacion: { ciudad: "Madrid", lat: 40.4, lon: -3.7, zonaHoraria: "Europe/Madrid" } }, local("2026-10-08T15:00:00")).motivo, "fuera_de_ventana");
  limpiar(u);
});

test("iniciativa/sueño: el evento ALARMA que manda Android bloquea '¿cómo dormiste?' antes de que suene", async () => {
  const u = usuario();
  const ahora = local("2026-10-08T07:30:00");
  const alarma = { id: "a1", referenciaEvento: "a1", categoria: "ALARMA", motivo: "Alarma", timestamp: local("2026-10-08T08:00:00"),
    expiresAt: local("2026-10-08T08:11:00"), fuente: "android_alarm_manager", contexto: { programadoPorUsuario: true, evidencia: "Alarma 08:00" } };
  const r = await evaluarAutonomia(solicitud(u, ahora, {}, [alarma]), { ...opts(ahora, async () => ({ used: true, respuesta: "x" })), debugFuentes: true });
  assert.notEqual(r.fuenteElegida, "sueno");
  // Lejos de la hora de la alarma (no gana "alarma_nativa_prioritaria"): igual el sueño queda bloqueado por la alarma del teléfono.
  const temprano = local("2026-10-08T06:10:00");
  const s2 = solicitud(u, temprano, {}, [{ ...alarma, timestamp: local("2026-10-08T11:00:00"), expiresAt: local("2026-10-08T11:11:00") }]);
  s2.perfilRitmo.configurado = { dormir: "00:00", despertar: "06:00" };
  const r2 = await evaluarAutonomia(s2,
    { ...opts(temprano, async () => ({ used: true, respuesta: "x" })), debugFuentes: true });
  assert.ok(r2.fallosFuentes.includes("sueno_no_elegible:antes_de_alarma"), JSON.stringify(r2));
  limpiar(u);
});

test("chat: la respuesta a '¿cómo dormiste?' se guarda en el estado del usuario (nota real para el teléfono)", async () => {
  const u = usuario();
  const r = await orquestadorChat("Dormí bastante bien, como 8 horas", {
    userId: u, timestamp: local("2026-10-08T09:00:00"),
    memoriaLocal: { source: "android_local_primary", recentConversation: [] },
    iniciativa: { id: "i1", categoria: "CONVERSACION", fuente: "sueno", mensaje: "¿Cómo dormiste?", contexto: { fuenteServidor: "sueno" } }
  });
  assert.deepEqual(r.memoriaLocalDelta?.notas, [{ categoria: "sueno", texto: "Cómo durmió (2026-10-08): Dormí bastante bien, como 8 horas" }]);
  const otro = await orquestadorChat("hola", { userId: u, memoriaLocal: { source: "android_local_primary", recentConversation: [] } });
  assert.equal(otro.memoriaLocalDelta?.notas, undefined, "solo si responde a la iniciativa de sueño");
  limpiar(u);
});

test("despertador: plan del orquestador = aviso 1 y 2 (vibración+notificación, 5 min) y aviso 3 alarma fuerte", () => {
  const plan = protocoloDespertador.despachosAndroid({ hora: "14:25", titulo: "Alarma" });
  assert.deepEqual(plan.map(p => [p.stage, p.offsetFromAlarmMs, p.notificationType, p.vibration]), [
    [1, 0, "message", "double"], [2, 5 * 60e3, "message", "double"], [3, 10 * 60e3, "alarm", "alarm"]
  ]);
});
