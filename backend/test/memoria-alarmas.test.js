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

test("alarma relativa ('en 10 minutos', 'en media hora') → hora local del teléfono, redondeada hacia arriba", async () => {
  const { detectarAlarma, horaRelativa } = await import("../modulos/detectorAlarmas.js");
  const ahora = Date.parse("2026-10-03T23:58:30Z"); // 20:58:30 en Buenos Aires
  const tz = { ahora, zonaHoraria: "America/Argentina/Buenos_Aires" };
  assert.equal(detectarAlarma("ponme alarma en 10 minutos", tz).hora, "21:09");
  assert.equal(detectarAlarma("poné una alarma en 2 min", tz).hora, "21:01");
  assert.equal(detectarAlarma("alarma en media hora", tz).hora, "21:29");
  assert.equal(detectarAlarma("despertame en una hora", tz).titulo, "Hora de despertar");
  assert.equal(detectarAlarma("poneme una alarma a las 7", tz).hora, "07:00"); // la hora explícita gana
  assert.equal(horaRelativa("en 5 minutos", ahora, "Europe/Madrid"), "02:04");
  assert.equal(horaRelativa("en 5 minutos", ahora, "Zona/Invalida"), "21:04");
  assert.equal(horaRelativa("en 0 minutos", ahora), null);
  assert.equal(detectarAlarma("despertame mañana temprano", tz).hora, null);
});
