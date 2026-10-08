// textoPlano.js — el chat de ME2 muestra texto plano: se quita el markdown que a veces devuelve el LLM
// (**negrita**, __negrita__, *cursiva*, `código`, # títulos, [texto](url)) sin tocar el contenido.
export function textoPlano(texto) {
  if (typeof texto !== "string") return texto;
  return texto
    .replace(/```[a-z]*\n?([\s\S]*?)```/gi, "$1")
    .replace(/`([^`\n]+)`/g, "$1")
    .replace(/\*\*([^*\n]+?)\*\*/g, "$1")
    .replace(/__([^_\n]+?)__/g, "$1")
    .replace(/(^|[^\w*])\*(?!\s)([^*\n]+?)(?<!\s)\*(?![\w*])/g, "$1$2")
    .replace(/(^|[^\w_])_(?!\s)([^_\n]+?)(?<!\s)_(?![\w_])/g, "$1$2")
    .replace(/^\s{0,3}#{1,6}\s+/gm, "")
    .replace(/\[([^\]\n]+)\]\((https?:\/\/[^)\s]+)\)/g, "$1: $2")
    .replace(/\*\*/g, "")
    .replace(/[ \t]+\n/g, "\n")
    .trim();
}
export default textoPlano;
