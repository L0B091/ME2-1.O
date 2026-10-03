// noticias.js — NEWS_API_KEY: se detecta el proveedor (NewsAPI o GNews) con 1 llamada; si falla, Google News RSS (gratis, sin key).
import HttpError from "../utils/httpError.js";

function decodificar(s = "") {
  return String(s)
    .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, "$1")
    .replace(/<[^>]+>/g, " ")
    .replace(/&amp;/g, "&").replace(/&lt;/g, "<").replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"').replace(/&#39;|&apos;/g, "'").replace(/&nbsp;/g, " ")
    .replace(/\s+/g, " ").trim();
}

function tag(item, nombre) {
  const m = item.match(new RegExp(`<${nombre}[^>]*>([\\s\\S]*?)</${nombre}>`));
  return m ? decodificar(m[1]) : null;
}

async function googleNewsRss(consulta, timeoutMs, limite) {
  const url = consulta
    ? new URL("https://news.google.com/rss/search")
    : new URL("https://news.google.com/rss");
  if (consulta) url.searchParams.set("q", consulta);
  url.searchParams.set("hl", "es-419");
  url.searchParams.set("gl", "AR");
  url.searchParams.set("ceid", "AR:es-419");
  const res = await fetch(url, { signal: AbortSignal.timeout(timeoutMs), headers: { "User-Agent": "ME2-backend/1.0" } });
  if (!res.ok) throw new HttpError(502, `Google News RSS HTTP ${res.status}`);
  const xml = await res.text();
  const items = xml.match(/<item>[\s\S]*?<\/item>/g) || [];
  return items.slice(0, limite).map(item => {
    const fechaRaw = tag(item, "pubDate");
    const fecha = fechaRaw && !Number.isNaN(Date.parse(fechaRaw)) ? new Date(fechaRaw).toISOString() : null;
    return {
      titulo: tag(item, "title"),
      descripcion: null,
      link: tag(item, "link"),
      fecha,
      fuente: tag(item, "source"),
      imagen: null,
      proveedor: "google-news-rss"
    };
  }).filter(n => n.titulo && n.link);
}

async function newsApi(ciudad, categorias, apiKey, timeoutMs, limite) {
  // Con intereses/ciudad: /everything en español (top-headlines country=ar suele venir vacío); sin consulta: titulares AR.
  const consulta = categorias.length > 0 ? categorias.join(" OR ") : ciudad;
  const url = new URL(consulta ? "https://newsapi.org/v2/everything" : "https://newsapi.org/v2/top-headlines");
  url.searchParams.set("pageSize", String(limite));
  url.searchParams.set("apiKey", apiKey);
  if (consulta) { url.searchParams.set("q", consulta); url.searchParams.set("language", "es"); url.searchParams.set("sortBy", "publishedAt"); url.searchParams.set("searchIn", "title"); }
  else url.searchParams.set("country", "ar");
  const res = await fetch(url, { signal: AbortSignal.timeout(timeoutMs) });
  const data = await res.json().catch(() => null);
  if (!res.ok || data?.status === "error") throw new HttpError(res.status || 502, data?.message || "NewsAPI no disponible");
  return (data?.articles || []).map(a => ({
    titulo: a.title, descripcion: a.description, link: a.url, fecha: a.publishedAt,
    fuente: a?.source?.name || null, imagen: a.urlToImage || null, proveedor: "newsapi"
  }));
}

async function gnews(ciudad, categorias, apiKey, timeoutMs, limite) {
  const consulta = categorias.length ? categorias.join(" OR ") : ciudad;
  const url = new URL(consulta ? "https://gnews.io/api/v4/search" : "https://gnews.io/api/v4/top-headlines");
  if (consulta) url.searchParams.set("q", consulta);
  for (const [k, v] of Object.entries({ lang: "es", country: "ar", max: String(limite), apikey: apiKey })) url.searchParams.set(k, v);
  const res = await fetch(url, { signal: AbortSignal.timeout(timeoutMs) });
  const data = await res.json().catch(() => null);
  if (!res.ok || data?.errors) throw new HttpError(res.status || 502, [].concat(data?.errors || "GNews no disponible").join(" "));
  return (data?.articles || []).map(a => ({
    titulo: a.title, descripcion: a.description, link: a.url, fecha: a.publishedAt,
    fuente: a?.source?.name || null, imagen: a.image || null, proveedor: "gnews"
  }));
}

const PROVEEDORES_KEY = { newsapi: newsApi, gnews };
let deteccion = null; // { proveedor, status } — memo por proceso

/** Prueba la key contra NewsAPI y luego GNews (1 llamada cada uno como máximo). Nunca expone la key. */
export async function detectarProveedorNoticias({ forzar = false, timeoutMs = 8000 } = {}) {
  const apiKey = String(process.env.NEWS_API_KEY || "").trim();
  if (!apiKey) return { configurado: false, proveedor: null };
  if (deteccion && !forzar) return deteccion;
  const forzado = String(process.env.NEWS_PROVIDER || "").trim().toLowerCase();
  const intentos = [];
  for (const nombre of (PROVEEDORES_KEY[forzado] ? [forzado] : ["newsapi", "gnews"])) {
    try {
      const r = await PROVEEDORES_KEY[nombre]("", [], apiKey, timeoutMs, 1);
      deteccion = { configurado: true, proveedor: nombre, ok: true, articulos: r.length, intentos };
      return deteccion;
    } catch (error) {
      intentos.push({ proveedor: nombre, status: error.status || null, error: String(error.message).replace(apiKey, "***").slice(0, 120) });
    }
  }
  deteccion = { configurado: true, proveedor: null, ok: false, intentos };
  return deteccion;
}

export async function obtenerNoticias(ciudad = "", categorias = [], opciones = {}) {
  const timeoutMs = opciones.timeoutMs || 8000;
  const limite = opciones.limite || 5;
  const apiKey = String(process.env.NEWS_API_KEY || "").trim();
  if (apiKey) {
    try {
      const det = await detectarProveedorNoticias({ timeoutMs });
      if (det.proveedor) {
        const r = await PROVEEDORES_KEY[det.proveedor](ciudad, categorias, apiKey, timeoutMs, limite);
        if (r.length) return r;
      }
    } catch (error) {
      console.error("[noticias] proveedor con key falló, uso RSS:", String(error.message).replace(apiKey, "***"));
    }
  }
  const consulta = categorias.length ? categorias.join(" OR ") : ciudad;
  return googleNewsRss(consulta, timeoutMs, limite);
}

export function proveedorNoticias() {
  if (!String(process.env.NEWS_API_KEY || "").trim()) return "google-news-rss";
  return deteccion?.proveedor || (deteccion ? "google-news-rss" : "pendiente-deteccion");
}

export function generarMensajePush(noticia) {
  if (!noticia) return "";
  return `${noticia.titulo}${noticia.descripcion ? ` - ${noticia.descripcion}` : ""}`;
}

export default { obtenerNoticias, generarMensajePush, proveedorNoticias, detectarProveedorNoticias };
