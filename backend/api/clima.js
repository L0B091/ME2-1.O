// clima.js — Open-Meteo (gratis, sin key). OpenWeather opcional si OPENWEATHER_API_KEY existe.
import HttpError from "../utils/httpError.js";

// Ubicación por defecto SOLO para desarrollo: se usa únicamente si ME2_DEV_DEFAULT_LOCATION=true.
export const UBICACION_DEFAULT = Object.freeze({
  ciudad: process.env.ME2_CIUDAD || "San Nicolás de los Arroyos",
  lat: Number(process.env.ME2_LAT || -33.3342),
  lon: Number(process.env.ME2_LON || -60.2108),
  zonaHoraria: process.env.ME2_TZ || "America/Argentina/Buenos_Aires"
});

export function ubicacionDevHabilitada() {
  return String(process.env.ME2_DEV_DEFAULT_LOCATION || "").trim().toLowerCase() === "true";
}

const WMO = {
  0: "despejado", 1: "mayormente despejado", 2: "parcialmente nublado", 3: "nublado",
  45: "niebla", 48: "niebla con escarcha", 51: "llovizna leve", 53: "llovizna", 55: "llovizna intensa",
  61: "lluvia leve", 63: "lluvia", 65: "lluvia intensa", 66: "lluvia helada", 67: "lluvia helada intensa",
  71: "nevada leve", 73: "nevada", 75: "nevada intensa", 80: "chaparrones leves", 81: "chaparrones",
  82: "chaparrones fuertes", 95: "tormenta", 96: "tormenta con granizo", 99: "tormenta fuerte con granizo"
};

async function openMeteo(lat, lon, timeoutMs) {
  const url = new URL("https://api.open-meteo.com/v1/forecast");
  url.searchParams.set("latitude", String(lat));
  url.searchParams.set("longitude", String(lon));
  url.searchParams.set("current", "temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m");
  url.searchParams.set("daily", "temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code");
  url.searchParams.set("forecast_days", "2");
  url.searchParams.set("timezone", "auto");
  const res = await fetch(url, { signal: AbortSignal.timeout(timeoutMs) });
  const data = await res.json().catch(() => null);
  if (!res.ok || !data?.current) throw new HttpError(502, data?.reason || "Open-Meteo no disponible");
  const c = data.current;
  const d = data.daily || {};
  return {
    proveedor: "open-meteo",
    temperatura: c.temperature_2m,
    sensacionTermica: c.apparent_temperature,
    humedad: c.relative_humidity_2m,
    vientoKmh: c.wind_speed_10m,
    descripcion: WMO[c.weather_code] || `código ${c.weather_code}`,
    hoy: d.time ? { max: d.temperature_2m_max?.[0], min: d.temperature_2m_min?.[0], probLluvia: d.precipitation_probability_max?.[0] } : null,
    manana: d.time?.[1] ? { max: d.temperature_2m_max?.[1], min: d.temperature_2m_min?.[1], probLluvia: d.precipitation_probability_max?.[1], descripcion: WMO[d.weather_code?.[1]] || null } : null,
    observadoEn: c.time
  };
}

const MET_SIMBOLOS = {
  clearsky: "despejado", fair: "mayormente despejado", partlycloudy: "parcialmente nublado", cloudy: "nublado",
  fog: "niebla", lightrain: "lluvia leve", rain: "lluvia", heavyrain: "lluvia intensa",
  lightrainshowers: "chaparrones leves", rainshowers: "chaparrones", heavyrainshowers: "chaparrones fuertes",
  rainandthunder: "tormenta", heavyrainandthunder: "tormenta fuerte", lightsleet: "aguanieve", snow: "nevada"
};

// MET Norway (api.met.no) — gratis, sin key, requiere User-Agent identificable.
async function metNorway(lat, lon, timeoutMs) {
  const url = `https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=${lat.toFixed(4)}&lon=${lon.toFixed(4)}`;
  const res = await fetch(url, { signal: AbortSignal.timeout(timeoutMs), headers: { "User-Agent": process.env.MET_USER_AGENT || "ME2-backend/1.0 github.com/L0B091/ME2-1.O" } });
  const data = await res.json().catch(() => null);
  const serie = data?.properties?.timeseries?.[0];
  const inst = serie?.data?.instant?.details;
  if (!res.ok || !Number.isFinite(inst?.air_temperature)) throw new HttpError(502, "MET Norway no disponible");
  const simbolo = String(serie.data?.next_1_hours?.summary?.symbol_code || serie.data?.next_6_hours?.summary?.symbol_code || "").replace(/_(day|night|polartwilight)$/, "");
  const prox6 = serie.data?.next_6_hours?.details || {};
  return {
    proveedor: "met-norway",
    temperatura: inst.air_temperature,
    humedad: inst.relative_humidity,
    vientoKmh: Number.isFinite(inst.wind_speed) ? Math.round(inst.wind_speed * 3.6) : null,
    descripcion: MET_SIMBOLOS[simbolo] || simbolo || null,
    proximas6h: { precipitacionMm: prox6.precipitation_amount ?? null },
    observadoEn: serie.time
  };
}

async function openWeather(lat, lon, apiKey, timeoutMs) {
  const url = new URL("https://api.openweathermap.org/data/2.5/weather");
  for (const [k, v] of Object.entries({ lat, lon, appid: apiKey, units: "metric", lang: "es" })) url.searchParams.set(k, String(v));
  const res = await fetch(url, { signal: AbortSignal.timeout(timeoutMs) });
  const data = await res.json().catch(() => null);
  if (!res.ok || !Number.isFinite(data?.main?.temp)) throw new HttpError(res.status || 502, data?.message || "OpenWeather no disponible");
  return {
    proveedor: "openweather",
    temperatura: data.main.temp,
    sensacionTermica: data.main.feels_like,
    humedad: data.main.humidity,
    descripcion: data.weather?.[0]?.description || null
  };
}

// WeatherAPI.com (por si WEATHER_API_KEY es de ese proveedor).
async function weatherApiCom(lat, lon, apiKey, timeoutMs) {
  const url = new URL("https://api.weatherapi.com/v1/current.json");
  for (const [k, v] of Object.entries({ key: apiKey, q: `${lat},${lon}`, lang: "es" })) url.searchParams.set(k, String(v));
  const res = await fetch(url, { signal: AbortSignal.timeout(timeoutMs) });
  const data = await res.json().catch(() => null);
  if (!res.ok || !Number.isFinite(data?.current?.temp_c)) throw new HttpError(res.status || 502, data?.error?.message || "WeatherAPI no disponible");
  return {
    proveedor: "weatherapi", temperatura: data.current.temp_c, sensacionTermica: data.current.feelslike_c,
    humedad: data.current.humidity, vientoKmh: data.current.wind_kph, descripcion: data.current.condition?.text || null
  };
}

/** Keys de clima: WEATHER_API_KEY / OPENWEATHER_API_KEY; admite varias separadas por salto de línea, coma o espacio. */
function climaKeys() {
  const raw = [process.env.WEATHER_API_KEY, process.env.OPENWEATHER_API_KEY].filter(Boolean).join("\n");
  return [...new Set(raw.split(/[\s,;]+/).map(k => k.trim()).filter(Boolean))];
}
const CON_KEY = { openweather: openWeather, weatherapi: weatherApiCom };
let deteccionClima = null; // { proveedor, keyIndex } — la key nunca se expone

function ocultar(texto, keys) {
  return keys.reduce((t, k) => t.split(k).join("***"), String(texto));
}

/** Detecta con llamadas reales qué key/proveedor funciona (OpenWeather, luego WeatherAPI). Nunca expone la key. */
export async function detectarProveedorClima({ forzar = false, timeoutMs = 8000 } = {}) {
  const keys = climaKeys();
  if (!keys.length) return { configurado: false, proveedor: null };
  if (deteccionClima && !forzar) return deteccionClima;
  const intentos = [];
  for (const [keyIndex, key] of keys.entries()) {
    for (const nombre of ["openweather", "weatherapi"]) {
      try {
        const r = await CON_KEY[nombre](UBICACION_DEFAULT.lat, UBICACION_DEFAULT.lon, key, timeoutMs);
        deteccionClima = { configurado: true, keys: keys.length, keyIndex, proveedor: nombre, ok: true, temperaturaPrueba: r.temperatura, intentos };
        return deteccionClima;
      } catch (error) {
        intentos.push({ keyIndex, proveedor: nombre, status: error.status || null, error: ocultar(error.message, keys).slice(0, 120) });
      }
    }
  }
  deteccionClima = { configurado: true, keys: keys.length, proveedor: null, ok: false, intentos };
  return deteccionClima;
}

export default async function obtenerClima(lat, lon, opciones = {}) {
  if (!Number.isFinite(Number(lat)) || !Number.isFinite(Number(lon))) {
    throw new HttpError(400, "Ubicación desconocida: se requieren lat/lon");
  }
  lat = Number(lat); lon = Number(lon);
  const timeoutMs = opciones.timeoutMs || 8000;
  const ciudad = opciones.ciudad || null;
  const keys = climaKeys();
  // Open-Meteo primero (pronóstico hoy/mañana + zona horaria); la key (OpenWeather/WeatherAPI) es el respaldo; MET Norway último.
  const conKey = keys.length ? [async () => {
    const det = await detectarProveedorClima({ timeoutMs });
    if (!det.proveedor) throw new HttpError(503, "key de clima inválida");
    return CON_KEY[det.proveedor](lat, lon, keys[det.keyIndex], timeoutMs);
  }] : [];
  const proveedores = [
    () => openMeteo(lat, lon, timeoutMs),
    ...conKey,
    () => metNorway(lat, lon, timeoutMs)
  ];
  const errores = [];
  for (const proveedor of proveedores) {
    try {
      return { ciudad, lat, lon, ...(await proveedor()) };
    } catch (error) {
      errores.push(error.message);
    }
  }
  throw new HttpError(503, `Clima no disponible (${errores.join(" | ")})`);
}
