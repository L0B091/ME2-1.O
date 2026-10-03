// Reemplaza a adult-mode-smoke.mjs (no tenía aserciones y pasaba aunque el desbloqueo fallara).
import test from "node:test";
import assert from "node:assert/strict";
import crypto from "node:crypto";
import adultMode from "../modulos/premium/adultMode.js";
import premiumManager from "../modulos/premium/premiumManager.js";
import verificacionEdad from "../auth/verificacionEdad.js";

const id = () => `test_adult_${crypto.randomBytes(5).toString("hex")}`;

test("modo adulto: Free nunca desbloquea; Premium + 18+ + palabra clave desbloquea con escalado gradual", async () => {
  const free = id();
  const r0 = await adultMode.procesarEnChat(free, "quiero sexo", { premium: { premiumActivo: false } });
  assert.equal(r0.evento, "premium_requerido");
  assert.equal(r0.adult.unlocked, false);

  const user = id();
  premiumManager.activarPremium(user, `pay-${crypto.randomUUID()}`, {});
  verificacionEdad.guardar(user, "1990-01-15", "test");
  const { keyword } = adultMode.habilitarExtension(user);
  assert.ok(keyword);

  assert.equal((await adultMode.procesarEnChat(user, "quiero sexo contigo")).evento, "bloqueado_sin_keyword");
  const un = await adultMode.procesarEnChat(user, `mi palabra es ${keyword}`);
  assert.equal(un.evento, "desbloqueado_con_keyword");
  assert.equal(un.adult.unlocked, true);
  assert.equal(un.adult.intensity, "soft_flirt");

  const salto = await adultMode.procesarEnChat(user, "quiero sexo oral");
  assert.notEqual(salto.adult.intensity, "explicit", "no salta directo al máximo");

  adultMode.bloquearSesion(user);
  assert.equal((await adultMode.procesarEnChat(user, "hola")).adult.unlocked, false, "solo dura la sesión");
});
