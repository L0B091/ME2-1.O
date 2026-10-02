// memoriaConversacional.js
// Memoria de hechos del usuario para el contexto del LLM.
// Extrae datos explícitos (nombre, ciudad, gustos, hechos "recordá que...") y los
// persiste en backend/data/memoria_llm/<userId>.json (sobrevive reinicios).
import storage from "../utils/jsonStorage.js";
import datosUsuario from "./datosUsuario.js";

const NAMESPACE = "memoria_llm";
const MAX_HECHOS = 100;

function base(userId) {
  return { userId, nombre: null, ciudad: null, gustos: [], disgustos: [], hechos: [], actualizado: null };
}

function limpiar(valor = "") {
  return String(valor)
    .replace(/\s+/g, " ")
    .replace(/^[\s,;:.¡!¿?"']+|[\s,;:.¡!¿?"']+$/g, "")
    .trim();
}

function cortarClausula(valor = "") {
  return limpiar(String(valor).split(/[.;!?\n]| y (?:vos|tu|tú|me |mi )|, (?:pero|aunque|y vos)/i)[0]).slice(0, 120);
}

function capitalizar(nombre) {
  return nombre.split(" ").map(p => p.charAt(0).toUpperCase() + p.slice(1).toLowerCase()).join(" ");
}

const NO_NOMBRES = new Set(["bien", "mal", "yo", "de", "un", "una", "el", "la", "muy", "re", "tan", "medio", "programador", "argentino", "nuevo", "nueva", "y", "que", "qué", "como", "cómo", "asi", "así"]);

export function extraerHechos(mensaje = "") {
  const texto = String(mensaje);
  const out = { nombre: null, ciudad: null, gustos: [], disgustos: [], hechos: [] };

  // Trigger insensible a mayúsculas; el apellido opcional debe venir capitalizado. Excluye preguntas ("¿cómo me llamo?").
  const nombre = texto.match(/(?<![Cc][oó]mo\s)(?<![Cc][oó]mo\ste\s)\b(?:[Mm]e llamo|[Mm]i nombre es|[Ll]lamame|[Ll]lámame)\s+([A-Za-zÁÉÍÓÚÑáéíóúñ]+)(?:\s+([A-ZÁÉÍÓÚÑ][a-záéíóúñ]+))?/);
  if (nombre && !NO_NOMBRES.has(nombre[1].toLowerCase()) && !/\?\s*$/.test(texto.slice(nombre.index, nombre.index + 60).split(/[.!]/)[0])) {
    out.nombre = capitalizar(limpiar([nombre[1], nombre[2]].filter(Boolean).join(" ")));
  }

  const ciudad = texto.match(/\b(?:vivo en|soy de|estoy viviendo en)\s+([A-Za-zÁÉÍÓÚÑáéíóúñ .]+?)(?=[,.;!?]|$| y )/i);
  if (ciudad) out.ciudad = limpiar(ciudad[1]).slice(0, 60);

  for (const m of texto.matchAll(/\b(?<!no )(?:me gusta(?:n)?|me encanta(?:n)?|amo|soy fan de|me copa(?:n)?)\s+(?:mucho\s+|el\s+|la\s+|los\s+|las\s+)*([^.;!?\n]+)/gi)) {
    for (const g of m[1].split(/,| y | e /)) {
      const v = limpiar(limpiar(g).replace(/^(el|la|los|las|un|una)\s+/i, "")).slice(0, 60);
      if (v.length >= 2) out.gustos.push(v.toLowerCase());
    }
  }
  for (const m of texto.matchAll(/\b(?:no me gusta(?:n)?|odio|detesto)\s+(?:el\s+|la\s+|los\s+|las\s+)*([^.;!?\n]+)/gi)) {
    for (const g of m[1].split(/,| y | e /)) {
      const v = limpiar(g).slice(0, 60);
      if (v.length >= 2) out.disgustos.push(v.toLowerCase());
    }
  }
  const recordar = texto.match(/\b(?:record[aá]|acordate|no te olvides|anot[aá])\s+(?:de\s+)?que\s+([^\n]+)/i);
  if (recordar) out.hechos.push(cortarClausula(recordar[1]));
  for (const m of texto.matchAll(/\bmi\s+(perro|perra|gato|gata|novia|novio|esposa|esposo|mujer|marido|hijo|hija|hermano|hermana|mam[aá]|pap[aá]|trabajo|equipo|cumplea[nñ]os|auto|mascota)\s+(?:se llama|es|son)\s+([^.;!?\n]+)/gi)) {
    out.hechos.push(`mi ${m[1].toLowerCase()} ${/se llama/i.test(m[0]) ? "se llama" : "es"} ${cortarClausula(m[2])}`);
  }
  for (const m of texto.matchAll(/\b(trabajo (?:de|como|en)|estudio)\s+([^.;!?\n]+)/gi)) {
    out.hechos.push(`${m[1].toLowerCase()} ${cortarClausula(m[2])}`);
  }
  return out;
}

function unicos(lista, max = 50) {
  return [...new Set(lista.filter(Boolean))].slice(-max);
}

export function obtener(userId) {
  return { ...base(userId), ...storage.readUserData(NAMESPACE, userId, base(userId)) };
}

export function registrar(userId, mensaje) {
  if (!userId) return { cambios: false, memoria: null };
  const nuevos = extraerHechos(mensaje);
  const actual = obtener(userId);
  const hoy = new Date().toLocaleDateString("en-CA", { timeZone: process.env.ME2_TZ || "America/Argentina/Buenos_Aires" });
  nuevos.hechos = nuevos.hechos.filter(Boolean).map(h => `${h} (dicho el ${hoy})`);
  const memoria = {
    ...actual,
    nombre: nuevos.nombre || actual.nombre,
    ciudad: nuevos.ciudad || actual.ciudad,
    gustos: unicos([...actual.gustos.filter(g => !nuevos.disgustos.includes(g)), ...nuevos.gustos]),
    disgustos: unicos([...actual.disgustos.filter(g => !nuevos.gustos.includes(g)), ...nuevos.disgustos]),
    hechos: unicos([...actual.hechos, ...nuevos.hechos], MAX_HECHOS)
  };
  const cambios = JSON.stringify(memoria) !== JSON.stringify(actual);
  if (cambios) {
    memoria.actualizado = new Date().toISOString();
    storage.writeUserData(NAMESPACE, userId, memoria);
    try {
      const parche = {};
      if (nuevos.nombre) parche.identidad = { nombre: nuevos.nombre, apodo: nuevos.nombre };
      if (nuevos.gustos.length) parche.intereses = unicos([...(datosUsuario.obtener(userId)?.intereses || []), ...nuevos.gustos]);
      if (Object.keys(parche).length) datosUsuario.actualizar(userId, parche);
    } catch (error) {
      console.error("[memoriaConversacional] datosUsuario:", error.message);
    }
  }
  return { cambios, extraido: nuevos, memoria };
}

export function olvidar(userId) {
  storage.writeUserData(NAMESPACE, userId, base(userId));
}

export default { extraerHechos, obtener, registrar, olvidar };
