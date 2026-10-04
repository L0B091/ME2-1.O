// backend/modulos/protocoloDespertador.js

import obtenerClima from "../api/clima.js";
import gestorDeAlarmas from "./gestorDeAlarmas.js";
import notificacionesApi from "../api/notificaciones.js";
import memoriaConversacional from "../memoria/memoriaConversacional.js";

export const ESPERA_ENTRE_INTENTOS_MS = 5 * 60 * 1000;

// El protocolo lo EJECUTA Android (AlarmManager: intento 1 → 5 min → intento 2 → 5 min → intento 3 alarma fuerte).
// El backend solo define los stages y registra los eventos que informa el teléfono. Los textos de notificación son
// etiquetas de sistema neutras (sin voz del personaje): con red la voz es de Dolphin; sin red, el banco offline.
const ETIQUETA_STAGE = {
  1: hora => `Alarma${hora ? ` · ${hora}` : ""}`,
  2: hora => `Alarma${hora ? ` · ${hora}` : ""} · segundo aviso`,
  3: hora => `Alarma${hora ? ` · ${hora}` : ""} · último aviso`
};

function etiquetaStage(stage, hora = null) {
  const f = ETIQUETA_STAGE[Number(stage)];
  return f ? f(hora) : "Alarma";
}

function obtenerMensajesStage(stage, hora = null) {
  return [etiquetaStage(stage, hora)];
}

function obtenerDefinicionStages(hora = null) {
  return [1, 2, 3].map((stage) => {
    const mensajes = obtenerMensajesStage(stage, hora);
    return {
      stage,
      channelId: stage >= 3 ? "ME2_ALARMS" : "ME2_MESSAGES",
      notificationType: stage >= 3 ? "alarm" : "message",
      vibration: stage >= 3 ? "alarm" : "double",
      sound: stage >= 3 ? "alarm" : "bubble",
      // Protocolo: intento 1 → 5 min → intento 2 → 5 min → intento 3 (alarma fuerte/persistente).
      delayToNextStageMs: stage < 3 ? ESPERA_ENTRE_INTENTOS_MS : 0,
      mensajes
    };
  });
}

/** Plan de los 3 intentos para Android (etiquetas del orquestador; offset desde la hora de la alarma). */
function despachosAndroid(alarma) {
  if (!alarma) return [];
  let offsetMs = 0;
  return obtenerDefinicionStages(alarma.hora || null).map((stage) => {
    const despacho = {
      stage: stage.stage,
      offsetFromAlarmMs: offsetMs,
      channelId: stage.channelId,
      notificationType: stage.notificationType,
      vibration: stage.vibration,
      sound: stage.sound,
      titulo: alarma.titulo || "Alarma",
      mensaje: stage.mensajes[0] || alarma.mensaje || "ME2 registró tu protocolo de despertar."
    };
    offsetMs += stage.delayToNextStageMs;
    return despacho;
  });
}

/** Clima como DATO (sin frase armada) para que Dolphin lo use si corresponde. */
async function climaComoDato(coords = null) {
  if (!Number.isFinite(Number(coords?.lat)) || !Number.isFinite(Number(coords?.lon))) return null;
  try {
    const c = await obtenerClima(Number(coords.lat), Number(coords.lon), { timeoutMs: 6000 });
    return Number.isFinite(c?.temperatura) ? { temperatura: c.temperatura, descripcion: c.descripcion || null } : null;
  } catch {
    return null;
  }
}

async function registrarRespuestaUsuario(userID, alarmId = null) {
  const ubicacion = memoriaConversacional.obtener(userID)?.ubicacion || null;
  const clima = await climaComoDato(ubicacion);
  gestorDeAlarmas.cerrarAlarma(userID, alarmId);
  return { estado: "respondio", mensaje: null, clima };
}

function registrarDisparoAndroid(userID, stage, alarmId = null) {
  const alarma = gestorDeAlarmas.obtenerAlarma(userID, alarmId);
  if (!alarma || alarma.estado !== "ACTIVE") {
    return null;
  }

  const mensaje = etiquetaStage(stage, alarma.hora);
  notificacionesApi.notificarAlarma(userID, mensaje);

  if (Number(stage) >= 3) {
    gestorDeAlarmas.cerrarAlarma(userID, alarmId);
    return {
      estado: "alarmaSonora",
      mensaje
    };
  }

  gestorDeAlarmas.actualizarAlarma(userID, {
    stage: Number(stage) + 1,
    intentos: Number(alarma.intentos || 0) + 1,
    ultimoDisparoStage: Number(stage)
  }, alarmId);

  return {
    estado: "stageProgramado",
    mensaje,
    stageSiguiente: Number(stage) + 1
  };
}

export default {
  obtenerDefinicionStages,
  despachosAndroid,
  registrarRespuestaUsuario,
  registrarDisparoAndroid
}; 
