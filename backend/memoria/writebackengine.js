// memoria/writeBackEngine.js

import memoriaPersistente from "./memoriaPersistente.js";
import recuerdosImportantes from "./recuerdosImportantes.js";
import datosUsuario from "./datosUsuario.js";
import historialConversacion from "./historialConversacion.js";
import codigoMemoria from "./codigoMemoria.js";
import documentosFiscales from "./documentosFiscales.js";

const IGNORED_EXPRESSIONS = new Set([
  "hola",
  "hola me2",
  "buenas",
  "buenos dias",
  "buenas tardes",
  "buenas noches",
  "gracias",
  "gracias me2",
  "ok",
  "okay",
  "dale"
]);

function evaluarWriteBack({
  userId,
  mensaje = "",
  entradaProcesada = null
}) {
  if (!userId || typeof mensaje !== "string" || !mensaje.trim()) {
    return null;
  }

  const original = obtenerTexto(
    entradaProcesada,
    mensaje
  );

  const texto = normalizar(original);

  if (!texto) {
    return {
      guardado: false,
      motivo: "sin_valor_de_memoria"
    };
  }

  const respuestaContextual =
    resolverRespuestaContextual(
      userId,
      original,
      texto
    );

  if (respuestaContextual) {
    return guardarDecision(
      userId,
      respuestaContextual.texto,
      respuestaContextual.decision,
      entradaProcesada
    );
  }

  if (noGuardar(texto)) {
    return {
      guardado: false,
      motivo: "sin_valor_de_memoria"
    };
  }

  let decision = decidir(texto);

  if (!decision.save) {
    const especial = detectarMemoriaEspecializada(texto, original);
    if (especial?.save) {
      try {
        return guardarEspecializada(userId, original, especial);
      } catch (error) {
        return {
          guardado: false,
          motivo: "error_memoria_especializada",
          error: error?.message || String(error)
        };
      }
    }
    return {
      guardado: false,
      motivo: "sin_informacion_persistente"
    };
  }

  if (decision.tipo === "codigo" || decision.tipo === "fiscal") {
    try {
      return guardarEspecializada(userId, original, decision);
    } catch (error) {
      return {
        guardado: false,
        motivo: "error_memoria_especializada",
        error: error?.message || String(error)
      };
    }
  }

  return guardarDecision(
    userId,
    original,
    decision,
    entradaProcesada
  );
}

function guardarDecision(
  userId,
  original,
  decision,
  entradaProcesada = null
) {
  const resultados = [];
  const timestamp = Date.now();

  if (decision.usuario) {
    const perfil = datosUsuario.aprender(
      userId,
      entradaProcesada || original
    );

    resultados.push({
      tipo: "datosUsuario",
      actualizado: !!perfil
    });
  }

  if (decision.tipo === "recuerdo") {
    const clave = generarClave(original);

    recuerdosImportantes.guardar(
      userId,
      clave,
      original
    );

    resultados.push({
      tipo: "recuerdo",
      clave: clave,
      categoria: decision.categoria,
      importancia: decision.importancia
    });
  }

  if (decision.tipo === "persistente") {
    const registro = {
      texto: original,
      categoria: decision.categoria,
      importancia: decision.importancia,
      timestamp: timestamp
    };

    const temporal = detectarContextoTemporal(
      normalizar(original)
    );

    if (temporal) {
      registro.contextoTemporal = temporal;
    }

    memoriaPersistente.agregar(
      userId,
      registro
    );

    resultados.push({
      tipo: "persistente",
      categoria: decision.categoria,
      importancia: decision.importancia,
      contextoTemporal: temporal
    });
  }

  return {
    guardado: resultados.length > 0,
    resultados: resultados,
    timestamp: timestamp
  };
}

function resolverRespuestaContextual(
  userId,
  original,
  texto
) {
  if (texto !== "si" && texto !== "no") {
    return null;
  }

  const historial =
    historialConversacion.obtenerHistorial(
      userId,
      10
    );

  if (!Array.isArray(historial) || historial.length === 0) {
    return null;
  }

  let pregunta = null;

  for (let i = historial.length - 1; i >= 0; i--) {
    const mensaje = historial[i];

    // historialConversacion stores `tipo` ("asistente"|"usuario"), not `rol`
    const rol =
      mensaje?.tipo ||
      mensaje?.rol ||
      mensaje?.role ||
      "";
    const esAsistente =
      rol === "asistente" ||
      rol === "assistant" ||
      rol === "me2";
    const textoMensaje =
      typeof mensaje?.mensaje === "string"
        ? mensaje.mensaje
        : typeof mensaje?.text === "string"
          ? mensaje.text
          : typeof mensaje?.contenido === "string"
            ? mensaje.contenido
            : null;
    if (mensaje && esAsistente && textoMensaje) {
      pregunta = textoMensaje;
      break;
    }
  }

  if (!pregunta) {
    return null;
  }

  const tipoPregunta =
    detectarTipoPregunta(pregunta);

  if (!tipoPregunta) {
    return null;
  }

  const contenido =
    extraerContenidoPregunta(
      pregunta,
      tipoPregunta
    );

  if (!contenido) {
    return null;
  }

  const resultado =
    construirRespuestaContextual(
      tipoPregunta,
      contenido,
      texto === "si"
    );

  if (!resultado) {
    return null;
  }

  return {
    texto: resultado.texto,
    decision: {
      save: true,
      tipo: "persistente",
      categoria: resultado.categoria,
      importancia: 2,
      usuario: true
    }
  };
}

function detectarTipoPregunta(pregunta) {
  const original = pregunta.trim();

  if (!original.includes("?")) {
    return null;
  }

  const texto = normalizar(original);

  if (contiene(texto, [
    "te gusta",
    "te encanta",
    "prefieres",
    "preferis",
    "odias",
    "te molesta"
  ])) {
    return "preferencia";
  }

  if (contiene(texto, [
    "te interesa",
    "quieres aprender",
    "estas aprendiendo",
    "te interesa aprender"
  ])) {
    return "interes";
  }

  if (contiene(texto, [
    "trabajas como",
    "trabajas en",
    "trabajas de",
    "te dedicas a",
    "tu profesion",
    "tu trabajo"
  ])) {
    return "trabajo";
  }

  if (contiene(texto, [
    "vives en",
    "eres de"
  ])) {
    return "ubicacion";
  }

  return null;
}

function extraerContenidoPregunta(
  pregunta,
  tipoPregunta
) {
  const texto = normalizar(pregunta);

  const patrones = {
    preferencia: [
      "te gusta",
      "te encanta",
      "prefieres",
      "preferis",
      "odias",
      "te molesta"
    ],

    interes: [
      "te interesa aprender",
      "te interesa",
      "quieres aprender",
      "estas aprendiendo"
    ],

    trabajo: [
      "trabajas como",
      "trabajas en",
      "trabajas de",
      "te dedicas a",
      "tu profesion",
      "tu trabajo"
    ],

    ubicacion: [
      "vives en",
      "eres de"
    ]
  };

  const lista = patrones[tipoPregunta] || [];

  for (const patron of lista) {
    const patronNormalizado =
      normalizar(patron);

    const indice =
      texto.indexOf(patronNormalizado);

    if (indice === -1) {
      continue;
    }

    let contenido = texto
      .slice(
        indice + patronNormalizado.length
      )
      .trim();

    /*
     * Una respuesta "si/no" no permite resolver
     * una pregunta con dos alternativas.
     *
     * Ejemplo:
     * "¿Te gusta el cafe o prefieres el te?"
     *
     * No debemos guardar "el cafe".
     */
    if (/\s+o\s+/.test(contenido)) {
      return null;
    }

    contenido = contenido
      .replace(/\?/g, "")
      .trim();

    contenido = contenido
      .replace(
        /\b(o no|verdad|o que|tambien)\b/gi,
        ""
      )
      .trim();

    contenido = normalizarPronombres(
      contenido,
      tipoPregunta
    );

    if (
      contenido &&
      contenido.length > 2
    ) {
      return contenido;
    }
  }

  return null;
}

function normalizarPronombres(
  contenido,
  tipoPregunta
) {
  if (tipoPregunta === "preferencia") {
    return contenido
      .replace(/\btu\b/gi, "su")
      .replace(/\btus\b/gi, "sus");
  }

  return contenido;
}

function construirRespuestaContextual(
  tipoPregunta,
  contenido,
  afirmacion
) {
  if (tipoPregunta === "preferencia") {
    return {
      texto: afirmacion
        ? "Al usuario le gusta " + contenido
        : "Al usuario no le gusta " + contenido,
      categoria: "preferencias"
    };
  }

  if (tipoPregunta === "interes") {
    return {
      texto: afirmacion
        ? "Al usuario le interesa " + contenido
        : "Al usuario no le interesa " + contenido,
      categoria: "intereses"
    };
  }

  if (tipoPregunta === "trabajo") {
    const prefijo =
      /^(como|en|de)\b/i.test(contenido)
        ? ""
        : "como ";

    return {
      texto: afirmacion
        ? "El usuario trabaja " +
          prefijo +
          contenido
        : "El usuario no trabaja " +
          prefijo +
          contenido,
      categoria: "perfil"
    };
  }

  if (tipoPregunta === "ubicacion") {
    const prefijo =
      /^(en)\b/i.test(contenido)
        ? ""
        : "en ";

    return {
      texto: afirmacion
        ? "El usuario vive " +
          prefijo +
          contenido
        : "El usuario no vive " +
          prefijo +
          contenido,
      categoria: "perfil"
    };
  }

  return null;
}

function obtenerTexto(
  entrada,
  mensaje
) {
  if (
    entrada &&
    typeof entrada === "object"
  ) {
    if (
      typeof entrada.textoOriginal === "string"
    ) {
      return entrada.textoOriginal.trim();
    }

    if (
      typeof entrada.textoNormalizado === "string"
    ) {
      return entrada.textoNormalizado.trim();
    }
  }

  if (typeof entrada === "string") {
    return entrada.trim();
  }

  return mensaje.trim();
}

function normalizar(texto) {
  return texto
    .toLowerCase()
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[¿?¡!.,;:()[\]{}"']/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function decidir(texto) {
  if (contiene(texto, [
    "recuerda",
    "recorda",
    "recordar",
    "recuerdalo",
    "recuerdame",
    "recordame",
    "guardalo",
    "guarda esto",
    "no lo olvides",
    "no olvides",
    "ten presente",
    "tenlo presente",
    "quiero que recuerdes"
  ])) {
    return {
      save: true,
      tipo: "recuerdo",
      categoria: categoria(texto),
      importancia: 3,
      usuario: esDatoUsuario(texto)
    };
  }

  if (contiene(texto, [
    "mi objetivo",
    "mi meta",
    "mi proposito",
    "quiero lograr",
    "quiero conseguir",
    "quiero alcanzar",
    "he decidido",
    "decidi que",
    "es importante para mi",
    "esto es importante"
  ])) {
    return {
      save: true,
      tipo: "recuerdo",
      categoria: categoria(texto),
      importancia: 3,
      usuario: false
    };
  }

  if (esPersona(texto)) {
    return {
      save: true,
      tipo: "persistente",
      categoria: "personas",
      importancia: 2,
      usuario: false
    };
  }

  if (esDatoUsuario(texto)) {
    return {
      save: true,
      tipo: "persistente",
      categoria: categoria(texto),
      importancia: 2,
      usuario: true
    };
  }

  if (contiene(texto, [
    "mi proyecto",
    "nuestro proyecto",
    "mi app",
    "mi aplicacion",
    "estoy desarrollando",
    "estoy creando",
    "quiero crear",
    "voy a crear",
    "voy a desarrollar",
    "estoy construyendo"
  ])) {
    return {
      save: true,
      tipo: "persistente",
      categoria: "proyecto",
      importancia: 2,
      usuario: false
    };
  }

  if (contiene(texto, [
    "mi negocio",
    "mi empresa",
    "quiero vender",
    "voy a vender",
    "quiero cobrar",
    "mis clientes",
    "mi cliente",
    "modelo de negocio",
    "ganar dinero",
    "generar dinero"
  ])) {
    return {
      save: true,
      tipo: "persistente",
      categoria: "negocio",
      importancia: 2,
      usuario: false
    };
  }

  if (contiene(texto, [
    "mañana voy",
    "manana voy",
    "la semana que viene",
    "el mes que viene",
    "voy a",
    "pienso hacer",
    "planeo",
    "tengo pensado"
  ])) {
    return {
      save: true,
      tipo: "persistente",
      categoria: "planes",
      importancia: 1,
      usuario: false
    };
  }

  if (contiene(texto, [
    "me interesa",
    "estoy interesado",
    "estoy interesada",
    "me gusta aprender",
    "quiero aprender",
    "estoy aprendiendo",
    "me dedico a"
  ])) {
    return {
      save: true,
      tipo: "persistente",
      categoria: "intereses",
      importancia: 1,
      usuario: true
    };
  }

  if (contiene(texto, [
    "me2 debe",
    "me2 tiene que",
    "quiero que me2",
    "me2 nunca",
    "me2 siempre",
    "quiero que vos",
    "quiero que me hables"
  ])) {
    return {
      save: true,
      tipo: "persistente",
      categoria: "preferencias_asistente",
      importancia: 2,
      usuario: false
    };
  }

  return {
    save: false
  };
}

function esDatoUsuario(texto) {
  return contiene(texto, [
    "me llamo",
    "mi nombre es",
    "soy de",
    "vivo en",
    "trabajo de",
    "trabajo como",
    "trabajo en",
    "mi trabajo",
    "mi profesion",
    "me dedico a",
    "me gusta",
    "me encanta",
    "no me gusta",
    "odio",
    "prefiero",
    "no quiero que",
    "me molesta"
  ]);
}

function esPersona(texto) {
  return contiene(texto, [
    "mi madre",
    "mi mama",
    "mi padre",
    "mi papa",
    "mi hermano",
    "mi hermana",
    "mi hijo",
    "mi hija",
    "mi pareja",
    "mi amigo",
    "mi amiga",
    "mi jefe",
    "mi compañero",
    "mi companero",
    "mi compañera",
    "mi companera",
    "se llama",
    "se llaman"
  ]);
}

function noGuardar(texto) {
  if (texto.length < 4) {
    return true;
  }

  if (IGNORED_EXPRESSIONS.has(texto)) {
    return true;
  }

  return contiene(texto, [
    "que hora es",
    "como estas",
    "que dia es",
    "que tiempo hace",
    "va a llover",
    "llueve ahora",
    "cuanto falta"
  ]);
}

function detectarContextoTemporal(texto) {
  if (contiene(texto, [
    "hoy",
    "esta tarde",
    "esta noche"
  ])) {
    return "hoy";
  }

  if (contiene(texto, [
    "mañana",
    "manana"
  ])) {
    return "mañana";
  }

  if (contiene(texto, [
    "pasado mañana",
    "pasado manana"
  ])) {
    return "pasado_mañana";
  }

  if (contiene(texto, [
    "la semana que viene",
    "la proxima semana",
    "la próxima semana"
  ])) {
    return "proxima_semana";
  }

  if (contiene(texto, [
    "el mes que viene",
    "el proximo mes",
    "el próximo mes"
  ])) {
    return "proximo_mes";
  }

  return null;
}

function categoria(texto) {
  if (esPersona(texto)) {
    return "personas";
  }

  if (contiene(texto, [
    "proyecto",
    "mi app",
    "mi aplicacion",
    "estoy desarrollando",
    "estoy creando",
    "quiero crear"
  ])) {
    return "proyecto";
  }

  if (contiene(texto, [
    "negocio",
    "empresa",
    "cliente",
    "clientes",
    "vender",
    "cobrar",
    "dinero"
  ])) {
    return "negocio";
  }

  if (contiene(texto, [
    "me gusta",
    "me encanta",
    "no me gusta",
    "prefiero",
    "odio",
    "me molesta"
  ])) {
    return "preferencias";
  }

  if (contiene(texto, [
    "mañana",
    "manana",
    "semana que viene",
    "mes que viene",
    "voy a",
    "planeo"
  ])) {
    return "planes";
  }

  if (contiene(texto, [
    "trabajo",
    "profesion",
    "me dedico a"
  ])) {
    return "perfil";
  }

  if (contiene(texto, [
    "me interesa",
    "quiero aprender",
    "estoy aprendiendo"
  ])) {
    return "intereses";
  }

  if (contiene(texto, [
    "me2 debe",
    "me2 tiene que",
    "quiero que me2",
    "me2 nunca",
    "me2 siempre",
    "quiero que vos",
    "quiero que me hables"
  ])) {
    return "preferencias_asistente";
  }

  return "general";
}

function contiene(texto, lista) {
  return lista.some(function (item) {
    const patronNormalizado =
      normalizar(item);

    const escaped =
      patronNormalizado.replace(
        /[.*+?^${}()|[\]\\]/g,
        "\\$&"
      );

    const regex = new RegExp(
      "\\b" +
      escaped +
      "\\b",
      "i"
    );

    return regex.test(texto);
  });
}

function generarClave(texto) {
  const fragmento = texto
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[^a-zA-Z0-9\s]/g, "")
    .trim()
    .slice(0, 40)
    .replace(/\s+/g, "_");

  const aleatorio = Math.random()
    .toString(36)
    .substring(2, 7);

  return "recuerdo_" +
    Date.now() +
    "_" +
    aleatorio +
    "_" +
    (fragmento || "general");
}


/**
 * Detecta y persiste memorias de código / fiscales vía el mismo write-back.
 * Android también escribe localmente; esto sincroniza el backend premium store.
 */
function detectarMemoriaEspecializada(texto, original) {
  const codigoHints = [
    "mi codigo", "mi código", "funcion ", "función ", "class ",
    "const ", "import ", "export ", "bug ", "stacktrace", "repo ",
    "archivo .js", "archivo .kt", "archivo .py", "pull request", "commit "
  ];
  const fiscalHints = [
    "factura", "monotributo", "afip", "comprobante", "iva ",
    "recibo", "cobro", "pago a", "cuit", "cuil", "contador",
    "factura a", "factura b", "factura c", "retencion", "retención"
  ];

  if (contiene(texto, codigoHints)) {
    return {
      save: true,
      tipo: "codigo",
      categoria: "codigo",
      importancia: 2,
      usuario: false
    };
  }
  if (contiene(texto, fiscalHints)) {
    const movimiento = contiene(texto, ["cobro", "me pagaron", "me depositaron"])
      ? "cobro"
      : contiene(texto, ["pague", "pagué", "pago a", "transferi", "transferí"])
        ? "pago"
        : "neutral";
    return {
      save: true,
      tipo: "fiscal",
      categoria: "fiscal",
      importancia: 2,
      usuario: false,
      movimiento
    };
  }
  return null;
}

function guardarEspecializada(userId, original, decision) {
  if (decision.tipo === "codigo") {
    const archivo = codigoMemoria.guardarArchivo(userId, {
      nombre: `nota_${Date.now()}.txt`,
      resumen: original.slice(0, 240),
      contenido: original,
      lenguaje: "texto",
      tags: ["writeback", "chat"]
    });
    return {
      guardado: true,
      tipo: "codigo",
      id: archivo.id,
      motivo: "memoria_codigo"
    };
  }
  if (decision.tipo === "fiscal") {
    const doc = documentosFiscales.guardarDocumento(userId, {
      tipo: "nota_chat",
      movimiento: decision.movimiento || "neutral",
      descripcion: original.slice(0, 500),
      tags: ["writeback", "chat"],
      estado: "registrado"
    });
    return {
      guardado: true,
      tipo: "fiscal",
      id: doc.id,
      motivo: "memoria_fiscal"
    };
  }
  return null;
}

export default {
  evaluarWriteBack,
  detectarMemoriaEspecializada
};
