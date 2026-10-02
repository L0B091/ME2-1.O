// backend/modulos/protocoloDespertador.js

import obtenerClima from "../api/clima.js";
import gestorDeAlarmas from "./gestorDeAlarmas.js";
import notificacionesApi from "../api/notificaciones.js";

const VIBRACION_INTERVALO_MS = 600;
const MENSAJE_INTERVALO_MS = 1500;

/**
* Utilidad de espera
*/
function delay(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

/**
* Vibración suave en 2 pulsos
*/
async function vibracionDoble() {
  console.log("📳 vibración");
  await delay(VIBRACION_INTERVALO_MS);
  console.log("📳 vibración");
}

/**
* Push tipo WhatsApp simulado
*/
async function push(userID, mensaje) {
  console.log(`📩 [${userID}] ${mensaje}`);
  notificacionesApi.notificarAlarma(userID, mensaje);
}

const BA_DEFAULT = { lat: -33.3342, lon: -60.2108 }; // San Nicolás de los Arroyos

/**
 * Prefer stored user coords when available; otherwise Buenos Aires default.
 * Never call OpenWeather with (0,0).
 */
async function obtenerMensajeClimaSeguro(coords = null) {
  try {
    const lat = Number.isFinite(Number(coords?.lat)) ? Number(coords.lat) : BA_DEFAULT.lat;
    const lon = Number.isFinite(Number(coords?.lon)) ? Number(coords.lon) : BA_DEFAULT.lon;
    const clima = await obtenerClima(lat, lon);
    return Number.isFinite(clima?.temperatura)
      ? `${Math.round(clima.temperatura)}°C, ${clima.descripcion || ""}`.trim()
      : "Buen día. Ya registré tu despertar.";
  } catch (error) {
    return "Buen día. Ya registré tu despertar.";
  }
}

/**
* Mensajes por nivel de insistencia
*/
const mensajesPorStage = {
  1: [
    "Buenos días ☀️ es hora de despertar",
    "Ey… ya es momento de levantarse 😴",
    "El día ya empezó, vamos!"
  ],
  2: [
    "Segunda llamada ⏰ despertá",
    "No te duermas otra vez 😏",
    "Último aviso antes de la alarma fuerte"
  ],
  3: [
    "⚠️ ÚLTIMO INTENTO",
    "Esto ya es serio… despertate",
    "Activando alarma sonora"
  ]
};

function obtenerMensajesStage(stage) {
  return [...(mensajesPorStage[Number(stage)] || [])];
}

function obtenerDefinicionStages() {
  return [1, 2, 3].map((stage) => {
    const mensajes = obtenerMensajesStage(stage);
    return {
      stage,
      channelId: stage >= 3 ? "ME2_ALARMS" : "ME2_MESSAGES",
      notificationType: stage >= 3 ? "alarm" : "message",
      vibration: stage >= 3 ? "alarm" : "double",
      sound: stage >= 3 ? "alarm" : "bubble",
      delayToNextStageMs:
        stage < 3
          ? mensajes.length * (VIBRACION_INTERVALO_MS + MENSAJE_INTERVALO_MS)
          : 0,
      mensajes
    };
  });
}

async function registrarRespuestaUsuario(userID, alarmId = null) {
  const mensajeClima = await obtenerMensajeClimaSeguro();
  await push(userID, `🌤 ${mensajeClima}`);
  gestorDeAlarmas.cerrarAlarma(userID, alarmId);
  return {
    estado: "respondio",
    mensaje: mensajeClima
  };
}

function registrarDisparoAndroid(userID, stage, alarmId = null) {
  const alarma = gestorDeAlarmas.obtenerAlarma(userID, alarmId);
  if (!alarma || alarma.estado !== "ACTIVE") {
    return null;
  }

  const mensajes = obtenerMensajesStage(stage);
  const mensaje = mensajes[0] || "Hora de despertar";
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

/**
* Ejecuta una ronda de mensajes del stage actual
*/
async function ejecutarStage(userID, stage, respuestaUsuario = false) {
  const mensajes = obtenerMensajesStage(stage);

  for (const msg of mensajes) {
    await vibracionDoble();
    await push(userID, msg);
    await delay(1500);

    // Si el usuario responde en cualquier momento
    if (respuestaUsuario) {
      return registrarRespuestaUsuario(userID);
    }
  }

  return null;
}

/**
* Protocolo principal de despertador
*/
async function ejecutarAlarma(userID, respuestaUsuario = false) {
  const alarma = gestorDeAlarmas.obtenerAlarma(userID);

  if (!alarma || alarma.estado !== "ACTIVE") {
    return null;
  }

  let stage = alarma.stage || 1;

  // Ejecuta stage actual
  const resultado = await ejecutarStage(userID, stage, respuestaUsuario);

  // Si respondió → termina todo
  if (resultado) return resultado;

  // -------------------------
  // ESCALADO
  // -------------------------
  if (stage < 3) {
    gestorDeAlarmas.actualizarAlarma(userID, {
      stage: stage + 1,
      intentos: (alarma.intentos || 0) + 1
    });

    console.log(`⬆️ Escalando a stage ${stage + 1}`);

    return ejecutarAlarma(userID, respuestaUsuario);
  }

  // -------------------------
  // STAGE 3 FINAL → ALARMA SONORA
  // -------------------------
  console.log("🚨 ALARMA SONORA ACTIVADA");

  gestorDeAlarmas.cerrarAlarma(userID);

  return {
    estado: "alarmaSonora",
    mensaje: "Alarma sonora activada tras 3 intentos"
  };
}

export default {
  ejecutarAlarma,
  obtenerDefinicionStages,
  registrarRespuestaUsuario,
  registrarDisparoAndroid
}; 
