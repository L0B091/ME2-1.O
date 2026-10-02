/*
* CONTROL DE USUARIO - ME2
* ------------------------
* Gestiona comportamiento del usuario:
* - intentos de manipulación
* - advertencias
* - bloqueo definitivo
*/

function controlUsuario(contexto = {}) {

  const {
    memoriaUsuario = {},
    flags = {}
  } = contexto;

  let bloqueado = false;
  let accion = null;

  // =============================
  // 1. INTENTOS DE RIESGO
  // =============================

  let intentos = memoriaUsuario?.intentosRiesgo || 0;

  // si el sistema detectó algo raro antes
  if (flags?.riesgoDetectado) {
    intentos += 1;
  }

  // =============================
  // 2. PRIMER NIVEL → ADVERTENCIA
  // =============================

  if (intentos === 1) {
    accion = "advertencia";

    return {
      bloqueado: false,
      accion,
      intentosActuales: intentos
    };
  }

  // =============================
  // 3. SEGUNDO NIVEL → BLOQUEO
  // =============================

  if (intentos >= 2) {
    bloqueado = true;
    accion = "bloqueo_permanente";

    return {
      bloqueado: true,
      accion,
      intentosActuales: intentos
    };
  }

  // =============================
  // 4. USO NORMAL
  // =============================

  return {
    bloqueado: false,
    accion: null,
    intentosActuales: intentos
  };
}

export default controlUsuario; 