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
import protocoloDespertador from "../modulos/protocoloDespertador.js";
import calendarioApi from "../api/calendario.js";
import { detectarAlarma } from "../modulos/detectorAlarmas.js";
import { detectarEvento, detectarEliminacion, detectarConsulta, lineaEvento } from "../modulos/detectorAgenda.js";
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

// [AGENDA] Calendario propio de ME2. En Android vive en el teléfono (fuente de verdad, funciona sin red): el
// orquestador decide crear/borrar y devuelve la acción estructurada; el teléfono la aplica y la guarda. Clientes sin
// memoria local (web) siguen usando el JSON del servidor. Consultas: los eventos van como dato al contexto del LLM.
function accionesAgenda(userId, mensaje, enTelefono, zonaHoraria, eventosTelefono) {
  const ahora = new Date();
  const vigente = () => enTelefono
    ? calendarioApi.agendaCombinada(userId, eventosTelefono || [], { ahora: ahora.getTime() })
    : (userId === "anonimo" ? [] : calendarioApi.obtenerEventosProximos(userId, ahora.getTime()));
  const acciones = [];
  let evento = null;
  const borrar = detectarEliminacion(mensaje, vigente(), ahora, { zonaHoraria });
  if (borrar) {
    if (borrar.motivo === "borrado") {
      // Copias heredadas en el servidor (versiones anteriores): se borran acá; las del teléfono las borra el teléfono.
      borrar.eventos.filter(e => !enTelefono || e.origen === "servidor").forEach(e => calendarioApi.eliminarEvento(userId, e.id));
      evento = { exito: true, local: enTelefono, accion: enTelefono ? "eliminar_local" : "eliminado", ids: borrar.ids };
      acciones.push(`Evento(s) BORRADO(S) del calendario: ${borrar.eventos.map(lineaEvento).join("; ")}.`);
    } else if (borrar.motivo === "ambiguo") {
      evento = { exito: false, accion: "eliminar_ambiguo", ids: [] };
      acciones.push(["El usuario pidió borrar un evento pero coinciden varios; NO se borró ninguno (hay que preguntarle cuál):",
        ...borrar.eventos.slice(0, 8).map(e => `    ◦ ${lineaEvento(e)}`)].join("\n"));
    } else {
      evento = { exito: false, accion: "eliminar_sin_coincidencias", ids: [] };
      acciones.push("El usuario pidió borrar un evento pero no hay ninguno que coincida en su agenda; no se borró nada.");
    }
    return { acciones, evento, borrados: evento.accion === "eliminar_local" || evento.accion === "eliminado" ? borrar.ids : [], tratado: true };
  }
  const consulta = detectarConsulta(mensaje, ahora, { zonaHoraria });
  if (consulta) {
    const enRango = vigente().filter(e => e.fecha >= consulta.desde && (!consulta.hasta || e.fecha <= consulta.hasta));
    const rango = consulta.hasta ? (consulta.hasta === consulta.desde ? consulta.desde : `${consulta.desde} a ${consulta.hasta}`) : `desde ${consulta.desde}`;
    acciones.push(enRango.length
      ? [`Agenda del usuario consultada (${rango}):`, ...enRango.slice(0, 15).map(e => `    ◦ ${lineaEvento(e)}`)].join("\n")
      : `Agenda del usuario consultada (${rango}): no tiene eventos en ese período.`);
  }
  return { acciones, evento, borrados: [], tratado: false };
}

// Acciones deterministas del orquestador (el LLM solo las confirma con su voz).
function ejecutarAcciones(userId, mensaje, persistir, zonaHoraria = null, eventosTelefono = null) {
  const acciones = [];
  const resultado = { alarma: null, evento: null };
  if (!userId) return { acciones, resultado, agenda: null };
  const enTelefono = persistir === false;
  const agendaTurno = (creado = null, borrados = []) => enTelefono
    ? calendarioApi.agendaCombinada(userId, [...(eventosTelefono || []).filter(e => !borrados.includes(e.id)), ...(creado ? [creado] : [])], { limite: 10 })
    : null;

  const agenda = accionesAgenda(userId, mensaje, enTelefono, zonaHoraria, eventosTelefono);
  acciones.push(...agenda.acciones);
  if (agenda.tratado) {
    resultado.evento = agenda.evento;
    return { acciones, resultado, agenda: agenda.evento?.accion === "eliminado" ? null : agendaTurno(null, agenda.borrados) };
  }
  // "agendá un evento..." / "poné en el calendario..." es agenda aunque use verbos de alarma ("programá").
  const pedidoEventoExplicito = /\b(evento|agenda|calendario)\b/i.test(mensaje) ? detectarEvento(mensaje, new Date(), { zonaHoraria }) : null;
  const pedidoAlarma = pedidoEventoExplicito ? null : detectarAlarma(mensaje, { zonaHoraria });
  // Diagnóstico (sin el texto del usuario): qué decidió el detector de alarmas en este turno.
  if (pedidoAlarma) console.log(`[alarma] ${pedidoAlarma.accion} hora=${pedidoAlarma.hora || "no_entendida"} destino=${persistir === false ? "telefono" : "servidor"} tz=${zonaHoraria || "default"}`);
  // Cliente con memoria local primaria (Android): la alarma vive en el teléfono (AlarmManager, funciona offline y
  // sobrevive reinicios); el teléfono la sincroniza con el servidor cuando hay conexión.
  if (persistir === false && pedidoAlarma?.accion === "crear" && pedidoAlarma.hora) {
    // Con el plan de los 3 intentos del orquestador: los textos de cada aviso salen de acá (no del teléfono).
    resultado.alarma = {
      accion: "crear_local", hora: pedidoAlarma.hora, titulo: pedidoAlarma.titulo || null,
      // Instante absoluto calculado con la hora del servidor (el teléfono lo arma con su reloj ME2, no con su hora).
      disparoEpochMs: pedidoAlarma.epochMs ?? null, servidorAhoraMs: Date.now(),
      dispatchPlan: protocoloDespertador.despachosAndroid({ hora: pedidoAlarma.hora, titulo: pedidoAlarma.titulo || null })
    };
    acciones.push(`Alarma CREADA en el teléfono para las ${pedidoAlarma.hora}${pedidoAlarma.titulo ? ` (${pedidoAlarma.titulo})` : ""}; suena aunque no haya conexión.`);
    return { acciones, resultado, agenda: agendaTurno() };
  }
  if (persistir === false && pedidoAlarma?.accion === "cancelar") {
    resultado.alarma = { accion: "cancelar_local", hora: pedidoAlarma.hora || null };
    acciones.push(`Alarma CANCELADA en el teléfono${pedidoAlarma.hora ? ` (${pedidoAlarma.hora})` : " (la próxima)"}.`);
    return { acciones, resultado, agenda: agendaTurno() };
  }
  // Teléfono (Android con memoria local primaria, o anónimo dev/demo): el evento se guarda en el calendario del
  // teléfono (acción crear_local) y el teléfono arma su aviso local; nada se guarda en el servidor.
  if (enTelefono) {
    if (pedidoAlarma?.accion === "crear" && !pedidoAlarma.hora) {
      acciones.push("El usuario pidió una alarma pero no se entendió la hora; no se creó ninguna alarma.");
    }
    const pedido = pedidoEventoExplicito || (!pedidoAlarma ? detectarEvento(mensaje, new Date(), { zonaHoraria }) : null);
    let creado = null;
    if (pedido) {
      creado = {
        id: calendarioApi.nuevoIdEvento(), fecha: pedido.fecha, hora: pedido.hora, fin: pedido.fin || null,
        descripcion: pedido.descripcion, notas: null, creadoPor: "chat", creadoEn: new Date().toISOString()
      };
      resultado.evento = { exito: true, local: true, accion: "crear_local", evento: creado };
      acciones.push(`Evento AGENDADO en el calendario del teléfono: ${lineaEvento(creado)}${pedido.horaIndicada ? "" : " (el usuario no dijo la hora; quedó a las 09:00)"}; avisa a esa hora aunque no haya conexión.`);
    }
    return { acciones, resultado, agenda: agendaTurno(creado) };
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

  const pedidoEvento = pedidoEventoExplicito || (!pedidoAlarma ? detectarEvento(mensaje, new Date(), { zonaHoraria }) : null);
  if (pedidoEvento) {
    const r = calendarioApi.agregarEvento(userId, pedidoEvento);
    resultado.evento = r;
    acciones.push(r.exito
      ? `Evento AGENDADO: ${lineaEvento(r.evento)}`
      : `No se pudo agendar el evento: ${r.mensaje}`);
  }
  return { acciones, resultado, agenda: null };
}

function memoriaVacia(userId) {
  return { userId, nombre: null, ciudad: null, ubicacion: null, gustos: [], disgustos: [], hechos: [], onboarding: { pendiente: null, horaConfirmada: null }, actualizado: null };
}

const VIDEO_BASE = Object.freeze({
  categoria: "calida", etiqueta: "calida", assetPath: "ME2_MEDIA/01_LOOP_NEUTRAL/NEUTRAL_005.mp4",
  assetName: "NEUTRAL_005.mp4", mediaId: "NEUTRAL_005", loop: true
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
  // Calendario del teléfono (dato saneado de memoriaLocal.calendario): agenda vigente para borrar/consultar.
  const eventosTelefono = memoriaLocal ? calendarioApi.normalizarEventosTelefono(contexto.memoriaLocal?.calendario) : null;
  const { acciones, resultado: accionesEjecutadas, agenda: agendaTurno } = ejecutarAcciones(userId, mensajeUsuario, persistirEnServidor && !esAnonimo, contexto.zonaHoraria || null, eventosTelefono);
  // Verificación de edad (Premium exige 18+): la cuenta no tiene fecha → el teléfono pide en ese momento el permiso de
  // fecha de nacimiento de Google (autorización incremental; el login solo pide la cuenta básica).
  const accionesResultado = flujo.evento === "edad_sin_dato" ? { ...(accionesEjecutadas || {}), verificarEdad: true } : accionesEjecutadas;

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
  // Respuesta a "¿cómo dormiste?" (fuente sueño de la iniciativa): queda guardada en el estado del usuario como
  // recuerdo real (en el teléfono para Android; en el servidor para clientes sin memoria local).
  const esSueno = ini && (ini.fuente === "sueno" || ini.contexto?.fuenteServidor === "sueno");
  if (esSueno && String(mensajeUsuario || "").trim()) {
    const fecha = new Date(contexto.timestamp || Date.now()).toLocaleDateString("en-CA", { timeZone: process.env.ME2_TZ || "America/Argentina/Buenos_Aires" });
    const nota = `Cómo durmió (${fecha}): ${guardia.datoDeUsuario(mensajeUsuario, 200)}`;
    if (!persistirEnServidor) {
      memoriaLocalDelta = { gustos: [], disgustos: [], ubicacion: null, ...(memoriaLocalDelta || {}), notas: [{ categoria: "sueno", texto: nota }] };
    } else if (userId !== "anonimo") {
      const actual = memoriaConversacional.obtener(userId);
      memoriaConversacional.actualizarCampos(userId, { hechos: [...(actual.hechos || []), `${nota} (dicho el ${fecha})`].slice(-50) });
    }
  }

  // [ALARMA] el mensaje del usuario responde una alarma que sonó en el teléfono (protocolo despertador): la respuesta
  // la redacta Dolphin en este turno (sin texto fijo); el clima, si hay ubicación, ya viene en las herramientas.
  const alarmaResp = contexto.alarmaRespondida && typeof contexto.alarmaRespondida === "object" ? contexto.alarmaRespondida : null;
  const alarmaHora = alarmaResp && /^\d{2}:\d{2}$/.test(String(alarmaResp.hora || "")) ? alarmaResp.hora : null;
  if (alarmaHora) {
    const titulo = guardia.datoDeUsuario(alarmaResp.titulo || "", 80);
    const intento = [1, 2, 3].includes(Number(alarmaResp.intento)) ? Number(alarmaResp.intento) : null;
    extra.push(`Acción del sistema: con este mensaje el usuario respondió la alarma de las ${alarmaHora}${titulo ? ` (${titulo})` : ""}${intento ? ` en el aviso ${intento} de 3` : ""}; la alarma quedó apagada.`);
  }

  // [GUARDIA] anti prompt-injection: el texto del usuario no cambia estado ni dispara acciones (ya decididas arriba).
  const inyeccion = guardia.detectarIntentoInyeccion(mensajeUsuario);
  if (inyeccion.sospechoso) extra.push(guardia.AVISO_INYECCION);

  // [CONTEXT] herramientas + memoria + funciones de la app
  const herramientas = await contextoLLM.obtenerHerramientas(userId, {
    lat: contexto.lat, lon: contexto.lon, ciudad: contexto.ciudad, zonaHoraria: contexto.zonaHoraria, memoria: memoriaHechos,
    agenda: agendaTurno
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
    memoriaLocalDelta: memoriaLocalDelta && (memoriaLocalDelta.gustos.length || memoriaLocalDelta.disgustos.length || memoriaLocalDelta.ubicacion || memoriaLocalDelta.notas?.length)
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
