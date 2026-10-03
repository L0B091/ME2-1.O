import test from "node:test";
import assert from "node:assert/strict";
import crypto from "node:crypto";
import { seleccionarHechos } from "../orquestador/contextoLLM.js";
import orquestadorChat, { quitarMensajeActual } from "../orquestador/orquestadorChat.js";

test("M3: la memoria enviada al LLM se filtra por relevancia (recientes + coincidentes), no toda", () => {
  const hechos = Array.from({ length: 40 }, (_, i) => `hecho genérico número ${i}`);
  hechos[3] = "mi perro se llama Toby y le encanta correr";
  const sel = seleccionarHechos(hechos, "¿Te acordás cómo se llama mi perro?");
  assert.ok(sel.length <= 12);
  assert.ok(sel.includes("mi perro se llama Toby y le encanta correr"));
  assert.ok(sel.includes("hecho genérico número 39"), "los más recientes siempre van");
  assert.deepEqual(seleccionarHechos(["a", "b"], "x"), ["a", "b"]);
});

test("M4: el mensaje actual no se duplica en el historial enviado al LLM", async () => {
  assert.equal(quitarMensajeActual([{ tipo: "usuario", mensaje: "Hola" }], "hola").length, 0);
  assert.equal(quitarMensajeActual([{ tipo: "asistente", mensaje: "hola" }], "hola").length, 1);
  const r = await orquestadorChat("contame algo", {
    userId: `m4-${crypto.randomBytes(4).toString("hex")}`,
    memoriaLocal: {
      source: "android_local_primary", persistentMemories: [], importantMemories: [],
      recentConversation: [{ role: "assistant", text: "hola!" }, { role: "user", text: "contame algo" }]
    }
  });
  assert.equal(r.debug.historialEnviado, 1, "solo el mensaje previo del asistente");
});
