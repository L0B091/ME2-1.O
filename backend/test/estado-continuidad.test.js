import test from "node:test";
import assert from "node:assert/strict";
import estadoEmocional, { detectar } from "../memoria/estadoEmocional.js";
import continuidad from "../memoria/continuidad.js";
import storage from "../utils/jsonStorage.js";

test("detecta emoción con negación simple", () => {
  assert.equal(detectar("hoy estoy re cansado").emocion, "cansancio");
  assert.equal(detectar("no estoy triste, tranqui"), null);
  assert.equal(detectar("solo quería saludar"), null);
});

test("estado emocional y pendientes se persisten por usuario", () => {
  const u = `test-estado-${Date.now()}`;
  estadoEmocional.registrar(u, "estoy muy estresado con el laburo");
  assert.equal(estadoEmocional.resumen(u).emocion, "ansiedad");
  continuidad.registrar(u, "mañana tengo una entrevista de trabajo");
  assert.equal(continuidad.pendientesVigentes(u)[0].texto, "mañana tengo una entrevista de trabajo");
  storage.writeUserData("estado_emocional", u, {});
  storage.writeUserData("continuidad", u, {});
});
