// fechaProgramada.js — fecha/hora EXACTA de lo que el orquestador programó (alarma o evento), en la zona fija de ME2,
// para que la respuesta del avatar no deje dudas ("hoy jueves 8/10 a las 14:32"). Solo datos: el LLM la redacta; el
// orquestador valida que su respuesta la incluya (fecha d/m + hora HH:mm) y, si falta, pide UNA regeneración.
const ZONA = () => process.env.ME2_TZ || "America/Argentina/Buenos_Aires";
const DIAS = ["domingo", "lunes", "martes", "miércoles", "jueves", "viernes", "sábado"];

function partes(epoch) {
  const p = Object.fromEntries(new Intl.DateTimeFormat("en-US", {
    timeZone: ZONA(), weekday: "short", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hourCycle: "h23"
  }).formatToParts(new Date(epoch)).map(x => [x.type, x.value]));
  return { dow: ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"].indexOf(p.weekday), y: p.year, m: Number(p.month), d: Number(p.day), hh: p.hour, mm: p.minute };
}

/** Instante de un evento de agenda ("YYYY-MM-DD", "HH:mm") en la zona de ME2 (Argentina, sin horario de verano). */
export function epochDeFechaHora(fecha, hora) {
  const t = Date.parse(`${fecha}T${hora}:00-03:00`);
  return Number.isFinite(t) ? t : null;
}

/** { texto: "hoy jueves 8/10 a las 14:32", hhmm, ddmm, relativo } — con "hoy"/"mañana" cuando corresponde. */
export function describirProgramado(epoch, ahora = Date.now()) {
  const p = partes(epoch);
  const hoy = partes(ahora);
  const manana = partes(ahora + 24 * 3600e3);
  const mismoDia = (a, b) => a.y === b.y && a.m === b.m && a.d === b.d;
  const relativo = mismoDia(p, hoy) ? "hoy" : mismoDia(p, manana) ? "mañana" : null;
  const ddmm = `${p.d}/${p.m}`;
  const hhmm = `${p.hh}:${p.mm}`;
  return { texto: `${relativo ? `${relativo} ` : ""}${DIAS[p.dow]} ${ddmm} a las ${hhmm}`, hhmm, ddmm, relativo, dia: DIAS[p.dow] };
}

/** ¿La respuesta menciona la fecha (d/m, con o sin ceros) y la hora (HH:mm o H:mm, con ":" o ".")? */
export function contieneFechaHora(texto, desc) {
  if (!texto || !desc) return false;
  const t = String(texto);
  const [d, m] = desc.ddmm.split("/").map(Number);
  const [hh, mm] = desc.hhmm.split(":").map(Number);
  const fecha = new RegExp(`(^|[^\\d])0?${d}\\s*/\\s*0?${m}(?![\\d])`);
  const hora = new RegExp(`(^|[^\\d])0?${hh}\\s*[:.]\\s*${String(mm).padStart(2, "0")}(?![\\d])`);
  return fecha.test(t) && hora.test(t);
}

/** minúsculas sin tildes (para validar respuestas del LLM). */
export function normalizar(t = "") {
  return String(t).toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "");
}

export const ESTADISTICAS_FECHA = { validadas: 0, regeneradas: 0, faltanteTrasReintento: 0 };

export default { epochDeFechaHora, describirProgramado, contieneFechaHora, normalizar, ESTADISTICAS_FECHA };
