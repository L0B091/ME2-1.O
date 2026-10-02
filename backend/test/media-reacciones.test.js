import test from "node:test";
import assert from "node:assert/strict";
import selectorMedia from "../modulos/media/selectorMedia.js";
import reacciones from "../modulos/interaccion/reacciones.js";
import storage from "../utils/jsonStorage.js";

// Catálogo de PRUEBA en memoria (solo ids/tags neutros; el catálogo real xxx está vacío).
const xxx = { nombre: "xxx", adulto: true, items: [
  { id: "c_playa", tipo: "clip", tags: ["playa", "verano"], intensidad: "soft_flirt" },
  { id: "c_noche", tipo: "clip", tags: ["noche", "luna"], intensidad: "intimate" },
  { id: "g_guino", tipo: "gif", tags: ["guiño", "playa"], intensidad: "soft_flirt" }
] };
const normal = { nombre: "normal", adulto: false, items: [{ id: "n_playa", tipo: "clip", tags: ["playa"] }] };
const cats = [normal, xxx];
const activo = { premiumActivo: true, unlocked: true, intensity: "soft_flirt" };

test("gating: fuera de sesión adulta el catálogo adulto nunca es seleccionable", () => {
  for (const adult of [null, { premiumActivo: false, unlocked: false }, { premiumActivo: true, unlocked: false }, { premiumActivo: false, unlocked: true }]) {
    assert.deepEqual(selectorMedia.catalogosPermitidos(adult, cats).map(c => c.nombre), ["normal"]);
    assert.equal(selectorMedia.seleccionar({ mensaje: "vamos a la playa", adult, catalogos: cats }), null);
    const { clip, media } = selectorMedia.mediosRespuesta({ mensaje: "vamos a la playa", adult, catalogos: cats, videoGaleria: { categoria: "alegre" } });
    assert.equal(clip.fuente, "galeria"); assert.equal(clip.categoria, "alegre"); assert.equal(media, null);
  }
});

test("selector por tags + tope de intensidad de la sesión; clip siempre presente, GIF opcional", () => {
  const r = selectorMedia.mediosRespuesta({ mensaje: "Vamos a la playa", adult: activo, catalogos: cats });
  assert.equal(r.clip.id, "c_playa"); assert.equal(r.clip.fuente, "catalogo_adulto");
  assert.equal(r.media.tipo, "gif"); assert.equal(r.media.id, "g_guino");
  assert.equal(r.media.url, "/api/media/xxx/g_guino");
  // "noche" pide intensidad intimate > soft_flirt → no se elige; cae a la galería (fallback)
  const n = selectorMedia.mediosRespuesta({ mensaje: "esta noche hay luna", adult: activo, catalogos: cats });
  assert.equal(n.clip.fuente, "galeria"); assert.equal(n.clip.fallback, true); assert.equal(n.media, null);
  const alto = selectorMedia.mediosRespuesta({ mensaje: "esta noche hay luna", adult: { ...activo, intensity: "intimate" }, catalogos: cats });
  assert.equal(alto.clip.id, "c_noche");
});

test("catálogo adulto real es un placeholder vacío", () => {
  const real = selectorMedia.cargarCatalogo("xxx");
  assert.equal(real.adulto, true);
  assert.equal(real.items.length, 0);
});

test("reacciones: solo con señal, baja probabilidad, nunca en turnos seguidos", () => {
  const u = `test-reac-${Date.now()}`;
  assert.equal(reacciones.candidato("hola, ¿qué hora es?"), null);
  assert.equal(reacciones.candidato("¡aprobé el final!").emoji, "🎉");
  assert.equal(reacciones.candidato("jajaja buenísimo").emoji, "😂");
  assert.equal(reacciones.candidato("hoy estoy re cansado").emoji, "😴");
  const siempre = () => 0;
  assert.equal(reacciones.decidir(u, "gracias!", { random: siempre }).emoji, "❤️");
  assert.equal(reacciones.decidir(u, "gracias de nuevo!", { random: siempre }), null, "no dos seguidas");
  assert.equal(reacciones.decidir(u, "gracias otra vez", { random: siempre }), null);
  assert.ok(reacciones.decidir(u, "mil gracias", { random: siempre }));
  assert.equal(reacciones.decidir(`${u}-b`, "gracias!", { random: () => 0.99 }), null, "probabilidad baja");
  assert.equal(reacciones.decidir(`${u}-c`, "¿qué hora es?", { random: siempre }), null, "sin señal no reacciona");
  [u, `${u}-b`, `${u}-c`].forEach(id => storage.writeUserData("reacciones", id, {}));
});
