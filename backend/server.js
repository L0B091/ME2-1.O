import express from "express";
import cors from "cors";
import dotenv from "dotenv";
import { rateLimit } from "express-rate-limit";

import orquestadorChat from "./orquestador/orquestadorChat.js";
import dolphinClient from "./llm/dolphinClient.js";
import horaApi from "./api/hora.js";
import relojApi from "./api/reloj.js";
import climaApi, { UBICACION_DEFAULT, ubicacionDevHabilitada } from "./api/clima.js";
import memoriaConversacional from "./memoria/memoriaConversacional.js";
import noticiasApi from "./api/noticias.js";
import calendarioApi from "./api/calendario.js";
import notificacionesApi from "./api/notificaciones.js";
import mercadoPagoApi from "./api/mercadoPago.js";
import premiumManager from "./modulos/premium/premiumManager.js";
import backupManager from "./modulos/premium/backupManager.js";
import adultMode from "./modulos/premium/adultMode.js";
import gestorDeAlarmas from "./modulos/gestorDeAlarmas.js";
import protocoloDespertador from "./modulos/protocoloDespertador.js";
import orquestadorNotificaciones from "./orquestador/orquestadorNotificaciones.js";
import codigoMemoria from "./memoria/codigoMemoria.js";
import documentosFiscales from "./memoria/documentosFiscales.js";
import googleAuth from "./auth/googleAuth.js";
import login from "./auth/login.js";
import { optionalAuth, requireAuth } from "./auth/authMiddleware.js";
import bitacoraManager from "./modulos/bitacora/bitacoraManager.js";
import datosUsuario from "./memoria/datosUsuario.js";
import flujoPremium from "./modulos/premium/flujoPremium.js";
import verificacionEdad from "./auth/verificacionEdad.js";

dotenv.config();

const app = express();
const PORT = process.env.PORT || 3000;
const DEFAULT_CORS_ORIGINS = [
  "http://localhost",
  "http://127.0.0.1",
  "http://10.0.2.2",
  "https://localhost",
  "https://127.0.0.1",
  "https://10.0.2.2"
];
const authRateLimit = rateLimit({
  windowMs: 60 * 1000,
  limit: 10,
  standardHeaders: true,
  legacyHeaders: false
});
const healthRateLimit = rateLimit({
  windowMs: 60 * 1000,
  limit: 60,
  standardHeaders: true,
  legacyHeaders: false
});
const initiativeRateLimit = rateLimit({
  windowMs: 60 * 1000, limit: 6, standardHeaders: true, legacyHeaders: false
});

function getAllowedOrigins() {
  const configured = String(process.env.CORS_ALLOWED_ORIGINS || "")
    .split(",")
    .map(item => item.trim())
    .filter(Boolean);
  return configured.length > 0 ? configured : DEFAULT_CORS_ORIGINS;
}

const allowedOrigins = getAllowedOrigins();

app.use(cors({
  origin(origin, callback) {
    if (!origin) {
      return callback(null, true);
    }
    const allowed = allowedOrigins.some(item => origin === item || origin.startsWith(`${item}:`));
    return callback(allowed ? null : new Error("Origen no permitido por CORS"), allowed);
  },
  methods: ["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"],
  allowedHeaders: ["Content-Type", "Authorization"]
}));
app.use(express.json({ limit: "5mb" }));

function handleAsync(handler) {
  return (req, res, next) => {
    Promise.resolve(handler(req, res, next)).catch(next);
  };
}

function authStatus() {
  return {
    googleEnabled: googleAuth.googleAuthEnabled(),
    googleConfigured: Boolean(String(process.env.GOOGLE_CLIENT_ID || "").trim()),
    mercadoPagoConfigured: Boolean(String(process.env.MERCADO_PAGO_ACCESS_TOKEN || "").trim()),
    openWeatherConfigured: Boolean(String(process.env.OPENWEATHER_API_KEY || "").trim()),
    newsConfigured: Boolean(String(process.env.NEWS_API_KEY || "").trim()),
    newsProvider: noticiasApi.proveedorNoticias(),
    weatherProvider: "open-meteo / met-norway (sin key)",
    devDefaultLocation: ubicacionDevHabilitada(),
    googleCalendarConfigured: calendarioApi.adapters.google.disponible(),
    cloudBackupAdapter: backupManager.adaptadorActivo()
  };
}

function ensureOwnUser(req) {
  const requested = String(req.params.userId || req.auth?.userId || "");
  if (!req.auth || !requested || requested !== req.auth.userId) {
    const error = new Error("No autorizado para acceder a este usuario");
    error.status = 403;
    throw error;
  }
  return requested;
}

function ensurePremium(userId, feature = "Premium") {
  const premium = premiumManager.obtenerEstado(userId);
  if (!premium.premiumActivo) {
    const error = new Error(`Premium requerido para ${feature}`);
    error.status = 403;
    throw error;
  }
  return premium;
}

function serializarAlarma(alarma) {
  if (!alarma) return null;
  return {
    ...alarma,
    dispatchPlan: orquestadorNotificaciones.construirDespachosAndroid(alarma)
  };
}

app.get("/", (req, res) => {
  res.json({
    ok: true,
    servicio: "ME2 BACKEND",
    estado: "activo",
    endpoints: ["/health", "/chat", "/api/auth/*", "/api/bitacora/me"]
  });
});

app.get("/health", healthRateLimit, (req, res) => {
  res.status(200).json({
    ok: true,
    servicio: "ME2 Backend",
    estado: "activo",
    timestamp: new Date().toISOString(),
    llm: dolphinClient.obtenerDiagnostico(),
    integrations: authStatus(),
    cors: {
      mode: "restricted",
      allowedOrigins
    }
  });
});

app.post("/api/auth/register", authRateLimit, handleAsync(async (req, res) => {
  const { email, password, displayName } = req.body || {};
  const resultado = login.registrarUsuario(email, password, { displayName });
  if (!resultado.ok) {
    return res.status(400).json(resultado);
  }

  datosUsuario.actualizar(resultado.perfil.userId, {
    identidad: {
      nombre: displayName || email,
      apodo: displayName || email
    },
    cuentas: {
      email
    }
  });

  return res.status(201).json({ ok: true, ...resultado });
}));

app.post("/api/auth/login", authRateLimit, handleAsync(async (req, res) => {
  const { email, password } = req.body || {};
  const resultado = login.loginUsuario(email, password);
  if (!resultado.ok) {
    return res.status(401).json(resultado);
  }
  return res.json({ ok: true, ...resultado });
}));

app.post("/api/auth/google", authRateLimit, handleAsync(async (req, res) => {
  const { idToken, serverAuthCode } = req.body || {};
  const resultado = await googleAuth.autenticarConGoogle(idToken, serverAuthCode || null);
  return res.status(200).json({ ok: true, ...resultado });
}));

app.get("/api/auth/me", requireAuth, (req, res) => {
  res.json({ ok: true, data: req.auth });
});

app.post("/api/auth/logout", requireAuth, (req, res) => {
  try { adultMode.bloquearSesion(req.auth.userId); } catch {}
  res.json(login.cerrarSesion(req.authToken));
});

app.get("/api/bitacora/me", requireAuth, (req, res) => {
  res.json({ ok: true, data: bitacoraManager.obtenerBitacora(req.auth.userId) });
});

app.patch("/api/bitacora/me", requireAuth, (req, res) => {
  res.json({ ok: true, data: bitacoraManager.actualizarBitacora(req.auth.userId, req.body || {}) });
});

app.get("/api/hora", (req, res) => {
  res.json({ ok: true, data: horaApi.obtenerHoraActual(req.query.zonaHoraria) });
});

app.get("/api/reloj/:userId", requireAuth, (req, res) => {
  const userId = ensureOwnUser(req);
  res.json({
    ok: true,
    data: {
      ahora: relojApi.obtenerHoraActual(),
      minutosDesdeUltimaInteraccion: relojApi.tiempoDesdeUltimaInteraccion(userId)
    }
  });
});

app.post("/api/reloj/:userId/interaccion", requireAuth, (req, res) => {
  const userId = ensureOwnUser(req);
  relojApi.guardarUltimaInteraccion(userId);
  res.json({ ok: true, data: { userId, ultimaInteraccionRegistrada: true } });
});

app.get("/api/clima", optionalAuth, handleAsync(async (req, res) => {
  let lat = req.query.lat != null ? Number(req.query.lat) : null;
  let lon = req.query.lon != null ? Number(req.query.lon) : null;
  let ciudad = null;
  if (lat == null || lon == null) {
    const ubicacion = req.auth ? memoriaConversacional.obtener(req.auth.userId)?.ubicacion : null;
    const dev = !ubicacion && ubicacionDevHabilitada() ? UBICACION_DEFAULT : null;
    const u = ubicacion || dev;
    if (!u) return res.status(400).json({ ok: false, disponible: false, motivo: "ubicacion_desconocida" });
    ({ lat, lon } = u);
    ciudad = u.ciudad;
  }
  try {
    const data = await climaApi(lat, lon, { ciudad });
    res.json({ ok: true, data });
  } catch (error) {
    res.status(error.status === 400 ? 400 : 503).json({ ok: false, disponible: false, error: error.message });
  }
}));

app.get("/api/noticias", handleAsync(async (req, res) => {
  const ciudad = String(req.query.ciudad || "");
  const categorias = String(req.query.categorias || "")
    .split(",")
    .map(item => item.trim())
    .filter(Boolean);

  try {
    const data = await noticiasApi.obtenerNoticias(ciudad, categorias);
    res.json({ ok: true, proveedor: noticiasApi.proveedorNoticias(), data });
  } catch (error) {
    res.status(503).json({ ok: false, disponible: false, error: error.message });
  }
}));

app.get("/api/calendario/:userId", requireAuth, (req, res) => {
  const userId = ensureOwnUser(req);
  res.json({ ok: true, data: calendarioApi.listarEventos(userId) });
});

app.post("/api/calendario/:userId", requireAuth, (req, res) => {
  const userId = ensureOwnUser(req);
  const data = calendarioApi.agregarEvento(userId, req.body);
  res.json({ ok: data.exito !== false, data });
});

app.delete("/api/calendario/:userId/:eventoId", requireAuth, (req, res) => {
  const userId = ensureOwnUser(req);
  const data = calendarioApi.eliminarEvento(userId, req.params.eventoId);
  res.json({ ok: data.exito, data });
});

app.get("/api/notificaciones/:userId", requireAuth, (req, res) => {
  const userId = ensureOwnUser(req);
  res.json({ ok: true, data: notificacionesApi.listarNotificaciones(userId) });
});

app.post("/api/notificaciones/:userId", requireAuth, (req, res) => {
  const userId = ensureOwnUser(req);
  const { titulo, mensaje, tipo } = req.body || {};
  res.json({ ok: true, data: notificacionesApi.enviarNotificacion(userId, titulo, mensaje, tipo) });
});

app.post("/api/notificaciones/:userId/:id/leida", requireAuth, (req, res) => {
  const userId = ensureOwnUser(req);
  const data = notificacionesApi.marcarLeida(userId, req.params.id);
  res.json({ ok: Boolean(data), data });
});

app.get("/api/alarmas/:userId", requireAuth, (req, res) => {
  const userId = ensureOwnUser(req);
  const data = gestorDeAlarmas
    .obtenerAlarmasPorUsuario(userId)
    .map(serializarAlarma);
  res.json({ ok: true, data });
});

app.post("/api/alarmas/:userId", requireAuth, (req, res) => {
  const userId = ensureOwnUser(req);
  const alarma = gestorDeAlarmas.crearAlarma(
    userId,
    req.body?.hora,
    {
      titulo: req.body?.titulo,
      mensaje: req.body?.mensaje
    }
  );
  res.status(201).json({ ok: true, data: serializarAlarma(alarma) });
});

app.patch("/api/alarmas/:userId/:alarmId", requireAuth, (req, res) => {
  const userId = ensureOwnUser(req);
  const payload = { ...req.body };
  if (payload.hora) {
    const hora = gestorDeAlarmas.normalizarHora(payload.hora);
    if (!hora) {
      return res.status(400).json({ ok: false, error: "Hora inválida" });
    }
    payload.hora = hora;
  }
  const data = gestorDeAlarmas.actualizarAlarma(userId, payload, req.params.alarmId);
  res.json({ ok: Boolean(data), data: serializarAlarma(data) });
});

app.delete("/api/alarmas/:userId/:alarmId", requireAuth, (req, res) => {
  const userId = ensureOwnUser(req);
  gestorDeAlarmas.cerrarAlarma(userId, req.params.alarmId);
  res.json({ ok: true, data: { userId, alarmId: req.params.alarmId, estado: "RESOLVED" } });
});

app.post("/api/alarmas/:userId/:alarmId/evento", requireAuth, handleAsync(async (req, res) => {
  const userId = ensureOwnUser(req);
  const stage = Number(req.body?.stage || 1);
  const estado = String(req.body?.estado || "disparada");

  const data = estado === "respondio"
    ? await protocoloDespertador.registrarRespuestaUsuario(userId, req.params.alarmId)
    : protocoloDespertador.registrarDisparoAndroid(userId, stage, req.params.alarmId);

  res.json({
    ok: Boolean(data),
    data: {
      ...data,
      alarma: serializarAlarma(gestorDeAlarmas.obtenerAlarma(userId, req.params.alarmId))
    }
  });
}));

app.get("/api/mercadopago/plan", (req, res) => {
  res.json({ ok: true, data: mercadoPagoApi.explicarPremium(String(req.query.feature || "M/A")) });
});

app.post("/api/mercadopago/checkout", requireAuth, handleAsync(async (req, res) => {
  const data = await mercadoPagoApi.generarLinkPago(req.auth.userId, req.body?.feature || "M/A");
  res.json({ ok: true, data });
}));

app.post("/api/mercadopago/verify", requireAuth, handleAsync(async (req, res) => {
  const data = await mercadoPagoApi.verificarPago(req.body?.paymentId, req.auth.userId);
  res.json({ ok: Boolean(data?.ok), data });
}));

app.post("/api/mercadopago/webhook", handleAsync(async (req, res) => {
  const data = await mercadoPagoApi.procesarWebhook(req.body || {}, req.query || {});
  res.json({ ok: true, data });
}));

app.get("/api/premium/:userId", requireAuth, (req, res) => {
  const userId = ensureOwnUser(req);
  const estado = premiumManager.obtenerEstado(userId);
  res.json({
    ok: true,
    data: {
      ...estado,
      adultMode: adultMode.obtenerEstado(userId)
    }
  });
});

app.get("/api/premium/:userId/adult", requireAuth, (req, res) => {
  const userId = ensureOwnUser(req);
  res.json({ ok: true, data: adultMode.obtenerEstado(userId) });
});

app.post("/api/premium/:userId/adult/enable", requireAuth, handleAsync(async (req, res) => {
  const userId = ensureOwnUser(req);
  ensurePremium(userId, "Modo Adulto");
  const result = adultMode.habilitarExtension(userId);
  // La palabra clave no viaja por API: la entrega el chat (LLM) en el próximo turno.
  if (result.keyword) flujoPremium.alActivarPremium(userId, result.keyword);
  res.json({ ok: true, data: { ok: result.ok, recienAsignada: result.recienAsignada, estado: result.estado } });
}));

// Mercado Pago simulado (solo sin MERCADO_PAGO_ACCESS_TOKEN)
app.get("/api/mercadopago/mock/checkout/:preferenceId", (req, res) => {
  if (!mercadoPagoApi.modoMock()) return res.status(404).json({ ok: false, error: "No disponible" });
  res.json({ ok: true, mock: true, preferenceId: req.params.preferenceId, pagar: "POST /api/mercadopago/mock/pagar { preferenceId }" });
});

app.post("/api/mercadopago/mock/pagar", requireAuth, handleAsync(async (req, res) => {
  const data = await mercadoPagoApi.pagarMock(req.body?.preferenceId, req.body?.estado || "approved", req.auth.userId);
  res.json({ ok: true, data });
}));

// Dev: simula la fecha de nacimiento que devolvería Google People API (adaptador de verificación de edad).
app.post("/api/dev/verificacion-edad", requireAuth, (req, res) => {
  if (process.env.NODE_ENV !== "development") return res.status(404).json({ ok: false, error: "No disponible" });
  const reg = verificacionEdad.guardar(req.auth.userId, req.body?.fechaNacimiento ?? null, "dev_mock_google");
  res.json({ ok: true, data: { tieneFecha: Boolean(reg.fechaNacimiento), fuente: reg.fuente } });
});

app.get("/api/premium/:userId/backup/materials", requireAuth, (req, res) => {
  const userId = ensureOwnUser(req);
  const premium = premiumManager.obtenerEstado(userId);
  if (!premium.premiumActivo) {
    return res.status(403).json({ ok: false, error: "Premium requerido para respaldo" });
  }
  res.json({ ok: true, data: premiumManager.obtenerMateriales(userId) });
});

app.get("/api/premium/:userId/backup", requireAuth, handleAsync(async (req, res) => {
  const userId = ensureOwnUser(req);
  const premium = premiumManager.obtenerEstado(userId);
  if (!premium.premiumActivo) {
    return res.status(403).json({ ok: false, error: "Premium requerido para restaurar respaldo" });
  }
  res.json({ ok: true, data: await backupManager.obtenerBackup(userId) });
}));

app.put("/api/premium/:userId/backup", requireAuth, handleAsync(async (req, res) => {
  const userId = ensureOwnUser(req);
  const premium = premiumManager.obtenerEstado(userId);
  if (!premium.premiumActivo) {
    return res.status(403).json({ ok: false, error: "Premium requerido para guardar respaldo" });
  }
  const backup = req.body?.backup || {};
  res.json({ ok: true, data: await backupManager.guardarBackup(userId, backup) });
}));

app.get("/api/memoria/codigo/:userId", requireAuth, (req, res) => {
  const userId = ensureOwnUser(req);
  ensurePremium(userId, "memoria de código");
  const q = String(req.query.q || "");
  const data = q ? codigoMemoria.buscarArchivos(userId, q) : codigoMemoria.listarArchivos(userId);
  res.json({ ok: true, data });
});

app.post("/api/memoria/codigo/:userId", requireAuth, (req, res) => {
  const userId = ensureOwnUser(req);
  ensurePremium(userId, "memoria de código");
  res.json({ ok: true, data: codigoMemoria.guardarArchivo(userId, req.body) });
});

app.get("/api/memoria/codigo/:userId/:archivoId", requireAuth, (req, res) => {
  const userId = ensureOwnUser(req);
  ensurePremium(userId, "memoria de código");
  const data = codigoMemoria.obtenerArchivo(userId, req.params.archivoId);
  res.json({ ok: Boolean(data), data });
});

app.delete("/api/memoria/codigo/:userId/:archivoId", requireAuth, (req, res) => {
  const userId = ensureOwnUser(req);
  ensurePremium(userId, "memoria de código");
  res.json({ ok: codigoMemoria.eliminarArchivo(userId, req.params.archivoId) });
});

app.get("/api/memoria/fiscal/:userId", requireAuth, (req, res) => {
  const userId = ensureOwnUser(req);
  ensurePremium(userId, "gestor fiscal");
  res.json({
    ok: true,
    data: {
      documentos: documentosFiscales.listarDocumentos(userId),
      resumen: documentosFiscales.resumen(userId)
    }
  });
});

app.post("/api/memoria/fiscal/:userId", requireAuth, (req, res) => {
  const userId = ensureOwnUser(req);
  ensurePremium(userId, "gestor fiscal");
  res.json({ ok: true, data: documentosFiscales.guardarDocumento(userId, req.body) });
});

app.post("/api/memoria/fiscal/:userId/:documentoId/estado", requireAuth, (req, res) => {
  const userId = ensureOwnUser(req);
  ensurePremium(userId, "gestor fiscal");
  const data = documentosFiscales.actualizarEstado(userId, req.params.documentoId, req.body?.estado);
  res.json({ ok: Boolean(data), data });
});

app.post("/api/memoria/fiscal/:userId/:documentoId/programar-envio", requireAuth, handleAsync(async (req, res) => {
  const userId = ensureOwnUser(req);
  ensurePremium(userId, "gestor fiscal");
  const data = await documentosFiscales.programarEnvioContador(userId, req.params.documentoId, req.body || {});
  res.json({ ok: Boolean(data), data });
}));

app.post("/api/iniciativas/evaluar", initiativeRateLimit, optionalAuth, handleAsync(async (req, res) => {
  if (req.authToken && !req.auth) return res.status(401).json({ ok: false, error: "Sesion vencida" });
  const data = await orquestadorNotificaciones.evaluarAutonomia({
    ...req.body, userId: req.auth?.userId || req.body?.userId
  });
  return res.json({ ok: true, data });
}));

app.post("/chat", optionalAuth, handleAsync(async (req, res) => {
  const { mensaje, userId, contexto } = req.body || {};
  if (!mensaje || typeof mensaje !== "string") {
    return res.status(400).json({ ok: false, error: "Mensaje inválido" });
  }

  const effectiveUserId = req.auth?.userId || userId || "anonimo";
  const resultado = await orquestadorChat(mensaje, {
    ...contexto,
    userId: effectiveUserId,
    timestamp: Date.now()
  });

  if (req.auth) {
    relojApi.guardarUltimaInteraccion(req.auth.userId);
  }

  if (!resultado?.respuesta) {
    return res.status(503).json({
      ok: false,
      error: "LLM no disponible",
      acciones: resultado?.acciones || null,
      debug: process.env.NODE_ENV === "development" ? resultado?.debug || null : undefined
    });
  }

  return res.status(200).json({
    ok: true,
    respuesta: resultado.respuesta,
    acciones: resultado?.acciones || null,
    video: resultado?.video || null,
    expresion: resultado?.expresion || null,
    premium: resultado?.premium || null,
    adultMode: resultado?.adultMode || null,
    checkout: resultado?.checkout || null,
    debug: process.env.NODE_ENV === "development" ? resultado?.debug || null : undefined
  });
}));

app.use((req, res) => {
  res.status(404).json({ ok: false, error: "Ruta no encontrada" });
});

app.use((err, _req, res, _next) => {
  console.error("💥 Error global:", {
    status: err.status || 500,
    message: err.message || "Fallo inesperado del servidor"
  });
  res.status(err.status || 500).json({
    ok: false,
    error: err.message || "Fallo inesperado del servidor",
    details: err.details || undefined
  });
});

const server = app.listen(PORT, () => {
  console.log(`🚀 ME2 corriendo en http://localhost:${server.address().port}`);
  // Server-side alarm tick (Android remains primary executor for notifications)
  try {
    orquestadorNotificaciones.iniciar(60_000);
    console.log("⏰ orquestadorNotificaciones tick iniciado (60s)");
  } catch (error) {
    console.error("No se pudo iniciar orquestadorNotificaciones:", error?.message || error);
  }
});
