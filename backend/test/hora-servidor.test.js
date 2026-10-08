import test from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import express from "express";
import { horaServidor } from "../utils/horaServidor.js";

test("reloj ME2: toda respuesta lleva la hora del servidor (X-ME2-Server-Time) y el tiempo de proceso (X-ME2-Proc-Ms)", async () => {
  const app = express();
  app.use(horaServidor);
  app.get("/lento", (_req, res) => setTimeout(() => res.json({ ok: true }), 60));
  app.get("/error", (_req, res) => res.status(401).json({ ok: false }));
  const server = app.listen(0);
  const port = server.address().port;
  const get = (p) => new Promise((ok, ko) => http.get({ port, path: p }, r => { r.resume(); r.on("end", () => ok(r)); }).on("error", ko));
  try {
    const antes = Date.now();
    const r = await get("/lento");
    const t = Number(r.headers["x-me2-server-time"]);
    assert.ok(t >= antes && t <= Date.now(), "epoch ms del servidor al responder");
    assert.ok(Number(r.headers["x-me2-proc-ms"]) >= 50, "descuenta el procesamiento");
    const e = await get("/error");
    assert.ok(Number(e.headers["x-me2-server-time"]) > 0, "también en errores (401)");
  } finally {
    server.close();
  }
});
