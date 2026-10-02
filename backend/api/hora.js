// hora.js — sin dependencias externas. Default: America/Argentina/Buenos_Aires.
export const ZONA_DEFAULT = process.env.ME2_TZ || "America/Argentina/Buenos_Aires";

function zonaValida(zona) {
  try { new Intl.DateTimeFormat("es-AR", { timeZone: zona }); return true; } catch { return false; }
}

function obtenerHoraActual(zonaHoraria = ZONA_DEFAULT, ahora = new Date()) {
  const zona = zonaHoraria && zonaValida(zonaHoraria) ? zonaHoraria : ZONA_DEFAULT;
  const partes = Object.fromEntries(new Intl.DateTimeFormat("en-CA", {
    timeZone: zona, year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false
  }).formatToParts(ahora).map(p => [p.type, p.value]));
  const hora = `${partes.hour === "24" ? "00" : partes.hour}:${partes.minute}`;
  const diaSemana = new Intl.DateTimeFormat("es-AR", { timeZone: zona, weekday: "long" }).format(ahora);
  const fechaLarga = new Intl.DateTimeFormat("es-AR", { timeZone: zona, weekday: "long", day: "numeric", month: "long", year: "numeric" }).format(ahora);
  return {
    zonaHoraria: zona,
    fecha: `${partes.year}-${partes.month}-${partes.day}`,
    hora,
    segundos: partes.second,
    diaSemana,
    fechaLarga,
    timestamp: ahora.getTime(),
    fechaCompleta: ahora.toISOString()
  };
}

function formatearFecha(fechaObj, zona = ZONA_DEFAULT) {
  const h = obtenerHoraActual(zona, fechaObj);
  const [y, m, d] = h.fecha.split("-");
  return `${d}/${m}/${y} ${h.hora}`;
}

export default { obtenerHoraActual, formatearFecha, ZONA_DEFAULT };
