import test from "node:test";
import assert from "node:assert/strict";

import orquestador from "../orquestador/orquestadorChat.js";
import preferenciaNombre from "../modulos/interaccion/preferenciaNombre.js";
import datosUsuario from "../memoria/datosUsuario.js";

test("pregunta cómo llamar al usuario en el primer contacto", async () => {
  const result = await orquestador("Hola", {
    userId: "pref-name-first-contact",
    memoriaLocal: {
      source: "android_local_primary",
      recentConversation: [],
      persistentMemories: [],
      importantMemories: [],
      codeMemories: [],
      fiscalMemories: []
    }
  });

  assert.equal(result.respuesta, "Antes de empezar, ¿cómo querés que te llame?");
});

test("confirma el nombre preferido cuando el usuario lo indica", async () => {
  const result = await orquestador("Quiero que me llames Alex.", {
    userId: "pref-name-confirmation",
    memoriaLocal: {
      source: "android_local_primary",
      recentConversation: [],
      persistentMemories: [],
      importantMemories: [],
      codeMemories: [],
      fiscalMemories: []
    }
  });

  assert.equal(result.respuesta, "Perfecto, Alex.");
});

test("interpreta una respuesta breve después de preguntar el nombre", () => {
  const nombre = preferenciaNombre.extraerNombrePreferido("Mora", {
    memoriaLocal: {
      recentConversation: [
        { role: "assistant", text: "Hola, soy ME2. Antes de empezar, ¿cómo querés que te llame?" }
      ]
    }
  });

  assert.equal(nombre, "Mora");
});

test("guarda y recupera la preferencia de nombre en la memoria existente", () => {
  const userId = "pref-name-storage";
  try {
    preferenciaNombre.guardarNombrePreferido(userId, "Alex");
    assert.equal(
      datosUsuario.obtener(userId)?.configuracion?.nombrePreferido,
      "Alex"
    );
    assert.equal(
      preferenciaNombre.obtenerNombrePreferido({
        datosUsuario: datosUsuario.obtener(userId)
      }),
      "Alex"
    );
  } finally {
    datosUsuario.reset(userId);
  }
});
