// perfilBasico.js — datos básicos de primer contacto (sin preguntas armadas).
// El orquestador detecta qué dato falta y lo pasa al LLM como HECHO de contexto (uno por vez);
// el LLM pregunta con su propia voz. Acá solo se extraen y persisten las respuestas.
import memoriaConversacional from "../../memoria/memoriaConversacional.js";
import datosUsuario from "../../memoria/datosUsuario.js";
import preferenciaNombre from "../interaccion/preferenciaNombre.js";
import { geocodificar } from "../../api/geocoding.js";
import horaApi from "../../api/hora.js";

export const CAMPOS = Object.freeze({
  nombre: "nombre del usuario (cómo quiere que lo llamen)",
  nombreAvatar: "nombre que el usuario quiere darle al avatar",
  ciudad: "ciudad donde vive el usuario (para clima y hora local)",
  confirmacionHora: "confirmación de la fecha y hora local del usuario"
});

const ORDEN = ["nombre", "nombreAvatar", "ciudad", "confirmacionHora"];
const NO_NOMBRE = /^(hola|buenas|buen d[ií]a|buenas tardes|buenas noches|s[ií]|no|ok|dale|gracias|bien|todo bien|nada|qu[eé]|c[oó]mo|hey|che)$/i;

function capitalizar(t) {
  return t.split(/\s+/).map(p => p.charAt(0).toUpperCase() + p.slice(1).toLowerCase()).join(" ");
}

// Respuesta corta tipo "Emanuel", "soy Emanuel", "decime Ema", "Nova".
function respuestaNombreCorta(mensaje) {
  const limpio = String(mensaje).trim()
    .replace(/^(?:soy|me dicen|decime|llamame|llámame|ponele|ponerle|que se llame|quiero que te llames|te vas a llamar|se llama|llamate|llámate)\s+/i, "")
    .replace(/[.!¡¿?,;:"']+/g, "").trim();
  if (!limpio || NO_NOMBRE.test(limpio)) return null;
  const palabras = limpio.split(/\s+/);
  if (palabras.length > 3 || palabras.some(p => !/^[A-Za-zÁÉÍÓÚÑáéíóúñü-]{2,20}$/.test(p))) return null;
  return capitalizar(limpio);
}

function respuestaCiudadCorta(mensaje) {
  const m = String(mensaje).trim().match(/^(?:(?:vivo|estoy|soy)\s+(?:en|de)\s+)?(?:la ciudad de\s+)?([A-Za-zÁÉÍÓÚÑáéíóúñü .,'-]{2,60})$/i);
  if (!m) return null;
  const v = m[1].replace(/[.!?]+$/, "").trim();
  return v.split(/\s+/).length <= 6 && !NO_NOMBRE.test(v) ? v : null;
}

export function camposFaltantes(memoria = {}, characterName = null) {
  const f = [];
  if (!memoria.nombre) f.push("nombre");
  if (!characterName) f.push("nombreAvatar");
  if (!memoria.ubicacion) f.push("ciudad");
  if (memoria.ubicacion && memoria.onboarding?.horaConfirmada == null) f.push("confirmacionHora");
  return ORDEN.filter(c => f.includes(c));
}

async function fijarCiudad(userId, texto, hechos) {
  try {
    const geo = await geocodificar(texto);
    if (!geo) {
      hechos.push(`No se encontró la ciudad "${texto}" en el servicio de geolocalización; la ubicación sigue desconocida`);
      return null;
    }
    memoriaConversacional.actualizarCampos(userId, { ciudad: geo.ciudad, ubicacion: { ...geo, consultado: texto } });
    if (geo.zonaHoraria) datosUsuario.actualizar(userId, { configuracion: { zonaHoraria: geo.zonaHoraria } });
    hechos.push(`Ubicación registrada: ${geo.ciudad}${geo.provincia ? `, ${geo.provincia}` : ""}${geo.pais ? `, ${geo.pais}` : ""} (zona horaria ${geo.zonaHoraria || "desconocida"})`);
    return geo;
  } catch (error) {
    hechos.push(`Geolocalización no disponible ahora (${error.message}); la ubicación sigue desconocida`);
    return null;
  }
}

/**
 * Procesa el mensaje del usuario contra el dato pendiente y extracciones explícitas.
 * @returns {Promise<{hechosTurno:string[], memoria:object, characterName:string|null}>}
 */
export async function procesar(userId, mensaje, { characterName = null, memoriaLocal = null } = {}) {
  const hechos = [];
  let memoria = memoriaConversacional.obtener(userId);
  const pendiente = memoria.onboarding?.pendiente || null;
  let avatar = characterName;

  // Nombre del usuario: explícito ("me llamo X") ya lo extrae memoriaConversacional; acá la respuesta corta.
  if (pendiente === "nombre" && !memoria.nombre) {
    const n = respuestaNombreCorta(mensaje);
    if (n) {
      memoria = memoriaConversacional.actualizarCampos(userId, { nombre: n });
      datosUsuario.actualizar(userId, { identidad: { nombre: n, apodo: n } });
      hechos.push(`Nombre del usuario registrado: ${n}`);
    }
  } else if (memoria.nombre && pendiente === "nombre") {
    hechos.push(`Nombre del usuario registrado: ${memoria.nombre}`);
  }

  // Nombre del avatar
  if (!avatar) {
    const explicito = preferenciaNombre.extraerNombrePersonaje(mensaje, { memoriaLocal });
    const corto = pendiente === "nombreAvatar" ? respuestaNombreCorta(mensaje) : null;
    const nombreAvatar = explicito || corto;
    if (nombreAvatar && nombreAvatar !== memoria.nombre) {
      preferenciaNombre.guardarNombrePersonaje(userId, nombreAvatar);
      avatar = nombreAvatar;
      hechos.push(`Nombre del avatar registrado: ${nombreAvatar}`);
    }
  }

  // Ciudad: "vivo en X" (extraído en memoria.ciudad sin geocodificar) o respuesta corta si estaba pendiente
  const norm = t => String(t || "").normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase().trim();
  const ciudadExplicita = memoria.ciudad && (!memoria.ubicacion ||
    (norm(memoria.ciudad) !== norm(memoria.ubicacion.ciudad) && norm(memoria.ciudad) !== norm(memoria.ubicacion.consultado)))
    ? memoria.ciudad : null;
  const ciudadCorta = pendiente === "ciudad" && !memoria.ubicacion ? respuestaCiudadCorta(mensaje) : null;
  const ciudadTexto = ciudadExplicita || ciudadCorta;
  if (ciudadTexto) {
    const geo = await fijarCiudad(userId, ciudadTexto, hechos);
    memoria = memoriaConversacional.obtener(userId);
    if (!geo && ciudadExplicita) memoria = memoriaConversacional.actualizarCampos(userId, { ciudad: memoria.ubicacion?.ciudad || null });
  }

  // Confirmación de hora
  if (pendiente === "confirmacionHora" && memoria.ubicacion) {
    const t = String(mensaje).trim().toLowerCase();
    if (/^(s[ií]|sip|correcto|exacto|as[ií] es|perfecto|dale|ok|okey|bien|est[aá] bien|coincide|esa es)/.test(t)) {
      memoria = memoriaConversacional.actualizarCampos(userId, { onboarding: { horaConfirmada: true } });
      hechos.push("El usuario confirmó que la fecha y hora local son correctas");
    } else if (/^no\b|incorrect|est[aá] mal|no es/.test(t)) {
      memoria = memoriaConversacional.actualizarCampos(userId, { onboarding: { horaConfirmada: false } });
      hechos.push("El usuario indicó que la fecha/hora local mostrada NO es correcta");
    }
  }

  const faltantes = camposFaltantes(memoria, avatar);
  const siguiente = faltantes[0] || null;
  memoria = memoriaConversacional.actualizarCampos(userId, {
    onboarding: { pendiente: siguiente, completado: !siguiente }
  });
  return { hechosTurno: hechos, memoria, characterName: avatar, faltantes, siguiente };
}

// Hechos de contexto para el LLM (no son preguntas armadas).
export function lineasContexto({ siguiente, faltantes, memoria }) {
  if (!siguiente) return [];
  const l = [`Perfil básico incompleto (primer contacto). Dato que falta obtener ahora: ${CAMPOS[siguiente]}`];
  if (siguiente === "confirmacionHora" && memoria?.ubicacion?.zonaHoraria) {
    const h = horaApi.obtenerHoraActual(memoria.ubicacion.zonaHoraria);
    l.push(`Fecha y hora local calculada para ${memoria.ubicacion.ciudad}: ${h.fechaLarga}, ${h.hora} (${h.zonaHoraria}) — pendiente de confirmación por el usuario`);
  }
  if (faltantes.length > 1) l.push(`Otros datos básicos aún desconocidos: ${faltantes.slice(1).map(c => CAMPOS[c]).join("; ")}`);
  return l;
}

export default { CAMPOS, camposFaltantes, procesar, lineasContexto };
