// selectorMedia.js — elige un clip o GIF del catálogo por contexto (tags), con gating estricto del catálogo adulto.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { INTENSITY_ORDER } from "../premium/adultMode.js";

const RAIZ = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../media/catalogos");

function normalizar(t = "") {
  return String(t).normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase();
}
function palabras(t) {
  return new Set(normalizar(t).match(/[a-z0-9ñ]{3,}/g) || []);
}

export function cargarCatalogo(nombre, raiz = RAIZ) {
  try {
    const m = JSON.parse(fs.readFileSync(path.join(raiz, nombre, "manifest.json"), "utf8"));
    const items = (Array.isArray(m.items) ? m.items : []).filter(i => i?.id && ["clip", "gif"].includes(i.tipo) && /^[a-z0-9_-]+$/i.test(i.id));
    return { nombre, adulto: Boolean(m.adulto), items };
  } catch {
    return { nombre, adulto: nombre === "xxx", items: [] };
  }
}

/** Solo devuelve catálogos permitidos: el adulto únicamente con sesión adulta desbloqueada. */
export function catalogosPermitidos(adult, catalogos = [cargarCatalogo("normal"), cargarCatalogo("xxx")]) {
  const adultoActivo = Boolean(adult?.premiumActivo && adult?.unlocked);
  return catalogos.filter(c => !c.adulto || adultoActivo);
}

/**
 * @param {{ mensaje: string, contexto?: string, adult?: object, catalogos?: object[], soloAdulto?: boolean }} p
 * @returns {{ tipo: "clip"|"gif", id: string, catalogo: string, url: string, tags: string[] }|null}
 */
export function seleccionar({ mensaje = "", contexto = "", adult = null, catalogos, soloAdulto = true, tipo = null, excluir = [] } = {}) {
  const permitidos = catalogosPermitidos(adult, catalogos).filter(c => !soloAdulto || c.adulto);
  if (!permitidos.length) return null;
  const tope = INTENSITY_ORDER.indexOf(adult?.intensity || "none");
  const claves = palabras(`${mensaje} ${contexto}`);
  let mejor = null;
  for (const c of permitidos) {
    for (const item of c.items) {
      if (tipo && item.tipo !== tipo) continue;
      if (excluir.includes(item.id)) continue;
      if (c.adulto && INTENSITY_ORDER.indexOf(item.intensidad || "explicit") > tope) continue;
      const puntaje = (item.tags || []).filter(t => [...palabras(t)].some(p => claves.has(p))).length;
      if (puntaje > 0 && (!mejor || puntaje > mejor.puntaje)) mejor = { puntaje, item, catalogo: c.nombre };
    }
  }
  if (!mejor) return null;
  return { tipo: mejor.item.tipo, id: mejor.item.id, catalogo: mejor.catalogo, url: `/api/media/${mejor.catalogo}/${mejor.item.id}`, tags: mejor.item.tags || [] };
}

/**
 * Contrato de respuesta: SIEMPRE hay clip (texto + clip). En modo adulto el clip sale del catálogo adulto si hay
 * coincidencia; si no, el clip de la galería del avatar (categoría elegida por contexto, o fallback).
 * El GIF lo decide formatoAdulto.js (solo modo adulto).
 */
export function mediosRespuesta({ mensaje = "", respuesta = "", adult = null, videoGaleria = null, catalogos } = {}) {
  const adultoActivo = Boolean(adult?.premiumActivo && adult?.unlocked);
  const clipAdulto = adultoActivo ? seleccionar({ mensaje, contexto: respuesta, adult, catalogos, tipo: "clip" }) : null;
  const clip = clipAdulto
    ? { ...clipAdulto, fuente: "catalogo_adulto" }
    : { tipo: "clip", fuente: "galeria", catalogo: "galeria", categoria: videoGaleria?.categoria || "loop_neutral", id: videoGaleria?.assetName || null, fallback: !videoGaleria?.categoria };
  return { clip };
}

export function archivoDe(catalogo, id, raiz = RAIZ) {
  // Anti path traversal: catálogo e id con formato estricto, archivo como nombre plano y ruta final contenida en la raíz.
  if (!/^[a-z0-9_-]{1,40}$/i.test(String(catalogo)) || !/^[a-z0-9_-]{1,120}$/i.test(String(id))) return null;
  const c = cargarCatalogo(catalogo, raiz);
  const item = c.items.find(i => i.id === id);
  if (!item?.archivo || /[\\/\0]|\.\./.test(item.archivo) || path.basename(item.archivo) !== item.archivo) return null;
  const base = path.resolve(raiz, catalogo);
  const ruta = path.resolve(base, item.archivo);
  if (!ruta.startsWith(base + path.sep)) return null;
  return fs.existsSync(ruta) && fs.statSync(ruta).isFile() ? { ruta, item, adulto: c.adulto } : null;
}

export default { cargarCatalogo, catalogosPermitidos, seleccionar, mediosRespuesta, archivoDe };
