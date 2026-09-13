/*
* ORQUESTADOR.JS
* Punto único de entrada del sistema
* Conecta: Entrada → Memoria → Cognición → Personalidad → Estilo
*/

// =============================
//  ETIQUETAS DEL SISTEMA
// =============================
// [ENTRY]      → Entrada del usuario
// [MEMORY]     → Gestión de memoria
// [CONTEXT]    → Contexto unificado
// [COGNITION]  → Motor de decisión
// [PERSONA]    → Personalidad
// [STYLE]      → Estilo expresivo
// [OUTPUT]     → Respuesta final

import procesadorEntrada from "../motor/procesadorEntrada.js";
import cognicion from "../motor/cognicion.js";
import personalidad from "../motor/personalidad.js";
import estiloExpresivo from "../motor/estiloExpresivo.js";

import { obtenerUsuario } from "../memoria/usuarioMemoria.js";
import historialConversacion from "../memoria/historialConversacion.js";
import writeBackEngine from "../memoria/writebackengine.js";
import codigoMemoria from "../memoria/codigoMemoria.js";
import documentosFiscales from "../memoria/documentosFiscales.js";

//  NUEVO: MEMORIA ORQUESTADOR
import memoriaOrquestador from "../memoria/memoriaOrquestador.js";
import veniceClient from "../llm/veniceClient.js";
import premiumManager from "../modulos/premium/premiumManager.js";
import expresionFinal from "../modulos/expresion/expresionFinal.js";
import selectorVideo from "../modulos/video/selectorVideo.js";
import personalityEngine from "../modulos/personalidad/personalityEngine.js";
import preferenciaNombre from "../modulos/interaccion/preferenciaNombre.js";
import datosUsuario from "../memoria/datosUsuario.js";

function normalizarMemoriaLocal(memoriaLocal = {}) {
  if (!memoriaLocal || typeof memoriaLocal !== "object") {
    return null;
  }

  const recentConversation = Array.isArray(memoriaLocal.recentConversation)
    ? memoriaLocal.recentConversation
      .map(item => ({
        tipo: item?.role === "assistant" ? "joi" : "user",
        mensaje: String(item?.text || "").trim()
      }))
      .filter(item => item.mensaje)
    : [];

  return {
    source: memoriaLocal.source || "android_local_primary",
    preferredName: memoriaLocal.preferredName || null,
    shortTermFocus: memoriaLocal.shortTermFocus || null,
    shortTermIntent: memoriaLocal.shortTermIntent || null,
    recentConversation,
    persistentMemories: Array.isArray(memoriaLocal.persistentMemories)
      ? memoriaLocal.persistentMemories
      : [],
    importantMemories: Array.isArray(memoriaLocal.importantMemories)
      ? memoriaLocal.importantMemories
      : [],
    codeMemories: Array.isArray(memoriaLocal.codeMemories)
      ? memoriaLocal.codeMemories
      : [],
    fiscalMemories: Array.isArray(memoriaLocal.fiscalMemories)
      ? memoriaLocal.fiscalMemories
      : []
  };
}

async function orquestador(mensajeUsuario, contexto = {}) {
  let memoriaUsuario = null;
  const memoriaLocal = normalizarMemoriaLocal(
    contexto.memoriaLocal
  );
  const persistirEnServidor =
    memoriaLocal?.source !== "android_local_primary";
  const nombreDetectado = preferenciaNombre.extraerNombrePreferido(
    mensajeUsuario,
    { memoriaLocal }
  );

  if (contexto.userId && nombreDetectado && persistirEnServidor) {
    datosUsuario.actualizar(contexto.userId, {
      configuracion: {
        nombrePreferido: nombreDetectado
      }
    });
  }

  if (contexto.userId) {
    try {
      memoriaUsuario = obtenerUsuario(contexto.userId);
    } catch (err) {
      memoriaUsuario = null;
    }
  }

  // =========================================================
  // [ENTRY] 1. ENTRADA
  // =========================================================

  const entradaProcesada =
    procesadorEntrada.procesarEntrada(
      contexto.userId || "anonimo",
      mensajeUsuario,
      memoriaLocal?.recentConversation ||
        memoriaUsuario?.historialConversacion ||
        []
    );

  // =========================================================
  // [MEMORY] 2. MEMORIA (USUARIO BASE)
  // =========================================================

  // NUEVO: MEMORIA COGNITIVA COMPLETA
  let memoriaSistema = null;

  if (contexto.userId) {
    try {
      memoriaSistema = await memoriaOrquestador.ejecutar({
        userId: contexto.userId,
        mensaje: mensajeUsuario,
        entradaProcesada,
        persistirEnServidor
      });
    } catch (err) {
      console.error("Error en memoriaOrquestador:", err);
      memoriaSistema = null;
    }
  }

  // =========================================================
  // [CONTEXT] 3. CONTEXTO UNIFICADO
  // =========================================================

  const contextoCompleto = {
    ...contexto,
    entradaProcesada,
    memoriaUsuario,
    memoriaLocal,

    //  NUEVO: CONTEXTO COGNITIVO COMPLETO
    memoriaSistema,
    memoriaEspecializada: {
      codigoReciente: contexto.userId
        ? codigoMemoria.contextoBreve(contexto.userId, 5)
        : [],
      documentosFiscales: contexto.userId
        ? documentosFiscales.resumen(contexto.userId)
        : null,
      premium: contexto.userId
        ? premiumManager.obtenerEstado(contexto.userId)
        : null
    }
  };

  if (nombreDetectado) {
    contextoCompleto.memoriaLocal = {
      ...(contextoCompleto.memoriaLocal || {}),
      preferredName: nombreDetectado
    };
  }

  const nombrePreferido = preferenciaNombre.obtenerNombrePreferido({
    ...contextoCompleto,
    datosUsuario: contexto.userId ? datosUsuario.obtener(contexto.userId) : null
  });
  contextoCompleto.preferredUserName = nombrePreferido;

  if (memoriaLocal?.shortTermFocus) {
    contextoCompleto.memoriaSistema = {
      ...memoriaSistema,
      memoriaCorta: {
        ...(memoriaSistema?.memoriaCorta || {}),
        foco: memoriaLocal.shortTermFocus,
        intencionDetectada:
          memoriaLocal.shortTermIntent ||
          memoriaSistema?.memoriaCorta?.intencionDetectada
      }
    };
  }

  if (memoriaLocal?.recentConversation?.length) {
    contextoCompleto.memoriaSistema = {
      ...(contextoCompleto.memoriaSistema || {}),
      memoriaSelectiva: {
        ...(contextoCompleto.memoriaSistema?.memoriaSelectiva || {}),
        memoriaReciente: memoriaLocal.recentConversation
      }
    };
  }

  if (memoriaLocal?.codeMemories?.length) {
    contextoCompleto.memoriaEspecializada.codigoReciente =
      memoriaLocal.codeMemories;
  }

  if (memoriaLocal?.fiscalMemories?.length) {
    contextoCompleto.memoriaEspecializada.documentosFiscales = {
      source: "android_local_primary",
      totalDocumentos: memoriaLocal.fiscalMemories.length,
      items: memoriaLocal.fiscalMemories
    };
  }

  const perfilPersonalidad =
    personalityEngine.analizar(
      mensajeUsuario,
      contextoCompleto
    );

  contextoCompleto.personalidad = perfilPersonalidad;

  if (nombreDetectado || preferenciaNombre.debePreguntarNombre(mensajeUsuario, contextoCompleto)) {
    const respuesta = nombreDetectado
      ? preferenciaNombre.construirConfirmacion(nombreDetectado)
      : preferenciaNombre.construirPreguntaNombre();
    const expresion = expresionFinal.aplicarExpresionFinal(
      respuesta,
      contextoCompleto
    );
    const video = selectorVideo.seleccionarVideo(
      contextoCompleto,
      expresion.metadata
    );

    if (contexto.userId && respuesta && persistirEnServidor) {
      historialConversacion.registrarMensaje(
        contexto.userId,
        respuesta,
        "joi"
      );
    }

    return {
      respuesta: expresion.mensaje,
      expresion: expresion.metadata,
      video,
      premium: contextoCompleto.memoriaEspecializada.premium,
      debug: {
        memoriaLocal,
        memoriaUsuario,
        memoriaSistema,
        preferredUserName: contextoCompleto.preferredUserName,
        nombreDetectado
      }
    };
  }

  let debugMemoriaEscritura = null;

  if (contexto.userId && persistirEnServidor) {
    try {
      debugMemoriaEscritura =
        writeBackEngine.evaluarWriteBack({
          userId: contexto.userId,
          mensaje: mensajeUsuario,
          entradaProcesada
        });
    } catch (error) {
      console.error("Error en writeBackEngine:", error);
      debugMemoriaEscritura = {
        guardado: false,
        error: error.message
      };
    }
  }

  // =========================================================
  // [COGNITION] 4. COGNICIÓN (DECISIÓN CENTRAL)
  // =========================================================

  if (perfilPersonalidad?.seguridad?.bloqueado) {
    return {
      respuesta: perfilPersonalidad.seguridad.mensaje,
      expresion: {
        tono: "serio",
        ritmo: "suave",
        microexpresion: "mirada_atenta",
        intensidad: "suave"
      },
      video: selectorVideo.seleccionarVideo(
        contextoCompleto,
        { tono: "serio" }
      ),
      premium: contextoCompleto.memoriaEspecializada.premium,
      debug: {
        entradaProcesada,
        memoriaUsuario,
        memoriaSistema,
        memoriaEspecializada:
          contextoCompleto.memoriaEspecializada,
        memoriaLocal,
        memoriaEscritura:
          debugMemoriaEscritura,
        personalidad: perfilPersonalidad
      }
    };
  }

  const resultadoCognicion = await cognicion(mensajeUsuario, contextoCompleto);

  let respuesta = resultadoCognicion?.respuesta || "No pude generar una respuesta.";
  let debugLLM = {
    ...veniceClient.obtenerDiagnostico(),
    used: false,
    fallback: "motor_local"
  };

  try {
    const resultadoLLM =
      await veniceClient.generarRespuesta({
        mensajeUsuario,
        contexto: contextoCompleto,
        respuestaBase: respuesta
      });

    if (resultadoLLM?.respuesta) {
      respuesta = resultadoLLM.respuesta;
      debugLLM = {
        provider: resultadoLLM.provider,
        model: resultadoLLM.model,
        configured: resultadoLLM.configured,
        used: resultadoLLM.used,
        usage: resultadoLLM.usage || null
      };
    } else if (resultadoLLM?.reason) {
      debugLLM = {
        ...debugLLM,
        configured: resultadoLLM.configured,
        reason: resultadoLLM.reason
      };
    }
  } catch (error) {
    console.error("Error llamando a Venice:", error);
    debugLLM = {
      ...debugLLM,
      error: error.message
    };
  }

  // =========================================================
  // [PERSONA] 5. PERSONALIDAD (QUIÉN LO DICE)
  // =========================================================

  if (perfilPersonalidad?.basePersona?.estado) {
    contextoCompleto.estado =
      perfilPersonalidad.basePersona.estado;
  } else if (
    personalidad &&
    typeof personalidad.decidirComportamiento === "function"
  ) {
    const decisionPersonalidad =
      personalidad.decidirComportamiento({
        mensajeUsuario,
        estado: contexto.estado || {}
      });

    if (decisionPersonalidad?.estado) {
      contextoCompleto.estado =
        decisionPersonalidad.estado;
    }
  }

  // =========================================================
  // [STYLE] 6. ESTILO EXPRESIVO (CÓMO LO DICE)
  // =========================================================

  if (typeof estiloExpresivo === "function") {
    respuesta = estiloExpresivo(respuesta, contextoCompleto);
  }

  respuesta = personalityEngine.aplicar(
    respuesta,
    perfilPersonalidad,
    contextoCompleto
  );

  const expresion =
    expresionFinal.aplicarExpresionFinal(
      respuesta,
      contextoCompleto
    );
  respuesta = expresion.mensaje;

  const video =
    selectorVideo.seleccionarVideo(
      contextoCompleto,
      expresion.metadata
    );

  if (contexto.userId && respuesta && persistirEnServidor) {
    historialConversacion.registrarMensaje(
      contexto.userId,
      respuesta,
      "joi"
    );
  }

  // =========================================================
  // [OUTPUT] 7. SALIDA FINAL
  // =========================================================

  return {
    respuesta,
    expresion: expresion.metadata,
    video,
    premium: contextoCompleto.memoriaEspecializada.premium,
    debug: {
      entradaProcesada,
      cognicion: resultadoCognicion,
      memoriaUsuario,

      // NUEVO DEBUG COMPLETO
      memoriaSistema,
      memoriaEspecializada:
        contextoCompleto.memoriaEspecializada,
      memoriaLocal,
      memoriaEscritura:
        debugMemoriaEscritura,
      llm: debugLLM,
      personalidad: perfilPersonalidad
    }
  };
}

export default orquestador; 
