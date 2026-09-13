import datosUsuario from "../../memoria/datosUsuario.js";

function limpiarBordes(texto = "") {
  const prohibidos = new Set(["\"", "'", "“", "”", "‘", "’", ".", ",", ";", ":", "!", "?", "…", " "]);
  let inicio = 0;
  let fin = texto.length;
  while (inicio < fin && prohibidos.has(texto[inicio])) inicio += 1;
  while (fin > inicio && prohibidos.has(texto[fin - 1])) fin -= 1;
  return texto.slice(inicio, fin);
}

function compactarEspacios(texto = "") {
  return String(texto || "").split(/\s+/).filter(Boolean).join(" ");
}

function normalizarNombre(valor = "") {
  const limpio = compactarEspacios(limpiarBordes(String(valor || "").trim()));
  if (!limpio || limpio.length > 40) return null;
  if (!/^[A-Za-zÁÉÍÓÚÜÑáéíóúüñ0-9][A-Za-zÁÉÍÓÚÜÑáéíóúüñ0-9 '\-_.]{0,39}$/.test(limpio)) {
    return null;
  }

  const invalido = limpio.toLowerCase();
  if ([
    "joi", "me2", "hola", "holi", "buenas", "gracias", "ninguno",
    "como quieras", "da igual", "sin nombre", "ningún nombre", "ningun nombre",
    "nombre", "un nombre", "nickname", "un nickname", "apodo", "un apodo"
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

function recortarNombre(texto = "") {
  const lower = String(texto || "").toLowerCase();
  const separadores = [
    " por ", " y además", " y ademas", " pero ", " porque ", ",", ".", "?", "!", ";", ":"
  ];
  let corte = texto.length;
  for (const separador of separadores) {
    const indice = lower.indexOf(separador);
    if (indice >= 0 && indice < corte) corte = indice;
  }
  return texto.slice(0, corte);
}

function pideNombrePersonaje(texto = "") {
  const normalizado = compactarEspacios(String(texto || "").toLowerCase());
  return [
    "quiero ponerte un nombre",
    "quiero ponerte nombre",
    "quiero darte un nombre",
    "quiero darte un nickname",
    "quiero asignarte un nombre",
    "quiero asignarte un nickname",
    "quiero cambiarte el nombre",
    "quiero cambiar tu nombre",
    "quiero cambiarte de nombre",
    "quiero ponerte un apodo",
    "quiero ponerte un nickname",
    "me gustaría ponerte un nombre",
    "me gustaria ponerte un nombre"
  ].some(frase => normalizado.includes(frase));
}

function extraerNombreDirecto(texto = "") {
  const patrones = [
    "quiero que te llames ",
    "quiero llamarte ",
    "te voy a llamar ",
    "voy a llamarte ",
    "quiero ponerte ",
    "quiero darte ",
    "tu nombre va a ser ",
    "tu nombre será ",
    "tu nombre sera ",
    "vas a llamarte ",
    "te llamaré ",
    "te llamare "
  ];
  const lower = String(texto || "").toLowerCase().trim();

  for (const patron of patrones) {
    const indice = lower.indexOf(patron);
    if (indice < 0) continue;
    const candidato = texto.slice(indice + patron.length);
    const nombre = normalizarNombre(recortarNombre(candidato));
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

function ultimoMensajePideNombrePersonaje(contexto = {}, mensajeActual = "") {
  const historial = obtenerHistorial(contexto);
  for (let index = historial.length - 1; index >= 0; index -= 1) {
    const item = historial[index];
    const rol = item?.role || item?.tipo;
    const texto = item?.text || item?.mensaje || "";
    if ((rol === "user" || rol === "usuario") && String(texto).trim() === String(mensajeActual).trim()) {
      continue;
    }
    if ((rol === "assistant" || rol === "joi") && String(texto).includes("¿Qué nombre o nickname querés que tenga?")) {
      return true;
    }
    if (rol === "user" || rol === "usuario") break;
  }
  return false;
}

function pareceNombreBreve(texto = "") {
  const nombre = normalizarNombre(texto);
  if (!nombre) return null;
  if (nombre.split(" ").length > 4) return null;
  return capitalizarNombre(nombre);
}

function extraerNombrePersonaje(texto = "", contexto = {}) {
  const directo = extraerNombreDirecto(texto);
  if (directo) return directo;
  if (ultimoMensajePideNombrePersonaje(contexto, texto)) {
    return pareceNombreBreve(texto);
  }
  return null;
}

function obtenerNombrePersonaje(contexto = {}) {
  const local = normalizarNombre(contexto.memoriaLocal?.characterName);
  if (local) return capitalizarNombre(local);

  const configurado = normalizarNombre(contexto.memoriaSistema?.datosUsuario?.configuracion?.nombrePersonaje);
  if (configurado) return capitalizarNombre(configurado);

  const core = normalizarNombre(contexto.datosUsuario?.configuracion?.nombrePersonaje);
  if (core) return capitalizarNombre(core);

  return null;
}

function guardarNombrePersonaje(userId, nombre) {
  if (!userId || !nombre) return null;
  return datosUsuario.actualizar(userId, {
    configuracion: {
      nombrePersonaje: nombre
    }
  });
}

function debePreguntarNombrePersonaje(mensajeUsuario = "", contexto = {}) {
  if (obtenerNombrePersonaje(contexto)) return false;
  if (extraerNombreDirecto(mensajeUsuario)) return false;
  if (ultimoMensajePideNombrePersonaje(contexto, mensajeUsuario)) return false;
  return pideNombrePersonaje(mensajeUsuario);
}

function construirPreguntaNombrePersonaje() {
  return "Claro. ¿Qué nombre o nickname querés que tenga?";
}

function construirConfirmacionNombrePersonaje(nombre) {
  return `Perfecto. Entonces voy a llamarme ${nombre}.`;
}

export default {
  normalizarNombre,
  pideNombrePersonaje,
  extraerNombrePersonaje,
  obtenerNombrePersonaje,
  guardarNombrePersonaje,
  debePreguntarNombrePersonaje,
  construirPreguntaNombrePersonaje,
  construirConfirmacionNombrePersonaje
};
