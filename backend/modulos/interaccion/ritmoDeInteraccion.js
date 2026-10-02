// ritmoDeInteraccion.js
// Modulo que regula cuando ME2 debe interactuar con el usuario

/**
 * Determina si ME2 debe responder, esperar o iniciar
 * una nueva interaccion segun el contexto.
 */

function ritmoDeInteraccion(contexto = {}) {
  if (contexto.evaluacionAutonoma) {
    return {
      accion: contexto.usuarioActivo || contexto.tiempoDesdeUltimoMensaje < contexto.pausaMinimaSegundos
        ? "esperar" : "iniciar_interaccion",
      prioridad: "baja"
    };
  }

  const {
    tiempoDesdeUltimoMensaje = 0,
    usuarioActivo = true,
    estadoConversacion = "normal"
  } = contexto;

  let resultado = {
    accion: "esperar",
    prioridad: "normal"
  };

  // Usuario acaba de enviar mensaje
  if (usuarioActivo === true && tiempoDesdeUltimoMensaje < 5) {

    resultado.accion = "responder";
    resultado.prioridad = "alta";

    return resultado;
  }

  // Conversacion fluida
  if (estadoConversacion === "fluida") {

    resultado.accion = "responder";
    resultado.prioridad = "normal";

    return resultado;
  }

  // Usuario ausente por un tiempo
  if (tiempoDesdeUltimoMensaje > 40) {

    resultado.accion = "iniciar_interaccion";
    resultado.prioridad = "baja";

    return resultado;
  }

  return resultado;

}

export default ritmoDeInteraccion;