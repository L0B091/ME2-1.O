// detectorAgenda.js — calendario de ME2 por chat (es-AR): crear, borrar y consultar eventos.
// Determinista (sin LLM): el orquestador decide la acción y el LLM solo la cuenta con su voz.
// Fechas relativas ("mañana a las 20", "el viernes", "el 15 de octubre", "en 2 horas") en la zona del usuario
// (por defecto America/Argentina/Buenos_Aires).
import { parsearHora } from "./detectorAlarmas.js";
import horaApi from "../api/hora.js";

// "agenda" sin tilde es sustantivo ("tengo la agenda llena"); como verbo solo cuenta al principio del mensaje.
const VERBO = /(?:^|\s)(agendá|agend[aá](?:me|lo|la)|(?<=^|[,.;:!¿¡]\s{0,3})agenda|anot[aá](?:me|lo|la)? (?:en (?:la|mi) agenda|en (?:el|mi) calendario)|(?:agreg[aá]|sum[aá]|pon[eé]|cre[aá]|arm[aá]|anot[aá]|program[aá]|guard[aá])(?:me|lo|la)? (?:un |el |este |otro )?(?:evento|al calendario|a la agenda|en la agenda|en el calendario)|record[aá]me|recordame|ten[eé] en cuenta que)(?=\s|$|[,.:;!])/i;
const VERBO_BORRAR = /(?:^|\s)(borr[aá]|elimin[aá]|cancel[aá]|sac[aá]|quit[aá]|anul[aá])(?:me|la|lo|las|los)?(?=\s|$)/i;
const SUSTANTIVO_AGENDA = /\b(evento|eventos|agenda|calendario|agendado|agendada|turno|cita|reuni[oó]n|recordatorio)\b/i;
const CONSULTA = /\b(qu[eé] tengo|tengo algo|tengo (?:algún|algun|alg[uú]n) evento|qu[eé] hay en (?:la|mi) agenda|mi agenda|mis eventos|mi calendario|estoy libre|qu[eé] (?:eventos|planes) tengo|qu[eé] ten[eé]s? (?:agendado|anotado))\b/i;
const NO_AGENDA = /alarma|despert/i;

const DIAS = { domingo: 0, lunes: 1, martes: 2, miercoles: 3, "miércoles": 3, jueves: 4, viernes: 5, sabado: 6, "sábado": 6 };
const DIAS_CORTOS = ["dom", "lun", "mar", "mié", "jue", "vie", "sáb"];
const MESES = { enero: 1, febrero: 2, marzo: 3, abril: 4, mayo: 5, junio: 6, julio: 7, agosto: 8, septiembre: 9, setiembre: 9, octubre: 10, noviembre: 11, diciembre: 12 };
const NUM = { un: 1, una: 1, media: 0.5, dos: 2, tres: 3, cuatro: 4, cinco: 5, diez: 10, quince: 15, veinte: 20, treinta: 30 };

const RE_DIA = new RegExp(`\\b(?:(?:el|este|esta|para el|del)\\s+)?(?:pr[oó]ximo\\s+)?(${Object.keys(DIAS).join("|")})(?:\\s+(?:que viene|pr[oó]ximo))?\\b`, "i");
const RE_FECHA_MES = new RegExp(`\\b(?:el\\s+)?(\\d{1,2})\\s+de\\s+(${Object.keys(MESES).join("|")})(?:\\s+(?:de|del)\\s+(\\d{4}))?\\b`, "i");
const RE_RELATIVA = /\b(?:en|dentro de)\s+(\d{1,3}|un|una|media|dos|tres|cuatro|cinco|diez|quince|veinte|treinta)\s*(minutos?|mins?|horas?|hs?)\b/i;
const RE_RANGO = /\bde\s+(?:las\s+)?(\d{1,2})(?:[:.](\d{2}))?\s*(?:hs?\s*)?a\s+(?:las\s+)?(\d{1,2})(?:[:.](\d{2}))?\s*(?:hs|horas|h)?\b/i;
const RE_HASTA = /\bhasta\s+(?:las\s+)?(\d{1,2})(?:[:.](\d{2}))?\s*(?:hs|horas|h)?\b/i;

const dos = n => String(n).padStart(2, "0");
export function sumarDias(fechaIso, dias) {
  const d = new Date(`${fechaIso}T12:00:00Z`);
  d.setUTCDate(d.getUTCDate() + dias);
  return d.toISOString().slice(0, 10);
}
const diaSemana = fechaIso => new Date(`${fechaIso}T12:00:00Z`).getUTCDay();
const fechaValida = (y, m, d) => {
  const dt = new Date(Date.UTC(y, m - 1, d));
  return dt.getUTCFullYear() === y && dt.getUTCMonth() === m - 1 && dt.getUTCDate() === d;
};

function ahoraEnZona(ahora = new Date(), zonaHoraria = null) {
  const h = horaApi.obtenerHoraActual(zonaHoraria || undefined, ahora instanceof Date ? ahora : new Date(ahora));
  return { fecha: h.fecha, hora: h.hora, zona: h.zonaHoraria };
}

/** Fecha mencionada en el texto (YYYY-MM-DD) relativa a `hoy` (fecha local del usuario), o null. */
export function resolverFecha(texto = "", hoy) {
  const t = String(texto).toLowerCase();
  if (/pasado mañana|pasado manana/.test(t)) return sumarDias(hoy, 2);
  // "a la mañana" / "de la mañana" es momento del día, no el día de mañana.
  if (/\bmañana\b|\bmanana\b/.test(t.replace(/\b(?:a|por|de) la (?:mañana|manana)\b/g, " "))) return sumarDias(hoy, 1);
  if (/\bhoy\b|esta noche|esta tarde/.test(t)) return hoy;
  const iso = t.match(/\b(\d{4})-(\d{2})-(\d{2})\b/);
  if (iso && fechaValida(+iso[1], +iso[2], +iso[3])) return iso[0];
  const [y0, m0, d0] = hoy.split("-").map(Number);
  const dm = t.match(/\b(\d{1,2})\/(\d{1,2})(?:\/(\d{2,4}))?\b/);
  if (dm) {
    const d = +dm[1], m = +dm[2];
    let y = dm[3] ? +(dm[3].length === 2 ? `20${dm[3]}` : dm[3]) : y0;
    if (!dm[3] && (m < m0 || (m === m0 && d < d0))) y += 1; // "15/1" en octubre = enero del año que viene
    if (fechaValida(y, m, d)) return `${y}-${dos(m)}-${dos(d)}`;
  }
  const fm = t.match(RE_FECHA_MES);
  if (fm) {
    const d = +fm[1], m = MESES[fm[2]];
    let y = fm[3] ? +fm[3] : y0;
    if (!fm[3] && (m < m0 || (m === m0 && d < d0))) y += 1;
    if (fechaValida(y, m, d)) return `${y}-${dos(m)}-${dos(d)}`;
  }
  const ds = t.match(RE_DIA);
  if (ds) {
    const objetivo = DIAS[ds[1]];
    const actual = diaSemana(hoy);
    let delta = (objetivo - actual + 7) % 7;
    // "el viernes" dicho un viernes = el de la semana que viene; "el próximo / que viene" nunca es hoy.
    if (delta === 0) delta = 7;
    return sumarDias(hoy, delta);
  }
  const elDia = t.match(/\b(?:el|para el)\s+(?:d[ií]a\s+)?(\d{1,2})\b(?!\s*(?:hs|horas|h\b|:|\.\d|\/|de\s|minutos?|personas?))/);
  if (elDia) {
    const d = +elDia[1];
    let y = y0, m = m0;
    if (d < d0) { m += 1; if (m > 12) { m = 1; y += 1; } }
    if (fechaValida(y, m, d)) return `${y}-${dos(m)}-${dos(d)}`;
  }
  return null;
}

function horaDe(h, min) {
  const H = Number(h), M = min != null ? Number(min) : 0;
  return H <= 23 && M <= 59 ? `${dos(H)}:${dos(M)}` : null;
}

function sumarMinutos(fecha, hora, minutos) {
  const [H, M] = hora.split(":").map(Number);
  const total = H * 60 + M + minutos;
  const dias = Math.floor(total / 1440);
  const resto = ((total % 1440) + 1440) % 1440;
  return { fecha: sumarDias(fecha, dias), hora: `${dos(Math.floor(resto / 60))}:${dos(resto % 60)}` };
}

function limpiarDescripcion(texto) {
  return String(texto)
    .replace(VERBO, " ")
    .replace(/\b(?:un|el|este|otro)\s+evento\b|\bevento\b/gi, " ")
    .replace(/\b(?:en|a|al|de)\s+(?:la|mi|el)?\s*(?:agenda|calendario)\b/gi, " ")
    .replace(/\b(?:por|a) la (?:mañana|manana|tarde|noche)\b/gi, " ")
    .replace(/pasado mañana|pasado manana|\bmañana\b|\bmanana\b|\bhoy\b|esta noche|esta tarde/gi, " ")
    .replace(RE_FECHA_MES, " ")
    .replace(RE_DIA, " ")
    .replace(RE_RELATIVA, " ")
    .replace(RE_RANGO, " ")
    .replace(RE_HASTA, " ")
    .replace(/\b\d{4}-\d{2}-\d{2}\b|\b\d{1,2}\/\d{1,2}(?:\/\d{2,4})?\b/g, " ")
    .replace(/\b(?:a las|a la|para las|las)\s*\d{1,2}(?:[:.]\d{2})?(?:\s*y\s+(?:media|cuarto|\d{1,2}))?(?:\s*(?:hs|horas|h))?(?:\s*(?:de la (?:mañana|manana|tarde|noche|madrugada)|am|pm))?/gi, " ")
    .replace(/\b\d{1,2}[:.]\d{2}\s*(?:hs|horas|h)?\b/gi, " ")
    .replace(/\b(?:el|para el)\s+(?:d[ií]a\s+)?\d{1,2}\b/gi, " ")
    .replace(/^\s*(?:(?:que|el|la|para|de|del|por favor|porfa|me)\s+)+/i, "")
    .replace(/\s+(?:(?:el|la|para|de|del|a|que)\s*)+$/i, "")
    .replace(/\s+/g, " ").replace(/^[\s,.:;¿¡-]+|[\s,.:;!?¿¡-]+$/g, "").trim();
}

/**
 * Pedido de crear un evento. Devuelve { fecha, hora, fin, descripcion, horaIndicada } o null.
 * Compatibilidad: detectarEvento(mensaje, ahora) sigue funcionando (zona por defecto).
 */
export function detectarEvento(mensaje = "", ahora = new Date(), { zonaHoraria = null } = {}) {
  const texto = String(mensaje);
  const t = texto.toLowerCase();
  if (!VERBO.test(t) || NO_AGENDA.test(t)) return null;
  if (VERBO_BORRAR.test(t) && SUSTANTIVO_AGENDA.test(t)) return null; // es un pedido de borrar
  const local = ahoraEnZona(ahora, zonaHoraria);
  let fecha = resolverFecha(t, local.fecha);
  let hora = null;
  let fin = null;
  const rango = t.match(RE_RANGO);
  if (rango) {
    hora = horaDe(rango[1], rango[2]);
    fin = horaDe(rango[3], rango[4]);
    if (hora && fin && Number(rango[3]) < 12 && Number(rango[1]) >= 12) fin = horaDe(Number(rango[3]) + 12, rango[4]);
  }
  if (!hora) hora = parsearHora(t);
  if (hora && !fin) {
    const hasta = t.match(RE_HASTA);
    if (hasta) fin = horaDe(hasta[1], hasta[2]);
  }
  // "en 2 horas" / "dentro de 30 minutos": instante local exacto (puede cruzar la medianoche).
  if (!hora) {
    const rel = t.match(RE_RELATIVA);
    if (rel) {
      const n = /^\d/.test(rel[1]) ? Number(rel[1]) : NUM[rel[1]];
      const minutos = /^h/.test(rel[2]) ? n * 60 : n;
      if (minutos >= 1 && minutos <= 7 * 1440) {
        const r = sumarMinutos(local.fecha, local.hora, Math.ceil(minutos));
        fecha = fecha || r.fecha; hora = r.hora;
      }
    }
  }
  // Solo hora ("agendá la reunión a las 20"): hoy si todavía no pasó, si no mañana.
  if (!fecha && hora) fecha = hora > local.hora ? local.fecha : sumarDias(local.fecha, 1);
  if (!fecha) return null;
  // "hoy por la noche" sin hora no se inventa: queda la hora por defecto y se avisa (horaIndicada=false).
  const horaIndicada = Boolean(hora);
  if (fin && hora && fin <= hora) fin = null;
  const descripcion = (limpiarDescripcion(texto) || "Evento").slice(0, 200);
  return { fecha, hora: hora || "09:00", fin, descripcion, horaIndicada };
}

const VACIAS = new Set(["evento", "eventos", "agenda", "calendario", "borra", "borrá", "elimina", "eliminá", "cancela", "cancelá",
  "saca", "sacá", "quita", "quitá", "anula", "anulá", "borrame", "borrala", "borralo", "sacalo", "sacala", "todos", "todas", "agendado",
  "agendada", "mañana", "manana", "pasado", "lunes", "martes", "miercoles", "miércoles", "jueves", "viernes", "sabado", "sábado",
  "domingo", "para", "este", "esta", "ultimo", "último", "acabo", "agendar", "agendé", "agende", "favor", "porfa", "tengo", "mismo"]);
const normal = s => String(s || "").toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "");
function palabras(s) {
  return normal(s).split(/[^a-z0-9ñ]+/).filter(w => w.length >= 4 && !VACIAS.has(w));
}

/**
 * Pedido de borrar evento(s). `eventos` = agenda vigente del usuario (teléfono + copia del servidor).
 * Devuelve null si no es un pedido de borrar; si lo es: { ids, eventos, motivo } con motivo
 * "borrado" | "ambiguo" | "sin_coincidencias".
 */
export function detectarEliminacion(mensaje = "", eventos = [], ahora = new Date(), { zonaHoraria = null } = {}) {
  const t = String(mensaje).toLowerCase();
  if (!VERBO_BORRAR.test(t) || NO_AGENDA.test(t)) return null;
  const claves = palabras(t.replace(VERBO_BORRAR, " "));
  const lista = Array.isArray(eventos) ? eventos : [];
  const mencionaTitulo = claves.some(k => lista.some(e => palabras(e.descripcion).includes(k)));
  if (!SUSTANTIVO_AGENDA.test(t) && !mencionaTitulo) return null;
  const local = ahoraEnZona(ahora, zonaHoraria);
  const fecha = resolverFecha(t, local.fecha);
  const hora = parsearHora(t);
  let candidatos = lista;
  if (/(?:^|\s)(?:[uú]ltimo|que acabo de agendar|que agend[eé] reci[eé]n)(?=\s|$|[,.!?])/.test(t) && !fecha && !claves.length) {
    const ult = [...lista].sort((a, b) => String(b.creadoEn || "").localeCompare(String(a.creadoEn || "")))[0];
    candidatos = ult ? [ult] : [];
  }
  if (fecha) candidatos = candidatos.filter(e => e.fecha === fecha);
  if (hora) candidatos = candidatos.filter(e => e.hora === hora);
  if (claves.length) {
    const conTexto = candidatos.filter(e => palabras(e.descripcion).some(w => claves.includes(w)));
    // Las palabras pueden ser solo de relleno ("borrá el evento de la tarde"): si nada coincide y hay fecha, queda la fecha.
    if (conTexto.length || !fecha) candidatos = conTexto;
  }
  const todos = /\b(todos|todas|toda la agenda|todo el calendario)\b/.test(t);
  if (!candidatos.length) return { ids: [], eventos: [], motivo: "sin_coincidencias" };
  if (candidatos.length > 1 && !todos) return { ids: [], eventos: candidatos, motivo: "ambiguo" };
  return { ids: candidatos.map(e => e.id), eventos: candidatos, motivo: "borrado" };
}

/** Pregunta por la agenda ("¿qué tengo mañana?", "¿estoy libre el sábado?"). { desde, hasta } (fechas) o null. */
export function detectarConsulta(mensaje = "", ahora = new Date(), { zonaHoraria = null } = {}) {
  const t = String(mensaje).toLowerCase();
  if (NO_AGENDA.test(t) || VERBO_BORRAR.test(t)) return null;
  const pregunta = CONSULTA.test(t) || (/\?/.test(t) && SUSTANTIVO_AGENDA.test(t));
  if (!pregunta || (VERBO.test(t) && !/\?/.test(t))) return null;
  const hoy = ahoraEnZona(ahora, zonaHoraria).fecha;
  if (/fin de semana|finde/.test(t)) {
    const dow = diaSemana(hoy);
    const sab = dow === 0 ? sumarDias(hoy, -1) : sumarDias(hoy, (6 - dow + 7) % 7);
    return { desde: dow === 0 ? hoy : sab, hasta: sumarDias(sab, 1) };
  }
  if (/esta semana|la semana|pr[oó]ximos d[ií]as/.test(t)) return { desde: hoy, hasta: sumarDias(hoy, 6) };
  const fecha = resolverFecha(t, hoy);
  return fecha ? { desde: fecha, hasta: fecha } : { desde: hoy, hasta: null };
}

/** "vie 2026-10-09 20:00–22:00 — Cena con Ana" (dato para el contexto del LLM). */
export function lineaEvento(e) {
  const dia = /^\d{4}-\d{2}-\d{2}$/.test(e?.fecha || "") ? `${DIAS_CORTOS[diaSemana(e.fecha)]} ` : "";
  return `${dia}${e.fecha} ${e.hora}${e.fin ? `–${e.fin}` : ""} — ${e.descripcion}${e.notas ? ` (${e.notas})` : ""}`;
}

export default { detectarEvento, detectarEliminacion, detectarConsulta, resolverFecha, lineaEvento, sumarDias };
