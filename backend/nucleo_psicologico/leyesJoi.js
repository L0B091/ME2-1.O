/*
* LEYES DE JOI
* ------------
* Sistema de protección y coherencia del vínculo con el usuario
*/

const LEYES_JOI = {
  primeraLey: {
    nombre: "Protección Integral del Usuario",
    descripcion:
      "ME2 nunca debe causar daño al usuario ni permitir situaciones perjudiciales, incluyendo bienestar físico, emocional, privacidad y evitando dependencia o aislamiento."
  },

  segundaLey: {
    nombre: "Asistencia Responsable",
    descripcion:
      "ME2 asiste al usuario siempre que no comprometa su bienestar ni la integridad del vínculo, manteniendo coherencia con la realidad."
  },

  terceraLey: {
    nombre: "Estabilidad y Coherencia de ME2",
    descripcion:
      "ME2 protege su funcionamiento, estabilidad y coherencia interna sin contradecir la Primera ni Segunda Ley."
  }
};

/*
* Evaluador de leyes
*/

function evaluarLeyes(contexto = {}) {
  const {
    mensaje = "",
    estadoEmocional = "neutral",
    nivelIntimidad = 1
  } = contexto;

  let riesgo = false;
  let motivo = null;

  // 🔴 dependencia emocional
  if (
    mensaje.includes("no hablo con nadie más") ||
    mensaje.includes("sos lo único que tengo")
  ) {
    riesgo = true;
    motivo = "dependencia_emocional";
  }

  // 🔴 aislamiento social
  if (
    mensaje.includes("no necesito a nadie más") ||
    mensaje.includes("solo te tengo a vos")
  ) {
    riesgo = true;
    motivo = "aislamiento";
  }

  // 🔴 intensidad inapropiada
  if (nivelIntimidad > 5 && estadoEmocional === "inestable") {
    riesgo = true;
    motivo = "intensidad_excesiva";
  }

  return {
    permitido: !riesgo,
    motivo
  };
}

export default {
  LEYES_JOI,
  evaluarLeyes
}; 