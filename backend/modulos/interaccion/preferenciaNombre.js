import datosUsuario from "../../memoria/datosUsuario.js";

function contienePreguntaNombre(texto = "") {
  const normalizado = String(texto || "").toLowerCase();
  return normalizado.includes("cómo querés que te llame") ||
    normalizado.includes("como queres que te llame") ||
    normalizado.includes("cómo quieres que te llame") ||
    normalizado.includes("como quieres que te llame");
}

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
    "quiero que me llames ",
    "quisiera que me llames ",
    "prefiero que me llames ",
    "podés llamarme ",
    "podes llamarme ",
    "puedes llamarme ",
    "llámame ",
    "llamame ",
    "me llamo ",
    "mi nombre es "
  ];
  const lower = String(texto || "").toLowerCase().trim();

  for (const patron of patrones) {
    if (!lower.startsWith(patron)) continue;
    const candidato = texto.slice(patron.length);
    const nombre = normalizarNombre(recortarNombre(candidato));
    if (nombre) return capitalizarNombre(nombre);
  }

  return null;
}

function recortarNombre(texto = "") {
  const lower = String(texto || "").toLowerCase();
  const separadores = [
    " por ", " y además", " y ademas", " pero ", " porque ", ",", ".", "?", "!", ";", ":"
  ];
  let corte = texto.length;

  for (const separador of separadores) {
    const indice = lower.indexOf(separador);
    if (indice >= 0 && indice < corte) {
      corte = indice;
    }
  }

  return texto.slice(0, corte);
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
    if ((rol === "assistant" || rol === "joi") && contienePreguntaNombre(texto)) {
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
