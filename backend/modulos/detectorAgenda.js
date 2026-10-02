// detectorAgenda.js — detecta "agendá/recordame X mañana a las 18" para el calendario local.
import { parsearHora } from "./detectorAlarmas.js";
import horaApi from "../api/hora.js";

const VERBO = /(?:^|\s)(agend[aá](?:me)?|anot[aá] en (?:la|mi) agenda|record[aá]me|recordame|ten[eé] en cuenta que)(?=\s)/i;

function sumarDias(fechaIso, dias) {
  const d = new Date(`${fechaIso}T12:00:00Z`);
  d.setUTCDate(d.getUTCDate() + dias);
  return d.toISOString().slice(0, 10);
}

export function detectarEvento(mensaje = "", ahora = new Date()) {
  const texto = String(mensaje);
  const t = texto.toLowerCase();
  if (!VERBO.test(t) || /alarma|despert/.test(t)) return null;
  const hoy = horaApi.obtenerHoraActual(undefined, ahora).fecha;
  let fecha = null;
  if (/pasado mañana|pasado manana/.test(t)) fecha = sumarDias(hoy, 2);
  else if (/\bmañana\b|\bmanana\b/.test(t)) fecha = sumarDias(hoy, 1);
  else if (/\bhoy\b/.test(t)) fecha = hoy;
  const iso = t.match(/\b(\d{4})-(\d{2})-(\d{2})\b/);
  const dm = t.match(/\b(\d{1,2})\/(\d{1,2})(?:\/(\d{2,4}))?\b/);
  if (iso) fecha = iso[0];
  else if (dm) {
    const y = dm[3] ? (dm[3].length === 2 ? `20${dm[3]}` : dm[3]) : hoy.slice(0, 4);
    fecha = `${y}-${dm[2].padStart(2, "0")}-${dm[1].padStart(2, "0")}`;
  }
  if (!fecha) return null;
  const hora = parsearHora(t) || "09:00";
  const descripcion = texto
    .replace(VERBO, " ")
    .replace(/pasado mañana|pasado manana|\bmañana\b|\bmanana\b|\bhoy\b/gi, " ")
    .replace(/\b\d{4}-\d{2}-\d{2}\b|\b\d{1,2}\/\d{1,2}(?:\/\d{2,4})?\b/g, " ")
    .replace(/\b(?:a las|a la|para las)\s*\d{1,2}(?:[:.]\d{2})?(?:\s*(?:hs|horas|h))?(?:\s*(?:de la (?:mañana|tarde|noche)|am|pm))?/gi, " ")
    .replace(/^\s*(?:(?:que|el|la)\s+)+/i, "")
    .replace(/\s+/g, " ").replace(/^[\s,.:;]+|[\s,.:;!?]+$/g, "").trim() || "Evento";
  return { fecha, hora, descripcion: descripcion.slice(0, 200) };
}

export default { detectarEvento };
