import test from "node:test";
import assert from "node:assert/strict";
import { extraerHechos } from "../memoria/memoriaConversacional.js";
import { detectarAlarma } from "../modulos/detectorAlarmas.js";

test("extrae nombre, ciudad, gustos y hechos; ignora preguntas", () => {
  const a = extraerHechos("Hola, me llamo Emanuel y vivo en San Nicolás. Me gusta el rock y Boca Juniors.");
  assert.equal(a.nombre, "Emanuel");
  assert.equal(a.ciudad, "San Nicolás");
  assert.deepEqual(a.gustos, ["rock", "boca juniors"]);
  assert.equal(extraerHechos("¿Cómo me llamo y qué cosas me gustan?").nombre, null);
  assert.deepEqual(extraerHechos("Mi perro se llama Toby.").hechos, ["mi perro se llama Toby"]);
});

test("detecta alarmas en lenguaje natural", () => {
  assert.equal(detectarAlarma("despertame a las 7:30").hora, "07:30");
  assert.equal(detectarAlarma("poné una alarma a las 19:40").hora, "19:40");
  assert.equal(detectarAlarma("alarma a las 8 y media de la noche").hora, "20:30");
  assert.equal(detectarAlarma("cancelá la alarma de las 7:30").accion, "cancelar");
  assert.equal(detectarAlarma("recordame comprar pan"), null);
});
