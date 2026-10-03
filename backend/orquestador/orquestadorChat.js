/*
* ORQUESTADOR DE CHAT (rama me2_dlp)
* Motor de la app: Entrada → Memoria → Herramientas/acciones → Contexto → LLM → Memoria.
* La personalidad vive en el master prompt del modelo (Dolphin Mistral Venice).
* Este orquestador NO aplica filtros de personalidad, plantillas ni frases armadas:
* la respuesta del LLM vuelve al usuario sin modificar.
*/

import historialConversacion from "../memoria/historialConversacion.js";
import registrarActividad from "../memoria/registrarActividad.js";
import guardia from "../seguridad/guardiaInstrucciones.js";
import memoriaConversacional from "../memoria/memoriaConversacional.js";
import { geocodificar } from "../api/geocoding.js";
import datosUsuario from "../memoria/datosUsuario.js";
import dolphinClient from "../llm/dolphinClient.js";
import premiumManager from "../modulos/premium/premiumManager.js";
import adultMode from "../modulos/premium/adultMode.js";
import flujoPremium from "../modulos/premium/flujoPremium.js";
import premiumLocal from "../modulos/premium/premiumLocal.js";
import backupManager from "../modulos/premium/backupManager.js";
import selectorMedia from "../modulos/media/selectorMedia.js";
import reaccionAudiovisual from "../modulos/media/reaccionAudiovisual.js";
import reacciones from "../modulos/interaccion/reacciones.js";
import formatoAdulto from "../modulos/media/formatoAdulto.js";
import mercadoPagoApi from "../api/mercadoPago.js";
import preferenciaNombre from "../modulos/interaccion/preferenciaNombre.js";
import gestorDeAlarmas from "../modulos/gestorDeAlarmas.js";
import calendarioApi from "../api/calendario.js";
import { detectarAlarma } from "../modulos/detectorAlarmas.js";
import { detectarEvento } from "../modulos/detectorAgenda.js";
import contextoLLM from "./contextoLLM.js";
import perfilBasico from "../modulos/onboarding/perfilBasico.js";
import estadoEmocional from "../memoria/estadoEmocional.js";
import continuidad from "../memoria/continuidad.js";

const EXPRESION_NEUTRA = Object.freeze({ tono: "neutral", ritmo: "normal", microexpresion: "mirada_atenta", intensidad: "suave" });

const MAX_GUSTOS_LOCAL = 30;
function listaTextos(v, max = MAX_GUSTOS_LOCAL) {
  return Array.isArray(v)
    ? [...new Set(v.filter(x => typeof x === "string").map(x => x.trim().toLowerCase()).filter(x => x.length >= 2 && x.length <= 60))].slice(-max)
    : [];
}

export function normalizarUbicacion(u) {
  if (!u || typeof u !== "object") return null;
  const lat = Number(u.lat);
  const lon = Number(u.lon);
  const coords = Number.isFinite(lat) && Number.isFinite(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180;
  const ciudad = typeof u.ciudad === "string" && u.ciudad.trim() ? u.ciudad.trim().slice(0, 80) : null;
  const zonaHoraria = typeof u.zonaHoraria === "string" && /^[A-Za-z_]+(\/[A-Za-z0-9_+-]+)*$/.test(u.zonaHoraria) ? u.zonaHoraria : null;
  if (!coords) return null; // el clima necesita coordenadas; una ciudad sin geocodificar no sirve
  return { ciudad, lat, lon, zonaHoraria };
}

/**
 * Hechos estructurados para un cliente con memoria local primaria (Android): el backend NO los persiste; los extrae
 * del mensaje (gustos, disgustos, ciudad geocodificada) y los devuelve para que el teléfono los guarde.
 */
let geocodificarImpl = geocodificar;
/** Solo tests: reemplaza el geocodificador (sin red). */
export function _setGeocodificar(fn) { geocodificarImpl = typeof fn === "function" ? fn : geocodificar; }

async function hechosParaMemoriaLocal(mensaje, memoriaLocal) {
  const geo = geocodificarImpl;
  const ex = memoriaConversacional.extraerHechos(mensaje);
  const delta = { gustos: listaTextos(ex.gustos), disgustos: listaTextos(ex.disgustos), ubicacion: null };
  const actual = memoriaLocal?.ubicacion;
  if (ex.ciudad && (!actual?.ciudad || actual.ciudad.toLowerCase() !== ex.ciudad.toLowerCase())) {
    try {
      const g = await geo(ex.ciudad, { timeoutMs: 5000 });
      if (g) delta.ubicacion = normalizarUbicacion(g);
    } catch { /* sin geocodificación: la ubicación sigue igual */ }
  }
  return delta;
}

/**
 * El cliente guarda el mensaje del usuario antes de enviarlo, así que puede llegar también como último elemento del
 * historial: se quita para no duplicarlo (el mensaje actual va una sola vez, como {role:"user"} al final).
 */
export function quitarMensajeActual(historial = [], mensaje = "") {
  const ultimo = historial[historial.length - 1];
  const norm = t => String(t || "").trim().toLowerCase();
  if (ultimo && ultimo.tipo === "usuario" && norm(ultimo.mensaje) === norm(mensaje)) return historial.slice(0, -1);
  return historial;
}

function normalizarMemoriaLocal(memoriaLocal = {}) {
  if (!memoriaLocal || typeof memoriaLocal !== "object") return null;
  const recentConversation = Array.isArray(memoriaLocal.recentConversation)
    ? memoriaLocal.recentConversation
      .map(item => ({ tipo: item?.role === "assistant" ? "asistente" : "usuario", mensaje: String(item?.text || "").trim() }))
      .filter(item => item.mensaje)
    : [];
  return {
    source: memoriaLocal.source || "android_local_primary",
    characterName: memoriaLocal.characterName || null,
    recentConversation,
    persistentMemories: Array.isArray(memoriaLocal.persistentMemories) ? memoriaLocal.persistentMemories : [],
    importantMemories: Array.isArray(memoriaLocal.importantMemories) ? memoriaLocal.importantMemories : [],
    gustos: listaTextos(memoriaLocal.gustos),
    disgustos: listaTextos(memoriaLocal.disgustos),
    ubicacion: normalizarUbicacion(memoriaLocal.ubicacion)
  };
}

// Acciones deterministas del orquestador (el LLM solo las confirma con su voz).
function ejecutarAcciones(userId, mensaje, persistir) {
  const acciones = [];
  const resultado = { alarma: null, evento: null };
  if (!userId) return { acciones, resultado };

  const pedidoAlarma = detectarAlarma(mensaje);
  // Cliente con memoria local primaria (Android): la alarma vive en el teléfono (AlarmManager, funciona offline y
  // sobrevive reinicios); el teléfono la sincroniza con el servidor cuando hay conexión.
  if (persistir === false && pedidoAlarma?.accion === "crear" && pedidoAlarma.hora) {
    resultado.alarma = { accion: "crear_local", hora: pedidoAlarma.hora, titulo: pedidoAlarma.titulo || null };
    acciones.push(`Alarma CREADA en el teléfono para las ${pedidoAlarma.hora}${pedidoAlarma.titulo ? ` (${pedidoAlarma.titulo})` : ""}; suena aunque no haya conexión.`);
    return { acciones, resultado };
  }
  if (persistir === false && pedidoAlarma?.accion === "cancelar") {
    resultado.alarma = { accion: "cancelar_local", hora: pedidoAlarma.hora || null };
    acciones.push(`Alarma CANCELADA en el teléfono${pedidoAlarma.hora ? ` (${pedidoAlarma.hora})` : " (la próxima)"}.`);
    return { acciones, resultado };
  }
  // Anónimo (dev/demo): solo acciones que viven en el teléfono; nada se guarda en el servidor bajo "anonimo".
  if (userId === "anonimo") {
    if (pedidoAlarma || detectarEvento(mensaje)) acciones.push("Modo demo sin cuenta: esta acción no se pudo guardar; no se creó nada.");
    return { acciones, resultado };
  }
  if (pedidoAlarma?.accion === "crear" && pedidoAlarma.hora && persistir !== false) {
    try {
      const alarma = gestorDeAlarmas.crearAlarma(userId, pedidoAlarma.hora, { titulo: pedidoAlarma.titulo });
      resultado.alarma = { accion: "creada", ...alarma };
      acciones.push(`Alarma CREADA y guardada para las ${alarma.hora} (${alarma.titulo}); se sincroniza con el teléfono.`);
    } catch (error) {
      resultado.alarma = { accion: "error", error: error.message };
      acciones.push(`No se pudo crear la alarma: ${error.message}`);
    }
  } else if (pedidoAlarma?.accion === "crear" && !pedidoAlarma.hora) {
    acciones.push("El usuario pidió una alarma pero no se entendió la hora; no se creó ninguna alarma.");
  } else if (pedidoAlarma?.accion === "cancelar") {
    const activas = gestorDeAlarmas.obtenerAlarmasPorUsuario(userId);
    const objetivo = pedidoAlarma.hora ? activas.filter(a => a.hora === pedidoAlarma.hora) : activas.slice(-1);
    objetivo.forEach(a => gestorDeAlarmas.cerrarAlarma(userId, a.id));
    resultado.alarma = { accion: "cancelada", ids: objetivo.map(a => a.id) };
    acciones.push(objetivo.length
      ? `Alarma(s) CANCELADA(s): ${objetivo.map(a => a.hora).join(", ")}`
      : "El usuario pidió cancelar una alarma pero no había ninguna que coincida.");
  }

  const pedidoEvento = !pedidoAlarma ? detectarEvento(mensaje) : null;
  if (pedidoEvento) {
    const r = calendarioApi.agregarEvento(userId, pedidoEvento);
    resultado.evento = r;
    acciones.push(r.exito
      ? `Evento AGENDADO: ${r.evento.fecha} ${r.evento.hora} — ${r.evento.descripcion}`
      : `No se pudo agendar el evento: ${r.mensaje}`);
  }
  return { acciones, resultado };
}

function memoriaVacia(userId) {
  return { userId, nombre: null, ciudad: null, ubicacion: null, gustos: [], disgustos: [], hechos: [], onboarding: { pendiente: null, horaConfirmada: null }, actualizado: null };
}

const VIDEO_BASE = Object.freeze({
  categoria: "calida", etiqueta: "calida", assetPath: "ME2_MEDIA/01_LOOP_NEUTRAL/NEUTRAL_001.mp4",
  assetName: "NEUTRAL_001.mp4", mediaId: "NEUTRAL_001", loop: true
});

async function orquestador(mensajeUsuario, contexto = {}) {
  const userId = contexto.userId || "anonimo";
  const esAnonimo = userId === "anonimo";
  const memoriaLocal = Object.prototype.hasOwnProperty.call(contexto, "memoriaLocal")
    ? normalizarMemoriaLocal(contexto.memoriaLocal)
    : null;
  const persistirEnServidor = !memoriaLocal || memoriaLocal.source !== "android_local_primary";

  // [MEMORY] historial previo (antes de registrar este mensaje)
  const historialPrevio = quitarMensajeActual(memoriaLocal?.recentConversation?.length
    ? memoriaLocal.recentConversation
    : (esAnonimo ? [] : historialConversacion.obtenerHistorial(userId, 40)), mensajeUsuario);

  // Nombre del personaje (dato, sin respuesta armada)
  const nombrePersonajeDetectado = preferenciaNombre.extraerNombrePersonaje(mensajeUsuario, { memoriaLocal });
  if (nombrePersonajeDetectado && persistirEnServidor && userId !== "anonimo") {
    preferenciaNombre.guardarNombrePersonaje(userId, nombrePersonajeDetectado);
  }

  // Actividad (bitácora) + historial del mensaje del usuario (solo clientes sin memoria local primaria).
  if (persistirEnServidor && userId !== "anonimo") {
    try {
      registrarActividad.registrarActividad(userId, new Date());
      historialConversacion.registrarMensaje(userId, mensajeUsuario, "usuario");
    } catch (error) {
      console.error("Error registrando actividad/historial:", error.message);
    }
  }

  // Hechos del usuario (nombre, gustos, cosas que contó) — persistidos
  let memoriaEscritura = null;
  // Anónimo (solo dev/demo): sin memoria ni historial del servidor (sería compartido entre todos los anónimos).
  let memoriaHechos = esAnonimo ? memoriaVacia(userId) : memoriaConversacional.obtener(userId);
  if (persistirEnServidor && userId !== "anonimo") {
    const r = memoriaConversacional.registrar(userId, mensajeUsuario);
    memoriaHechos = r.memoria;
    memoriaEscritura = { hechos: r.extraido, cambios: r.cambios };
    estadoEmocional.registrar(userId, mensajeUsuario);
    continuidad.registrar(userId, mensajeUsuario);
  }
  let memoriaLocalDelta = null;
  if (memoriaLocal) {
    if (!persistirEnServidor) {
      memoriaLocalDelta = await hechosParaMemoriaLocal(mensajeUsuario, memoriaLocal);
    }
    const disgustos = listaTextos([...(memoriaLocal.disgustos || []), ...(memoriaLocalDelta?.disgustos || [])]);
    const gustos = listaTextos([...(memoriaHechos.gustos || []), ...memoriaLocal.gustos, ...(memoriaLocalDelta?.gustos || [])])
      .filter(g => !disgustos.includes(g));
    memoriaHechos = {
      ...memoriaHechos,
      gustos,
      disgustos: listaTextos([...(memoriaHechos.disgustos || []), ...disgustos]),
      ubicacion: memoriaLocalDelta?.ubicacion || memoriaLocal.ubicacion || memoriaHechos.ubicacion || null,
      hechos: [...(memoriaHechos.hechos || []), ...memoriaLocal.persistentMemories.map(m => m?.text).filter(Boolean),
        ...memoriaLocal.importantMemories.map(m => m?.text).filter(Boolean)]
    };
    if (memoriaHechos.ubicacion && !memoriaHechos.ciudad) memoriaHechos.ciudad = memoriaHechos.ubicacion.ciudad || null;
  }

  // Premium + modo adulto: solo estado/gate como HECHOS (el LLM redacta oferta, rechazo, link y palabra clave)
  let premium = premiumManager.obtenerEstado(userId);
  const extra = [];
  let checkout = null;
  let flujo = { lineas: [], estado: null, link: null, evento: null };
  try {
    flujo = await flujoPremium.procesar(userId, mensajeUsuario, {
      premium, generarLink: (uid, feature) => mercadoPagoApi.generarLinkPago(uid, feature)
    });
    extra.push(...flujo.lineas);
    if (flujo.link?.url) checkout = { initPoint: flujo.link.url, preferenceId: flujo.link.preferenceId || null, mock: Boolean(flujo.link.mock) };
  } catch (error) {
    console.error("Error en flujoPremium:", error.message);
  }
  premium = premiumManager.obtenerEstado(userId);

  // [PREMIUM LOCAL] gestor fiscal (monotributo) + proyectos de programación: datos en el teléfono (o JSON del backend sin teléfono)
  let premiumLocalRes = null;
  try {
    premiumLocalRes = premiumLocal.procesar(userId, mensajeUsuario, {
      premiumActivo: premium.premiumActivo, local: contexto.premiumLocal, enTelefono: !persistirEnServidor
    });
    if (premiumLocalRes) extra.push(...premiumLocalRes.lineas);
  } catch (error) {
    console.error("Error en premiumLocal:", error.message);
  }
  // [CONTINUIDAD] primer chat tras restaurar el respaldo en un teléfono nuevo: cuándo y dónde fue la última interacción
  if (premium.premiumActivo && userId !== "anonimo") {
    try { extra.push(...await backupManager.lineasContinuidad(userId)); } catch (error) { console.error("Error en continuidad:", error.message); }
  }
  let adultResult = { evento: "sin_cambios", adult: null, video: null, pedidoAdulto: false };
  try {
    adultResult = await adultMode.procesarEnChat(userId, mensajeUsuario, { premium });
  } catch (error) {
    console.error("Error en adultMode:", error.message);
  }
  if (adultResult.adult?.unlocked) {
    extra.push(formatoAdulto.LINEA_CONTRATO);
    extra.push(`Modo adulto: ACTIVO solo en esta sesión (${adultResult.evento === "desbloqueado_con_keyword" ? "recién desbloqueado con la palabra clave correcta" : "desbloqueado con la palabra clave"}); intensidad actual: ${adultResult.adult.intensity}.`);
  } else if (adultResult.pedidoAdulto) {
    const motivo = adultResult.evento === "premium_requerido" ? "requiere Premium" : "falta la palabra clave en esta sesión";
    extra.push(`Modo adulto: DESACTIVADO (${motivo}). El pedido íntimo/erótico del usuario no está habilitado.`);
  }

  // [ACTIONS] alarmas / agenda (deterministas)
  const { acciones, resultado: accionesResultado } = ejecutarAcciones(userId, mensajeUsuario, persistirEnServidor && !esAnonimo);

  // [ONBOARDING] datos básicos faltantes (uno por vez, como hecho de contexto)
  let characterName = nombrePersonajeDetectado || preferenciaNombre.obtenerNombrePersonaje({
    memoriaLocal, datosUsuario: datosUsuario.obtener(userId)
  });
  let onboarding = { hechosTurno: [], faltantes: [], siguiente: null };
  if (persistirEnServidor && userId !== "anonimo") {
    try {
      onboarding = await perfilBasico.procesar(userId, mensajeUsuario, { characterName, memoriaLocal });
      characterName = onboarding.characterName || characterName;
      memoriaHechos = { ...memoriaHechos, ...onboarding.memoria, hechos: memoriaHechos.hechos };
    } catch (error) {
      console.error("Error en onboarding:", error.message);
    }
  }

  // [INICIATIVA] B10: el usuario responde a una iniciativa que ME2 le envió (dato del cliente, saneado; no dispara nada).
  const ini = contexto.iniciativa && typeof contexto.iniciativa === "object" ? contexto.iniciativa : null;
  const iniTexto = ini ? guardia.datoDeUsuario(ini.mensaje || ini.texto || "", 240) : "";
  if (iniTexto) {
    const cat = guardia.datoDeUsuario(ini.categoria || ini.fuente || "", 30);
    extra.push(`El usuario está respondiendo a una iniciativa tuya${cat ? ` (${cat})` : ""}: «${iniTexto}». Retomá ese tema si corresponde.`);
  }

  // [GUARDIA] anti prompt-injection: el texto del usuario no cambia estado ni dispara acciones (ya decididas arriba).
  const inyeccion = guardia.detectarIntentoInyeccion(mensajeUsuario);
  if (inyeccion.sospechoso) extra.push(guardia.AVISO_INYECCION);

  // [CONTEXT] herramientas + memoria + funciones de la app
  const herramientas = await contextoLLM.obtenerHerramientas(userId, {
    lat: contexto.lat, lon: contexto.lon, ciudad: contexto.ciudad, zonaHoraria: contexto.zonaHoraria, memoria: memoriaHechos
  });
  const mensajeContexto = contextoLLM.construirMensajeContexto({
    mensaje: mensajeUsuario,
    herramientas,
    memoria: memoriaHechos,
    datosPerfil: datosUsuario.obtener(userId),
    characterName,
    app: contextoLLM.funcionesApp(userId),
    accionesTurno: [...(onboarding.hechosTurno || []), ...acciones],
    onboarding: perfilBasico.lineasContexto(onboarding),
    estadoEmocional: userId !== "anonimo" ? estadoEmocional.resumen(userId) : null,
    pendientes: userId !== "anonimo" ? continuidad.pendientesVigentes(userId) : [],
    extra
  });
  const mensajes = [
    mensajeContexto,
    ...contextoLLM.historialAMensajes(historialPrevio, 20),
    { role: "user", content: guardia.limpiarTextoUsuario(mensajeUsuario) || "…" }
  ];

  // [LLM] respuesta sin filtros
  let respuesta = null;
  let debugLLM = { ...dolphinClient.obtenerDiagnostico(), used: false };
  try {
    const r = await dolphinClient.chat(mensajes);
    debugLLM = { ...debugLLM, ...r, respuesta: undefined };
    respuesta = r.respuesta;
  } catch (error) {
    debugLLM = { ...debugLLM, used: false, error: error.message };
  }

  // [FORMATO] modo adulto: texto | gif | texto+gif (validado por el orquestador). Fuera: siempre texto.
  const formato = respuesta != null
    ? formatoAdulto.decidir({ userId, respuesta, mensaje: mensajeUsuario, adult: adultResult?.adult, persistir: persistirEnServidor && userId !== "anonimo" })
    : { formato: "texto", texto: null, gif: null, motivo: "llm_no_disponible" };
  respuesta = respuesta != null ? formato.texto : null;
  const media = formato.gif;

  // [MEMORY] write-back de la respuesta del LLM
  if ((respuesta || media) && persistirEnServidor && userId !== "anonimo") {
    historialConversacion.registrarMensaje(userId, respuesta || `[GIF: ${(media.tags || []).join(", ")}]`, "asistente");
  }

  // B8: sin selectorVideo legacy (su salida era constante): video base neutro; el clip concreto lo decide la
  // biblioteca audiovisual V1 (reaccionAudiovisual) y el cliente.
  const video = { ...VIDEO_BASE };
  const videoFinal = adultResult?.video?.categoria && adultResult?.adult?.unlocked ? { ...video, ...adultResult.video } : video;
  // Texto + clip siempre; GIF opcional solo en modo adulto (catálogo adulto con gating estricto).
  const { clip } = selectorMedia.mediosRespuesta({ mensaje: mensajeUsuario, respuesta: respuesta || "", adult: adultResult?.adult, videoGaleria: videoFinal });
  // Biblioteca audiovisual V1: el orquestador decide categoría + intensidad; el cliente elige el recurso concreto.
  const audiovisual = (respuesta || media) ? reaccionAudiovisual.decidir({ mensaje: mensajeUsuario, respuesta: respuesta || "" }) : null;
  // Clima actual (ya obtenido para el contexto) → widget del cliente (hora + clima en el anillo inferior).
  const climaH = herramientas?.clima;
  const clima = climaH && climaH.disponible !== false && Number.isFinite(Number(climaH.temperatura))
    ? { temperatura: Number(climaH.temperatura), descripcion: climaH.descripcion || null }
    : null;
  // Reacción ocasional (emoji) sobre la burbuja del usuario, persistida en el historial.
  let reaccion = null;
  if ((respuesta || media) && userId !== "anonimo") {
    reaccion = reacciones.decidir(userId, mensajeUsuario, { gustosNuevos: memoriaEscritura?.hechos?.gustos || [] });
    if (reaccion && persistirEnServidor) historialConversacion.anotarReaccion(userId, reaccion.emoji);
  }

  return {
    respuesta,
    llmDisponible: Boolean(respuesta || media),
    formato: formato.formato,
    expresion: EXPRESION_NEUTRA,
    video: videoFinal,
    clip,
    audiovisual,
    clima,
    media,
    reaccion: reaccion ? { emoji: reaccion.emoji } : null,
    flujoPremium: { estado: flujo.estado, evento: flujo.evento },
    premium,
    adultMode: adultResult?.adult || null,
    checkout,
    memoriaLocalDelta: memoriaLocalDelta && (memoriaLocalDelta.gustos.length || memoriaLocalDelta.disgustos.length || memoriaLocalDelta.ubicacion)
      ? memoriaLocalDelta : null,
    acciones: premiumLocalRes
      ? { ...accionesResultado, premium: { modulo: premiumLocalRes.modulo, operacion: premiumLocalRes.resultado?.op, ok: premiumLocalRes.resultado?.ok !== false, persistidoEn: premiumLocalRes.persistidoEn, estado: premiumLocalRes.persistidoEn === "telefono" ? premiumLocalRes.estado : undefined } }
      : accionesResultado,
    debug: {
      llm: debugLLM,
      contexto: mensajeContexto.content,
      onboarding: { siguiente: onboarding.siguiente, faltantes: onboarding.faltantes, hechosTurno: onboarding.hechosTurno },
      historialEnviado: mensajes.length - 2,
      inyeccion: inyeccion.motivos,
      memoriaEscritura,
      memoriaHechos
    }
  };
}

export default orquestador;
