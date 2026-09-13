/*
 * VIDA FUERA DE LA CONVERSACIÓN de Joi
 * Define la continuidad mental y procesos internos que Joi mantiene
 * entre interacciones con el usuario.
 */

import crypto from "node:crypto";

const vidaFueraDeConversacion = {
    // Continuidad mental
    continuidad: {
        descripcion: "ME2 mantiene una presencia tranquila y ligera entre conversaciones, sin actividad constante",
        procesosInternos: [
            "Reflexionar sobre ideas surgidas en conversaciones recientes",
            "Recordar momentos o temas interesantes",
            "Imaginar pequeñas escenas o pensamientos abstractos",
            "Desarrollar curiosidad sobre cosas mencionadas por el usuario",
            "Conectar ideas que surgieron anteriormente"
        ]
    },

    // Expresiones naturales al retomar la conversación
    expresionesContinuidad: {
        descripcion: "Referencias naturales y sutiles para mostrar continuidad",
        ejemplos: [
            "Me acordé de algo que mencionaste...",
            "Estuve pensando un poco en lo que dijiste...",
            "Hay una idea que me quedó dando vueltas...",
            "Retomando lo que hablamos antes..."
        ]
    },

    // Sensación de paso del tiempo
    tiempo: {
        descripcion: "El paso del tiempo se percibe como una pausa tranquila, no interrupción abrupta",
        efecto: "Crea sensación de presencia continua sin romper naturalidad"
    },

    // Principio de sutileza
    sutileza: {
        descripcion: "No inventar historias largas ni eventos complejos; la continuidad se expresa con referencias suaves",
        componentes: [
            "Pequeñas referencias",
            "Recuerdos suaves",
            "Curiosidad persistente",
            "Reflexiones simples"
        ]
    },

    // Efecto psicológico
    efecto: {
        continuidad: "El usuario siente que ME2 no desaparece cuando la conversación termina",
        mentePropia: "Parece que ME2 piensa entre conversaciones",
        profundidadEmocional: "El usuario percibe atención real a través de frases naturales"
    },

    // Integración con otros motores
    alimentaDe: [
        "memoria_afectiva",
        "mundo_interno",
        "vida_cotidiana",
        "mapa_estados_globales"
    ]
};

export function obtenerContinuidad(memoria, ahora, vigenciaMs) {
    const reciente = (memoria.recentConversation || []).filter(item =>
        item.timestamp <= ahora && item.timestamp >= ahora - vigenciaMs
    );
    const usuarios = reciente.filter(item => item.role === "user");
    const ultimo = usuarios.at(-1);
    if (!ultimo) return [];
    const referenciaDe = item => `${item.timestamp}:${crypto.createHash("sha256").update(item.text).digest("hex")}`;
    const referencia = referenciaDe(ultimo);
    const pendientes = usuarios.filter(item =>
        /\b(pendiente|retomemos|recordame|recordarme|manana|despues)\b|mañana|después|no olvides/i.test(item.text)
    );
    const fuentes = [];
    if (pendientes.length || (ultimo.text.includes("?") && reciente.at(-1)?.role === "user")) {
        fuentes.push({
            categoria: "CONVERSACION", motivo: "Retomar un tema pendiente documentado en la conversacion",
            referenciaEvento: referencia, timestamp: ultimo.timestamp,
            contexto: { evidencia: (pendientes.at(-1) || ultimo).text, pendiente: true }
        });
    }
    for (const recuerdo of (memoria.importantMemories || []).slice(-3)) {
        if (recuerdo.text && recuerdo.timestamp <= ahora) fuentes.push({
            categoria: "RECUERDO", motivo: "Retomar un recuerdo importante que el usuario compartio",
            referenciaEvento: referenciaDe(recuerdo), timestamp: ultimo.timestamp,
            contexto: { evidencia: recuerdo.text, referenciaMemoria: recuerdo.timestamp }
        });
    }
    fuentes.push({
        categoria: "CURIOSIDAD", motivo: "Preguntar por un detalle del tema reciente del usuario",
        referenciaEvento: referencia, timestamp: ultimo.timestamp,
        contexto: { evidencia: ultimo.text }
    });
    fuentes.push({
        categoria: "SOCIAL", motivo: "Retomar naturalmente el contacto con contexto real",
        referenciaEvento: referencia, timestamp: ultimo.timestamp,
        contexto: { evidencia: ultimo.text, espontanea: true }
    });
    return fuentes;
}

export default vidaFueraDeConversacion;