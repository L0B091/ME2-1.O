import path from "path";

// Fuente única: android/app/src/main/assets/ME2_MEDIA (las rutas son relativas a assets/; el backend no abre los
// archivos: informa la ruta y el id = nombre de archivo sin extensión, igual que el id V1 de MediaLibrary).

const catalogo = {
  alegre: [
    { etiqueta: "alegre", archivo: "ME2_MEDIA/02_REACCIONES/ALEGRIA/ALEGRIA_MEDIO_001.mp4" },
    { etiqueta: "alegre", archivo: "ME2_MEDIA/02_REACCIONES/ALEGRIA/ALEGRIA_MAXIMO_002.mp4" }
  ],
  atenta: [
    { etiqueta: "atenta", archivo: "ME2_MEDIA/03_CONVERSACION/ATENCION/ATENCION_001.mp4" },
    { etiqueta: "atenta", archivo: "ME2_MEDIA/03_CONVERSACION/PENSANDO/PENSANDO_001.mp4" }
  ],
  calida: [
    { etiqueta: "calida", archivo: "ME2_MEDIA/01_LOOP_NEUTRAL/NEUTRAL_001.mp4" },
    { etiqueta: "calida", archivo: "ME2_MEDIA/02_REACCIONES/AFECTO/AFECTO_NORMAL_001.mp4" }
  ],
  aliviada: [
    { etiqueta: "aliviada", archivo: "ME2_MEDIA/02_REACCIONES/EMPATIA/EMPATIA_NORMAL_001.mp4" },
    { etiqueta: "aliviada", archivo: "ME2_MEDIA/02_REACCIONES/ALIVIO/ALIVIO_NORMAL_001.mp4" }
  ],
  agradecida: [
    { etiqueta: "agradecida", archivo: "ME2_MEDIA/02_REACCIONES/AFECTO/AFECTO_NORMAL_002.mp4" }
  ],
  texting: [
    { etiqueta: "texting", archivo: "ME2_MEDIA/03_CONVERSACION/PROCESANDO/PROCESANDO_001.mp4" }
  ]
};

function determinarCategoria(contexto = {}, expresion = {}) {
  const estadoVisual =
    contexto.personalidad?.ejes?.H?.estadoVisual?.video || "";
  if (estadoVisual.includes("interactivo")) return "texting";
  if (estadoVisual.includes("calmo")) return "aliviada";
  if (estadoVisual.includes("relajado")) return "calida";
  const emocion = contexto.entradaProcesada?.emocion || "neutral";
  const tipo = contexto.entradaProcesada?.intencion || "comentario";
  const tono = expresion?.tono || "calido";

  if (tipo === "pregunta") return "atenta";
  if (tipo === "agradecimiento") return "agradecida";
  if (emocion === "triste") return "aliviada";
  if (emocion === "feliz" || tono === "alegre") return "alegre";
  if (contexto.memoriaSistema?.memoriaCorta?.intencionDetectada === "desarrollo") return "texting";
  return "calida";
}

function seleccionarVideo(contexto = {}, expresion = {}) {
  const categoria = determinarCategoria(contexto, expresion);
  const opciones = catalogo[categoria] || catalogo.calida;
  const sugerencia =
    contexto.personalidad?.personalidadVisual?.estilo?.tono;
  const indice = sugerencia === "cálido" && opciones.length > 1 ? 1 : 0;
  const seleccionado = opciones[indice];
  return {
    categoria,
    etiqueta: seleccionado.etiqueta,
    assetPath: seleccionado.archivo,
    assetName: path.basename(seleccionado.archivo),
    mediaId: path.basename(seleccionado.archivo, path.extname(seleccionado.archivo)),
    loop: true
  };
}

export default {
  seleccionarVideo
};
