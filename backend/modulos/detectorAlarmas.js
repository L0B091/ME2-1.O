// detectorAlarmas.js — detecta pedidos de alarma en lenguaje natural (es-AR).
// Ej: "despertame a las 7:30", "poné una alarma a las 19:40", "alarma 8 y media de la noche".
const VERBOS = /\b(despert[aá]me|despertarme|levant[aá]me|alarma|alarmas|avis[aá]me|recordame|record[aá]me|pon[eé]me una alarma|program[aá])\b/i;
const CANCELAR = /(?:^|\s)(cancel[aá]|borr[aá]|elimin[aá]|sac[aá]|apag[aá]|quit[aá])(?:la|me)?\s[^.]*\balarma/i;

function aMinutos(fraccion = "") {
  const f = fraccion.toLowerCase();
  if (/media/.test(f)) return 30;
  if (/cuarto/.test(f)) return 15;
  const n = f.match(/\d{1,2}/);
  return n ? Number(n[0]) : 0;
}

// "en 10 minutos", "en 2 min", "en una hora", "en media hora", "en 1 hora y media": hora local HH:mm del usuario
// (zona del teléfono). Se redondea al minuto siguiente para no sonar antes de lo pedido.
const RELATIVA = /\ben\s+(?:(\d{1,3})|(un|una)|(media))\s*(minutos?|mins?|horas?|hs?)\b(\s+y\s+media)?/;

export function horaRelativa(texto = "", ahora = Date.now(), zonaHoraria = null) {
  const m = String(texto).toLowerCase().match(RELATIVA);
  if (!m) return null;
  const n = m[1] ? Number(m[1]) : m[2] ? 1 : 0.5;
  const minutos = /^h/.test(m[4]) ? n * 60 + (m[5] ? 30 : 0) : n;
  if (!(minutos >= 1 && minutos <= 24 * 60)) return null;
  const objetivo = Math.ceil((ahora + minutos * 60e3) / 60e3) * 60e3;
  const zona = zonaHoraria || process.env.ME2_TZ || "America/Argentina/Buenos_Aires";
  const fmt = (tz) => new Intl.DateTimeFormat("en-GB", { timeZone: tz, hour: "2-digit", minute: "2-digit", hourCycle: "h23" }).format(objetivo);
  try { return fmt(zona); } catch { return fmt("America/Argentina/Buenos_Aires"); }
}

export function detectarAlarma(mensaje = "", { ahora = Date.now(), zonaHoraria = null } = {}) {
  const texto = String(mensaje).toLowerCase().normalize("NFC");
  if (CANCELAR.test(texto)) {
    const h = parsearHora(texto);
    return { accion: "cancelar", hora: h };
  }
  if (!VERBOS.test(texto)) return null;
  // Para "recordame/avisame" exigimos la palabra alarma o despertar para no pisar recordatorios
  if (/\b(avis[aá]me|recordame|record[aá]me)\b/.test(texto) && !/alarma|despert/.test(texto)) return null;
  const hora = parsearHora(texto) || horaRelativa(texto, ahora, zonaHoraria);
  if (!hora) {
    if (!/despert|levant|\bpon|program|cre[aá]|necesito una alarma|quiero una alarma/.test(texto)) return null;
    return { accion: "crear", hora: null, motivo: "hora_no_entendida" };
  }
  const titulo = /despert|levant/.test(texto) ? "Hora de despertar" : "Alarma";
  return { accion: "crear", hora, titulo };
}

export function parsearHora(texto = "") {
  const t = String(texto).toLowerCase();
  const m =
    t.match(/\b(?:a las|a la|para las|las|alarma(?: para)?(?: las)?)\s*(\d{1,2})(?:[:.h](\d{2}))?(?:\s*(?:y\s+(media|cuarto|\d{1,2})))?(?:\s*(?:hs|horas|h))?(?:\s*(am|pm|a\.m\.|p\.m\.|de la mañana|de la manana|de la tarde|de la noche|de la madrugada))?/) ||
    t.match(/\b(\d{1,2})[:.](\d{2})\b(?:\s*(?:y\s+(media|cuarto|\d{1,2})))?(?:\s*(am|pm|de la mañana|de la manana|de la tarde|de la noche|de la madrugada))?/);
  if (!m) return null;
  let h = Number(m[1]);
  let min = m[2] != null ? Number(m[2]) : aMinutos(m[3] || "");
  const sufijo = m[4] || "";
  if (/pm|p\.m\.|tarde|noche/.test(sufijo) && h < 12) h += 12;
  if (/am|a\.m\.|mañana|manana|madrugada/.test(sufijo) && h === 12) h = 0;
  if (h > 23 || min > 59) return null;
  return `${String(h).padStart(2, "0")}:${String(min).padStart(2, "0")}`;
}

export default { detectarAlarma, parsearHora, horaRelativa };
