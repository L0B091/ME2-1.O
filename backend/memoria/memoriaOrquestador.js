// Backend/memoria/memoriaOrquestador.js

import registrarActividad from "./registrarActividad.js";
import historialConversacion from "./historialConversacion.js";
import memoriaCorta from "./memoriaCorta.js";
import memoriaSelectiva from "./memoriaSelectiva.js";
import memoriaPersistente from "./memoriaPersistente.js";
import recuerdosImportantes from "./recuerdosImportantes.js";
import datosUsuario from "./datosUsuario.js";

/**
 * Coordina las capas de memoria de ME2.
 *
 * Este módulo NO decide qué información debe persistir.
 * Esa responsabilidad pertenece a writeBackEngine.js.
 *
 * Su función es:
 * - registrar actividad
 * - registrar el mensaje
 * - actualizar memoria corta
 * - construir memoria selectiva
 * - recuperar memoria persistente
 * - recuperar recuerdos importantes
 * - recuperar datos del usuario
 *
 * No almacena información nueva de forma permanente
 * por decisión propia.
 */
async function ejecutar({
  userId,
  mensaje,
  entradaProcesada = null,
  persistirEnServidor = true
}) {
  if (
    !userId ||
    typeof mensaje !== "string" ||
    !mensaje.trim()
  ) {
    return null;
  }

  const timestamp = Date.now();

  // =========================================================
  // 1. ACTIVIDAD
  // =========================================================

  const actividad =
    persistirEnServidor
      ? registrarActividad.registrarActividad(
          userId,
          new Date(timestamp)
        )
      : registrarActividad.obtenerActividad(userId);

  // =========================================================
  // 2. HISTORIAL
  // =========================================================

  if (persistirEnServidor) {
    historialConversacion.registrarMensaje(
      userId,
      mensaje,
      "usuario"
    );
  }

  // =========================================================
  // 3. MEMORIA CORTA
  // =========================================================

  const memoriaCortaActiva =
    memoriaCorta.actualizar(
      userId,
      mensaje
    );

  // =========================================================
  // 4. MEMORIA SELECTIVA
  // =========================================================

  const memoriaSelectivaActiva =
    memoriaSelectiva.construir(
      userId,
      {
        mensaje,
        entradaProcesada,
        memoriaCorta:
          memoriaCortaActiva
      }
    );

  // =========================================================
  // 5. MEMORIA PERSISTENTE
  // =========================================================
  //
  // Aquí SOLO recuperamos la memoria existente.
  //
  // No se agrega información nueva.
  // writeBackEngine decide posteriormente qué conservar.
  // =========================================================

  const memoriaPersistenteActual =
    memoriaPersistente.obtener(
      userId
    );

  // =========================================================
  // 6. RECUERDOS IMPORTANTES
  // =========================================================

  const recuerdosActuales =
    recuerdosImportantes.obtenerTodos(
      userId
    );

  // =========================================================
  // 7. DATOS DEL USUARIO
  // =========================================================
  //
  // Solo recuperamos el perfil.
  //
  // El aprendizaje persistente del usuario queda fuera
  // de este coordinador y puede ser gestionado por
  // writeBackEngine cuando corresponda.
  // =========================================================

  const perfil =
    datosUsuario.obtener(
      userId
    );

  // =========================================================
  // 8. SALIDA INTEGRADA
  // =========================================================

  return {
    memoriaCorta:
      memoriaCortaActiva,
    memoriaSelectiva:
      memoriaSelectivaActiva,
    memoriaPersistente:
      memoriaPersistenteActual,
    recuerdosImportantes:
      recuerdosActuales,
    actividad,
    datosUsuario: perfil,
    meta: {
      userId,
      timestamp
    }
  };
}

export default {
  ejecutar
};