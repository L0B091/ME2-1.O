import fs from "fs";
import path from "path";

/**
* GESTOR DE ALARMAS JOI
* Backend liviano: administra datos y sincronización
* La ejecución real ocurre en el cliente móvil oficial
*/

const DB_PATH = path.resolve("./backend/data/alarmas.json");

function normalizarHora(hora) {
  const value = String(hora || "").trim();
  if (!/^\d{2}:\d{2}$/.test(value)) return null;
  const [horas, minutos] = value.split(":").map(Number);
  if (!Number.isInteger(horas) || !Number.isInteger(minutos)) return null;
  if (horas < 0 || horas > 23 || minutos < 0 || minutos > 59) return null;
  return `${String(horas).padStart(2, "0")}:${String(minutos).padStart(2, "0")}`;
}

/* =========================================================
   UTILIDADES
========================================================= */

function leerDB() {
  try {
    if (!fs.existsSync(DB_PATH)) return [];
    const data = fs.readFileSync(DB_PATH, "utf-8");
    return JSON.parse(data || "[]");
  } catch (err) {
    console.error("❌ Error leyendo alarmas:", err);
    return [];
  }
}

function escribirDB(alarmas) {
  try {
    fs.mkdirSync(path.dirname(DB_PATH), { recursive: true });
    fs.writeFileSync(DB_PATH, JSON.stringify(alarmas, null, 2));
  } catch (err) {
    console.error("❌ Error guardando alarmas:", err);
  }
}

/* =========================================================
   API PRINCIPAL
========================================================= */

/**
* Crear alarma
*/
function crearAlarma(userID, hora, data = {}) {
  const alarmas = leerDB();
  const horaNormalizada = normalizarHora(hora);
  if (!userID || !horaNormalizada) {
    throw new Error("Datos de alarma inválidos");
  }

  const duplicada = alarmas.find((alarma) =>
    alarma.userID === userID &&
    alarma.estado === "ACTIVE" &&
    alarma.hora === horaNormalizada &&
    String(alarma.titulo || "Hora de despertar") === String(data.titulo || "Hora de despertar")
  );

  if (duplicada) {
    return actualizarAlarma(userID, {
      ...data,
      hora: horaNormalizada
    }, duplicada.id);
  }

  const nuevaAlarma = {
    id: Date.now().toString(),
    userID,
    hora: horaNormalizada,
    titulo: data.titulo || "Hora de despertar",
    mensaje: data.mensaje || "ME2 registró tu protocolo de despertar.",
    stage: Number(data.stage || 1),
    intentos: Number(data.intentos || 0),
    estado: "ACTIVE",
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString()
  };

  alarmas.push(nuevaAlarma);
  escribirDB(alarmas);

  return nuevaAlarma;
}

/**
* Obtener todas las alarmas
*/
function obtenerAlarmas() {
  return leerDB();
}

/**
* Obtener alarmas activas
*/
function obtenerAlarmasActivas() {
  const alarmas = leerDB();
  return alarmas.filter(a => a.estado === "ACTIVE");
}

/**
* Obtener alarmas por usuario
*/
function obtenerAlarmasPorUsuario(userID) {
  const alarmas = leerDB();
  return alarmas.filter(
    a => a.userID === userID && a.estado === "ACTIVE"
  );
}

function obtenerAlarma(userID, alarmId = null) {
  const alarmas = leerDB();
  return alarmas.find(
    (a) =>
      a.userID === userID &&
      a.estado === "ACTIVE" &&
      (alarmId ? a.id === alarmId : true)
  ) ?? null;
}

/**
* Cerrar alarma (cuando el usuario la apaga desde el cliente)
*/
function cerrarAlarma(userID, alarmId = null) {
  const alarmas = leerDB();

  const actualizadas = alarmas.map(a => {
    if (
      a.userID === userID &&
      a.estado === "ACTIVE" &&
      (alarmId ? a.id === alarmId : true)
    ) {
      return {
        ...a,
        estado: "RESOLVED",
        updatedAt: new Date().toISOString()
      };
    }
    return a;
  });

  escribirDB(actualizadas);
}

function actualizarAlarma(userID, data = {}, alarmId = null) {
  const alarmas = leerDB();

  const actualizadas = alarmas.map((a) => {
    if (
      a.userID === userID &&
      a.estado === "ACTIVE" &&
      (alarmId ? a.id === alarmId : true)
    ) {
      return {
        ...a,
        ...data,
        updatedAt: new Date().toISOString()
      };
    }
    return a;
  });

  escribirDB(actualizadas);
  return obtenerAlarma(userID, alarmId);
}

/**
* Sincronizar cambios desde el cliente móvil
*/
function actualizarDesdeApp(alarmId, data = {}) {
  const alarmas = leerDB();

  const actualizadas = alarmas.map(a => {
    if (a.id === alarmId) {
      return {
        ...a,
        ...data,
        updatedAt: new Date().toISOString()
      };
    }
    return a;
  });

  escribirDB(actualizadas);
  return actualizadas.find(a => a.id === alarmId) ?? null;
}

/* ========================================================= */

export default {
  normalizarHora,
  crearAlarma,
  obtenerAlarmas,
  obtenerAlarmasActivas,
  obtenerAlarmasPorUsuario,
  obtenerAlarma,
  cerrarAlarma,
  actualizarAlarma,
  actualizarDesdeApp
}; 
