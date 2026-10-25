import test from "node:test";
import assert from "node:assert/strict";
import rav, { GESTOS, REACCIONES_V1, parsearGesto } from "../modulos/media/reaccionAudiovisual.js";

test("gestos nuevos están en REACCIONES_V1", () => {
  for (const g of GESTOS) assert.ok(REACCIONES_V1.includes(g.toUpperCase()), g);
});
test("parsearGesto quita la pista y valida el nombre", () => {
  assert.deepEqual(parsearGesto("¡Se me ocurre algo!\n[[gesto: idea]]"), { texto: "¡Se me ocurre algo!", gesto: "IDEA" });
  assert.deepEqual(parsearGesto("Mm no [[gesto: Negación]]"), { texto: "Mm no", gesto: "NEGACION" });
  assert.deepEqual(parsearGesto("hola [[gesto: bailar]]"), { texto: "hola", gesto: null });
  assert.equal(parsearGesto("sin pista").gesto, null);
});
test("pista del LLM tiene prioridad sobre señales; saludo sigue primero", () => {
  assert.equal(rav.decidir({ mensaje: "jajaja", gesto: "beso" }).subcategoria, "BESO");
  assert.equal(rav.decidir({ mensaje: "hola", gesto: "beso" }).subcategoria, "SALUDO");
  assert.equal(rav.decidir({ mensaje: "jajaja", gesto: "inventado" }).subcategoria, "RISAS");
});
test("señales del usuario → gestos", () => {
  const c = (m) => rav.decidir({ mensaje: m }).subcategoria;
  assert.equal(c("que asco eso"), "DISGUSTO");
  assert.equal(c("te mando un beso 😘"), "BESO");
  assert.equal(c("tengo una idea 💡"), "IDEA");
  assert.equal(c("ni loca hago eso"), "NEGACION");
  assert.equal(c("exacto, tal cual 👍"), "CONFIRMACION");
  assert.equal(c("me sacaste una sonrisa"), "SONRISA");
  assert.notEqual(c("dale, nos hablamos"), "CONFIRMACION");
});
