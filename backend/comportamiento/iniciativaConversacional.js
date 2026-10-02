// iniciativaConversacional.js
// Política que regula cuándo ME2 toma la iniciativa (solo decisión y datos; el texto lo escribe el LLM).

import crypto from "node:crypto";
import ritmoDeInteraccion from "../modulos/interaccion/ritmoDeInteraccion.js";
import { evaluarPerfilRitmo } from "../modulos/interaccion/perfilRitmoUsuario.js";
import { obtenerContinuidad } from "../memoria/continuidad.js";

const HORA = 60 * 60 * 1000;
export const POLITICA_INICIATIVA = Object.freeze({
  cooldownMs: 4 * HORA, pausaConversacionMs: HORA, maxDiarias: 3,
  cooldownCategoriaMs: 24 * HORA, esperaEspontaneaMs: 24 * HORA,
  separacionMinimaMs: 15 * 60 * 1000,
  expiracionMs: 24 * HORA, vigenciaContextoMs: 7 * 24 * HORA,
  vigenciaNoticiaMs: 24 * HORA, ventanaAprendizajeMs: 30 * 24 * HORA,
  minDiasAprendizaje: 7, minHorasObservadasPorDia: 4, horasDescansoAprendido: 8,
  descansoProvisional: Object.freeze({ dormir: "22:00", despertar: "09:00" }),
  prioridades: Object.freeze({
    ALARMA: 100, EVENTO: 90, TRANSITO: 80, TRANSPORTE: 80, CLIMA: 75, NOTICIA: 70,
    EVENTO_SOCIAL: 60, CONVERSACION: 50, RECUERDO: 40, PLANEAR_SALIDA: 36, SUENO: 35, SALIDA_PAREJA: 34,
    PELICULA_JUNTOS: 33, JUEGO_PASATIEMPO: 33, CURIOSIDAD: 30, SOCIAL: 20, EVENTO_INTERNO: 30
  }),
  // Tema alternativo "cómo dormiste": solo de mañana, peso bajo, no diario.
  sueno: Object.freeze({ ventanaDesde: 6, ventanaHasta: 12, cooldownDias: 3 }),
  // Temas de compañía opcionales: días mínimos entre usos de cada fuente.
  compania: Object.freeze({
    salida_pareja: Object.freeze({ cooldownDias: 3 }), juego_pasatiempo: Object.freeze({ cooldownDias: 4 }),
    pelicula_juntos: Object.freeze({ cooldownDias: 3 }), planear_salida: Object.freeze({ cooldownDias: 5 })
  }),
  // Penalización de prioridad por fuente usada recientemente (rotación).
  penalizacionRotacion: 6
});

export const CATEGORIAS = Object.freeze([
  "CONVERSACION", "RECUERDO", "CURIOSIDAD", "NOTICIA", "EVENTO", "SOCIAL",
  "TRANSITO", "TRANSPORTE", "ALARMA", "EVENTO_INTERNO"
]);

const STOP = new Set(("para como esta este esto esas esos desde hasta sobre tengo quiero " +
  "hola gracias mucho algo tema temas noticia noticias nuevo nueva gusta gustan prefiero " +
  "podemos hablar despues manana estoy interes interesa intereses importante usuario " +
  "siempre conmigo porque cuando donde tambien recuerdo").split(" "));

export function palabras(texto) {
  return [...new Set(String(texto).normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase()
    .match(/[a-z0-9]{4,}/g) || [])].filter(p => !STOP.has(p));
}

export function interesesDe(memoria) {
  const preferencias = (memoria.persistentMemories || []).map(item => item.text);
  const recientes = (memoria.recentConversation || []).filter(item => item.role === "user").slice(-4).map(item => item.text);
  return palabras([...preferencias, ...(memoria.importantMemories || []).map(item => item.text), ...recientes].join(" ")).slice(0, 30);
}

function firma(fuente, referencia) {
  return crypto.createHash("sha256").update(`${fuente}:${referencia}`).digest("hex");
}

// Fuentes construidas por el servidor con datos del usuario → clave de prioridad.
const CLAVE_FUENTE = Object.freeze({
  recuerdo_relevante: "RECUERDO", clima: "CLIMA", sueno: "SUENO", salida_pareja: "SALIDA_PAREJA",
  juego_pasatiempo: "JUEGO_PASATIEMPO", pelicula_juntos: "PELICULA_JUNTOS", planear_salida: "PLANEAR_SALIDA"
});

function prepararCandidato(evento, intereses, ahora, politica) {
  const interno = evento.fuente === "continuidad";
  if (!CATEGORIAS.includes(evento.categoria) || !evento.motivo || !evento.contexto) return null;
  if (evento.timestamp > ahora || evento.expiresAt <= ahora) return null;
  // Native alarms own delivery; evaluating them here would duplicate their staged protocol.
  if (evento.categoria === "ALARMA") return null;
  const evidencia = String(evento.contexto.evidencia || evento.contexto.descripcion || "");
  const coincidencias = palabras(evidencia).filter(p => intereses.includes(p));
  // Interés nombrado por el usuario: basta una coincidencia fuerte, verificada contra la evidencia.
  const interesNombrado = typeof evento.contexto.interesUsuario === "string" &&
    palabras(evento.contexto.interesUsuario).some(p => palabras(evidencia).includes(p));
  // Fuentes construidas por el servidor desde datos del propio usuario (recuerdo/clima de su ciudad).
  const fuenteServidor = Object.hasOwn(CLAVE_FUENTE, evento.contexto.fuenteServidor || "");
  if (!interno && !evento.contexto.programadoPorUsuario && !interesNombrado && !fuenteServidor && coincidencias.length < 2) return null;
  if (!evidencia.trim()) return null;
  const referencia = evento.referenciaEvento || evento.id || firma(evento.fuente, evidencia);
  const clavePrioridad = evento.categoria === "SOCIAL" && !evento.contexto.espontanea ? "EVENTO_SOCIAL"
    : CLAVE_FUENTE[evento.contexto.fuenteServidor] || evento.categoria;
  const penalizacion = Math.max(0, Number(evento.contexto.penalizacionRotacion) || 0);
  const base = politica.prioridades[clavePrioridad];
  // La rotación reordena, pero no hunde una fuente por debajo del umbral espontáneo.
  const prioridad = penalizacion ? Math.max(base - penalizacion, Math.min(base, politica.prioridades.CURIOSIDAD + 1)) : base;
  return {
    id: firma(evento.fuente, `${evento.categoria}:${referencia}`),
    categoria: evento.categoria, motivo: evento.motivo, timestamp: ahora,
    referenciaEvento: referencia, prioridad,
    fuente: evento.fuente,
    contexto: { ...evento.contexto, interesesRelacionados: coincidencias },
    expiresAt: Math.min(evento.expiresAt, ahora + politica.expiracionMs)
  };
}

export function evaluarIniciativa({
  memoriaLocal = {}, registro = [], perfilRitmo = {}, disponibilidad = {}, eventos = [], ahora = Date.now()
}, politica = POLITICA_INICIATIVA) {
  const perfil = evaluarPerfilRitmo(perfilRitmo, ahora, politica);
  const esperar = motivoEspera => ({ decision: "ESPERAR", motivoEspera, perfilRitmo: perfil });
  if (disponibilidad.notificacionesHabilitadas !== true) return esperar("notificaciones_denegadas");
  if (disponibilidad.enPrimerPlano !== false) return esperar("en_primer_plano");
  if (perfil.dormido) return esperar("descanso_probable");
  if (eventos.some(item => item.categoria === "ALARMA" && item.contexto?.programadoPorUsuario === true &&
      Math.abs(item.timestamp - ahora) <= politica.separacionMinimaMs && item.expiresAt > ahora)) {
    return esperar("alarma_nativa_prioritaria");
  }
  const ultimoMensaje = (memoriaLocal.recentConversation || []).filter(item => item.role === "user").at(-1);
  const ultimaInteraccion = Math.max(ultimoMensaje?.timestamp || 0, perfil.ultimaInteraccion, ...(perfil.observaciones || [0]));
  const ritmo = ritmoDeInteraccion({
    evaluacionAutonoma: true, usuarioActivo: false,
    tiempoDesdeUltimoMensaje: (ahora - ultimaInteraccion) / 1000,
    pausaMinimaSegundos: politica.pausaConversacionMs / 1000
  });
  if (ritmo.accion !== "iniciar_interaccion") return esperar("conversacion_reciente");
  const intereses = interesesDe(memoriaLocal);
  const continuidad = obtenerContinuidad(memoriaLocal, ahora, politica.vigenciaContextoMs)
    .filter(item => item.categoria !== "RECUERDO" || palabras(item.contexto.evidencia)
      .some(p => palabras(ultimoMensaje?.text || "").includes(p)))
    .map(item => ({ ...item, fuente: "continuidad", expiresAt: item.timestamp + politica.vigenciaContextoMs }));
  const candidatos = [...eventos, ...continuidad]
    .map(item => prepararCandidato(item, intereses, ahora, politica)).filter(Boolean)
    .sort((a, b) => b.prioridad - a.prioridad || a.id.localeCompare(b.id));
  const entregadas = registro.filter(item => Number.isFinite(item.entregada) && item.entregada <= ahora);
  const recientes = entregadas.filter(item => item.entregada > ahora - politica.ventanaAprendizajeMs)
    .sort((a, b) => b.entregada - a.entregada);
  let ignoradas = 0;
  for (const item of recientes) {
    if (item.estado === "RESPONDIDA") {
      if (item.tipoRespuesta !== "negativa") break;
      ignoradas++;
    }
    if (item.estado === "IGNORADA" || (item.estado === "ENVIADA" && item.expiresAt <= ahora)) ignoradas++;
  }
  const cooldown = politica.cooldownMs * (2 ** Math.min(ignoradas, 4));
  const enviadasHoy = entregadas.filter(item => item.entregada > ahora - 24 * HORA);
  if (recientes.some(item => item.tipoRespuesta === "negativa" && ahora - item.respondida < cooldown)) {
    return esperar("respuesta_negativa");
  }
  if (recientes.some(item => ahora - item.entregada < politica.separacionMinimaMs)) return esperar("separacion_minima");
  if (ignoradas >= 2 && recientes.some(item => ahora - item.entregada < cooldown)) return esperar("ignorada_repetida");
  let motivoEspera = "sin_motivo_relevante";
  for (const candidato of candidatos) {
    if (registro.some(item => item.id === candidato.id || (
      item.fuente === candidato.fuente && item.referenciaEvento === candidato.referenciaEvento
    ))) { motivoEspera = "duplicada"; continue; }
    if (candidato.prioridad <= politica.prioridades.CURIOSIDAD &&
        ahora - ultimaInteraccion < politica.esperaEspontaneaMs) {
      motivoEspera = "espontanea_prematura"; continue;
    }
    // Lower-priority delivery must not exhaust a more important event's budget.
    if (enviadasHoy.filter(item => item.prioridad >= candidato.prioridad).length >=
        Math.max(1, politica.maxDiarias - Math.min(ignoradas, 2))) {
      motivoEspera = "limite_diario"; continue;
    }
    if (recientes.some(item => ahora - item.entregada < cooldown && item.prioridad >= candidato.prioridad)) {
      motivoEspera = "cooldown"; continue;
    }
    if (recientes.some(item => item.categoria === candidato.categoria &&
        ahora - item.entregada < politica.cooldownCategoriaMs)) {
      motivoEspera = "cooldown_categoria"; continue;
    }
    if (recientes.some(item => item.estado === "ABIERTA" && ahora - item.abierta < cooldown &&
        item.prioridad >= candidato.prioridad)) {
      motivoEspera = "iniciativa_abierta"; continue;
    }
    return { decision: "INICIAR", perfilRitmo: perfil, iniciativa: candidato };
  }
  return esperar(motivoEspera);
}
