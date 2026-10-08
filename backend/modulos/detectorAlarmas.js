// detectorAlarmas.js — detecta pedidos de alarma en lenguaje natural (es-AR).
// Ej: "despertame a las 7:30", "poné una alarma a las 19:40", "alarma 8 y media de la noche".
const VERBOS = /\b(despert[aá]me|despertarme|despertador|levant[aá]me|alarma|alarmas|avis[aá]me|recordame|record[aá]me|pon[eé]me una alarma|program[aá])\b/i;
const CANCELAR = /(?:^|\s)(cancel[aá]|borr[aá]|elimin[aá]|sac[aá]|apag[aá]|quit[aá])(?:la|me)?\s[^.]*\balarma/i;

function aMinutos(fraccion = "") {
  const f = fraccion.toLowerCase();
  if (/media/.test(f)) return 30;
  if (/cuarto/.test(f)) return 15;
  const n = f.match(/\d{1,2}/);
  return n ? Number(n[0]) : 0;
}

// "en 10 minutos", "en 2 min", "dentro de dos minutos", "de acá a 5 minutos", "para 2 minutos", "2 minutos más",
// "en una hora", "en media hora", "en 1 hora y media": hora local HH:mm del usuario (zona del teléfono).
// Se redondea al minuto siguiente para no sonar antes de lo pedido.
const NUMEROS = {
  un: 1, una: 1, uno: 1, dos: 2, tres: 3, cuatro: 4, cinco: 5, seis: 6, siete: 7, ocho: 8, nueve: 9, diez: 10,
  once: 11, doce: 12, quince: 15, veinte: 20, treinta: 30, cuarenta: 40, cincuenta: 50, media: 0.5
};
const CANTIDAD = `(\\d{1,3}|${Object.keys(NUMEROS).join("|")})`;
const UNIDAD = "(minutos?|mins?|horas?|hs?)";
const RELATIVA = [
  // "en / dentro de / de acá a / para dentro de N unidad"
  new RegExp(`\\b(?:en|dentro\\s+de|de\\s+ac[aá]\\s+a)\\s+(?:unos?\\s+)?${CANTIDAD}\\s*${UNIDAD}\\b(\\s+y\\s+media)?`),
  // "para 2 minutos" (solo minutos: "para las 2" / "para 2 hs" son horas del reloj)
  new RegExp(`\\bpara\\s+(?:unos?\\s+)?${CANTIDAD}\\s*(minutos?|mins?)\\b()`),
  // "2 minutos más", "5 minutos desde ahora"
  new RegExp(`\\b${CANTIDAD}\\s*${UNIDAD}\\s+(?:m[aá]s|desde\\s+ahora|a\\s+partir\\s+de\\s+ahora)\\b()`)
];

// Zona de ME2 fija (no la del teléfono): "hoy", "mañana" y HH:mm siempre en esta zona.
const ZONA_ME2 = () => process.env.ME2_TZ || "America/Argentina/Buenos_Aires";

function formatoHora(epoch) {
  const fmt = (tz) => new Intl.DateTimeFormat("en-GB", { timeZone: tz, hour: "2-digit", minute: "2-digit", hourCycle: "h23" }).format(epoch);
  try { return fmt(ZONA_ME2()); } catch { return fmt("America/Argentina/Buenos_Aires"); }
}

/** Instante absoluto (epoch ms, hora del servidor) de un pedido relativo; null si no hay plazo relativo. */
export function epochRelativo(texto = "", ahora = Date.now()) {
  const t = String(texto).toLowerCase();
  const m = RELATIVA.map(re => t.match(re)).find(Boolean);
  if (!m) return null;
  const n = /^\d/.test(m[1]) ? Number(m[1]) : NUMEROS[m[1]];
  const minutos = /^h/.test(m[2]) ? n * 60 + (m[3] ? 30 : 0) : n;
  if (!(minutos >= 1 && minutos <= 24 * 60)) return null;
  return Math.ceil((ahora + minutos * 60e3) / 60e3) * 60e3;
}

/** Próxima ocurrencia de "HH:mm" en la zona de ME2, estrictamente después de [ahora] (epoch ms del servidor). */
export function epochDeHora(hora, ahora = Date.now()) {
  const m = /^(\d{2}):(\d{2})$/.exec(String(hora || ""));
  if (!m) return null;
  const objetivo = Number(m[1]) * 60 + Number(m[2]);
  const minutoActual = Math.floor(ahora / 60e3) * 60e3;
  const [hh, mm] = formatoHora(minutoActual).split(":").map(Number);
  let delta = objetivo - (hh * 60 + mm);
  if (delta <= 0) delta += 24 * 60;
  return minutoActual + delta * 60e3;
}

export function horaRelativa(texto = "", ahora = Date.now(), _zonaHoraria = null) {
  const epoch = epochRelativo(texto, ahora);
  return epoch == null ? null : formatoHora(epoch);
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
  // Relativa primero: "alarma para 2 minutos" no es la hora 02:00. El instante absoluto (epochMs) se calcula con la
  // hora del SERVIDOR en la zona fija de ME2; el teléfono lo arma con su reloj ME2 (nunca con su propia hora).
  const relativo = epochRelativo(texto, ahora);
  const hora = relativo != null ? formatoHora(relativo) : parsearHora(texto);
  if (!hora) {
    // Pedido con plazo/hora que no se pudo leer ("alarma en un ratito minutos..."): se informa, nunca se confirma.
    if (!/despert|levant|\bpon|program|cre[aá]|necesito una alarma|quiero una alarma|\bminutos?\b|\bhoras?\b/.test(texto)) return null;
    return { accion: "crear", hora: null, motivo: "hora_no_entendida" };
  }
  const titulo = /despert|levant/.test(texto) ? "Hora de despertar" : "Alarma";
  return { accion: "crear", hora, titulo, epochMs: relativo ?? epochDeHora(hora, ahora) };
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

export default { detectarAlarma, parsearHora, horaRelativa, epochRelativo, epochDeHora };
