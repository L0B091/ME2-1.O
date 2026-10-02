// contextoLLM.js
// Construye el contexto factual que el orquestador entrega al LLM.
// SOLO datos (hora, clima, noticias, agenda, alarmas, memoria, funciones de la app).
// Sin instrucciones de personalidad ni de estilo: eso vive en el master prompt del modelo.
import horaApi from "../api/hora.js";
import obtenerClima, { UBICACION_DEFAULT, ubicacionDevHabilitada } from "../api/clima.js";
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

function normalizar(t = "") {
  return String(t).normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase();
}

// Un titular es de interés si contiene, como palabra completa, algún gusto nombrado por el usuario
// (o una palabra fuerte ≥4 letras de un gusto compuesto, ej. "Boca" de "Boca Juniors").
export function interesCoincidente(texto, gustos = []) {
  const t = ` ${normalizar(texto).replace(/[^a-z0-9ñ]+/g, " ")} `;
  for (const g of gustos) {
    const frase = normalizar(g).replace(/[^a-z0-9ñ]+/g, " ").trim();
    if (frase.length >= 3 && t.includes(` ${frase} `)) return g;
    const fuertes = frase.split(" ").filter(p => p.length >= 4);
    const hit = fuertes.find(p => t.includes(` ${p} `));
    if (hit) return g;
  }
  return null;
}

export async function noticiasDeInteres(gustos = [], opciones = {}) {
  if (!gustos.length) return { disponible: false, motivo: "sin_intereses_registrados" };
  const clave = `noticias:${gustos.join("|")}`;
  const lista = await cacheado(clave, 20 * 60 * 1000, () =>
    noticiasApi.obtenerNoticias("", gustos.slice(0, 5).map(g => (g.includes(" ") ? `"${g}"` : g)), { timeoutMs: opciones.timeoutMs || 6000, limite: 10 }));
  return lista
    .map(n => ({ ...n, interes: interesCoincidente(n.titulo, gustos) }))
    .filter(n => n.interes)
    .slice(0, opciones.limite || 3);
}

// Ubicación del usuario (memoria) o, solo si está habilitado, la de desarrollo.
export function resolverUbicacion(memoria = {}) {
  if (memoria?.ubicacion && Number.isFinite(memoria.ubicacion.lat)) return { ...memoria.ubicacion, origen: "usuario" };
  if (ubicacionDevHabilitada()) return { ...UBICACION_DEFAULT, origen: "dev_default" };
  return null;
}

export async function obtenerHerramientas(userId, opciones = {}) {
  const memoria = opciones.memoria || {};
  const ubicacion = Number.isFinite(Number(opciones.lat)) && Number.isFinite(Number(opciones.lon))
    ? { lat: Number(opciones.lat), lon: Number(opciones.lon), ciudad: null, zonaHoraria: opciones.zonaHoraria || null, origen: "cliente" }
    : resolverUbicacion(memoria);
  const gustos = memoria.gustos || [];
  const [clima, noticias, agenda] = await Promise.allSettled([
    ubicacion
      ? cacheado(`clima:${ubicacion.lat},${ubicacion.lon}`, 10 * 60 * 1000, () => obtenerClima(ubicacion.lat, ubicacion.lon, { timeoutMs: 6000, ciudad: ubicacion.ciudad }))
      : Promise.reject(new Error("ubicacion_desconocida")),
    gustos.length ? noticiasDeInteres(gustos) : Promise.reject(new Error("sin_intereses_registrados")),
    calendarioApi.proximosUnificados(userId, 5)
  ]);
  const zona = opciones.zonaHoraria || ubicacion?.zonaHoraria || null;
  return {
    ubicacion,
    hora: { ...horaApi.obtenerHoraActual(zona || undefined), zonaDelUsuario: Boolean(zona) },
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
  if (c?.motivo === "ubicacion_desconocida") return "Clima: no consultado porque la ubicación del usuario es desconocida";
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
  if (n?.motivo === "sin_intereses_registrados") return [];
  if (!Array.isArray(n)) return [`Noticias de interés: NO DISPONIBLES en este momento (${n?.motivo || "sin datos"})`];
  if (!n.length) return ["Noticias de interés: ningún titular reciente coincide con los gustos del usuario"];
  const fuente = n[0]?.proveedor === "newsapi" ? "NewsAPI" : "Google News Argentina";
  return [`Noticias de interés del usuario obtenidas por la app en tiempo real (${fuente}):`, ...n.slice(0, 3).map(x => `  • ${x.titulo}${x.fecha ? ` [${x.fecha.slice(0, 10)}]` : ""}${x.interes ? ` (interés: ${x.interes})` : ""}`)];
}

function lineasAgenda(a) {
  if (!a || a.disponible === false) return [`Agenda: NO DISPONIBLE (${a?.motivo || "sin datos"})`];
  if (!a.eventos.length) return ["Agenda: sin eventos próximos registrados"];
  return ["Próximos eventos en la agenda del usuario:", ...a.eventos.map(e => `  • ${e.fecha} ${e.hora} — ${e.descripcion}`)];
}

/**
 * Mensaje de sistema neutral: únicamente hechos de contexto.
 */
function lineasEstado(estadoEmocional, pendientes = []) {
  const l = [];
  if (estadoEmocional) {
    const hace = estadoEmocional.haceMinutos < 60 ? `hace ${estadoEmocional.haceMinutos} min` : `hace ${Math.round(estadoEmocional.haceMinutos / 60)} h`;
    l.push(estadoEmocional.vigente
      ? `Estado emocional del usuario (señal detectada en sus mensajes): ${estadoEmocional.emocion}, ${hace} ("${estadoEmocional.evidencia}")`
      : `Último estado emocional detectado del usuario: ${estadoEmocional.emocion}, ${hace} (ya no vigente)`);
  }
  if (pendientes.length) {
    l.push("Temas pendientes que el usuario mencionó:", ...pendientes.slice(-5).map(p => `  • ${p.texto} (${new Date(p.timestamp).toISOString().slice(0, 10)})`));
  }
  return l;
}

export function construirMensajeContexto({ herramientas, memoria, datosPerfil, characterName, app, accionesTurno = [], extra = [], onboarding = [], estadoEmocional = null, pendientes = [] }) {
  const h = herramientas.hora;
  const l = [];
  l.push("[Contexto de la app ME2 — datos del sistema]");
  const nombre = memoria?.nombre || null;
  l.push(`Nombre del usuario: ${nombre || "desconocido"}`);
  const nombreCuenta = datosPerfil?.identidad?.nombre;
  if (!nombre && nombreCuenta && !String(nombreCuenta).includes("@")) l.push(`Nombre de la cuenta (sin confirmar en la conversación): ${nombreCuenta}`);
  if (onboarding.length) l.push(...onboarding);
  if (characterName) l.push(`Nombre que el usuario eligió para vos: ${characterName}`);
  if (memoria?.gustos?.length) l.push(`Gustos del usuario: ${memoria.gustos.join(", ")}`);
  if (memoria?.disgustos?.length) l.push(`No le gusta: ${memoria.disgustos.join(", ")}`);
  if (memoria?.hechos?.length) l.push("Cosas que el usuario contó:", ...memoria.hechos.slice(-15).map(x => `  • ${x}`));
  l.push(...lineasEstado(estadoEmocional, pendientes));
  l.push(`Hora actual: ${h.hora} (${h.zonaHoraria}${h.zonaDelUsuario ? "" : ", zona horaria por defecto del servidor; la del usuario aún no se conoce"})`);
  l.push(`Fecha de hoy: ${h.fechaLarga}`);
  const u = herramientas.ubicacion;
  l.push(u
    ? `Ubicación del usuario: ${u.ciudad || `${u.lat},${u.lon}`}${u.provincia ? `, ${u.provincia}` : ""}${u.pais ? `, ${u.pais}` : ""}${u.origen === "dev_default" ? " (ubicación de desarrollo por defecto, NO confirmada)" : ""}`
    : "Ubicación del usuario: desconocida");
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
    role: item.role === "assistant" || (item.tipo && item.tipo !== "usuario" && item.tipo !== "user") ? "assistant" : "user",
    content: String(item.mensaje ?? item.text ?? "").slice(0, HISTORIAL_CHARS)
  })).filter(m => m.content.trim());
}

export default { obtenerHerramientas, funcionesApp, construirMensajeContexto, historialAMensajes, noticiasDeInteres, interesCoincidente, resolverUbicacion };
