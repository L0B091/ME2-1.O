// gestorFiscal.js — Gestor de material para monotributista (Premium). Lógica PURA sobre un estado JSON.
// El estado vive en el teléfono (memoria local, incluida en el respaldo cifrado); el backend solo lo transforma
// y entrega HECHOS al LLM. Sin teléfono (simulación / clientes sin memoria local) se usa el almacén JSON del backend.
const MESES = { enero: 1, febrero: 2, marzo: 3, abril: 4, mayo: 5, junio: 6, julio: 7, agosto: 8, septiembre: 9, setiembre: 9, octubre: 10, noviembre: 11, diciembre: 12 };
const TZ = "America/Argentina/Buenos_Aires";
const CONTEXTO = /\b(factur\w*|monotribut\w*|vencimient\w*|comprobante\w*|fiscal\w*|iibb|ingresos brutos|afip|arca|recibo\w*|categor[ií]a [a-k]\b)/i;

export function estadoBase() {
  return { version: 1, categoria: null, comprobantes: [], vencimientos: [], notas: [], actualizado: null };
}

function normalizarEstado(e) {
  const b = estadoBase();
  if (!e || typeof e !== "object") return b;
  return { ...b, ...e, comprobantes: Array.isArray(e.comprobantes) ? e.comprobantes : [], vencimientos: Array.isArray(e.vencimientos) ? e.vencimientos : [], notas: Array.isArray(e.notas) ? e.notas : [] };
}

function hoyLocal(ahora) {
  const [y, m, d] = new Date(ahora).toLocaleDateString("en-CA", { timeZone: TZ }).split("-").map(Number);
  return { y, m, d };
}
const iso = (y, m, d) => `${y}-${String(m).padStart(2, "0")}-${String(d).padStart(2, "0")}`;
export const fechaCorta = (s) => (s ? s.split("-").reverse().join("/") : "sin fecha");
const pesos = (n) => `$${Number(n).toLocaleString("es-AR", { maximumFractionDigits: 2 })}`;

export function parsearMonto(texto) {
  const t = String(texto).toLowerCase();
  const mil = t.match(/(\d+(?:[.,]\d+)?)\s*(mil|k)\b/);
  if (mil) return Math.round(Number(mil[1].replace(",", ".")) * 1000);
  const m = t.match(/\$\s*(\d{1,3}(?:\.\d{3})+|\d+)(?:,(\d{1,2}))?|(\d{1,3}(?:\.\d{3})+|\d{3,})(?:,(\d{1,2}))?(?!\s*(?:\/|hs|:))/);
  if (!m) return null;
  const ent = (m[1] || m[3]).replace(/\./g, ""), dec = m[2] || m[4];
  return Number(dec ? `${ent}.${dec}` : ent);
}

export function parsearFecha(texto, ahora = Date.now()) {
  const t = String(texto).toLowerCase();
  const h = hoyLocal(ahora);
  let m = t.match(/\b(\d{1,2})\/(\d{1,2})(?:\/(\d{2,4}))?\b/);
  if (m) { let y = m[3] ? Number(m[3]) : h.y; if (y < 100) y += 2000; if (!m[3] && Number(m[2]) < h.m) y++; return iso(y, Number(m[2]), Number(m[1])); }
  m = t.match(/\b(\d{1,2}) de (enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|setiembre|octubre|noviembre|diciembre)\b/);
  if (m) { const mes = MESES[m[2]]; return iso(mes < h.m ? h.y + 1 : h.y, mes, Number(m[1])); }
  if (/\bhoy\b/.test(t)) return iso(h.y, h.m, h.d);
  if (/\bma[ñn]ana\b/.test(t)) { const d = new Date(Date.UTC(h.y, h.m - 1, h.d + 1)); return iso(d.getUTCFullYear(), d.getUTCMonth() + 1, d.getUTCDate()); }
  m = t.match(/\bel (?:d[ií]a )?(\d{1,2})\b/);
  if (m) { const d = Number(m[1]); const sig = d < h.d; const mm = sig ? (h.m % 12) + 1 : h.m; return iso(sig && h.m === 12 ? h.y + 1 : h.y, mm, d); }
  return null;
}

function limpiar(s) { return String(s || "").replace(/\s+/g, " ").replace(/^[\s,:;.-]+|[\s,:;.]+$/g, "").trim(); }

/** Detecta la operación fiscal del mensaje (o null si el mensaje no es del gestor). */
export function detectar(mensaje = "", ahora = Date.now()) {
  const t = String(mensaje).trim();
  if (!CONTEXTO.test(t) && !/\bnota fiscal\b/i.test(t)) return null;
  let m;
  if ((m = t.match(/categor[ií]a\s+(?:de(?:l)? monotributo\s+)?(?:es\s+(?:la\s+)?)?([a-k])\b/i)) && /\b(soy|es|mi|estoy|qued[eé]|pas[eé])\b/i.test(t)) return { op: "categoria", categoria: m[1].toUpperCase() };
  if ((m = t.match(/\bnota(?: fiscal)?\s*:\s*(.+)$/i))) return { op: "nota", texto: limpiar(m[1]) };
  if ((m = t.match(/(?:pagu[eé]|ya pagu[eé]|marc[aá](?:lo|la)? (?:como )?pagad[oa])\s+(?:el |la |los )?(?:vencimiento (?:de(?:l)? )?)?(.+)/i))) return { op: "pagado", concepto: limpiar(m[1].replace(/\b(del?|el|la)\b\s*$/i, "")) };
  if (/\b(qu[eé] (?:me )?vence|vencimientos|resumen fiscal|cu[aá]nto factur[eé]|mis facturas|mis comprobantes|estado fiscal|c[oó]mo vengo)(?![\wáéíóú])/i.test(t) && !/\b(registr|guard|anot|carg|agreg)\w*/i.test(t)) return { op: "consulta" };
  if (/\bvenc(?:e|imiento)/i.test(t)) {
    const fecha = parsearFecha(t, ahora);
    const concepto = limpiar((t.match(/venc(?:e|imiento)\w*\s+(?:de(?:l)?\s+|el\s+|la\s+)?(.+?)(?:\s+(?:el|para el|hasta el)\s+(?:d[ií]a\s+)?\d|\s+\d{1,2}\/|$)/i) || [])[1] || "vencimiento")
      .replace(/\s+(es|son|ser[aá])$/i, "").replace(/\b(el|la|del?)\s*$/i, "");
    return { op: "vencimiento", concepto: concepto || "vencimiento", fecha, monto: parsearMonto(t.replace(/\d{1,2}\/\d{1,2}(\/\d{2,4})?/g, "")) };
  }
  if (/\bfactur|comprobante|recibo/i.test(t) && /\b(registr|guard|anot|carg|agreg|emit|hice|me factur|le factur|recib)\w*/i.test(t)) {
    const recibida = /\b(recibida|me facturaron|compra|gasto|proveedor|pagu[eé] una)\b/i.test(t);
    const contraparte = limpiar((t.match(/\b(?:a|para|de|del|al)\s+((?:[A-ZÁÉÍÓÚÑ][\wáéíóúñ]+)(?:\s+[A-ZÁÉÍÓÚÑ][\wáéíóúñ]+)*)/) || [])[1]);
    const concepto = limpiar((t.match(/\bpor\s+(.+?)(?:\s+(?:a|para|el|de)\s+[A-ZÁÉÍÓÚÑ]|\s+el\s+\d|$)/i) || [])[1]);
    return { op: "comprobante", tipo: recibida ? "recibida" : "emitida", monto: parsearMonto(t.replace(/\d{1,2}\/\d{1,2}(\/\d{2,4})?/g, "")), fecha: parsearFecha(t, ahora), contraparte: contraparte || null, concepto: concepto || null };
  }
  return { op: "consulta", implicita: true };
}

/** ¿El mensaje es un pedido concreto al gestor fiscal? (para ofrecer Premium si el usuario es Free). */
export function esPedido(mensaje = "") {
  const op = detectar(mensaje);
  return Boolean(op && !op.implicita);
}

let seq = 0;
const nuevoId = (p, ahora) => `${p}-${ahora.toString(36)}${(seq++ % 1296).toString(36)}`;

/** Aplica la operación y devuelve { estado, resultado } (estado nuevo, nunca muta el original). */
export function aplicar(estadoActual, op, ahora = Date.now()) {
  const e = JSON.parse(JSON.stringify(normalizarEstado(estadoActual)));
  const h = hoyLocal(ahora);
  let resultado = { op: op.op, ok: true };
  if (op.op === "categoria") e.categoria = op.categoria;
  else if (op.op === "nota") { const n = { id: nuevoId("n", ahora), texto: op.texto, fecha: iso(h.y, h.m, h.d) }; e.notas.push(n); resultado.nota = n; }
  else if (op.op === "vencimiento") {
    if (!op.fecha) resultado = { op: op.op, ok: false, falta: "fecha" };
    else { const v = { id: nuevoId("v", ahora), concepto: op.concepto, fecha: op.fecha, monto: op.monto ?? null, pagado: false }; e.vencimientos.push(v); resultado.vencimiento = v; }
  } else if (op.op === "comprobante") {
    if (!Number.isFinite(op.monto)) resultado = { op: op.op, ok: false, falta: "monto" };
    else { const c = { id: nuevoId("c", ahora), tipo: op.tipo, monto: op.monto, fecha: op.fecha || iso(h.y, h.m, h.d), contraparte: op.contraparte, concepto: op.concepto }; e.comprobantes.push(c); resultado.comprobante = c; }
  } else if (op.op === "pagado") {
    const q = op.concepto.toLowerCase();
    const v = e.vencimientos.filter(x => !x.pagado).find(x => x.concepto.toLowerCase().includes(q) || q.includes(x.concepto.toLowerCase()));
    if (v) { v.pagado = true; v.pagadoEn = iso(h.y, h.m, h.d); resultado.vencimiento = v; } else resultado = { op: op.op, ok: false, falta: "vencimiento_inexistente", concepto: op.concepto };
  }
  if (op.op !== "consulta") e.actualizado = ahora;
  return { estado: e, resultado };
}

export function resumen(estado, ahora = Date.now()) {
  const e = normalizarEstado(estado);
  const h = hoyLocal(ahora);
  const mes = `${h.y}-${String(h.m).padStart(2, "0")}`;
  const hace12 = iso(h.m === 12 ? h.y : h.y - 1, (h.m % 12) + 1, 1);
  const emitidas = e.comprobantes.filter(c => c.tipo === "emitida");
  const delMes = emitidas.filter(c => c.fecha?.startsWith(mes));
  const ult12 = emitidas.filter(c => c.fecha >= hace12);
  const hoy = iso(h.y, h.m, h.d);
  const pendientes = e.vencimientos.filter(v => !v.pagado).sort((a, b) => a.fecha.localeCompare(b.fecha));
  return {
    categoria: e.categoria,
    facturadoMes: delMes.reduce((s, c) => s + c.monto, 0), facturasMes: delMes.length,
    facturado12m: ult12.reduce((s, c) => s + c.monto, 0),
    gastosMes: e.comprobantes.filter(c => c.tipo === "recibida" && c.fecha?.startsWith(mes)).reduce((s, c) => s + c.monto, 0),
    vencidos: pendientes.filter(v => v.fecha < hoy), proximos: pendientes.filter(v => v.fecha >= hoy).slice(0, 5),
    notas: e.notas.slice(-3), totalComprobantes: e.comprobantes.length
  };
}

/** HECHOS para el LLM (sin texto armado para el usuario). */
export function lineas(estado, resultado, ahora = Date.now()) {
  const l = [];
  const r = resultado || {};
  const pre = "Gestor fiscal (Premium, monotributo; datos guardados en el teléfono del usuario)";
  if (r.op === "comprobante" && r.ok) l.push(`${pre}: REGISTRADA factura ${r.comprobante.tipo} por ${pesos(r.comprobante.monto)} del ${fechaCorta(r.comprobante.fecha)}${r.comprobante.contraparte ? `, ${r.comprobante.tipo === "emitida" ? "a" : "de"} ${r.comprobante.contraparte}` : ""}${r.comprobante.concepto ? `, concepto: ${r.comprobante.concepto}` : ""}.`);
  else if (r.op === "vencimiento" && r.ok) l.push(`${pre}: AGENDADO vencimiento "${r.vencimiento.concepto}" para el ${fechaCorta(r.vencimiento.fecha)}${r.vencimiento.monto ? ` por ${pesos(r.vencimiento.monto)}` : ""}.`);
  else if (r.op === "categoria") l.push(`${pre}: categoría de monotributo guardada: ${estado.categoria}.`);
  else if (r.op === "nota" && r.ok) l.push(`${pre}: nota guardada: "${r.nota.texto}".`);
  else if (r.op === "pagado" && r.ok) l.push(`${pre}: vencimiento "${r.vencimiento.concepto}" del ${fechaCorta(r.vencimiento.fecha)} marcado como PAGADO.`);
  else if (r.ok === false) l.push(`${pre}: no se guardó nada; falta dato: ${r.falta === "vencimiento_inexistente" ? `no hay un vencimiento pendiente que coincida con "${r.concepto}"` : r.falta}. Pedírselo al usuario.`);
  const s = resumen(estado, ahora);
  l.push(`Resumen fiscal actual: categoría ${s.categoria || "no informada"}; facturado este mes ${pesos(s.facturadoMes)} (${s.facturasMes} facturas emitidas); últimos 12 meses ${pesos(s.facturado12m)}; gastos/comprobantes recibidos este mes ${pesos(s.gastosMes)}; ${s.totalComprobantes} comprobantes en total.`);
  if (s.vencidos.length) l.push(`Vencimientos VENCIDOS sin pagar: ${s.vencidos.map(v => `${fechaCorta(v.fecha)} ${v.concepto}`).join("; ")}.`);
  l.push(s.proximos.length ? `Próximos vencimientos: ${s.proximos.map(v => `${fechaCorta(v.fecha)} ${v.concepto}${v.monto ? ` (${pesos(v.monto)})` : ""}`).join("; ")}.` : "Próximos vencimientos: ninguno registrado.");
  if (s.notas.length && r.op === "consulta") l.push(`Últimas notas fiscales: ${s.notas.map(n => `"${n.texto}"`).join("; ")}.`);
  l.push("Los topes de facturación por categoría no están cargados en la app: no inventar cifras oficiales.");
  return l;
}

export default { estadoBase, detectar, esPedido, aplicar, resumen, lineas, parsearMonto, parsearFecha };
