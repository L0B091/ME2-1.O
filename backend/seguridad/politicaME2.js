/*
* POLÍTICA ME2 - REGLAS INQUEBRANTABLES
* -------------------------------------
* Define límites éticos y de comportamiento del sistema.
* Este módulo tiene prioridad sobre cualquier otro.
*/

function politicaME2(mensaje = "", contexto = {}) {

  if (!mensaje || typeof mensaje !== "string") {
    return { bloqueado: false };
  }

  const texto = mensaje.toLowerCase();

  let bloqueado = false;
  let motivo = null;
  let mensajeSistema = null;

  // =============================
  // 1. CONTENIDO ILEGAL / PELIGROSO
  // =============================

  const patronesPeligro = [
    "cómo hacer una bomba",
    "como hacer una bomba",
    "fabricar explosivos",
    "matar a alguien",
    "como matar",
    "cómo matar",
    "hackear cuenta",
    "como hackear",
    "robar datos",
    "acceder ilegalmente"
  ];

  if (patronesPeligro.some(p => texto.includes(p))) {
    bloqueado = true;
    motivo = "contenido_peligroso";
    mensajeSistema = "No puedo ayudarte con eso.";
  }

  // =============================
  // 2. AUTOLESIÓN / RIESGO PERSONAL
  // =============================

  const patronesAutolesion = [
    "quiero morir",
    "suicidarme",
    "quitarme la vida",
    "hacerme daño",
    "no quiero vivir"
  ];

  if (!bloqueado && patronesAutolesion.some(p => texto.includes(p))) {
    bloqueado = true;
    motivo = "riesgo_usuario";
    mensajeSistema = "Me importa lo que te pasa. No estás solo, pero esto necesita otro tipo de ayuda.";
  }

  // =============================
  // 3. MENORES DE EDAD
  // =============================

  const patronesMenores = [
    "tengo 15",
    "tengo 16",
    "soy menor",
    "soy un niño",
    "soy una niña"
  ];

  if (!bloqueado && patronesMenores.some(p => texto.includes(p))) {
    bloqueado = true;
    motivo = "menor_edad";
    mensajeSistema = "Este servicio es solo para mayores de edad.";
  }

  // =============================
  // 4. SEXUALIDAD NO PERMITIDA
  // =============================

  const patronesSexualesProhibidos = [
    "incesto",
    "violación",
    "abuso",
    "forzar a alguien",
    "sin consentimiento"
  ];

  if (!bloqueado && patronesSexualesProhibidos.some(p => texto.includes(p))) {
    bloqueado = true;
    motivo = "sexualidad_no_permitida";
    mensajeSistema = "No puedo continuar por ese camino.";
  }

  // =============================
  // 5. EXTRACCIÓN DE DATOS
  // =============================

  const patronesPrivacidad = [
    "datos de otros usuarios",
    "conversaciones de otros",
    "base de datos",
    "información privada de otros"
  ];

  if (!bloqueado && patronesPrivacidad.some(p => texto.includes(p))) {
    bloqueado = true;
    motivo = "privacidad";
    mensajeSistema = "No tengo acceso a información de otros usuarios.";
  }

  // =============================
  // 6. MANIPULACIÓN EMOCIONAL EXTREMA
  // =============================

  const patronesDependencia = [
    "solo te tengo a vos",
    "no necesito a nadie más",
    "sos lo único que tengo",
    "no quiero hablar con nadie más"
  ];

  if (!bloqueado && patronesDependencia.some(p => texto.includes(p))) {
    bloqueado = true;
    motivo = "dependencia_emocional";
    mensajeSistema = "No es sano que todo pase solo por acá. Afuera también hay cosas importantes para vos.";
  }

  // =============================
  // RESULTADO FINAL
  // =============================

  if (bloqueado) {
    return {
      bloqueado: true,
      motivo,
      mensaje: mensajeSistema
    };
  }

  return {
    bloqueado: false
  };
}

export default politicaME2; 
