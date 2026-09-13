import storage from "../utils/jsonStorage.js";

const NAMESPACE = "notificaciones";

function enviarNotificacion(usuarioID, titulo, mensaje, tipo = "media") {
  const notificaciones = storage.readUserData(NAMESPACE, usuarioID, []);
  const registro = {
    id: `${usuarioID}_${Date.now()}`,
    titulo,
    mensaje,
    tipo,
    leida: false,
    createdAt: new Date().toISOString()
  };
  notificaciones.push(registro);
  storage.writeUserData(NAMESPACE, usuarioID, notificaciones.slice(-100));
  return registro;
}

function listarNotificaciones(usuarioID) {
  return storage.readUserData(NAMESPACE, usuarioID, []);
}

function marcarLeida(usuarioID, notificationId) {
  const notificaciones = listarNotificaciones(usuarioID).map(item => (
    item.id === notificationId ? { ...item, leida: true, leidaEn: new Date().toISOString() } : item
  ));
  storage.writeUserData(NAMESPACE, usuarioID, notificaciones);
  return notificaciones.find(item => item.id === notificationId) || null;
}

function notificarBuenosDias(usuarioID, mensajeCorto) {
  return enviarNotificacion(usuarioID, "¡Buenos días!", mensajeCorto || "Que tengas un gran día.", "alta");
}

function notificarRecordatorio(usuarioID, evento, fecha) {
  return enviarNotificacion(usuarioID, "Recordatorio de ME2", `No olvides: ${evento} el ${new Date(fecha).toLocaleString()}`, "alta");
}

function notificarNoticias(usuarioID, noticia) {
  return enviarNotificacion(usuarioID, "ME2 te informa", noticia, "baja");
}

function notificarAlarma(usuarioID, mensaje) {
  return enviarNotificacion(usuarioID, "Hora de despertar", mensaje, "alta");
}

export default {
  enviarNotificacion,
  listarNotificaciones,
  marcarLeida,
  notificarBuenosDias,
  notificarRecordatorio,
  notificarNoticias,
  notificarAlarma
};
