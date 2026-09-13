import gestorDeAlarmas from "../modulos/gestorDeAlarmas.js";
import protocoloDespertador from "../modulos/protocoloDespertador.js";
import { evaluarIniciativa, interesesDe, CATEGORIAS, POLITICA_INICIATIVA } from "../comportamiento/iniciativaConversacional.js";
import { validarConfiguracion, instanteLocal } from "../modulos/interaccion/perfilRitmoUsuario.js";
import noticiasApi from "../api/noticias.js";
import veniceClient from "../llm/veniceClient.js";
import HttpError from "../utils/httpError.js";

function objeto(value, nombre) {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new HttpError(400, `${nombre} invalido`);
  return value;
}

function lista(value, maximo, nombre) {
  if (!Array.isArray(value) || value.length > maximo) throw new HttpError(400, `${nombre} invalido`);
  return value;
}

function texto(value, maximo, nombre) {
  if (typeof value !== "string" || !value.trim() || value.length > maximo) {
    throw new HttpError(400, `${nombre} invalido`);
  }
}

function fecha(value, nombre) {
  if (!Number.isSafeInteger(value) || value < 0 || value > 8640000000000000) {
    throw new HttpError(400, `${nombre} invalido`);
  }
}

export function validarSolicitudIniciativa(body) {
  objeto(body, "Solicitud");
  texto(body.userId, 160, "userId");
  const memoria = objeto(body.memoriaLocal, "memoriaLocal");
  for (const key of ["recentConversation", "persistentMemories", "importantMemories"]) {
    lista(memoria[key] || [], key === "recentConversation" ? 200 : 64, key).forEach(item => {
      objeto(item, key);
      texto(item.text, 12000, "Texto de memoria");
      fecha(item.timestamp, "Fecha de memoria");
      if (key === "recentConversation" && !["user", "assistant"].includes(item.role)) {
        throw new HttpError(400, "Rol de conversacion invalido");
      }
    });
  }
  const registro = lista(body.registro || [], 200, "registro");
  registro.forEach(item => {
    objeto(item, "Registro");
    texto(item.id, 200, "id");
    texto(item.fuente, 160, "fuente");
    texto(item.referenciaEvento, 16000, "referenciaEvento");
    if (!CATEGORIAS.includes(item.categoria) ||
        !["ENVIADA", "ABIERTA", "RESPONDIDA", "IGNORADA", "CANCELADA"].includes(item.estado) ||
        !Number.isFinite(item.prioridad)) throw new HttpError(400, "Registro de iniciativa invalido");
    fecha(item.timestamp, "timestamp");
    fecha(item.expiresAt, "expiresAt");
    if (item.tipoRespuesta != null && !["positiva", "neutral", "negativa"].includes(item.tipoRespuesta)) {
      throw new HttpError(400, "Tipo de respuesta invalido");
    }
    for (const campo of ["enviada", "entregada", "abierta", "respondida"]) {
      if (item[campo] != null) fecha(item[campo], campo);
    }
  });
  const perfil = objeto(body.perfilRitmo || {}, "perfilRitmo");
  if (perfil.ultimaInteraccion != null) fecha(perfil.ultimaInteraccion, "ultimaInteraccion");
  lista(perfil.observaciones || [], 512, "observaciones").forEach(ts => fecha(ts, "observacion"));
  try {
    texto(perfil.zonaHoraria || "UTC", 100, "zonaHoraria");
    instanteLocal(Date.now(), perfil.zonaHoraria || "UTC");
    if (perfil.configurado != null) validarConfiguracion(perfil.configurado);
  } catch {
    throw new HttpError(400, "Zona horaria o descanso invalido");
  }
  const disponibilidad = objeto(body.disponibilidad, "disponibilidad");
  if (typeof disponibilidad.enPrimerPlano !== "boolean" || typeof disponibilidad.notificacionesHabilitadas !== "boolean") {
    throw new HttpError(400, "Disponibilidad invalida");
  }
  const eventos = lista(body.eventos || [], 50, "eventos");
  eventos.forEach(item => {
    objeto(item, "Evento");
    if (!CATEGORIAS.includes(item.categoria)) throw new HttpError(400, "Categoria de evento invalida");
    texto(item.motivo, 500, "motivo");
    texto(item.fuente, 160, "fuente");
    if (item.id != null) texto(item.id, 200, "id de evento");
    if (item.referenciaEvento != null) texto(item.referenciaEvento, 2000, "referencia de evento");
    fecha(item.timestamp, "timestamp de evento");
    fecha(item.expiresAt, "expiresAt de evento");
    objeto(item.contexto, "contexto de evento");
    texto(item.contexto.evidencia || item.contexto.descripcion, 8000, "evidencia de evento");
    if (item.fuente === "continuidad") throw new HttpError(400, "Fuente reservada");
  });
  if (Buffer.byteLength(JSON.stringify(body)) > 250000) throw new HttpError(413, "Contexto de iniciativa demasiado grande");
  return { ...body, registro, perfilRitmo: perfil, eventos };
}

export async function evaluarAutonomia(body, opciones = {}) {
  const solicitud = validarSolicitudIniciativa(body);
  const ahora = opciones.ahora ?? Date.now();
  const generar = opciones.generar || ((iniciativa, memoria) => veniceClient.generarIniciativa(iniciativa, memoria));
  const configurado = opciones.llmConfigurado ?? veniceClient.estaConfigurado();
  const fallosFuentes = [];
  const eventos = [...solicitud.eventos];
  let decision = evaluarIniciativa({ ...solicitud, ahora, eventos });
  const salir = motivoEspera => ({
    decision: "ESPERAR", motivoEspera, perfilRitmo: decision.perfilRitmo, fallosFuentes
  });
  if (["notificaciones_denegadas", "en_primer_plano", "descanso_probable", "conversacion_reciente",
    "alarma_nativa_prioritaria", "separacion_minima", "ignorada_repetida", "respuesta_negativa"].includes(decision.motivoEspera)) {
    return { ...decision, fallosFuentes };
  }
  if (!configurado) return salir("llm_no_configurado");
  const intereses = interesesDe(solicitud.memoriaLocal).slice(0, 5);
  if (intereses.length && (opciones.newsConfigurado ?? Boolean(process.env.NEWS_API_KEY?.trim()))) {
    try {
      const noticias = await (opciones.noticias || noticiasApi.obtenerNoticias)("", intereses);
      for (const noticia of noticias) {
        const timestamp = Date.parse(noticia.fecha);
        if (!Number.isFinite(timestamp) || timestamp > ahora || timestamp < ahora - POLITICA_INICIATIVA.vigenciaNoticiaMs) continue;
        if (!noticia.link || !noticia.titulo) continue;
        const url = new URL(noticia.link);
        if (!["http:", "https:"].includes(url.protocol)) continue;
        eventos.push({
          categoria: "NOTICIA", fuente: "newsapi", referenciaEvento: noticia.link,
          motivo: "Una noticia reciente coincide con intereses documentados del usuario",
          timestamp, expiresAt: timestamp + POLITICA_INICIATIVA.vigenciaNoticiaMs,
          contexto: { evidencia: `${noticia.titulo}. ${noticia.descripcion || ""}`, enlace: noticia.link, fecha: noticia.fecha }
        });
      }
    } catch (error) {
      console.error("[iniciativa] Fuente noticias no disponible:", error.name);
      fallosFuentes.push("noticias_no_disponible");
    }
  }
  decision = evaluarIniciativa({ ...solicitud, ahora, eventos });
  if (decision.decision !== "INICIAR") return { ...decision, fallosFuentes };
  try {
    const resultado = await generar(decision.iniciativa, solicitud.memoriaLocal);
    if (!resultado?.used || typeof resultado.respuesta !== "string" || !resultado.respuesta.trim()) {
      return salir("llm_sin_contenido");
    }
    const finalAhora = opciones.ahora ?? Date.now();
    const vigente = evaluarIniciativa({ ...solicitud, ahora: finalAhora, eventos });
    if (vigente.decision !== "INICIAR") return { ...vigente, fallosFuentes };
    if (decision.iniciativa.expiresAt <= finalAhora || vigente.iniciativa.id !== decision.iniciativa.id) {
      return salir("contexto_vencido_durante_generacion");
    }
    return {
      ...decision, perfilRitmo: vigente.perfilRitmo, fallosFuentes,
      iniciativa: { ...decision.iniciativa, mensaje: resultado.respuesta.trim().slice(0, 240) }
    };
  } catch (error) {
    console.error("[iniciativa] Generacion no disponible:", error.name);
    return salir("llm_no_disponible");
  }
}

/**
* ORQUESTADOR DE NOTIFICACIONES JOI
* Ejecuta alarmas automáticamente cuando corresponde
* Sistema multiusuario centralizado
*/

function obtenerHoraActual() {
  const now = new Date();

  const horas = String(now.getHours()).padStart(2, "0");
  const minutos = String(now.getMinutes()).padStart(2, "0");

  return `${horas}:${minutos}`;
}

/**
* Revisión principal de alarmas
*/
async function tick() {
  const alarmas = gestorDeAlarmas.obtenerAlarmasActivas();

  if (!alarmas || alarmas.length === 0) return;

  const horaActual = obtenerHoraActual();

  const alarmasParaEjecutar = alarmas.filter(
    (alarma) =>
      alarma.estado === "ACTIVE" &&
      alarma.hora === horaActual
  );

  if (alarmasParaEjecutar.length === 0) return;

  for (const alarma of alarmasParaEjecutar) {
    try {
      console.log(` Alarma activada para usuario: ${alarma.userID}`);

      const resultado = await protocoloDespertador.ejecutarAlarma(
        alarma.userID,
        false // respuestaUsuario se maneja desde cliente en versión real
      );

      // ✔ Usuario respondió
      if (resultado?.estado === "respondio") {
        gestorDeAlarmas.cerrarAlarma(alarma.userID);
        continue;
      }

      //  Llegó a alarma sonora final
      if (resultado?.estado === "alarmaSonora") {
        console.log(` Alarma sonora activa: ${alarma.userID}`);
      }

    } catch (error) {
      console.error(` Error en alarma de ${alarma.userID}`, error);
    }
  }
}

function construirDespachosAndroid(alarma) {
 if (!alarma) return [];
 let offsetMs = 0;
 return protocoloDespertador.obtenerDefinicionStages().map((stage) => {
   const despacho = {
     stage: stage.stage,
     offsetFromAlarmMs: offsetMs,
     channelId: stage.channelId,
     notificationType: stage.notificationType,
     vibration: stage.vibration,
     sound: stage.sound,
     titulo:
       stage.stage >= 3
         ? "Hora de despertar"
         : alarma.titulo || "Hora de despertar",
     mensaje:
       stage.mensajes[0] ||
       alarma.mensaje ||
       "ME2 registró tu protocolo de despertar."
   };
   offsetMs += stage.delayToNextStageMs;
   return despacho;
 });
}

function iniciar(intervalMs = 60000) {
 return setInterval(tick, intervalMs);
}

/**
* LOOP DEL ORQUESTADOR
* Ejecuta revisión cada 60 segundos
*/
export default {
 tick,
 iniciar,
 construirDespachosAndroid,
 evaluarAutonomia
};
