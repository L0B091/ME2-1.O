// contextoLLM.js
// Construye el contexto factual que el orquestador entrega al LLM.
// SOLO datos (hora, clima, noticias, agenda, alarmas, memoria, funciones de la app).
// Sin instrucciones de personalidad ni de estilo: eso vive en el master prompt del modelo.
import horaApi from "../api/hora.js";
import obtenerClima, { UBICACION_DEFAULT } from "../api/clima.js";
import noticiasApi from "../api/noticias.js";
import calendarioApi from "../api/calendario.js";
import gestorDeAlarmas from "../modulos/gestorDeAlarmas.js";
import premiumManager from "../modulos/premium/premiumManager.js";
import adultMode from "../modulos/premium/adultMode.js";

const cache = new Map();
async function cacheado(clave, ttlMs, fn) {
  const hit = cache.get(clave);
  if (hit && Date.now() - hit.t < ttlMs) return hit.v;
  const v = await fn();
  cache.set(clave, { t: Date.now(), v });
  return v;
}

export async function obtenerHerramientas(userId, opciones = {}) {
  const lat = Number.isFinite(Number(opciones.lat)) ? Number(opciones.lat) : UBICACION_DEFAULT.lat;
  const lon = Number.isFinite(Number(opciones.lon)) ? Number(opciones.lon) : UBICACION_DEFAULT.lon;
  const [clima, noticias, agenda] = await Promise.allSettled([
    cacheado(`clima:${lat},${lon}`, 10 * 60 * 1000, () => obtenerClima(lat, lon, { timeoutMs: 6000 })),
    cacheado(`noticias:${(opciones.temas || []).join(",")}`, 20 * 60 * 1000, () => noticiasApi.obtenerNoticias("", opciones.temas || [], { timeoutMs: 6000, limite: 3 })),
    calendarioApi.proximosUnificados(userId, 5)
  ]);
  return {
    hora: horaApi.obtenerHoraActual(opciones.zonaHoraria),
    clima: clima.status === "fulfilled" ? clima.value : { disponible: false, motivo: clima.reason?.message || "error" },
    noticias: noticias.status === "fulfilled" ? noticias.value : { disponible: false, motivo: noticias.reason?.message || "error" },
    agenda: agenda.status === "fulfilled" ? agenda.value : { disponible: false, motivo: agenda.reason?.message || "error" },
    alarmas: userId ? gestorDeAlarmas.obtenerAlarmasPorUsuario(userId).map(a => ({ id: a.id, hora: a.hora, titulo: a.titulo })) : []
  };
}

export function funcionesApp(userId) {
  const premium = userId ? premiumManager.obtenerEstado(userId) : { premiumActivo: false };
  let adulto = null;
  try { adulto = userId ? adultMode.obtenerEstado(userId) : null; } catch { adulto = null; }
  return {
    premium,
    lineas: [
      `Plan del usuario: ${premium.premiumActivo ? "Premium activo" : "Free"} (Premium cuesta ARS ${Number(process.env.PREMIUM_PRICE_ARS || 3000)} por 30 días, pago por Mercado Pago)`,
      `Modo adulto: requiere Premium; estado: ${adulto?.unlocked ? "desbloqueado" : adulto?.extensionEnabled ? "habilitado, falta palabra clave" : "inactivo"}`,
      "Gestor de código: Premium (guardar y buscar archivos de código del usuario)",
      "Gestor fiscal: Premium (comprobantes, estado y envío al contador)",
      "Respaldo en la nube: Premium (copia cifrada de la memoria local del teléfono y restauración en otro teléfono)",
      "Alarmas, clima, noticias, calendario y hora: disponibles en Free"
    ]
  };
}

function lineaClima(c) {
  if (!c || c.disponible === false) return `Clima: NO DISPONIBLE en este momento (${c?.motivo || "sin datos"})`;
  const partes = [`${c.ciudad || "ubicación del usuario"}: ${c.temperatura}°C`, c.descripcion];
  if (Number.isFinite(c.sensacionTermica)) partes.push(`sensación ${c.sensacionTermica}°C`);
  if (Number.isFinite(c.humedad)) partes.push(`humedad ${Math.round(c.humedad)}%`);
  if (Number.isFinite(c.vientoKmh)) partes.push(`viento ${c.vientoKmh} km/h`);
  if (c.hoy) partes.push(`hoy máx ${c.hoy.max}°C mín ${c.hoy.min}°C, prob. lluvia ${c.hoy.probLluvia ?? "?"}%`);
  if (c.manana) partes.push(`mañana máx ${c.manana.max}°C mín ${c.manana.min}°C ${c.manana.descripcion || ""}`.trim());
  if (c.proximas6h?.precipitacionMm != null) partes.push(`lluvia próximas 6h ${c.proximas6h.precipitacionMm} mm`);
  return `Clima actual (fuente ${c.proveedor}): ${partes.filter(Boolean).join(", ")}`;
}

function lineasNoticias(n) {
  if (!Array.isArray(n)) return [`Noticias: NO DISPONIBLES en este momento (${n?.motivo || "sin datos"})`];
  if (!n.length) return ["Noticias: sin titulares recientes"];
  const fuente = n[0]?.proveedor === "newsapi" ? "NewsAPI" : "Google News Argentina";
  return [`Noticias de hoy obtenidas por la app en tiempo real (${fuente}):`, ...n.slice(0, 3).map(x => `  • ${x.titulo}${x.fecha ? ` [${x.fecha.slice(0, 10)}]` : ""}`)];
}

function lineasAgenda(a) {
  if (!a || a.disponible === false) return [`Agenda: NO DISPONIBLE (${a?.motivo || "sin datos"})`];
  if (!a.eventos.length) return ["Agenda: sin eventos próximos registrados"];
  return ["Próximos eventos en la agenda del usuario:", ...a.eventos.map(e => `  • ${e.fecha} ${e.hora} — ${e.descripcion}`)];
}

/**
 * Mensaje de sistema neutral: únicamente hechos de contexto.
 */
export function construirMensajeContexto({ herramientas, memoria, datosPerfil, characterName, app, accionesTurno = [], extra = [] }) {
  const h = herramientas.hora;
  const l = [];
  l.push("[Contexto de la app ME2 — datos del sistema]");
  const nombre = memoria?.nombre || datosPerfil?.identidad?.nombre || null;
  l.push(`Nombre del usuario: ${nombre || "desconocido"}`);
  if (characterName) l.push(`Nombre que el usuario eligió para vos: ${characterName}`);
  if (memoria?.gustos?.length) l.push(`Gustos del usuario: ${memoria.gustos.join(", ")}`);
  if (memoria?.disgustos?.length) l.push(`No le gusta: ${memoria.disgustos.join(", ")}`);
  if (memoria?.hechos?.length) l.push("Cosas que el usuario contó:", ...memoria.hechos.slice(-15).map(x => `  • ${x}`));
  l.push(`Hora actual: ${h.hora} (${h.zonaHoraria})`);
  l.push(`Fecha de hoy: ${h.fechaLarga}`);
  l.push(`Ubicación por defecto del usuario: ${memoria?.ciudad || UBICACION_DEFAULT.ciudad}`);
  l.push(lineaClima(herramientas.clima));
  l.push(...lineasNoticias(herramientas.noticias));
  l.push(...lineasAgenda(herramientas.agenda));
  l.push(herramientas.alarmas.length
    ? `Alarmas activas del usuario: ${herramientas.alarmas.map(a => `${a.hora} (${a.titulo})`).join(", ")}`
    : "Alarmas activas del usuario: ninguna");
  l.push("Funciones de la app:", ...app.lineas.map(x => `  • ${x}`));
  if (accionesTurno.length) l.push("Acciones del sistema en este mensaje:", ...accionesTurno.map(x => `  • ${x}`));
  if (extra.length) l.push(...extra);
  return { role: "system", content: l.join("\n") };
}

const HISTORIAL_MAX = Number(process.env.ME2_HISTORY_MESSAGES || 12);
const HISTORIAL_CHARS = Number(process.env.ME2_HISTORY_CHARS || 1200);

export function historialAMensajes(historial = [], limite = HISTORIAL_MAX) {
  return historial.slice(-Math.min(limite, HISTORIAL_MAX)).map(item => ({
    role: item.tipo === "joi" || item.role === "assistant" ? "assistant" : "user",
    content: String(item.mensaje ?? item.text ?? "").slice(0, HISTORIAL_CHARS)
  })).filter(m => m.content.trim());
}

export default { obtenerHerramientas, funcionesApp, construirMensajeContexto, historialAMensajes };
