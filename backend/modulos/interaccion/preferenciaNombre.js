import datosUsuario from "../../memoria/datosUsuario.js";

const PREGUNTA_NOMBRE_REGEX = /c[oó]mo quer[eé]s que te llame/i;

function normalizarNombre(valor = "") {
  const limpio = String(valor || "")
    .trim()
    .replace(/^["'“”‘’\s]+|["'“”‘’.,;:!?…\s]+$/g, "")
    .replace(/\s+/g, " ");

  if (!limpio || limpio.length > 40) return null;
  if (!/^[A-Za-zÁÉÍÓÚÜÑáéíóúüñ0-9][A-Za-zÁÉÍÓÚÜÑáéíóúüñ0-9 '\-_.]{0,39}$/.test(limpio)) {
    return null;
  }

  const invalido = limpio.toLowerCase();
  if ([
    "vos", "tú", "tu", "usted", "como quieras", "da igual",
    "hola", "holi", "buenas", "buen día", "buen dia", "gracias", "ninguno"
  ].includes(invalido)) {
    return null;
  }

  return limpio;
}

function capitalizarNombre(nombre = "") {
  return nombre
    .split(" ")
    .filter(Boolean)
    .map(parte => parte.charAt(0).toUpperCase() + parte.slice(1))
    .join(" ");
}

function extraerDesdePatron(texto = "") {
  const patrones = [
    /(?:quiero|quisiera|prefiero)\s+que\s+me\s+llames?\s+(.+)$/i,
    /(?:pod[eé]s|puedes)\s+llamarme\s+(.+)$/i,
    /ll[aá]mame\s+(.+)$/i,
    /me\s+llamo\s+(.+)$/i,
    /mi\s+nombre\s+es\s+(.+)$/i
  ];

  for (const patron of patrones) {
    const match = String(texto || "").match(patron);
    if (!match?.[1]) continue;
    const nombre = normalizarNombre(match[1].split(/(?:\s+por\s+|,\s*|[.?!])/)[0]);
    if (nombre) return capitalizarNombre(nombre);
  }

  return null;
}

function obtenerHistorial(contexto = {}) {
  const local = contexto.memoriaLocal?.recentConversation;
  if (Array.isArray(local) && local.length > 0) return local;
  const selectiva = contexto.memoriaSistema?.memoriaSelectiva?.memoriaReciente;
  if (Array.isArray(selectiva) && selectiva.length > 0) return selectiva;
  return Array.isArray(contexto.memoriaUsuario?.historialConversacion)
    ? contexto.memoriaUsuario.historialConversacion
    : [];
}

function ultimoMensajeAsistentePreguntaNombre(contexto = {}, mensajeActual = "") {
  const historial = obtenerHistorial(contexto);
  for (let index = historial.length - 1; index >= 0; index -= 1) {
    const item = historial[index];
    const rol = item?.role || item?.tipo;
    const texto = item?.text || item?.mensaje || "";
    if ((rol === "user" || rol === "usuario") && String(texto).trim() === String(mensajeActual).trim()) {
      continue;
    }
    if ((rol === "assistant" || rol === "joi") && PREGUNTA_NOMBRE_REGEX.test(String(texto))) {
      return true;
    }
    if (rol === "user" || rol === "usuario") {
      break;
    }
  }
  return false;
}

function pareceNombreBreve(texto = "") {
  const nombre = normalizarNombre(texto);
  if (!nombre) return null;
  if (nombre.split(" ").length > 4) return null;
  return capitalizarNombre(nombre);
}

function extraerNombrePreferido(texto = "", contexto = {}) {
  const directo = extraerDesdePatron(texto);
  if (directo) return directo;
  if (ultimoMensajeAsistentePreguntaNombre(contexto, texto)) {
    return pareceNombreBreve(texto);
  }
  return null;
}

function obtenerNombrePreferido(contexto = {}) {
  const local = normalizarNombre(contexto.memoriaLocal?.preferredName);
  if (local) return capitalizarNombre(local);

  const configurado = normalizarNombre(contexto.memoriaSistema?.datosUsuario?.configuracion?.nombrePreferido);
  if (configurado) return capitalizarNombre(configurado);

  const core = normalizarNombre(contexto.datosUsuario?.configuracion?.nombrePreferido);
  if (core) return capitalizarNombre(core);

  return null;
}

function guardarNombrePreferido(userId, nombre) {
  if (!userId || !nombre) return null;
  return datosUsuario.actualizar(userId, {
    configuracion: {
      nombrePreferido: nombre
    }
  });
}

function debePreguntarNombre(mensajeUsuario = "", contexto = {}) {
  if (obtenerNombrePreferido(contexto)) return false;
  if (extraerDesdePatron(mensajeUsuario)) return false;
  if (ultimoMensajeAsistentePreguntaNombre(contexto, mensajeUsuario)) return false;
  return obtenerHistorial(contexto).length === 0;
}

function construirPreguntaNombre() {
  return "Antes de empezar, ¿cómo querés que te llame?";
}

function construirConfirmacion(nombre) {
  return `Perfecto, ${nombre}.`;
}

export default {
  normalizarNombre,
  extraerNombrePreferido,
  obtenerNombrePreferido,
  guardarNombrePreferido,
  debePreguntarNombre,
  construirPreguntaNombre,
  construirConfirmacion
};
