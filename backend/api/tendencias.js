// tendencias.js — datos públicos gratuitos y sin key para temas de iniciativa.
// Juegos: Steam (top ventas). Películas: ranking de iTunes Argentina (RSS JSON).
// Si fallan, la fuente correspondiente no aporta datos (no se inventan tendencias).
const CACHE_MS = 6 * 3600e3;
const cache = new Map();

async function json(url, timeoutMs) {
  const res = await fetch(url, { signal: AbortSignal.timeout(timeoutMs), headers: { "User-Agent": "ME2-backend/1.0" } });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

async function cacheado(clave, fn) {
  const c = cache.get(clave);
  if (c && Date.now() - c.t < CACHE_MS) return c.v;
  const v = await fn();
  cache.set(clave, { t: Date.now(), v });
  return v;
}

export function juegosEnTendencia({ timeoutMs = 6000, limite = 5 } = {}) {
  return cacheado("juegos", async () => {
    const d = await json("https://store.steampowered.com/api/featuredcategories?cc=ar&l=spanish", timeoutMs);
    const nombres = [...new Set((d?.top_sellers?.items || []).map(i => String(i.name || "").trim()).filter(Boolean))]
      .filter(n => !/^Steam (Machine|Frame|Deck|Controller|Link)\b/i.test(n));
    return nombres.slice(0, limite).map(nombre => ({ nombre, proveedor: "steam-top-sellers" }));
  });
}

export function peliculasEnTendencia({ timeoutMs = 6000, limite = 15 } = {}) {
  return cacheado("peliculas", async () => {
    const d = await json(`https://itunes.apple.com/ar/rss/topmovies/limit=${limite}/json`, timeoutMs);
    return (d?.feed?.entry || []).map(e => ({
      titulo: e?.["im:name"]?.label, genero: e?.category?.attributes?.label || null, proveedor: "itunes-top-movies-ar"
    })).filter(p => p.titulo);
  });
}

export default { juegosEnTendencia, peliculasEnTendencia };
