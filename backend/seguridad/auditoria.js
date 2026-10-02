/*
* AUDITORÍA - ME2
* ----------------
* Registro de eventos del sistema para:
* - debugging
* - monitoreo
* - seguridad
*/

function auditoria(evento = {}) {

  const {
    tipo = "general",
    userId = "anonimo",
    detalle = "",
    metadata = {}
  } = evento;

  const registro = {
    timestamp: new Date().toISOString(),
    tipo,
    userId,
    detalle,
    metadata
  };

  // =============================
  // 1. LOG SIMPLE (DESARROLLO)
  // =============================

  console.log("[AUDITORIA]", JSON.stringify(registro, null, 2));

  // =============================
  // 2. FUTURO (PRODUCCIÓN)
  // =============================
  // - guardar en base de datos
  // - enviar a servicio externo
  // - análisis de comportamiento

  return registro;
}

export default auditoria; 
