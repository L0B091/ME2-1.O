import storage from "../../utils/jsonStorage.js";
import datosUsuario from "../../memoria/datosUsuario.js";
import usuariosMemoria from "../../memoria/usuariosMemoria.js";

const NAMESPACE = "premium";
const PREMIUM_DIAS = 30;

function premiumBetaHabilitado() {
  // Pausado para pruebas: solo free, salvo BETA_PREMIUM_DEFAULT=true explícito.
  const env = String(process.env.BETA_PREMIUM_DEFAULT || "").trim().toLowerCase();
  return env === "true";
}

function obtenerPrecioPremium() {
  return Number(process.env.PREMIUM_PRICE_ARS || 3000);
}

export const PLAN = {
  free: [
    "Conversación con IA",
    "APIs",
    "Memoria básica",
    "Personalidad y expresión",
    "Respuestas visuales / video"
  ],
  premium: [
    "Todo lo incluido en Free",
    "Respaldo de memoria en la nube (cifrado) y restauración en un teléfono nuevo, retomando el hilo en tiempo y lugar",
    "Gestor de material para monotributista (facturas, gastos, categoría, vencimientos y notas, por chat)",
    "Memoria dedicada para proyectos de programación (mini-repo por proyecto con versiones, diff y restauración, por chat)",
    "Modo Adulto (18+, palabra clave por sesión, galería dedicada de clips y GIFs)"
  ]
};

/** Alcance COMPLETO de Premium como HECHOS para el LLM (la primera vez que el usuario pregunta/pide Premium). */
export const ALCANCE_PREMIUM = [
  "1) Respaldo de memoria en la nube: copia cifrada en el teléfono antes de subirla (AES-GCM con una clave derivada de tu cuenta; el servidor guarda solo el contenido cifrado, aunque no es cifrado de extremo a extremo) de toda la memoria local (conversación, recuerdos, datos fiscales y proyectos). En un teléfono nuevo se restaura y el avatar retoma el hilo: sabe cuándo y en qué lugar fue la última charla.",
  "2) Gestor de material para monotributista: por chat registra facturas emitidas y recibidas (monto, fecha, cliente/concepto), la categoría de monotributo, vencimientos (monotributo, IIBB, etc.) y notas; responde cuánto facturó en el mes y en 12 meses, qué vence y qué está vencido. Todo queda guardado en el teléfono.",
  "3) Memoria dedicada para proyectos de programación: un mini-repositorio por proyecto dentro de la app; por chat guarda archivos (bloques de código), hace snapshots/versiones, muestra diferencias (diff) entre versiones y restaura una versión anterior. Guardado en el teléfono.",
  "4) Modo Adulto: solo 18+ verificado; se habilita por sesión con una palabra clave privada; incluye una galería dedicada de clips del avatar y GIFs adultos (nunca fuera del modo adulto)."
];

function obtenerRegistro(userId) {
  return storage.readUserData(NAMESPACE, userId, {
    userId,
    premiumHasta: null,
    historial: []
  });
}

function guardarRegistro(userId, data) {
  storage.writeUserData(NAMESPACE, userId, {
    userId,
    premiumHasta: data.premiumHasta || null,
    historial: Array.isArray(data.historial) ? data.historial.slice(-50) : []
  });
}

function sincronizarPlanUsuario(userId, premiumHasta = null) {
  const plan = premiumHasta && new Date(premiumHasta).getTime() > Date.now() ? "premium" : "free";
  datosUsuario.actualizar(userId, {
    configuracion: {
      plan
    }
  });

  const authUser = usuariosMemoria.obtenerUsuarioPorId(userId);
  if (authUser) {
    usuariosMemoria.guardarUsuario(authUser.email, {
      ...authUser,
      premiumUntil: premiumHasta
    });
  }
}

function obtenerEstado(userId) {
  const registro = obtenerRegistro(userId);
  const premiumHastaMs = registro.premiumHasta ? new Date(registro.premiumHasta).getTime() : 0;
  const premiumRealActivo = premiumHastaMs > Date.now();
  const premiumActivo = premiumRealActivo || premiumBetaHabilitado();
  const premiumHasta = premiumRealActivo
    ? registro.premiumHasta
    : premiumActivo
      ? "beta-debug"
      : null;

  if (!premiumRealActivo && registro.premiumHasta) {
    sincronizarPlanUsuario(userId, null);
  }

  return {
    userId,
    premiumActivo,
    premiumHasta,
    plan: premiumActivo ? "premium" : "free",
    funciones: premiumActivo ? PLAN.premium : PLAN.free,
    backupMaterial: premiumActivo
      ? usuariosMemoria.obtenerOMaterializarBackupMaterial(userId)
      : null,
    betaForced: !premiumRealActivo && premiumActivo,
    historial: Array.isArray(registro.historial) ? registro.historial : []
  };
}

function explicarPlan(feature = "M/A") {
  return {
    feature,
    precioARS: obtenerPrecioPremium(),
    duracionDias: PREMIUM_DIAS,
    renovacionAutomatica: false,
    mensaje: `Para usar ${feature}, ME2 habilita Premium por ${PREMIUM_DIAS} días mediante Mercado Pago. No se renueva automáticamente.`,
    free: PLAN.free,
    premium: PLAN.premium
  };
}

function registrarCheckout(userId, checkout = {}) {
  const registro = obtenerRegistro(userId);
  registro.historial = [
    ...(registro.historial || []),
    {
      tipo: "checkout_generado",
      preferenceId: checkout.preferenceId || null,
      feature: checkout.feature || "M/A",
      amountARS: checkout.amountARS || obtenerPrecioPremium(),
      initPoint: checkout.initPoint || null,
      createdAt: new Date().toISOString()
    }
  ].slice(-50);
  guardarRegistro(userId, registro);
  return registro;
}

/** Monto mínimo aceptable: el menor entre el precio actual y los checkouts generados para el usuario. */
function montoEsperado(userId) {
  const montos = (obtenerRegistro(userId).historial || [])
    .filter(h => h?.tipo === "checkout_generado" && Number.isFinite(Number(h.amountARS)))
    .map(h => Number(h.amountARS));
  return Math.min(obtenerPrecioPremium(), ...montos);
}

function activarPremium(userId, paymentId, detail = {}) {
  const registro = obtenerRegistro(userId);
  const previo = (registro.historial || []).find(h => h?.tipo === "pago_aprobado" && paymentId && h.paymentId === paymentId);
  if (previo) {
    // Idempotencia: el mismo pago no extiende Premium otra vez.
    return { ok: true, yaProcesado: true, premiumActivo: Date.parse(registro.premiumHasta || 0) > Date.now(), premiumHasta: registro.premiumHasta || null, paymentId, status: previo.status, feature: previo.feature };
  }
  const ahora = Date.now();
  const aprobado = Date.parse(detail.aprobadoEn || "");
  const vigente = Date.parse(registro.premiumHasta || "");
  // Desde la aprobación del pago (no desde "ahora"); si hay Premium vigente, se acumula.
  const inicio = Math.max(Number.isFinite(aprobado) ? Math.min(aprobado, ahora) : ahora, Number.isFinite(vigente) && vigente > ahora ? vigente : 0);
  const premiumHasta = new Date(inicio + PREMIUM_DIAS * 24 * 60 * 60 * 1000).toISOString();
  registro.premiumHasta = premiumHasta;
  registro.historial = [
    ...(registro.historial || []),
    {
      tipo: "pago_aprobado",
      paymentId,
      preferenceId: detail.preferenceId || null,
      feature: detail.feature || "M/A",
      status: detail.status || "approved",
      premiumHasta,
      createdAt: new Date().toISOString()
    }
  ].slice(-50);
  guardarRegistro(userId, registro);
  sincronizarPlanUsuario(userId, premiumHasta);
  return {
    ok: true,
    premiumActivo: true,
    premiumHasta,
    paymentId,
    status: detail.status || "approved",
    feature: detail.feature || "M/A"
  };
}

export default {
  PLAN,
  ALCANCE_PREMIUM,
  explicarPlan,
  obtenerEstado,
  obtenerMateriales(userId) {
    return {
      userId,
      backupMaterial:
        usuariosMemoria.obtenerOMaterializarBackupMaterial(userId)
    };
  },
  registrarCheckout,
  montoEsperado,
  activarPremium,
  sincronizarPlanUsuario,
  obtenerPrecioPremium
};
