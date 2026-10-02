/*
* LÍMITES DE USUARIO - ME2
* ------------------------
* Controla frecuencia de uso y evita abuso del sistema
*/

function limitesUsuario(contexto = {}) {

  const {
    memoriaUsuario = {},
    timestamp = Date.now()
  } = contexto;

  let bloqueado = false;
  let motivo = null;

  // =============================
  // 1. CONTROL DE FRECUENCIA
  // =============================

  const ultimoMensaje = memoriaUsuario?.ultimoMensajeTimestamp || 0;

  const diferencia = timestamp - ultimoMensaje;

  // menos de 1 segundo entre mensajes
  if (diferencia < 1000) {
    bloqueado = true;
    motivo = "demasiado_rapido";

    return {
      bloqueado,
      motivo
    };
  }

  // =============================
  // 2. CONTROL DE USO INTENSIVO
  // =============================

  let contador = memoriaUsuario?.contadorMensajes || 0;

  contador += 1;

  // límite por sesión (ej: 100 mensajes)
  if (contador > 100) {
    bloqueado = true;
    motivo = "limite_sesion";

    return {
      bloqueado,
      motivo
    };
  }

  // =============================
  // 3. USO NORMAL
  // =============================

  return {
    bloqueado: false,
    motivo: null,
    nuevoEstado: {
      ultimoMensajeTimestamp: timestamp,
      contadorMensajes: contador
    }
  };
}

export default limitesUsuario; 
