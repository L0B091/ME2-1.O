// dolphinClient.js
// Cliente OpenAI-compatible (/v1/chat/completions) para Dolphin Mistral Venice
// en el servidor privado del usuario. La personalidad vive en el master prompt
// del modelo (servidor); este cliente NO inyecta instrucciones de comportamiento.
//
// Env:
//   DOLPHIN_URL      base URL OpenAI-compatible (ej: https://mi-servidor/v1 o http://localhost:11434/v1)
//   DOLPHIN_API_KEY  token Bearer (opcional para Ollama local)
//   DOLPHIN_MODEL    nombre del modelo
//   DOLPHIN_TIMEOUT_MS, DOLPHIN_MAX_TOKENS, DOLPHIN_TEMPERATURE (opcionales)

function config() {
  const url = String(process.env.DOLPHIN_URL || "").trim().replace(/\/+$/, "");
  return {
    url,
    apiKey: String(process.env.DOLPHIN_API_KEY || "").trim(),
    model: String(process.env.DOLPHIN_MODEL || "").trim(),
    timeoutMs: Number(process.env.DOLPHIN_TIMEOUT_MS || 120000),
    maxTokens: Number(process.env.DOLPHIN_MAX_TOKENS || 400),
    temperature: process.env.DOLPHIN_TEMPERATURE != null && process.env.DOLPHIN_TEMPERATURE !== ""
      ? Number(process.env.DOLPHIN_TEMPERATURE)
      : undefined
  };
}

function endpoint(url) {
  if (/\/chat\/completions$/.test(url)) return url;
  if (/\/v1$/.test(url)) return `${url}/chat/completions`;
  return `${url}/v1/chat/completions`;
}

export function estaConfigurado() {
  const c = config();
  return Boolean(c.url && c.model);
}

export function obtenerDiagnostico() {
  const c = config();
  return {
    provider: "dolphin-openai-compatible",
    model: c.model || null,
    url: c.url || null,
    configured: estaConfigurado(),
    hasApiKey: Boolean(c.apiKey)
  };
}

/**
 * @param {Array<{role:string, content:string}>} messages
 * @returns {Promise<{used:boolean, respuesta:string|null, model?:string, usage?:object, reason?:string}>}
 */
export async function chat(messages, opciones = {}) {
  const c = config();
  if (!estaConfigurado()) return { used: false, respuesta: null, reason: "dolphin_no_configurado" };
  const headers = { "Content-Type": "application/json" };
  if (c.apiKey) headers.Authorization = `Bearer ${c.apiKey}`;
  const body = {
    model: c.model,
    messages,
    max_tokens: opciones.maxTokens || c.maxTokens,
    stream: false
  };
  if (Number.isFinite(c.temperature)) body.temperature = c.temperature;

  const res = await fetch(endpoint(c.url), {
    method: "POST",
    headers,
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(opciones.timeoutMs || c.timeoutMs)
  });
  const raw = await res.text();
  let data = null;
  try { data = raw ? JSON.parse(raw) : null; } catch { data = null; }
  if (!res.ok) {
    return { used: false, respuesta: null, reason: `http_${res.status}`, detail: data?.error || raw.slice(0, 300) };
  }
  const contenido = data?.choices?.[0]?.message?.content;
  if (typeof contenido !== "string" || !contenido.trim()) {
    return { used: false, respuesta: null, reason: "respuesta_vacia" };
  }
  return { used: true, respuesta: contenido.trim(), model: data?.model || c.model, usage: data?.usage || null };
}

export default { chat, estaConfigurado, obtenerDiagnostico };
