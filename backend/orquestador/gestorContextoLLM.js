const DEFAULT_BUDGET = 800;
const MAX_MEMORIES = 3;
const MAX_HISTORY = 4;
const MAX_CODE = 2;
const STOP = new Set("que cual cuales como cuando donde habian habias habia hemos sobre para con desde hasta entre esto eso esta este estos estas eran era fue son una uno unos unas por del los las les me mi mis tu tus te nos ayer hoy que".split(" "));

function norm(value = "") {
  return String(value).toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "").replace(/[¿?¡!.,;:()[\]{}"'\x60]/g, " ").replace(/\s+/g, " ").trim();
}
function tokens(value = "") {
  return (norm(value).match(/[a-z0-9]+/g) || []).filter(word => (word.length > 2 || word === "ia") && !STOP.has(word));
}
function has(text, patterns) { return patterns.some(pattern => text.includes(norm(pattern))); }

function clasificarNecesidad(message = "") {
  const text = norm(message);
  if (!text) return { nivel: "NONE", categorias: [], motivo: "consulta vacía" };
  if (has(text, ["cual era mi nombre", "como me llamo", "que sabes de mi", "que recordas de mi", "que recuerdas de mi", "mis intereses", "mi perfil", "cuantos anos tengo"])) {
    return { nivel: "PROFILE", categorias: ["personal"], motivo: "pregunta explícita sobre el perfil básico" };
  }
  if (has(text, ["documento fiscal", "documentos fiscales", "comprobante", "factura", "recibo", "ticket", "cuit", "mis gastos", "monto del documento"])) {
    return { nivel: "RELEVANT", categorias: ["fiscal"], motivo: "pregunta sobre documento o dato fiscal" };
  }
  const categorias = [];
  const code = has(text, ["archivo", "codigo que", "modificamos", "cambiamos", "editamos", "tocamos", "bug", "funcion que hicimos", "clase que hicimos"]);
  const conversation = has(text, ["hablamos", "conversamos", "dijiste", "decidimos", "acordamos", "habia dicho", "habias dicho", "que paso con", "ayer", "la otra vez", "antes habiamos", "que habiamos"]);
  const project = has(text, ["proyecto", "avatar", "app", "pantalla", "decidimos", "acordamos", "que elegimos"]);
  const preferences = has(text, ["que prefiero", "mi preferencia", "mis preferencias", "que me gusta", "que no me gusta", "que me encanta", "mi estilo"]);
  const relational = has(text, ["nuestra relacion", "entre nosotros", "lo nuestro", "que sabes de nosotros", "nuestro vinculo"]);
  const personal = has(text, ["mi familia", "mi pareja", "mi madre", "mi mama", "mi padre", "mi papa", "mi hermano", "mi amiga", "mi amigo", "te conte de"]);
  if (code) categorias.push("code");
  if (conversation) categorias.push("conversation");
  if (project) categorias.push("project");
  if (preferences) categorias.push("preferences");
  if (relational) categorias.push("relational");
  if (personal) categorias.push("personal");
  if (!categorias.length) return { nivel: "NONE", categorias: [], motivo: "no se detectó una referencia clara a memoria previa" };
  const priority = ["project", "code", "preferences", "relational", "personal", "conversation"];
  categorias.sort((a, b) => priority.indexOf(a) - priority.indexOf(b));
  const deep = has(text, ["reconstruime", "reconstruye", "contexto completo", "repasemos todo", "desde el principio", "detallame todo", "resumen completo"]);
  return { nivel: deep ? "DEEP" : "RELEVANT", categorias: [...new Set(categorias)], motivo: deep ? "solicita reconstruir contexto previo amplio" : "referencia concreta a información previa" };
}

function construirConsulta(message = "", decision = {}) {
  const terminos = [...new Set(tokens(message))].slice(0, 8);
  return { texto: terminos.join(" "), terminos, categorias: decision.categorias || [], original: String(message) };
}
function decidirNecesidadMemoria(message = "", context = {}) {
  const decision = clasificarNecesidad(message);
  const consulta = construirConsulta(message, decision);
  return { necesaria: decision.nivel !== "NONE", nivel: decision.nivel, categoria: decision.categorias[0] || null, categorias: decision.categorias, consulta: consulta.texto, motivo: decision.motivo, estadoDisponible: Boolean(context.entradaProcesada || context.estado) };
}

function categoria(value = "") {
  const key = norm(value).replace(/\s+/g, "_");
  if (["proyecto", "project", "desarrollo"].includes(key)) return "project";
  if (["personas", "personal", "perfil", "intereses"].includes(key)) return "personal";
  if (["preferencia", "preferencias", "preferences"].includes(key)) return "preferences";
  if (["conversation", "conversacion", "historial"].includes(key)) return "conversation";
  if (["codigo", "code"].includes(key)) return "code";
  if (["fiscal", "documento_fiscal", "documentos_fiscales"].includes(key)) return "fiscal";
  if (["relacional", "relational", "relacion"].includes(key)) return "relational";
  return key;
}
function textOf(item = {}) {
  return [item.texto, item.valor, item.recuerdo?.valor, item.resumen, item.nombre, item.ruta, item.descripcion, item.numero, item.emisor, item.tipo, ...(Array.isArray(item.tags) ? item.tags : [])].filter(value => typeof value === "string").join(" ");
}
function score(item, query, categories) {
  const text = norm(textOf(item));
  const matches = (query.terminos || []).filter(term => term.length > 2 && text.includes(norm(term))).length;
  const categoryMatch = categories.includes(categoria(item.categoria || item.tipo || ""));
  if (!matches && !categoryMatch) return 0;
  const importance = Number(item.importancia) || 0;
  const timestamp = Number(new Date(item.timestamp || item.actualizadoEn || item.fecha || 0).getTime()) || 0;
  const recency = timestamp ? Math.min(5, timestamp / 1e13) : 0;
  return matches * 20 + (categoryMatch ? 8 : 0) + importance * 3 + recency;
}
function rankearMemorias(items = [], query = {}, categories = []) {
  const unique = new Map();
  for (const item of items) {
    const key = norm(textOf(item));
    const value = score(item, query, categories);
    if (!key || value <= 0) continue;
    const previous = unique.get(key);
    if (!previous || value > previous.score) unique.set(key, { item, score: value });
  }
  return [...unique.values()].sort((a, b) => b.score - a.score ||
    (Number(b.item.importancia) || 0) - (Number(a.item.importancia) || 0) ||
    (Number(b.item.timestamp) || 0) - (Number(a.item.timestamp) || 0) ||
    norm(textOf(a.item)).localeCompare(norm(textOf(b.item)))
  ).map(entry => entry.item);
}

function clean(value, limit = 240) {
  if (typeof value !== "string") return "";
  return value.replace(/(?:password|contrase(?:n|ñ)a|token|api[_ -]?key|secret|credencial|bearer)\s*[:=]\s*\S+/gi, "[dato omitido]").replace(/\s+/g, " ").trim().slice(0, limit);
}
function obtenerPerfilBase(userId, fuentes, includeSummary = false) {
  if (!userId || typeof fuentes?.datosUsuario?.obtener !== "function") return null;
  const user = fuentes.datosUsuario.obtener(userId) || {};
  const nombre = clean(user.identidad?.nombre || user.identidad?.apodo, 80);
  const profile = {
    nombre: nombre || null,
    intereses: Array.isArray(user.intereses) ? [...new Set(user.intereses.map(value => clean(value, 60)).filter(Boolean))].slice(0, 5) : [],
    preferencias: []
  };
  if (includeSummary) {
    const resumen = clean(user.resumen, 240);
    if (resumen) profile.resumen = resumen;
  }
  return profile;
}
function obtenerEstadoConversacion({ entradaProcesada = {}, estado = {} } = {}) {
  const source = Object.keys(estado || {}).length ? estado : entradaProcesada;
  const safe = value => clean(typeof value === "string" ? value : "", 80);
  return { intencion: safe(source.intencion || source.calibracion?.tipoInteraccion), emocion: safe(source.emocion || source.estadoEmocional), energia: safe(source.calibracion?.energia || source.energia) };
}
function inferMemoryCategory(rawCategory, text) {
  const explicit = categoria(rawCategory || "");
  if (["project", "personal", "relational", "preferences"].includes(explicit)) return explicit;
  const body = norm(text);
  if (has(body, ["avatar", "proyecto", "mi app", "pantalla"])) return "project";
  if (has(body, ["prefiero", "me gusta", "no me gusta", "preferencia"])) return "preferences";
  if (has(body, ["nosotros", "relacion", "vinculo", "lo nuestro"])) return "relational";
  return "personal";
}
function normalizedMemory(item = {}) {
  const text = clean(String(item.texto || item.valor || item.recuerdo?.valor || item.mensaje || ""), 500);
  return {
    texto: text,
    categoria: inferMemoryCategory(item.categoria || item.tipo, text),
    importancia: Number(item.importancia) || 0,
    timestamp: item.timestamp || item.recuerdo?.timestamp || item.actualizadoEn || 0
  };
}
function normalizedHistory(item = {}) {
  return { tipo: item.tipo === "joi" || item.rol === "joi" ? "assistant" : "user", mensaje: clean(item.mensaje || item.texto || "", 260), timestamp: item.timestamp || 0 };
}
async function cargarFuentes() {
  const [persistent, important, profile, history, code, fiscal] = await Promise.all([
    import("../memoria/memoriaPersistente.js"), import("../memoria/recuerdosImportantes.js"),
    import("../memoria/datosUsuario.js"), import("../memoria/historialConversacion.js"),
    import("../memoria/codigoMemoria.js"), import("../memoria/documentosFiscales.js")
  ]);
  return { memoriaPersistente: persistent.default, recuerdosImportantes: important.default, datosUsuario: profile.default, historialConversacion: history.default, codigoMemoria: code.default, documentosFiscales: fiscal.default };
}
function matchesQuery(item, query) {
  const text = norm(textOf(item));
  return (query.terminos || []).some(term => term.length > 2 && text.includes(norm(term)));
}

function consultarMemoria(userId, decision, query, sources) {
  const result = { perfil: null, elementos: [], historial: [], codigo: [], fiscal: null, encontrados: 0 };
  if (!userId || !decision.necesaria) return result;
  if (decision.nivel === "PROFILE") {
    const includeSummary = has(norm(query.original || query.texto), ["sabes de mi", "recordas de mi", "recuerdas de mi", "perfil"]);
    result.perfil = obtenerPerfilBase(userId, sources, includeSummary);
    result.encontrados = result.perfil ? 1 : 0;
    return result;
  }
  const categories = decision.categorias || [];
  const terms = query.terminos || [];
  const generalCategories = categories.filter(value => ["personal", "relational", "project", "preferences"].includes(value));

  if (generalCategories.length) {
    const candidates = [];
    if (typeof sources.memoriaPersistente?.relevantes === "function") {
      for (const term of terms.slice(0, 4)) candidates.push(...(sources.memoriaPersistente.relevantes(userId, term, 20) || []));
    }
    if (typeof sources.recuerdosImportantes?.obtenerTodos === "function") {
      const memories = sources.recuerdosImportantes.obtenerTodos(userId) || {};
      candidates.push(...(Array.isArray(memories) ? memories : Object.values(memories)));
    }
    const selected = candidates.map(normalizedMemory)
      .filter(item => generalCategories.includes(item.categoria) && matchesQuery(item, query));
    result.encontrados += selected.length;
    result.elementos = rankearMemorias(selected, query, generalCategories).slice(0, MAX_MEMORIES)
      .map(item => ({ texto: clean(item.texto, 360), categoria: item.categoria, importancia: item.importancia, timestamp: item.timestamp }));
  }

  if (categories.includes("conversation") || decision.nivel === "DEEP") {
    const history = typeof sources.historialConversacion?.obtenerHistorial === "function" ? sources.historialConversacion.obtenerHistorial(userId, 50) || [] : [];
    const selected = history.map(normalizedHistory).filter(item => item.mensaje && matchesQuery({ texto: item.mensaje }, query));
    result.encontrados += selected.length;
    result.historial = rankearMemorias(selected.map(item => ({ texto: item.mensaje, categoria: "conversation", timestamp: item.timestamp, tipo: item.tipo })), query, ["conversation"])
      .slice(0, MAX_HISTORY).map(item => ({ tipo: item.tipo, mensaje: clean(item.texto, 240), timestamp: item.timestamp }));
  }

  if (categories.includes("code")) {
    const candidates = [];
    if (typeof sources.codigoMemoria?.buscarArchivos === "function") {
      const termsToSearch = [...new Set([...terms, "archivo", "codigo"])].slice(0, 5);
      for (const term of termsToSearch) candidates.push(...(sources.codigoMemoria.buscarArchivos(userId, term) || []));
    }
    const selected = candidates.map(item => ({
      nombre: item.nombre || item.fileName || "", ruta: item.ruta || item.path || "",
      lenguaje: item.lenguaje || item.language || "", resumen: item.resumen || item.summary || "",
      actualizadoEn: item.actualizadoEn || item.updatedAt || ""
    })).filter(item => matchesQuery(item, query) || has(norm(query.texto), ["archivo", "modificamos", "cambiamos"]));
    result.encontrados += selected.length;
    result.codigo = rankearMemorias(selected.map(item => ({ ...item, texto: [item.nombre, item.ruta, item.lenguaje, item.resumen].join(" "), categoria: "code", timestamp: item.actualizadoEn })), query, ["code"])
      .slice(0, MAX_CODE).map(item => ({ nombre: clean(item.nombre, 100), ruta: clean(item.ruta, 160), lenguaje: clean(item.lenguaje, 40), resumen: clean(item.resumen, 180), actualizadoEn: item.actualizadoEn || null }));
  }

  if (categories.includes("fiscal")) {
    const docs = typeof sources.documentosFiscales?.listarDocumentos === "function" ? sources.documentosFiscales.listarDocumentos(userId) || [] : [];
    const safeDocs = docs.map(item => ({
      tipo: item.tipo || "", numero: item.numero || "", emisor: item.emisor || "",
      monto: Number(item.monto) || 0, moneda: item.moneda || "", fecha: item.fecha || "",
      vencimiento: item.vencimiento || null, estado: item.estado || "", descripcion: item.descripcion || "",
      tags: Array.isArray(item.tags) ? item.tags.slice(0, 5).map(String) : [],
      actualizadoEn: item.actualizadoEn || item.creadoEn || ""
    }));
    let ranked = rankearMemorias(safeDocs.map(item => ({ ...item, texto: [item.tipo, item.numero, item.emisor, item.descripcion, ...item.tags].join(" "), categoria: "fiscal", timestamp: item.actualizadoEn || item.fecha })), query, []);
    if (!ranked.length && safeDocs.length === 1) ranked = safeDocs;
    result.encontrados += safeDocs.length;
    const doc = ranked[0];
    result.fiscal = doc ? { tipo: clean(doc.tipo, 60), numero: clean(doc.numero, 80), emisor: clean(doc.emisor, 100), monto: doc.monto, moneda: clean(doc.moneda, 12), fecha: clean(doc.fecha, 24), vencimiento: clean(doc.vencimiento || "", 24) || null, estado: clean(doc.estado, 40), descripcion: clean(doc.descripcion, 180), tags: doc.tags.slice(0, 5).map(tag => clean(tag, 40)) } : null;
  }
  return result;
}

function tokenEstimate(context) {
  const body = { perfil: context.perfil, memoria: context.memoria, historial: context.historial, especializados: context.especializados, estado: context.estado };
  return Math.ceil(JSON.stringify(body).length / 4);
}
function aplicarPresupuesto(context, budget = DEFAULT_BUDGET) {
  const limit = Math.max(64, Math.floor(Number(budget) || DEFAULT_BUDGET));
  context.memoria.elementos = context.memoria.elementos.slice(0, MAX_MEMORIES);
  context.historial.elementos = context.historial.elementos.slice(-MAX_HISTORY);
  context.especializados.codigo = context.especializados.codigo.slice(0, MAX_CODE);
  while (tokenEstimate(context) > limit) {
    if (context.historial.elementos.length) context.historial.elementos.pop();
    else if (context.especializados.codigo.length) context.especializados.codigo.pop();
    else if (context.memoria.elementos.length) context.memoria.elementos.pop();
    else if (context.especializados.fiscal) context.especializados.fiscal = null;
    else if (context.perfil?.resumen) delete context.perfil.resumen;
    else if (context.perfil?.intereses?.length) context.perfil.intereses.pop();
    else if (context.perfil?.nombre) context.perfil.nombre = null;
    else break;
  }
  context.metadatos.presupuesto = limit;
  context.metadatos.tokensEstimados = tokenEstimate(context);
  context.memoria.usada = Boolean(context.perfil || context.memoria.elementos.length || context.historial.elementos.length || context.especializados.codigo.length || context.especializados.fiscal);
  context.metadatos.resultadosSeleccionados = context.memoria.elementos.length + context.historial.elementos.length + context.especializados.codigo.length + (context.especializados.fiscal ? 1 : 0);
  return context;
}
function emptyContext({ message = "", entradaProcesada = null, estado = null, budget } = {}) {
  const decision = decidirNecesidadMemoria(message, { entradaProcesada, estado });
  const limit = Number(budget || process.env.CONTEXTO_LLM_PRESUPUESTO_TOKENS || DEFAULT_BUDGET);
  return aplicarPresupuesto({
    perfil: null,
    memoria: { usada: false, nivel: decision.nivel, categoria: decision.categoria, categorias: decision.categorias, consulta: decision.consulta, elementos: [] },
    historial: { elementos: [] }, especializados: { codigo: [], fiscal: null },
    estado: obtenerEstadoConversacion({ entradaProcesada: entradaProcesada || {}, estado: estado || {} }),
    metadatos: { motivoMemoria: decision.motivo, resultadosEncontrados: 0, resultadosSeleccionados: 0, presupuesto: limit, tokensEstimados: 0 }
  }, limit);
}
async function construirContextoLLM({ userId = null, mensajeUsuario = "", entradaProcesada = null, estado = null, presupuestoTokens = null, fuentes = null } = {}) {
  const decision = decidirNecesidadMemoria(mensajeUsuario, { entradaProcesada, estado });
  const query = construirConsulta(mensajeUsuario, decision);
  if (!decision.necesaria || !userId) return emptyContext({ message: mensajeUsuario, entradaProcesada, estado, budget: presupuestoTokens });
  const sources = fuentes || await cargarFuentes();
  const recovered = consultarMemoria(userId, decision, query, sources);
  const limit = Number(presupuestoTokens || process.env.CONTEXTO_LLM_PRESUPUESTO_TOKENS || DEFAULT_BUDGET);
  return aplicarPresupuesto({
    perfil: decision.nivel === "PROFILE" ? recovered.perfil : null,
    memoria: { usada: Boolean(recovered.perfil || recovered.elementos.length || recovered.historial.length || recovered.codigo.length || recovered.fiscal), nivel: decision.nivel, categoria: decision.categoria, categorias: decision.categorias, consulta: query.texto, elementos: recovered.elementos },
    historial: { elementos: recovered.historial },
    especializados: { codigo: recovered.codigo, fiscal: recovered.fiscal },
    estado: obtenerEstadoConversacion({ entradaProcesada: entradaProcesada || {}, estado: estado || {} }),
    metadatos: { motivoMemoria: decision.motivo, resultadosEncontrados: recovered.encontrados, resultadosSeleccionados: 0, presupuesto: limit, tokensEstimados: 0 }
  }, limit);
}

export { aplicarPresupuesto, construirConsulta, construirContextoLLM, clasificarNecesidad, consultarMemoria, decidirNecesidadMemoria, obtenerEstadoConversacion, obtenerPerfilBase, rankearMemorias };
export default { aplicarPresupuesto, construirConsulta, construirContextoLLM, clasificarNecesidad, consultarMemoria, decidirNecesidadMemoria, obtenerEstadoConversacion, obtenerPerfilBase, rankearMemorias };