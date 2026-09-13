import test from "node:test";
import assert from "node:assert/strict";

import orquestador from "../orquestador/orquestadorChat.js";
import preferenciaNombre from "../modulos/interaccion/preferenciaNombre.js";
import datosUsuario from "../memoria/datosUsuario.js";

test("no asigna un nombre por defecto al personaje", () => {
  assert.equal(preferenciaNombre.obtenerNombrePersonaje({}), null);
  assert.equal(preferenciaNombre.normalizarNombre("Joi"), null);
  assert.equal(preferenciaNombre.normalizarNombre("ME2"), null);
});

test("pregunta por el nombre del personaje cuando el usuario quiere asignarlo", async () => {
  const result = await orquestador("Quiero ponerte un nombre.", {
    userId: "character-name-ask",
    memoriaLocal: {
      source: "android_local_primary",
      recentConversation: [],
      persistentMemories: [],
      importantMemories: [],
      codeMemories: [],
      fiscalMemories: []
    }
  });

  assert.equal(result.respuesta, "Claro. ¿Qué nombre o nickname querés que tenga?");
});

test("interpreta una respuesta breve como nombre del personaje después de preguntar", () => {
  const nombre = preferenciaNombre.extraerNombrePersonaje("Luna", {
    memoriaLocal: {
      recentConversation: [
        { role: "assistant", text: "Claro. ¿Qué nombre o nickname querés que tenga?" }
      ]
    }
  });

  assert.equal(nombre, "Luna");
});

test("guarda y confirma el nombre del personaje", async () => {
  const userId = "character-name-save";
  try {
    const result = await orquestador("Quiero que te llames Nova.", { userId });
    assert.equal(result.respuesta, "Perfecto. Entonces voy a llamarme Nova.");
    assert.equal(
      datosUsuario.obtener(userId)?.configuracion?.nombrePersonaje,
      "Nova"
    );
  } finally {
    datosUsuario.reset(userId);
  }
});
