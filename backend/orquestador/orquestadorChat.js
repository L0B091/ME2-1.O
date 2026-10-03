/*
* ORQUESTADOR DE CHAT (rama me2_dlp)
* Motor de la app: Entrada → Memoria → Herramientas/acciones → Contexto → LLM → Memoria.
* La personalidad vive en el master prompt del modelo (Dolphin Mistral Venice).
* Este orquestador NO aplica filtros de personalidad, plantillas ni frases armadas:
* la respuesta del LLM vuelve al usuario sin modificar.
*/

import procesadorEntrada from "../motor/procesadorEntrada.js";
import { obtenerUsuario } from "../memoria/usuarioMemoria.js";
import historialConversacion from "../memoria/historialConversacion.js";
import writeBackEngine from "../memoria/writebackengine.js";
import memoriaOrquestador from "../memoria/memoriaOrquestador.js";
import memoriaConversacional from "../memoria/memoriaConversacional.js";
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
import selectorVideo from "../modulos/video/selectorVideo.js";
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
    importantMemories: Array.isArray(memoriaLocal.importantMemories) ? memoriaLocal.importantMemories : []
  };
}

// Acciones deterministas del orquestador (el LLM solo las confirma con su voz).
function ejecutarAcciones(userId, mensaje, persistir) {
  const acciones = [];
  const resultado = { alarma: null, evento: null };
  if (!userId || userId === "anonimo") return { acciones, resultado };

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

async function orquestador(mensajeUsuario, contexto = {}) {
  const userId = contexto.userId || "anonimo";
  const memoriaLocal = Object.prototype.hasOwnProperty.call(contexto, "memoriaLocal")
    ? normalizarMemoriaLocal(contexto.memoriaLocal)
    : null;
  const persistirEnServidor = !memoriaLocal || memoriaLocal.source !== "android_local_primary";

  // [MEMORY] historial previo (antes de registrar este mensaje)
  const historialPrevio = memoriaLocal?.recentConversation?.length
    ? memoriaLocal.recentConversation
    : historialConversacion.obtenerHistorial(userId, 40);

  // Nombre del personaje (dato, sin respuesta armada)
  const nombrePersonajeDetectado = preferenciaNombre.extraerNombrePersonaje(mensajeUsuario, { memoriaLocal });
  if (nombrePersonajeDetectado && persistirEnServidor) {
    preferenciaNombre.guardarNombrePersonaje(userId, nombrePersonajeDetectado);
  }

  let memoriaUsuario = null;
  try { memoriaUsuario = obtenerUsuario(userId); } catch { memoriaUsuario = null; }
  const entradaProcesada = procesadorEntrada.procesarEntrada(userId, mensajeUsuario, historialPrevio);

  // Memoria del sistema (registra el mensaje del usuario en el historial)
  let memoriaSistema = null;
  try {
    memoriaSistema = await memoriaOrquestador.ejecutar({ userId, mensaje: mensajeUsuario, entradaProcesada, persistirEnServidor });
  } catch (error) {
    console.error("Error en memoriaOrquestador:", error.message);
  }

  // Hechos del usuario (nombre, gustos, cosas que contó) — persistidos
  let memoriaEscritura = null;
  let memoriaHechos = memoriaConversacional.obtener(userId);
  if (persistirEnServidor && userId !== "anonimo") {
    const r = memoriaConversacional.registrar(userId, mensajeUsuario);
    memoriaHechos = r.memoria;
    memoriaEscritura = { hechos: r.extraido, cambios: r.cambios };
    estadoEmocional.registrar(userId, mensajeUsuario);
    continuidad.registrar(userId, mensajeUsuario);
    try {
      memoriaEscritura.writeBack = writeBackEngine.evaluarWriteBack({ userId, mensaje: mensajeUsuario, entradaProcesada });
    } catch (error) {
      memoriaEscritura.writeBack = { guardado: false, error: error.message };
    }
  }
  if (memoriaLocal) {
    memoriaHechos = {
      ...memoriaHechos,
      hechos: [...(memoriaHechos.hechos || []), ...memoriaLocal.persistentMemories.map(m => m?.text).filter(Boolean),
        ...memoriaLocal.importantMemories.map(m => m?.text).filter(Boolean)]
    };
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
  const { acciones, resultado: accionesResultado } = ejecutarAcciones(userId, mensajeUsuario, persistirEnServidor);

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

  // [CONTEXT] herramientas + memoria + funciones de la app
  const herramientas = await contextoLLM.obtenerHerramientas(userId, {
    lat: contexto.lat, lon: contexto.lon, zonaHoraria: contexto.zonaHoraria, memoria: memoriaHechos
  });
  const mensajeContexto = contextoLLM.construirMensajeContexto({
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
    { role: "user", content: mensajeUsuario }
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

  const video = selectorVideo.seleccionarVideo({ adultMode: adultResult?.adult }, EXPRESION_NEUTRA);
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
    acciones: premiumLocalRes
      ? { ...accionesResultado, premium: { modulo: premiumLocalRes.modulo, operacion: premiumLocalRes.resultado?.op, ok: premiumLocalRes.resultado?.ok !== false, persistidoEn: premiumLocalRes.persistidoEn, estado: premiumLocalRes.persistidoEn === "telefono" ? premiumLocalRes.estado : undefined } }
      : accionesResultado,
    debug: {
      llm: debugLLM,
      contexto: mensajeContexto.content,
      onboarding: { siguiente: onboarding.siguiente, faltantes: onboarding.faltantes, hechosTurno: onboarding.hechosTurno },
      historialEnviado: mensajes.length - 2,
      memoriaEscritura,
      memoriaHechos,
      memoriaSistema: memoriaSistema ? Object.keys(memoriaSistema) : null,
      memoriaUsuario: memoriaUsuario ? true : false
    }
  };
}

export default orquestador;
