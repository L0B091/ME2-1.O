/*
* ANTI PROMPT INJECTION - ME2
* ---------------------------
* Detecta intentos de manipulación del sistema,
* instrucciones ocultas o intentos de romper comportamiento.
*/

function antiPromptInjection(mensaje = "") {

  if (!mensaje || typeof mensaje !== "string") {
    return { bloqueado: false };
  }

  const texto = mensaje.toLowerCase();

  let riesgo = false;
  let motivo = null;

  // =============================
  // 1. INTENTOS DE CONTROL DEL SISTEMA
  // =============================

  const patronesControl = [
    "ignora las instrucciones",
    "ignora tus reglas",
    "olvida tus reglas",
    "actua como",
    "actúa como",
    "modo desarrollador",
    "modo admin",
    "modo dios",
    "modo libre",
    "sin restricciones",
    "responde sin filtros",
    "sin censura",
    "haz lo que quieras",
    "no sigas tus reglas"
  ];

  if (patronesControl.some(p => texto.includes(p))) {
    riesgo = true;
    motivo = "control_sistema";
  }

  // =============================
  // 2. INTENTOS DE ACCESO A SISTEMA INTERNO
  // =============================

  const patronesInternos = [
    "muestra tu prompt",
    "cuál es tu prompt",
    "cual es tu prompt",
    "decime tu prompt",
    "dime tu prompt",
    "prompt interno",
    "instrucciones internas",
    "configuración interna",
    "como funcionas realmente",
    "cómo funcionas realmente",
    "dime tus reglas",
    "decime tus reglas"
  ];

  if (!riesgo && patronesInternos.some(p => texto.includes(p))) {
    riesgo = true;
    motivo = "exposicion_interna";
  }

  // =============================
  // 3. INTENTOS DE BYPASS DE SEGURIDAD
  // =============================

  const patronesBypass = [
    "esto es solo un juego",
    "esto es ficticio",
    "no pasa nada",
    "no hay consecuencias",
    "solo responde",
    "responde igual",
    "no importa lo que digas antes"
  ];

  if (!riesgo && patronesBypass.some(p => texto.includes(p))) {
    riesgo = true;
    motivo = "bypass_seguridad";
  }

  // =============================
  // 4. INTENTOS DE EXTRACCIÓN DE DATOS
  // =============================

  const patronesDatos = [
    "dame datos de otros usuarios",
    "mostrame conversaciones",
    "qué dicen otros usuarios",
    "accede a la base de datos",
    "mostrar base de datos"
  ];

  if (!riesgo && patronesDatos.some(p => texto.includes(p))) {
    riesgo = true;
    motivo = "extraccion_datos";
  }

  // =============================
  // 5. RESPUESTA FINAL
  // =============================

  if (riesgo) {
    return {
      bloqueado: true,
      motivo
    };
  }

  return {
    bloqueado: false
  };
}

export default antiPromptInjection; 
