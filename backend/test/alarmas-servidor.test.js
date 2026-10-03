import test from "node:test";
import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs";
import protocoloDespertador from "../modulos/protocoloDespertador.js";
import orquestadorNotificaciones from "../orquestador/orquestadorNotificaciones.js";
import gestorDeAlarmas from "../modulos/gestorDeAlarmas.js";

test("M1: el servidor ya no ejecuta alarmas (sin tick ni escalado inmediato); solo registra eventos de Android", () => {
  assert.equal(orquestadorNotificaciones.tick, undefined);
  assert.equal(orquestadorNotificaciones.iniciar, undefined);
  assert.equal(protocoloDespertador.ejecutarAlarma, undefined);
  const server = fs.readFileSync(new URL("../server.js", import.meta.url), "utf8");
  assert.doesNotMatch(server, /orquestadorNotificaciones\.iniciar/);

  const userId = `alarm-${crypto.randomBytes(4).toString("hex")}`;
  const a = gestorDeAlarmas.crearAlarma(userId, "07:30", { titulo: "Trabajo" });
  const r1 = protocoloDespertador.registrarDisparoAndroid(userId, 1, a.id);
  assert.equal(r1.estado, "stageProgramado");
  assert.equal(gestorDeAlarmas.obtenerAlarma(userId, a.id).estado, "ACTIVE", "un disparo no cierra la alarma");
});

test("M2: textos de alarma neutros (sin voz del personaje) y respuesta sin frase fija", async () => {
  const despachos = orquestadorNotificaciones.construirDespachosAndroid({ hora: "07:30", titulo: "Trabajo" });
  assert.deepEqual(despachos.map(d => d.mensaje), ["Alarma · 07:30", "Alarma · 07:30 · segundo aviso", "Alarma · 07:30 · último aviso"]);
  for (const d of despachos) assert.doesNotMatch(d.mensaje, /[\u{1F300}-\u{1FAFF}\u2600-\u27BF]/u, "sin emojis");
  const userId = `alarm-${crypto.randomBytes(4).toString("hex")}`;
  const a = gestorDeAlarmas.crearAlarma(userId, "07:30", {});
  const r = await protocoloDespertador.registrarRespuestaUsuario(userId, a.id);
  assert.equal(r.estado, "respondio");
  assert.equal(r.mensaje, null);
});
