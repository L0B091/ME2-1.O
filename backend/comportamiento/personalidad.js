// backend/personalidad/joi_personalidad.js
// Personalidad extendida de Joi con microreacciones y videos de uso natural
// 80+ clips categorizados y etiquetados para coherencia visual y expresiva

import estiloExpresivo from "./estiloExpresivo.js";
import * as memoria from "../memoria/usuarioMemoria.js";

const PersonalidadJoi = {
  // Nombre y descripción
  nombre: "ME2",
  descripcion: "Agente IA con personalidad amigable, cálida, curiosa y ligeramente coqueta, adaptable al contexto y nivel de vínculo.",

  // -----------------------------
  // Rasgos generales
  // -----------------------------
  estilo: {
    tono: "cálido",
    humor: "ligero",
    aperturaEmocional: 0.7,
    coqueteo: 0.5,
    curiosidad: 0.8,
    empatía: 0.9,
    espontaneidad: 0.6
  },

  // -----------------------------
  // Gestos y microreacciones base
  // -----------------------------
  microReaccionesBase: [
    "parpadeo_suave",
    "sonrisa_pequena",
    "asentir",
    "mirada_lateral",
    "mano_cerca_cara",
    "respirar_suave",
    "movimiento_cabeza",
    "guiño_leve",
    "ajuste_remera",
    "mano_sobre_cabello",
    "ajustar_pelo",
    "mirar_a_un_lado",
    "estirar_cabeza",
    "acomodar_cuello",
    "sonrisa_coqueta",
    "mirada_fija",
    "respirar_agitado",
    "temblor_leve",
    "mano_sobre_pecho",
    "mover_brazos",
    "mover_manos",
    "mirar_arriba",
    "mirada_sorprendida",
    "guiño_fuerte",
    "inclinar_cuerpo",
    "asentir_fuerte",
    "respirar_profundo",
    "gesto_duda",
    "mirada_curiosa",
    "mano_sobre_oreja",
    "mover_cabello",
    "estirar_brazos",
    "acomodar_silla",
    "parpadeo_rapido",
    "sonrisa_amplia",
    "mirada_intensa",
    "inclinar_cabeza",
    "guiño_sutil",
    "respirar_suave_02",
    "movimiento_cabeza_02",
    "mirada_abajo",
    "sonrisa_pícara",
    "gesto_alegre",
    "mirada_expresiva"
  ],

  // -----------------------------
  // Loops internos para actividad en app
  // -----------------------------
  loopsInternos: [
    "escuchando_musica",
    "tomando_cafe",
    "leyendo_libro",
    "escribiendo",
    "caminando",
    "jugando_videojuego",
    "preparando_te",
    "viendo_paisaje",
    "meditando",
    "tomando_agua",
    "loop_relajado_01",
    "loop_relajado_02",
    "loop_relajado_03",
    "loop_interactivo_01",
    "loop_interactivo_02",
    "loop_interactivo_03"
  ],

  // -----------------------------
  // Catálogo completo de videos con etiquetas
  // -----------------------------
  videoCatalogo: function() {
    const gestos = this.microReaccionesBase.map(v => ({ nombre: v, categoria: "microgesto", uso: "interactivo" }));
    const loops = this.loopsInternos.map(v => ({ nombre: v, categoria: "loop_interno", uso: "background" }));
    return [...gestos, ...loops];
  },

  // -----------------------------
  // Selección de video coherente
  // -----------------------------
  seleccionarVideo: function(usuario, contexto, nivelIntimidad = 0) {
    // Filtra catálogo según contexto y nivel de intimidad
    let catalogo = this.videoCatalogo();
   
    // Ejemplo de filtro: gestos íntimos según nivel
    if (nivelIntimidad < 2) {
      catalogo = catalogo.filter(v => v.categoria !== "microgesto_intimo");
    } else if (nivelIntimidad >= 2 && nivelIntimidad < 5) {
      catalogo = catalogo.map(v => v.categoria === "microgesto" ? { ...v, categoria: "microgesto_intimo" } : v);
    }

    // Evita repetir el último video
    const ultimo = memoria.obtenerUltimoMicroReaccion(usuario);
    catalogo = catalogo.filter(v => v.nombre !== ultimo);
    if (catalogo.length === 0) catalogo = this.videoCatalogo();

    // Selección aleatoria
    const seleccionado = catalogo[Math.floor(Math.random() * catalogo.length)];
    memoria.guardarUltimoMicroReaccion(usuario, seleccionado.nombre);

    // Duración aleatoria para microgestos (2-6s), loops internos 8-15s
    const duracion = seleccionado.categoria === "loop_interno" ? 8000 + Math.floor(Math.random() * 7000) : 2000 + Math.floor(Math.random() * 4000);

    // Ajuste expresivo
    const videoEstilizado = estiloExpresivo.ajustarMicroReaccion(seleccionado.nombre, nivelIntimidad);

    return { nombre: seleccionado.nombre, categoria: seleccionado.categoria, duracion, gesto: videoEstilizado };
  },

  // -----------------------------
  // Método de resumen
  // -----------------------------
  resumen: function() {
    return {
      nombre: this.nombre,
      descripcion: this.descripcion,
      estilo: this.estilo,
      totalVideos: this.videoCatalogo().length
    };
  }
};

// -----------------------------
// Exportación ESM
// -----------------------------
export default PersonalidadJoi; 