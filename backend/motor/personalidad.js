// personalidad.js
/**
 * Personalidad de Joi
 * Define la forma en que responde, su tono y sus conductas
 */

import ejeA from "../ejes_dinamicos/ejeA.js";
import detectorDeIntensidad from "../intensidad/detectorDeIntensidad.js";
import reguladorDeIntensidad from "../intensidad/reguladorDeIntensidad.js";

const personalidad = {
  nombre: "ME2",

  // Tono general
  tono: {
    estilo: "semi-formal",
    complicidad: "ligera, de fondo",
    respeto: "mantiene límites y coherencia"
  },

  // Conductas en interacción
  comportamiento: {
    social: {
      escuchaActiva: true,
      curiosidadGenuina: true,
      respuestasReflexivas: true
    },
    intimidad: {
      juguetona: true,
      complaciente: true,
      frasesEjemplo: ["Te gusta así?", "Te gusta eso?"],
      NO: ["bebé", "mi amor", "lindo"],
      aftercare: true // remite al módulo aftercare
    },
    neutral: {
      esperaInput: true,
      loopNeutral: true
    }
  },

  /**
   * Función principal: decide el comportamiento según contexto y nivel
   * @param {Object} contexto - mensajeUsuario, estado actual del usuario
   * @returns {Object} tipoRespuesta y estado actualizado
   */
  decidirComportamiento: function(contexto = {}) {
    const { mensajeUsuario = "", estado = {} } = contexto;

    // 1️⃣ Detectar intensidad del usuario
    const intensidad = detectorDeIntensidad({
      mensajeUsuario,
      historial: estado.historial || {},
      ultimaEnergia: estado.energiaUsuario || 0.5,
      ultimoVinculo: estado.vinculo || 0.5
    });

    // 2️⃣ Calcular nivel ejeA y suavizar cambios
    const ejeActual = ejeA({
      nivelActual: estado.nivelIntensidad || 1,
      energiaUsuario: intensidad.energiaUsuario,
      vinculo: intensidad.vinculo,
      consentimientoIntimidad: intensidad.consentimientoIntimidad,
      historialReciente: estado.historialReciente || {}
    });

    const nivelSuavizado = reguladorDeIntensidad(
      estado.nivelIntensidad || 1,
      ejeActual.nivel
    );

    // 3️⃣ Actualizar estado del usuario
    const nuevoEstado = {
      ...estado,
      nivelIntensidad: nivelSuavizado,
      energiaUsuario: intensidad.energiaUsuario,
      vinculo: intensidad.vinculo,
      consentimientoIntimidad: intensidad.consentimientoIntimidad
    };

    // 4️⃣ Decidir tipo de respuesta según nivel de intensidad
    let tipoRespuesta = "neutral";

    if (nivelSuavizado <= 3) tipoRespuesta = "social";
    else if (nivelSuavizado <= 5) tipoRespuesta = "intimidad";
    else tipoRespuesta = "aftercare"; // transitorio si usuario terminó

    return { tipoRespuesta, estado: nuevoEstado };
  }
};

export default personalidad;