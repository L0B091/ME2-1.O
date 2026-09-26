import storage from "../utils/jsonStorage.js";

const NAMESPACE = "documentos_fiscales";

/**
 * Stub mail transport — replace with real SMTP when credentials exist.
 * Never throws; returns a clear status for the client/UI.
 */
async function enviarEmailStub({ to, subject, body, attachments = [] }) {
  const configured = Boolean(String(process.env.SMTP_HOST || "").trim());
  if (!configured) {
    return {
      ok: false,
      stub: true,
      error: "SMTP no configurado (SMTP_HOST / SMTP_USER / SMTP_PASS). Envío diferido.",
      to,
      subject,
      attachments: attachments.length
    };
  }
  // Interface ready for nodemailer or similar — not wired without deps/creds.
  return {
    ok: false,
    stub: true,
    error: "Transport SMTP pendiente de implementación; credenciales detectadas pero envío no activo.",
    to,
    subject
  };
}

function normalizarDocumento(data = {}) {
  const timestamp = new Date().toISOString();
  const movimiento = String(data.movimiento || data.tipoMovimiento || "").toLowerCase();
  const tipoMovimiento =
    movimiento === "cobro" || movimiento === "pago" || movimiento === "neutral"
      ? movimiento
      : data.tipo === "factura_venta" || data.tipo === "cobro"
        ? "cobro"
        : data.tipo === "factura_compra" || data.tipo === "pago"
          ? "pago"
          : "neutral";

  const imagen = data.imagen || data.image || null;
  const imagenMeta = imagen && typeof imagen === "object"
    ? {
        uri: imagen.uri || imagen.url || null,
        mimeType: imagen.mimeType || imagen.mime || null,
        width: imagen.width ?? null,
        height: imagen.height ?? null,
        sizeBytes: imagen.sizeBytes ?? imagen.size ?? null,
        capturadoEn: imagen.capturadoEn || imagen.capturedAt || null,
        fuente: imagen.fuente || imagen.source || "camara"
      }
    : null;

  return {
    id: String(data.id || data.numero || Date.now()),
    tipo: String(data.tipo || "comprobante"),
    movimiento: tipoMovimiento,
    numero: String(data.numero || data.id || "sin-numero"),
    emisor: String(data.emisor || "sin-emisor"),
    receptor: data.receptor ? String(data.receptor) : null,
    monto: Number.isFinite(Number(data.monto)) ? Number(data.monto) : 0,
    moneda: String(data.moneda || "ARS"),
    fecha: String(data.fecha || timestamp.slice(0, 10)),
    vencimiento: data.vencimiento ? String(data.vencimiento) : null,
    estado: String(data.estado || "registrado"),
    descripcion: String(data.descripcion || "").trim(),
    tags: Array.isArray(data.tags) ? data.tags.map(String) : [],
    imagen: imagenMeta,
    contadorEmail: data.contadorEmail ? String(data.contadorEmail).trim() : null,
    envioProgramado: data.envioProgramado
      ? {
          fecha: String(data.envioProgramado.fecha || data.envioProgramado.at || ""),
          email: String(data.envioProgramado.email || data.contadorEmail || ""),
          estado: String(data.envioProgramado.estado || "pendiente"),
          ultimoIntento: data.envioProgramado.ultimoIntento || null,
          ultimoError: data.envioProgramado.ultimoError || null
        }
      : null,
    creadoEn: data.creadoEn || timestamp,
    actualizadoEn: data.actualizadoEn || timestamp
  };
}

function listarDocumentos(userId) {
  return storage.readUserData(NAMESPACE, userId, []);
}

function guardarDocumento(userId, documento) {
  const documentos = listarDocumentos(userId);
  const normalizado = normalizarDocumento(documento);
  const indice = documentos.findIndex(item => item.id === normalizado.id || item.numero === normalizado.numero);
  if (indice >= 0) {
    normalizado.creadoEn = documentos[indice].creadoEn || normalizado.creadoEn;
    documentos[indice] = { ...documentos[indice], ...normalizado, actualizadoEn: new Date().toISOString() };
  } else {
    documentos.push(normalizado);
  }
  storage.writeUserData(NAMESPACE, userId, documentos);
  return normalizado;
}

function obtenerDocumento(userId, documentoId) {
  return listarDocumentos(userId).find(item => item.id === documentoId || item.numero === documentoId) || null;
}

function actualizarEstado(userId, documentoId, estado) {
  const documento = obtenerDocumento(userId, documentoId);
  if (!documento) return null;
  return guardarDocumento(userId, { ...documento, estado });
}

async function programarEnvioContador(userId, documentoId, opciones = {}) {
  const documento = obtenerDocumento(userId, documentoId);
  if (!documento) return null;
  const email = String(opciones.email || documento.contadorEmail || "").trim();
  const fecha = String(opciones.fecha || opciones.at || new Date().toISOString());
  if (!email) {
    return {
      ...documento,
      envioProgramado: {
        fecha,
        email: "",
        estado: "error",
        ultimoIntento: new Date().toISOString(),
        ultimoError: "Email del contador requerido"
      }
    };
  }

  const envioProgramado = {
    fecha,
    email,
    estado: "programado",
    ultimoIntento: null,
    ultimoError: null
  };

  // If scheduled for now/past, attempt stub send immediately
  const when = Date.parse(fecha);
  if (!Number.isNaN(when) && when <= Date.now() + 1000) {
    const result = await enviarEmailStub({
      to: email,
      subject: `ME2 fiscal — ${documento.tipo} ${documento.numero}`,
      body: `Documento ${documento.numero} (${documento.movimiento}) monto ${documento.monto} ${documento.moneda}`,
      attachments: documento.imagen?.uri ? [{ uri: documento.imagen.uri, mimeType: documento.imagen.mimeType }] : []
    });
    envioProgramado.estado = result.ok ? "enviado" : "pendiente_smtp";
    envioProgramado.ultimoIntento = new Date().toISOString();
    envioProgramado.ultimoError = result.ok ? null : result.error;
  }

  return guardarDocumento(userId, {
    ...documento,
    contadorEmail: email,
    envioProgramado
  });
}

function resumen(userId) {
  const documentos = listarDocumentos(userId);
  const total = documentos.reduce((acc, item) => acc + (item.monto || 0), 0);
  const cobros = documentos.filter(item => item.movimiento === "cobro");
  const pagos = documentos.filter(item => item.movimiento === "pago");
  const pendientes = documentos.filter(item => item.estado !== "pagado" && item.estado !== "cobrado").length;
  return {
    totalDocumentos: documentos.length,
    montoTotal: total,
    montoCobros: cobros.reduce((a, i) => a + (i.monto || 0), 0),
    montoPagos: pagos.reduce((a, i) => a + (i.monto || 0), 0),
    pendientes,
    conImagen: documentos.filter(item => item.imagen?.uri).length,
    enviosPendientes: documentos.filter(item => item.envioProgramado?.estado === "programado" || item.envioProgramado?.estado === "pendiente_smtp").length,
    monedas: [...new Set(documentos.map(item => item.moneda))]
  };
}

export default {
  listarDocumentos,
  guardarDocumento,
  obtenerDocumento,
  actualizarEstado,
  programarEnvioContador,
  enviarEmailStub,
  resumen
};
