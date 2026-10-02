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
import mercadoPagoApi from "../api/mercadoPago.js";
import selectorVideo from "../modulos/video/selectorVideo.js";
import preferenciaNombre from "../modulos/interaccion/preferenciaNombre.js";
import gestorDeAlarmas from "../modulos/gestorDeAlarmas.js";
import calendarioApi from "../api/calendario.js";
import { detectarAlarma } from "../modulos/detectorAlarmas.js";
import { detectarEvento } from "../modulos/detectorAgenda.js";
import contextoLLM from "./contextoLLM.js";
import perfilBasico from "../modulos/onboarding/perfilBasico.js";

const EXPRESION_NEUTRA = Object.freeze({ tono: "neutral", ritmo: "normal", microexpresion: "mirada_atenta", intensidad: "suave" });

function normalizarMemoriaLocal(memoriaLocal = {}) {
  if (!memoriaLocal || typeof memoriaLocal !== "object") return null;
  const recentConversation = Array.isArray(memoriaLocal.recentConversation)
    ? memoriaLocal.recentConversation
      .map(item => ({ tipo: item?.role === "assistant" ? "joi" : "user", mensaje: String(item?.text || "").trim() }))
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

  // Modo adulto: solo estado/gate (sin respuestas armadas)
  const premium = premiumManager.obtenerEstado(userId);
  let adultResult = { intercept: false, adult: null };
  try {
    adultResult = await adultMode.procesarEnChat(userId, mensajeUsuario, { premium, intentarCheckout: true });
  } catch (error) {
    console.error("Error en adultMode:", error.message);
  }
  let checkout = adultResult?.checkout || null;
  const extra = [];
  if (adultResult?.intercept) {
    extra.push(`Evento modo adulto: ${adultResult.video?.etiqueta || (adultResult.needsCheckout ? "premium_requerido" : "estado_actualizado")}`);
    if (adultResult.adult?.keyword && adultResult.video?.etiqueta === "keyword_asignada") {
      extra.push(`Palabra clave del modo adulto asignada al usuario: ${adultResult.adult.keyword}`);
    }
    if (adultResult.needsCheckout && userId !== "anonimo") {
      try {
        const link = await mercadoPagoApi.generarLinkPago(userId, "Modo Adulto");
        checkout = { initPoint: link?.init_point || link?.sandbox_init_point || null, preferenceId: link?.id || link?.preferenceId || null };
        premiumManager.registrarCheckout(userId, { preferenceId: checkout.preferenceId, feature: "Modo Adulto", initPoint: checkout.initPoint });
        if (checkout.initPoint) extra.push(`Link de pago Premium generado: ${checkout.initPoint}`);
      } catch {
        extra.push("Link de pago Premium: no disponible (Mercado Pago no configurado)");
      }
    }
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

  // [MEMORY] write-back de la respuesta del LLM
  if (respuesta && persistirEnServidor && userId !== "anonimo") {
    historialConversacion.registrarMensaje(userId, respuesta, "joi");
  }

  const video = selectorVideo.seleccionarVideo({ adultMode: adultResult?.adult }, EXPRESION_NEUTRA);

  return {
    respuesta,
    llmDisponible: Boolean(respuesta),
    expresion: EXPRESION_NEUTRA,
    video: adultResult?.video?.categoria && adultResult?.adult?.unlocked ? { ...video, ...adultResult.video } : video,
    premium,
    adultMode: adultResult?.adult || null,
    checkout,
    acciones: accionesResultado,
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
