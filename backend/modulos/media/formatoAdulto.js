// formatoAdulto.js — formato de la burbuja en MODO ADULTO: "texto" | "gif" | "texto+gif".
// El GIF funciona como reemplazo de emoji. El LLM puede devolver una pista estructurada al final:
//   [[media: tags=a,b; formato=gif]]
// El orquestador la valida: sesión adulta activa, tags que existan en el manifest, sin repetir GIFs recientes,
// y sin muchos "gif solo" seguidos. Sin coincidencia → texto. Fuera del modo adulto: siempre texto.
import storage from "../../utils/jsonStorage.js";
import selectorMedia from "./selectorMedia.js";

const NAMESPACE = "media_adulto";
export const POLITICA = Object.freeze({ sinRepetirUltimos: 5, maxGifSoloSeguidos: 2 });
const FORMATOS = new Set(["texto", "gif", "texto+gif"]);
const RE_PISTA = /\[\[\s*media\s*:([^\]]*)\]\]/i;

// Contrato técnico (no de personalidad) que se agrega al contexto SOLO con modo adulto activo.
export const LINEA_CONTRATO = "Formato técnico disponible en modo adulto: al final de la respuesta podés agregar una línea [[media: tags=<palabras clave>; formato=texto|gif|texto+gif]] para acompañar o reemplazar el texto con un GIF del catálogo.";

export function parsearPista(respuesta = "") {
  const m = String(respuesta).match(RE_PISTA);
  const texto = String(respuesta).replace(RE_PISTA, "").trim();
  if (!m) return { texto, pista: null };
  const campos = Object.fromEntries(m[1].split(";").map(p => p.split("=").map(x => x.trim())).filter(p => p.length === 2 && p[0]));
  const formato = FORMATOS.has((campos.formato || "").toLowerCase()) ? campos.formato.toLowerCase() : "texto";
  const tags = String(campos.tags || "").split(/[,\s]+/).map(t => t.trim()).filter(Boolean).slice(0, 8);
  return { texto, pista: { formato, tags } };
}

function estado(userId) {
  return storage.readUserData(NAMESPACE, userId, { ultimosGif: [], gifSoloSeguidos: 0 });
}

/**
 * @returns {{ formato: "texto"|"gif"|"texto+gif", texto: string, gif: object|null, motivo: string }}
 */
export function decidir({ userId, respuesta = "", mensaje = "", adult = null, catalogos, persistir = true } = {}) {
  const { texto, pista } = parsearPista(respuesta);
  const activo = Boolean(adult?.premiumActivo && adult?.unlocked);
  const textoSeguro = texto || "";
  if (!activo) return { formato: "texto", texto: textoSeguro, gif: null, motivo: "fuera_de_modo_adulto" };
  const e = userId ? estado(userId) : { ultimosGif: [], gifSoloSeguidos: 0 };
  let formato = pista?.formato || "texto";
  let gif = null;
  let motivo = pista ? "pista_llm" : "sin_pista";
  if (formato !== "texto") {
    gif = selectorMedia.seleccionar({ mensaje: (pista?.tags || []).join(" ") || mensaje, adult, catalogos, tipo: "gif", excluir: e.ultimosGif.slice(-POLITICA.sinRepetirUltimos) });
    if (!gif) { formato = "texto"; motivo = "sin_gif_coincidente"; }
  }
  if (formato === "gif" && (e.gifSoloSeguidos >= POLITICA.maxGifSoloSeguidos || false)) {
    formato = textoSeguro ? "texto+gif" : "gif";
    if (textoSeguro) motivo = "limite_gif_solo_seguidos";
  }
  if (formato === "texto+gif" && !textoSeguro) formato = "gif";
  if (formato === "texto" && !textoSeguro && !gif) motivo = "sin_contenido";
  if (userId && persistir) {
    storage.writeUserData(NAMESPACE, userId, {
      ultimosGif: gif ? [...e.ultimosGif, gif.id].slice(-20) : e.ultimosGif,
      gifSoloSeguidos: formato === "gif" ? e.gifSoloSeguidos + 1 : 0
    });
  }
  return { formato, texto: formato === "gif" ? "" : textoSeguro, gif, motivo };
}

export default { POLITICA, LINEA_CONTRATO, parsearPista, decidir };
