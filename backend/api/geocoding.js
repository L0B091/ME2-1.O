// geocoding.js — ciudad → coordenadas + zona horaria (gratis, sin key).
// Open-Meteo Geocoding primero; Nominatim (OSM) como respaldo (sin zona horaria → se infiere por país AR).
const UA = process.env.MET_USER_AGENT || "ME2-backend/1.0 github.com/L0B091/ME2-1.O";

async function openMeteo(nombre, timeoutMs) {
  const url = new URL("https://geocoding-api.open-meteo.com/v1/search");
  url.searchParams.set("name", nombre);
  url.searchParams.set("count", "5");
  url.searchParams.set("language", "es");
  url.searchParams.set("format", "json");
  const res = await fetch(url, { signal: AbortSignal.timeout(timeoutMs) });
  const data = await res.json().catch(() => null);
  if (!res.ok || data?.error) throw new Error(data?.reason || `open-meteo geocoding HTTP ${res.status}`);
  const lista = data?.results || [];
  // Preferir Argentina si hay ambigüedad (app en es-AR)
  const r = lista.find(x => x.country_code === "AR") || lista[0];
  if (!r) return null;
  return {
    ciudad: r.name, provincia: r.admin1 || null, pais: r.country || null, paisCodigo: r.country_code || null,
    lat: r.latitude, lon: r.longitude, zonaHoraria: r.timezone || null, proveedor: "open-meteo-geocoding"
  };
}

async function nominatim(nombre, timeoutMs) {
  const url = new URL("https://nominatim.openstreetmap.org/search");
  url.searchParams.set("q", nombre);
  url.searchParams.set("format", "json");
  url.searchParams.set("limit", "5");
  url.searchParams.set("addressdetails", "1");
  url.searchParams.set("accept-language", "es");
  const res = await fetch(url, { signal: AbortSignal.timeout(timeoutMs), headers: { "User-Agent": UA } });
  if (!res.ok) throw new Error(`nominatim HTTP ${res.status}`);
  const lista = await res.json();
  const r = lista.find(x => x.address?.country_code === "ar") || lista[0];
  if (!r) return null;
  const ar = r.address?.country_code === "ar";
  return {
    ciudad: r.address?.city || r.address?.town || r.address?.village || r.name, provincia: r.address?.state || null,
    pais: r.address?.country || null, paisCodigo: (r.address?.country_code || "").toUpperCase() || null,
    lat: Number(r.lat), lon: Number(r.lon), zonaHoraria: ar ? "America/Argentina/Buenos_Aires" : null, proveedor: "nominatim"
  };
}

export async function geocodificar(nombre, opciones = {}) {
  const q = String(nombre || "").trim();
  if (q.length < 2) return null;
  const timeoutMs = opciones.timeoutMs || 6000;
  const errores = [];
  for (const fn of [openMeteo, nominatim]) {
    try {
      const r = await fn(q, timeoutMs);
      if (r) return r;
    } catch (error) {
      errores.push(error.message);
    }
  }
  if (errores.length === 2) throw new Error(`Geocoding no disponible (${errores.join(" | ")})`);
  return null;
}

export default { geocodificar };
