import { normalizarUbicacion } from "./orquestadorChat.js";
import gestorDeAlarmas from "../modulos/gestorDeAlarmas.js";
import protocoloDespertador from "../modulos/protocoloDespertador.js";
import { evaluarIniciativa, interesesDe, palabras, CATEGORIAS, POLITICA_INICIATIVA } from "../comportamiento/iniciativaConversacional.js";
import { validarConfiguracion, instanteLocal } from "../modulos/interaccion/perfilRitmoUsuario.js";
import noticiasApi from "../api/noticias.js";
import dolphinClient from "../llm/dolphinClient.js";
import memoriaConversacional from "../memoria/memoriaConversacional.js";
import historialConversacion from "../memoria/historialConversacion.js";
import datosUsuario from "../memoria/datosUsuario.js";
import contextoLLM from "./contextoLLM.js";
import estadoEmocional from "../memoria/estadoEmocional.js";
import continuidad from "../memoria/continuidad.js";
import historialIniciativas from "../memoria/historialIniciativas.js";
import relojApi from "../api/reloj.js";
import calendarioApi from "../api/calendario.js";
import obtenerClima from "../api/clima.js";
import tendenciasApi from "../api/tendencias.js";
import crypto from "node:crypto";
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

// Genera el mensaje de iniciativa con el LLM usando solo contexto factual
// (gustos, noticias, clima, agenda, motivo del evento). Sin plantillas.
export async function generarIniciativaLLM(iniciativa, memoriaLocal = {}, userId = null) {
  const memoria = userId ? memoriaConversacional.obtener(userId) : {};
  const herramientas = await contextoLLM.obtenerHerramientas(userId, { memoria });
  const extras = [
    ...(memoriaLocal.persistentMemories || []), ...(memoriaLocal.importantMemories || [])
  ].map(m => m?.text).filter(Boolean);
  const contexto = contextoLLM.construirMensajeContexto({
    herramientas,
    memoria: { ...memoria, hechos: [...(memoria.hechos || []), ...extras] },
    datosPerfil: userId ? datosUsuario.obtener(userId) : null,
    characterName: userId ? datosUsuario.obtener(userId)?.configuracion?.nombrePersonaje || null : null,
    app: contextoLLM.funcionesApp(userId),
    estadoEmocional: userId ? estadoEmocional.resumen(userId) : null,
    pendientes: userId ? continuidad.pendientesVigentes(userId) : []
  });
  const historial = (memoriaLocal.recentConversation || []).length
    ? memoriaLocal.recentConversation.map(i => ({ tipo: i.role === "assistant" ? "asistente" : "usuario", mensaje: i.text }))
    : (userId ? historialConversacion.obtenerHistorial(userId, 20) : []);
  // Los datos de la iniciativa van en un mensaje de sistema FINAL (después del historial):
  // así el turno a generar es el de la app, aunque el último mensaje del historial sea del asistente.
  const solicitudIniciativa = {
    role: "system",
    content: [
      "Tipo de solicitud: mensaje de INICIATIVA (la app inicia la conversación; el usuario no escribió ahora; se envía como notificación, máximo 240 caracteres).",
      `Fuente elegida: ${iniciativa.contexto?.fuenteServidor || (iniciativa.fuente === "calendario" ? "recordatorio" : iniciativa.categoria.toLowerCase())}`,
      `Motivo de la iniciativa: ${iniciativa.motivo} (categoría ${iniciativa.categoria})`,
      `Dato del evento: ${iniciativa.contexto?.evidencia || iniciativa.contexto?.descripcion || ""}`,
      ...(iniciativa.contexto?.relacion?.length ? [`Relación del recuerdo con el usuario: ${iniciativa.contexto.relacion.join(", ")}`] : []),
      ...(iniciativa.contexto?.enlace ? [`Enlace: ${iniciativa.contexto.enlace}`] : [])
    ].join("\n")
  };
  const r = await dolphinClient.chat([contexto, ...contextoLLM.historialAMensajes(historial, Number(process.env.ME2_INITIATIVE_HISTORY || 4)), solicitudIniciativa], { maxTokens: 160 });
  return { ...r, contexto: contexto.content };
}

function fuenteDe(iniciativa) {
  if (iniciativa.contexto?.fuenteServidor) return iniciativa.contexto.fuenteServidor;
  if (iniciativa.fuente === "calendario") return "recordatorio";
  if (iniciativa.categoria === "NOTICIA") return "noticia";
  return iniciativa.categoria.toLowerCase();
}

const DIA_MS = 24 * 3600e3;

function zonaDe(userId, memoria) {
  return contextoLLM.resolverUbicacion(memoria)?.zonaHoraria
    || datosUsuario.obtener(userId)?.configuracion?.zonaHoraria
    || process.env.ME2_TZ || "America/Argentina/Buenos_Aires";
}

function horaLocal(ahora, zona) {
  const p = Object.fromEntries(new Intl.DateTimeFormat("en-GB", { timeZone: zona, hour: "2-digit", minute: "2-digit", hourCycle: "h23" })
    .formatToParts(new Date(ahora)).map(x => [x.type, x.value]));
  return Number(p.hour) * 60 + Number(p.minute);
}

// Recuerdo relevante: SOLO hechos guardados en memoria, ordenados por relación con gustos,
// temas pendientes y fecha. Sin memorias guardadas, la fuente no es elegible.
export function recuerdoRelevante(userId, memoria, ahora = Date.now()) {
  const hechos = (memoria?.hechos || []).filter(h => typeof h === "string" && h.trim());
  if (!hechos.length) return null;
  const usadas = userId ? historialIniciativas.referenciasUsadas(userId, "recuerdo_relevante") : new Set();
  const gustos = new Set(palabras((memoria.gustos || []).join(" ")));
  const pendientes = new Set(palabras((userId ? continuidad.pendientesVigentes(userId, ahora) : []).map(p => p.texto).join(" ")));
  const ranked = hechos.map((hecho, i) => {
    const fecha = hecho.match(/\(dicho el (\d{4}-\d{2}-\d{2})\)/)?.[1];
    const ts = Math.min(fecha ? Date.parse(`${fecha}T00:00:00-03:00`) : ahora - 60000, ahora - 1000);
    const p = palabras(hecho.replace(/\(dicho el [^)]*\)/, ""));
    const conGustos = p.filter(x => gustos.has(x)).length;
    const conPendientes = p.filter(x => pendientes.has(x)).length;
    const dias = (ahora - ts) / DIA_MS;
    const porFecha = dias <= 1 ? 2 : dias <= 7 ? 1 : 0;
    const referencia = `recuerdo:${crypto.createHash("sha256").update(hecho).digest("hex").slice(0, 16)}`;
    const relacion = [conGustos && "gustos", conPendientes && "pendientes", porFecha && "fecha_reciente"].filter(Boolean);
    return { hecho, ts, referencia, relacion, puntaje: conGustos * 3 + conPendientes * 3 + porFecha, i };
  }).filter(r => !usadas.has(r.referencia) && r.ts + POLITICA_INICIATIVA.vigenciaContextoMs > ahora)
    .sort((a, b) => b.puntaje - a.puntaje || b.i - a.i);
  return ranked[0] || null;
}

// Sueño: ventana de mañana, ≥ cooldownDias desde el último uso, nunca dos veces seguidas,
// y si hay una alarma matinal activa, solo después de que sonó.
export function suenoElegible(userId, memoria, ahora = Date.now(), alarmasUsuario = null) {
  const cfg = POLITICA_INICIATIVA.sueno;
  const zona = zonaDe(userId, memoria);
  const min = horaLocal(ahora, zona);
  const dia = new Date(ahora).toLocaleDateString("en-CA", { timeZone: zona });
  if (min < cfg.ventanaDesde * 60 || min >= cfg.ventanaHasta * 60) return { elegible: false, motivo: "fuera_de_ventana", dia };
  const ultimo = userId ? historialIniciativas.ultimoUso(userId, "sueno") : null;
  if (ultimo && ahora - ultimo < cfg.cooldownDias * DIA_MS) return { elegible: false, motivo: "cooldown_dias", dia };
  if (userId && historialIniciativas.ultimas(userId, 1)[0]?.fuente === "sueno") return { elegible: false, motivo: "repetida", dia };
  const alarmas = alarmasUsuario || (userId ? gestorDeAlarmas.obtenerAlarmasPorUsuario(userId) : []);
  const matinales = alarmas.map(a => String(a.hora || "").match(/^(\d{1,2}):(\d{2})/)).filter(Boolean)
    .map(m => Number(m[1]) * 60 + Number(m[2])).filter(m => m >= 4 * 60 && m < cfg.ventanaHasta * 60);
  const pendiente = matinales.filter(m => m > min);
  if (pendiente.length && !matinales.some(m => m <= min)) return { elegible: false, motivo: "antes_de_alarma", dia };
  const sono = matinales.filter(m => m <= min).sort((a, b) => b - a)[0];
  const hhmm = m => `${String(Math.floor(m / 60)).padStart(2, "0")}:${String(m % 60).padStart(2, "0")}`;
  return {
    elegible: true, dia,
    evidencia: `Mañana del ${dia}, ${hhmm(min)} hora local.${sono != null ? ` La alarma de las ${hhmm(sono)} ya sonó.` : ""} Tema: cómo durmió el usuario anoche.`
  };
}

function fuenteEvento(e) {
  if (e.contexto?.fuenteServidor) return e.contexto.fuenteServidor;
  if (e.fuente === "calendario") return "recordatorio";
  if (e.categoria === "NOTICIA") return "noticia";
  return String(e.categoria || "").toLowerCase();
}

// Rotación: la última fuente usada no se repite (salvo recordatorios agendados por el usuario);
// las usadas antes reciben una penalización de prioridad decreciente.
export function aplicarRotacion(userId, eventos) {
  const recientes = userId ? historialIniciativas.ultimas(userId, 3).map(u => u.fuente) : [];
  if (!recientes.length) return eventos;
  return eventos.filter(e => fuenteEvento(e) !== recientes[0] || fuenteEvento(e) === "recordatorio").map(e => {
    const idx = recientes.indexOf(fuenteEvento(e));
    if (idx <= 0) return e;
    const pen = Math.round(POLITICA_INICIATIVA.penalizacionRotacion / idx);
    return { ...e, contexto: { ...e.contexto, penalizacionRotacion: pen } };
  });
}

function momentoLocal(ahora, zona) {
  const p = Object.fromEntries(new Intl.DateTimeFormat("en-US", { timeZone: zona, weekday: "short", hour: "2-digit", minute: "2-digit", hourCycle: "h23" })
    .formatToParts(new Date(ahora)).map(x => [x.type, x.value]));
  const dow = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"].indexOf(p.weekday);
  return { dow, min: Number(p.hour) * 60 + Number(p.minute), dia: new Date(ahora).toLocaleDateString("en-CA", { timeZone: zona }) };
}

function cooldownCompania(userId, fuente, ahora) {
  const ultimo = userId ? historialIniciativas.ultimoUso(userId, fuente) : null;
  return !ultimo || ahora - ultimo >= POLITICA_INICIATIVA.compania[fuente].cooldownDias * DIA_MS;
}

const RE_JUEGOS = /\b(juego|juegos|videojuego|videojuegos|gaming|gamer|play ?station|ps[45]|xbox|nintendo|switch|steam|pc gamer|fifa|fc ?2\d)\b/i;
const DIAS_ES = ["domingo", "lunes", "martes", "miércoles", "jueves", "viernes", "sábado"];

// Temas de compañía: cada uno con ventana horaria, cooldown en días y datos reales (o no es elegible).
export async function fuentesCompania(userId, memoria, ahora = Date.now(), { clima = null, ubicacion = null, tendencias = null, calendario = null, fallos = [] } = {}) {
  const zona = zonaDe(userId, memoria);
  const { dow, min, dia } = momentoLocal(ahora, zona);
  const finde = dow === 0 || dow === 6;
  const tarde = min >= 18 * 60 && min < 23 * 60;
  const gustos = (memoria?.gustos || []).filter(Boolean);
  const momento = `${DIAS_ES[dow]} ${dia}, ${String(Math.floor(min / 60)).padStart(2, "0")}:${String(min % 60).padStart(2, "0")} hora local`;
  const climaTxt = clima && ubicacion ? ` Clima en ${ubicacion.ciudad}: ${clima.temperatura}°C, ${clima.descripcion || ""}.` : " Clima: sin ubicación conocida.";
  const fuentesT = tendencias || tendenciasApi;
  const out = [];
  const ev = (fuente, motivo, evidencia, horas = 4) => ({
    categoria: "CONVERSACION", fuente, referenciaEvento: `${fuente}:${dia}`, motivo,
    timestamp: ahora - 1000, expiresAt: ahora + horas * 3600e3, contexto: { evidencia, fuenteServidor: fuente }
  });
  // (a) salida recreativa en pareja: tardes/noches o fines de semana de día.
  if ((tarde || (finde && min >= 10 * 60 && min < 21 * 60)) && cooldownCompania(userId, "salida_pareja", ahora)) {
    const lluvia = /lluv|tormenta|chaparr|llovizna|nieve/i.test(clima?.descripcion || "");
    out.push(ev("salida_pareja", "Tema alternativo: proponer una salida recreativa en pareja",
      `${momento}${finde ? " (fin de semana)" : ""}.${climaTxt}${lluvia ? " Hay lluvia: mejor un plan bajo techo." : ""}${gustos.length ? ` Gustos del usuario: ${gustos.slice(0, 5).join(", ")}.` : ""}`));
  }
  // (b) juego o pasatiempo: tendencia real (Steam) si al usuario le gustan los juegos; si no, un gusto suyo.
  if ((tarde || finde) && gustos.length && cooldownCompania(userId, "juego_pasatiempo", ahora)) {
    let evidencia = null;
    if (gustos.some(g => RE_JUEGOS.test(g))) {
      try {
        const juegos = await fuentesT.juegosEnTendencia();
        if (juegos.length) evidencia = `Juegos más vendidos ahora en Steam (dato real): ${juegos.slice(0, 3).map(j => j.nombre).join(", ")}. Gustos del usuario: ${gustos.slice(0, 5).join(", ")}.`;
      } catch { fallos.push("juegos_no_disponible"); }
    }
    if (!evidencia) evidencia = `Sin datos de tendencias; pasatiempos según gustos guardados del usuario: ${gustos.slice(0, 5).join(", ")}.`;
    out.push(ev("juego_pasatiempo", "Tema alternativo: proponer un juego o pasatiempo para hacer juntos", `${momento}. ${evidencia}`));
  }
  // (c) película juntos: noche o fin de semana, según gustos; ranking real de iTunes si está disponible.
  if ((tarde || finde) && gustos.length && cooldownCompania(userId, "pelicula_juntos", ahora)) {
    let evidencia = `Gustos del usuario: ${gustos.slice(0, 5).join(", ")}. Sin ranking de películas disponible.`;
    try {
      const pelis = await fuentesT.peliculasEnTendencia();
      const gp = new Set(palabras(gustos.join(" ")));
      const afines = pelis.filter(p => palabras(`${p.genero || ""} ${p.titulo}`).some(w => gp.has(w)));
      const lista = (afines.length ? afines : pelis).slice(0, 3).map(p => `${p.titulo}${p.genero ? ` (${p.genero})` : ""}`);
      if (lista.length) evidencia = `Películas en el ranking de iTunes Argentina (dato real)${afines.length ? " afines a sus gustos" : ""}: ${lista.join(", ")}. Gustos del usuario: ${gustos.slice(0, 5).join(", ")}.`;
    } catch { fallos.push("peliculas_no_disponible"); }
    out.push(ev("pelicula_juntos", "Tema alternativo: proponer ver una película juntos", `${momento}. ${evidencia}`));
  }
  // (d) planear una salida con anticipación: lunes a jueves, para el próximo fin de semana, sin pisar la agenda.
  if (dow >= 1 && dow <= 4 && min >= 10 * 60 && min < 21 * 60 && cooldownCompania(userId, "planear_salida", ahora)) {
    const sab = new Date(Date.parse(`${dia}T12:00:00Z`) + (6 - dow) * DIA_MS).toISOString().slice(0, 10);
    const dom = new Date(Date.parse(`${sab}T12:00:00Z`) + DIA_MS).toISOString().slice(0, 10);
    let agenda = [];
    let agendaOk = true;
    try {
      agenda = calendario ? await calendario(userId) : (await calendarioApi.proximosUnificados(userId, 50)).eventos;
    } catch { agendaOk = false; fallos.push("calendario_no_disponible"); }
    const ocupados = agenda.filter(e => e.fecha === sab || e.fecha === dom);
    const diasOcupados = new Set(ocupados.map(e => e.fecha));
    if (agendaOk && diasOcupados.size < 2) {
      out.push(ev("planear_salida", "Tema alternativo: planear con anticipación una salida para el próximo fin de semana",
        `${momento}. Próximo fin de semana: sábado ${sab} y domingo ${dom}. Agenda del usuario: ${ocupados.length ? ocupados.map(e => `${e.fecha} ${e.hora} ${e.descripcion} (evitar ese horario)`).join("; ") : "libre ambos días"}.${gustos.length ? ` Gustos del usuario: ${gustos.slice(0, 5).join(", ")}.` : ""}`, 8));
    }
  }
  return out;
}

export async function evaluarAutonomia(body, opciones = {}) {
  const solicitud = validarSolicitudIniciativa(body);
  const ahora = opciones.ahora ?? Date.now();
  const generar = opciones.generar || ((iniciativa, memoria) => generarIniciativaLLM(iniciativa, memoria, solicitud.userId));
  const configurado = opciones.llmConfigurado ?? dolphinClient.estaConfigurado();
  // Cliente con memoria local primaria (Android): sus gustos/ubicación viajan en memoriaLocal y se suman a lo del servidor.
  const memoriaGuardada = memoriaConversacional.obtener(solicitud.userId);
  const gustosLocales = Array.isArray(solicitud.memoriaLocal?.gustos)
    ? solicitud.memoriaLocal.gustos.filter(g => typeof g === "string" && g.trim().length >= 2 && g.length <= 60).map(g => g.trim().toLowerCase()).slice(-30)
    : [];
  const memoriaServidor = {
    ...memoriaGuardada,
    gustos: [...new Set([...(memoriaGuardada.gustos || []), ...gustosLocales])],
    ubicacion: memoriaGuardada.ubicacion || normalizarUbicacion(solicitud.memoriaLocal?.ubicacion)
  };
  if (!(solicitud.memoriaLocal.recentConversation || []).length) {
    solicitud.memoriaLocal = {
      ...solicitud.memoriaLocal,
      recentConversation: historialConversacion.obtenerHistorial(solicitud.userId, 40)
        .filter(m => m.mensaje && Number.isFinite(Number(m.timestamp)))
        .map(m => ({ role: m.tipo === "usuario" ? "user" : "assistant", text: String(m.mensaje), timestamp: Number(m.timestamp) }))
    };
  }
  const gustosServidor = memoriaServidor.gustos || [];
  // Gustos guardados en el servidor también cuentan como intereses
  if (gustosServidor.length) {
    solicitud.memoriaLocal = {
      ...solicitud.memoriaLocal,
      persistentMemories: [
        ...(solicitud.memoriaLocal.persistentMemories || []),
        { text: `Le gusta: ${gustosServidor.join(", ")}`, timestamp: ahora }
      ]
    };
  }
  // Última interacción conocida por el servidor (historial/reloj) para respetar el timing.
  const ultimoServidor = Math.max(
    ...historialConversacion.obtenerHistorial(solicitud.userId, 50).filter(m => m.tipo === "usuario").map(m => Number(m.timestamp) || 0), 0,
    (() => { const min = relojApi.tiempoDesdeUltimaInteraccion(solicitud.userId); return min == null ? 0 : Date.now() - min * 60000; })()
  );
  if (ultimoServidor > (solicitud.perfilRitmo.ultimaInteraccion || 0)) {
    solicitud.perfilRitmo = { ...solicitud.perfilRitmo, ultimaInteraccion: Math.min(ultimoServidor, ahora) };
  }
  const fallosFuentes = [];
  // Las marcas de fuente del servidor no se aceptan desde el cliente.
  const eventos = solicitud.eventos.map(e => {
    const { fuenteServidor, interesUsuario, ...ctx } = e.contexto || {};
    return { ...e, contexto: ctx };
  });
  let decision = evaluarIniciativa({ ...solicitud, ahora, eventos });
  const salir = motivoEspera => ({
    decision: "ESPERAR", motivoEspera, perfilRitmo: decision.perfilRitmo, fallosFuentes
  });
  if (["notificaciones_denegadas", "en_primer_plano", "descanso_probable", "conversacion_reciente",
    "alarma_nativa_prioritaria", "separacion_minima", "ignorada_repetida", "respuesta_negativa"].includes(decision.motivoEspera)) {
    return { ...decision, fallosFuentes };
  }
  if (!configurado) return salir("llm_no_configurado");

  // Fuente: recuerdo relevante (solo memorias guardadas; si no hay, no es elegible)
  const recuerdo = recuerdoRelevante(solicitud.userId, memoriaServidor, ahora);
  if (recuerdo) {
    eventos.push({
      categoria: "RECUERDO", fuente: "memoria", referenciaEvento: recuerdo.referencia,
      motivo: "Retomar algo que el usuario contó (memoria guardada)", timestamp: recuerdo.ts, expiresAt: recuerdo.ts + POLITICA_INICIATIVA.vigenciaContextoMs,
      contexto: { evidencia: recuerdo.hecho, fuenteServidor: "recuerdo_relevante", relacion: recuerdo.relacion }
    });
  }
  // Fuente: sueño (tema alternativo de mañana, con cooldown de varios días)
  const sueno = suenoElegible(solicitud.userId, memoriaServidor, ahora);
  if (sueno.elegible) {
    eventos.push({
      categoria: "CONVERSACION", fuente: "sueno", referenciaEvento: `sueno:${sueno.dia}`,
      motivo: "Tema alternativo de la mañana: preguntar cómo durmió el usuario", timestamp: ahora - 1000, expiresAt: ahora + 3 * 3600e3,
      contexto: { evidencia: sueno.evidencia, fuenteServidor: "sueno" }
    });
  } else if (opciones.debugFuentes) fallosFuentes.push(`sueno_no_elegible:${sueno.motivo}`);
  // Fuente: recordatorios del calendario (próximas 24 h)
  try {
    const { eventos: proximos } = opciones.calendario
      ? { eventos: await opciones.calendario(solicitud.userId) }
      : await calendarioApi.proximosUnificados(solicitud.userId, 5);
    for (const ev of proximos) {
      const t = Date.parse(`${ev.fecha}T${ev.hora}:00-03:00`);
      if (!Number.isFinite(t) || t - ahora > 24 * 3600e3) continue;
      eventos.push({
        categoria: "EVENTO", fuente: "calendario", referenciaEvento: `calendario:${ev.id}`,
        motivo: "Recordatorio de un evento próximo de la agenda del usuario", timestamp: ahora - 1000, expiresAt: t,
        contexto: { evidencia: `${ev.fecha} ${ev.hora} — ${ev.descripcion}`, programadoPorUsuario: true }
      });
    }
  } catch { fallosFuentes.push("calendario_no_disponible"); }
  // Fuente: clima (solo con ubicación conocida del usuario)
  let climaActual = null;
  const ubicacion = contextoLLM.resolverUbicacion(memoriaServidor);
  if (ubicacion && (opciones.climaConfigurado ?? true)) {
    try {
      const c = await (opciones.clima || obtenerClima)(ubicacion.lat, ubicacion.lon, { ciudad: ubicacion.ciudad, timeoutMs: 6000 });
      climaActual = c;
      const dia = new Date(ahora).toLocaleDateString("en-CA", { timeZone: ubicacion.zonaHoraria || "America/Argentina/Buenos_Aires" });
      eventos.push({
        categoria: "EVENTO", fuente: "clima", referenciaEvento: `clima:${ubicacion.ciudad}:${dia}`,
        motivo: "Reporte del clima de la ciudad del usuario", timestamp: ahora - 1000, expiresAt: ahora + 6 * 3600e3,
        contexto: { evidencia: `Clima en ${ubicacion.ciudad}: ${c.temperatura}°C, ${c.descripcion || ""}`.trim(), fuenteServidor: "clima" }
      });
    } catch { fallosFuentes.push("clima_no_disponible"); }
  }
  // Fuente: noticias filtradas por intereses
  const intereses = gustosServidor.length ? gustosServidor.slice(0, 5) : interesesDe(solicitud.memoriaLocal).slice(0, 5);
  if (intereses.length && (opciones.newsConfigurado ?? true)) {
    try {
      const consulta = intereses.map(g => (g.includes(" ") ? `"${g}"` : g));
      const noticias = await (opciones.noticias || noticiasApi.obtenerNoticias)("", consulta);
      for (const noticia of noticias) {
        const timestamp = Date.parse(noticia.fecha);
        if (!Number.isFinite(timestamp) || timestamp > ahora || timestamp < ahora - POLITICA_INICIATIVA.vigenciaNoticiaMs) continue;
        if (!noticia.link || !noticia.titulo) continue;
        const url = new URL(noticia.link);
        if (!["http:", "https:"].includes(url.protocol)) continue;
        const interes = gustosServidor.length ? contextoLLM.interesCoincidente(noticia.titulo, gustosServidor) : null;
        eventos.push({
          categoria: "NOTICIA", fuente: noticia.proveedor || "noticias", referenciaEvento: noticia.link,
          motivo: "Una noticia reciente coincide con intereses documentados del usuario",
          timestamp, expiresAt: timestamp + POLITICA_INICIATIVA.vigenciaNoticiaMs,
          contexto: { evidencia: `${noticia.titulo}. ${noticia.descripcion || ""}`, enlace: noticia.link, fecha: noticia.fecha, ...(interes ? { interesUsuario: interes } : {}) }
        });
      }
    } catch (error) {
      console.error("[iniciativa] Fuente noticias no disponible:", error.name);
      fallosFuentes.push("noticias_no_disponible");
    }
  }
  // Temas de compañía opcionales (salida, juego/pasatiempo, película, planear salida)
  if (opciones.companiaConfigurada ?? true) {
    eventos.push(...await fuentesCompania(solicitud.userId, memoriaServidor, ahora, {
      clima: climaActual, ubicacion, tendencias: opciones.tendencias, calendario: opciones.calendarioCompania, fallos: fallosFuentes
    }));
  }
  const rotados = aplicarRotacion(solicitud.userId, eventos);
  decision = evaluarIniciativa({ ...solicitud, ahora, eventos: rotados });
  if (decision.decision !== "INICIAR") return { ...decision, fallosFuentes };
  try {
    const resultado = await generar(decision.iniciativa, solicitud.memoriaLocal);
    if (!resultado?.used || typeof resultado.respuesta !== "string" || !resultado.respuesta.trim()) {
      return salir("llm_sin_contenido");
    }
    const finalAhora = opciones.ahora ?? Date.now();
    const vigente = evaluarIniciativa({ ...solicitud, ahora: finalAhora, eventos: rotados });
    if (vigente.decision !== "INICIAR") return { ...vigente, fallosFuentes };
    if (decision.iniciativa.expiresAt <= finalAhora || vigente.iniciativa.id !== decision.iniciativa.id) {
      return salir("contexto_vencido_durante_generacion");
    }
    const fuenteElegida = fuenteDe(decision.iniciativa);
    // Prefetch (entrega diferida offline): no se registra en la rotación hasta que realmente se entregue.
    if (!opciones.prefetch) {
      historialIniciativas.registrar(solicitud.userId, { fuente: fuenteElegida, referencia: decision.iniciativa.referenciaEvento }, finalAhora);
    }
    return {
      ...decision, perfilRitmo: vigente.perfilRitmo, fallosFuentes,
      fuenteElegida, fuentesRecientes: historialIniciativas.ultimas(solicitud.userId, 3).map(u => u.fuente),
      ...(process.env.NODE_ENV === "development" && resultado.contexto ? { debug: { contexto: resultado.contexto } } : {}),
      iniciativa: {
        ...decision.iniciativa, mensaje: resultado.respuesta.trim().slice(0, 240),
        ...(opciones.prefetch ? { diferida: true, entregarDesde: finalAhora } : {})
      }
    };
  } catch (error) {
    console.error("[iniciativa] Generacion no disponible:", error.name);
    return salir("llm_no_disponible");
  }
}

// Mismo plan que viaja en la acción crear_local del chat (ver protocoloDespertador.despachosAndroid).
function construirDespachosAndroid(alarma) {
  return protocoloDespertador.despachosAndroid(alarma);
}

export default {
 construirDespachosAndroid,
 evaluarAutonomia,
 generarIniciativaLLM
};
