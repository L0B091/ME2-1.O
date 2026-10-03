// gestorProyectos.js — Memoria dedicada para proyectos de programación (Premium): mini-repo por proyecto
// (archivos de trabajo + versiones/snapshots, diff y restauración). Lógica PURA sobre un estado JSON que vive en
// el teléfono (memoria local, incluida en el respaldo cifrado). El backend transforma y entrega HECHOS al LLM.
const MAX_ARCHIVO = 200_000;
const MAX_LINEAS_CTX = 3000;
const NOMBRE = "([\\w.\\-]+)";

export function estadoBase() { return { version: 1, activo: null, proyectos: {} }; }

function normalizar(e) {
  const b = estadoBase();
  if (!e || typeof e !== "object") return b;
  return { ...b, ...e, proyectos: e.proyectos && typeof e.proyectos === "object" ? e.proyectos : {} };
}

function bloqueCodigo(t) {
  const m = String(t).match(/```[\w+-]*\n?([\s\S]*?)```/);
  return m ? m[1].replace(/\n$/, "") : null;
}

/** Detecta la operación del mensaje (o null). */
export function detectar(mensaje = "") {
  const t = String(mensaje);
  const sinCodigo = t.replace(/```[\s\S]*?```/g, " ");
  let m;
  if ((m = sinCodigo.match(new RegExp(`\\b(?:cre[aá]|nuevo|arm[aá]|abr[ií])\\w*\\s+(?:un\\s+|el\\s+)?proyecto(?:\\s+de\\s+programaci[oó]n)?\\s+(?:llamado\\s+|que se llame\\s+)?["“']?${NOMBRE}`, "i")))) return { op: "crear", proyecto: m[1] };
  if ((m = sinCodigo.match(/\bguard[aá]\w*\s+(?:en\s+(?:el\s+proyecto\s+)?([\w.-]+)\s+)?(?:el\s+|este\s+)?archivo\s+([\w./-]+)(?:\s+en\s+(?:el\s+proyecto\s+)?([\w.-]+))?/i))) {
    const contenido = bloqueCodigo(t);
    return { op: "archivo", proyecto: m[1] || m[3] || null, ruta: m[2], contenido };
  }
  if ((m = sinCodigo.match(/\b(?:snapshot|commit|versionar|guard[aá]\w*\s+(?:una\s+)?versi[oó]n)\b(?:\s+(?:de(?:l)?\s+)?(?:proyecto\s+)?([\w.-]+))?(?:\s*[:,-]\s*(.+))?/i))) {
    const p = m[1] && !/^(con|que|del?)$/i.test(m[1]) ? m[1] : null;
    return { op: "snapshot", proyecto: p, mensaje: (m[2] || "").trim() || null };
  }
  if ((m = sinCodigo.match(/\b(?:restaur[aá]\w*|volv[eé]\w*|revert\w*)(?![\wáéíóú]).*?\bversi[oó]n\s*v?(\d+)/i))) return { op: "restaurar", proyecto: sinCodigo.match(/proyecto\s+([\w.-]+)/i)?.[1] || null, version: Number(m[1]) };
  if (/\b(diff|diferencias|qu[eé] cambi[oó]|cambios)/i.test(sinCodigo) && /\b(proyecto|versi[oó]n|v\d+|diff)/i.test(sinCodigo)) {
    const vs = [...sinCodigo.matchAll(/\bv(?:ersi[oó]n)?\s*(\d+)\b/gi)].map(x => Number(x[1]));
    m = sinCodigo.match(/proyecto\s+([\w.-]+)/i);
    return { op: "diff", proyecto: m?.[1] || null, desde: vs[0] ?? null, hasta: vs[1] ?? null };
  }
  if ((m = sinCodigo.match(/\b(?:mostr[aá]\w*|abr[ií]\w*|ver|leer|le[eé]\w*)\s+(?:el\s+)?archivo\s+([\w./-]+)(?:\s+(?:de(?:l)?|en)\s+(?:proyecto\s+)?([\w.-]+))?/i))) return { op: "ver", ruta: m[1], proyecto: m[2] || null };
  if (/\b(mis proyectos|list[aá]\w*\s+(?:los\s+|mis\s+)?proyectos|qu[eé] proyectos)\b/i.test(sinCodigo)) return { op: "listar" };
  if ((m = sinCodigo.match(/\b(?:historial|estado|versiones)\s+del?\s+proyecto\s+([\w.-]+)/i))) return { op: "estado", proyecto: m[1] };
  return null;
}

/** Diff de líneas (LCS) en formato unificado simple. */
export function diffLineas(a = "", b = "") {
  const x = a === "" ? [] : a.split("\n"), y = b === "" ? [] : b.split("\n");
  const n = x.length, m = y.length;
  const L = Array.from({ length: n + 1 }, () => new Int32Array(m + 1));
  for (let i = n - 1; i >= 0; i--) for (let j = m - 1; j >= 0; j--) L[i][j] = x[i] === y[j] ? L[i + 1][j + 1] + 1 : Math.max(L[i + 1][j], L[i][j + 1]);
  const out = []; let i = 0, j = 0;
  while (i < n && j < m) {
    if (x[i] === y[j]) { i++; j++; } else if (L[i + 1][j] >= L[i][j + 1]) out.push(`- ${x[i++]}`); else out.push(`+ ${y[j++]}`);
  }
  while (i < n) out.push(`- ${x[i++]}`);
  while (j < m) out.push(`+ ${y[j++]}`);
  return out;
}

function diffArchivos(a = {}, b = {}) {
  const rutas = [...new Set([...Object.keys(a), ...Object.keys(b)])].sort();
  const cambios = [];
  for (const r of rutas) {
    if (a[r] === b[r]) continue;
    const tipo = a[r] == null ? "nuevo" : b[r] == null ? "borrado" : "modificado";
    cambios.push({ ruta: r, tipo, lineas: diffLineas(a[r] ?? "", b[r] ?? "") });
  }
  return cambios;
}

function proyectoDe(e, nombre) {
  const n = nombre || e.activo;
  if (!n) return null;
  const clave = Object.keys(e.proyectos).find(k => k.toLowerCase() === String(n).toLowerCase());
  return clave ? { nombre: clave, p: e.proyectos[clave] } : null;
}

export function aplicar(estadoActual, op, ahora = Date.now()) {
  const e = JSON.parse(JSON.stringify(normalizar(estadoActual)));
  const r = { op: op.op, ok: true };
  if (op.op === "listar") { r.proyectos = Object.entries(e.proyectos).map(([k, p]) => ({ nombre: k, archivos: Object.keys(p.archivos).length, versiones: p.versiones.length })); return { estado: e, resultado: r }; }
  if (op.op === "crear") {
    if (proyectoDe(e, op.proyecto)) { e.activo = proyectoDe(e, op.proyecto).nombre; return { estado: e, resultado: { ...r, ok: false, motivo: "ya_existe", proyecto: e.activo } }; }
    e.proyectos[op.proyecto] = { creado: ahora, archivos: {}, versiones: [] };
    e.activo = op.proyecto;
    return { estado: e, resultado: { ...r, proyecto: op.proyecto } };
  }
  const pr = proyectoDe(e, op.proyecto);
  if (!pr) return { estado: e, resultado: { ...r, ok: false, motivo: op.proyecto ? "proyecto_inexistente" : "sin_proyecto_activo", proyecto: op.proyecto || null } };
  const { nombre, p } = pr;
  e.activo = nombre; r.proyecto = nombre;
  if (op.op === "archivo") {
    if (op.contenido == null) return { estado: e, resultado: { ...r, ok: false, motivo: "falta_contenido", ruta: op.ruta } };
    if (op.contenido.length > MAX_ARCHIVO) return { estado: e, resultado: { ...r, ok: false, motivo: "archivo_muy_grande", ruta: op.ruta } };
    const previo = p.archivos[op.ruta];
    p.archivos[op.ruta] = op.contenido;
    p.modificado = ahora;
    Object.assign(r, { ruta: op.ruta, nuevo: previo == null, lineas: op.contenido.split("\n").length, cambios: previo == null ? null : diffLineas(previo, op.contenido) });
  } else if (op.op === "snapshot") {
    const ultima = p.versiones.at(-1);
    const cambios = diffArchivos(ultima?.archivos || {}, p.archivos);
    if (ultima && !cambios.length) return { estado: e, resultado: { ...r, ok: false, motivo: "sin_cambios", version: ultima.v } };
    const v = { v: (ultima?.v || 0) + 1, mensaje: op.mensaje || `snapshot ${new Date(ahora).toISOString().slice(0, 16)}`, fecha: ahora, archivos: { ...p.archivos } };
    p.versiones.push(v);
    Object.assign(r, { version: v.v, mensaje: v.mensaje, cambios: cambios.map(c => ({ ruta: c.ruta, tipo: c.tipo, mas: c.lineas.filter(l => l[0] === "+").length, menos: c.lineas.filter(l => l[0] === "-").length })) });
  } else if (op.op === "diff") {
    const ver = (n) => p.versiones.find(x => x.v === n);
    const desde = op.desde != null ? ver(op.desde) : p.versiones.at(-1);
    const hasta = op.hasta != null ? ver(op.hasta) : null;
    if ((op.desde != null && !desde) || (op.hasta != null && !hasta)) return { estado: e, resultado: { ...r, ok: false, motivo: "version_inexistente" } };
    Object.assign(r, { desde: desde?.v ?? 0, hasta: hasta?.v ?? "trabajo", cambios: diffArchivos(desde?.archivos || {}, hasta ? hasta.archivos : p.archivos) });
  } else if (op.op === "restaurar") {
    const v = p.versiones.find(x => x.v === op.version);
    if (!v) return { estado: e, resultado: { ...r, ok: false, motivo: "version_inexistente", version: op.version } };
    const cambios = diffArchivos(p.archivos, v.archivos);
    p.archivos = { ...v.archivos }; p.modificado = ahora;
    Object.assign(r, { version: v.v, cambios: cambios.map(c => ({ ruta: c.ruta, tipo: c.tipo })) });
  } else if (op.op === "ver") {
    const contenido = p.archivos[op.ruta];
    if (contenido == null) return { estado: e, resultado: { ...r, ok: false, motivo: "archivo_inexistente", ruta: op.ruta, archivos: Object.keys(p.archivos) } };
    Object.assign(r, { ruta: op.ruta, contenido });
  } else if (op.op === "estado") {
    Object.assign(r, { archivos: Object.keys(p.archivos), versiones: p.versiones.map(v => ({ v: v.v, mensaje: v.mensaje, fecha: v.fecha })), sinGuardar: diffArchivos(p.versiones.at(-1)?.archivos || {}, p.archivos).map(c => c.ruta) });
  }
  return { estado: e, resultado: r };
}

const recortar = (s) => (s.length > MAX_LINEAS_CTX ? `${s.slice(0, MAX_LINEAS_CTX)}\n…(recortado)` : s);

export function lineas(estado, r) {
  const pre = "Proyectos de programación (Premium; mini-repo guardado en el teléfono del usuario)";
  const fecha = (ms) => new Date(ms).toLocaleString("es-AR", { timeZone: "America/Argentina/Buenos_Aires", dateStyle: "short", timeStyle: "short" });
  if (!r) return [];
  if (r.ok === false) {
    const motivos = { ya_existe: `el proyecto "${r.proyecto}" ya existía (queda como proyecto activo)`, proyecto_inexistente: `no existe el proyecto "${r.proyecto}"`, sin_proyecto_activo: "no indicó proyecto y no hay uno activo", falta_contenido: `para guardar ${r.ruta} falta el contenido en un bloque de código \`\`\``, archivo_muy_grande: `${r.ruta} supera 200 KB`, sin_cambios: `no hay cambios desde la versión ${r.version}`, version_inexistente: `la versión ${r.version ?? ""} no existe`, archivo_inexistente: `no existe ${r.ruta} (archivos: ${(r.archivos || []).join(", ") || "ninguno"})` };
    return [`${pre}: operación "${r.op}" NO realizada: ${motivos[r.motivo] || r.motivo}.`];
  }
  const l = [];
  if (r.op === "listar") l.push(r.proyectos.length ? `${pre}: ${r.proyectos.map(p => `${p.nombre} (${p.archivos} archivos, ${p.versiones} versiones)`).join("; ")}.` : `${pre}: todavía no hay proyectos.`);
  if (r.op === "crear") l.push(`${pre}: CREADO el proyecto "${r.proyecto}" (vacío, activo).`);
  if (r.op === "archivo") { l.push(`${pre}: GUARDADO ${r.ruta} en "${r.proyecto}" (${r.nuevo ? "archivo nuevo" : "modificado"}, ${r.lineas} líneas; sin versionar hasta un snapshot).`); if (r.cambios?.length) l.push(`Cambios en ${r.ruta}:\n${recortar(r.cambios.join("\n"))}`); }
  if (r.op === "snapshot") l.push(`${pre}: SNAPSHOT v${r.version} de "${r.proyecto}" ("${r.mensaje}"): ${r.cambios.map(c => `${c.ruta} ${c.tipo} +${c.mas}/-${c.menos}`).join(", ")}.`);
  if (r.op === "diff") l.push(r.cambios.length ? `${pre}: diff de "${r.proyecto}" v${r.desde} → ${r.hasta === "trabajo" ? "copia de trabajo" : `v${r.hasta}`}:\n${recortar(r.cambios.map(c => `--- ${c.ruta} (${c.tipo})\n${c.lineas.join("\n")}`).join("\n"))}` : `${pre}: sin diferencias en "${r.proyecto}" (v${r.desde} → ${r.hasta === "trabajo" ? "copia de trabajo" : `v${r.hasta}`}).`);
  if (r.op === "restaurar") l.push(`${pre}: RESTAURADA la copia de trabajo de "${r.proyecto}" a la versión v${r.version}${r.cambios.length ? ` (${r.cambios.map(c => `${c.ruta} ${c.tipo}`).join(", ")})` : " (ya coincidía)"}.`);
  if (r.op === "ver") l.push(`${pre}: contenido de ${r.ruta} en "${r.proyecto}":\n\`\`\`\n${recortar(r.contenido)}\n\`\`\``);
  if (r.op === "estado") l.push(`${pre}: "${r.proyecto}": archivos ${r.archivos.join(", ") || "ninguno"}; versiones ${r.versiones.map(v => `v${v.v} "${v.mensaje}" (${fecha(v.fecha)})`).join("; ") || "ninguna"}; sin versionar: ${r.sinGuardar.join(", ") || "nada"}.`);
  return l;
}

export function esPedido(mensaje = "") { return Boolean(detectar(mensaje)); }

export default { estadoBase, detectar, esPedido, aplicar, lineas, diffLineas };
