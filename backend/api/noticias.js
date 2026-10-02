// noticias.js — NewsAPI si NEWS_API_KEY existe; si no, Google News RSS (gratis, sin key).
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
  const url = new URL("https://newsapi.org/v2/top-headlines");
  url.searchParams.set("pageSize", String(limite));
  url.searchParams.set("language", "es");
  url.searchParams.set("country", "ar");
  url.searchParams.set("apiKey", apiKey);
  if (categorias.length > 0) url.searchParams.set("q", categorias.join(" OR "));
  else if (ciudad) url.searchParams.set("q", ciudad);
  const res = await fetch(url, { signal: AbortSignal.timeout(timeoutMs) });
  const data = await res.json().catch(() => null);
  if (!res.ok || data?.status === "error") throw new HttpError(res.status || 502, data?.message || "NewsAPI no disponible");
  return (data?.articles || []).map(a => ({
    titulo: a.title, descripcion: a.description, link: a.url, fecha: a.publishedAt,
    fuente: a?.source?.name || null, imagen: a.urlToImage || null, proveedor: "newsapi"
  }));
}

export async function obtenerNoticias(ciudad = "", categorias = [], opciones = {}) {
  const timeoutMs = opciones.timeoutMs || 8000;
  const limite = opciones.limite || 5;
  const apiKey = String(process.env.NEWS_API_KEY || "").trim();
  if (apiKey) {
    try {
      const r = await newsApi(ciudad, categorias, apiKey, timeoutMs, limite);
      if (r.length) return r;
    } catch (error) {
      console.error("[noticias] NewsAPI falló, uso RSS:", error.message);
    }
  }
  const consulta = categorias.length ? categorias.join(" OR ") : ciudad;
  return googleNewsRss(consulta, timeoutMs, limite);
}

export function proveedorNoticias() {
  return String(process.env.NEWS_API_KEY || "").trim() ? "newsapi" : "google-news-rss";
}

export function generarMensajePush(noticia) {
  if (!noticia) return "";
  return `${noticia.titulo}${noticia.descripcion ? ` - ${noticia.descripcion}` : ""}`;
}

export default { obtenerNoticias, generarMensajePush, proveedorNoticias };
