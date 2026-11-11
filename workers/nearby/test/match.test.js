import { test } from "node:test";
import assert from "node:assert/strict";
import { combina } from "../src/match.js";

const base = { ritmo: "moderado", distancia: "50a150", parada: "cafe" };

test("tudo igual combina", () => {
  assert.equal(combina(base, { ...base }), true);
});

test("ritmo diferente não combina", () => {
  assert.equal(combina(base, { ...base, ritmo: "animado" }), false);
});

test("ate50 com mais150 não combina (não vizinhas)", () => {
  assert.equal(combina({ ...base, distancia: "ate50" }, { ...base, distancia: "mais150" }), false);
});

test("ate50 com 50a150 combina, nos dois sentidos", () => {
  assert.equal(combina({ ...base, distancia: "ate50" }, base), true);
  assert.equal(combina(base, { ...base, distancia: "ate50" }), true);
});

test("50a150 com mais150 combina", () => {
  assert.equal(combina(base, { ...base, distancia: "mais150" }), true);
});

test("sem_parada com so_estrada combina, nos dois sentidos", () => {
  assert.equal(combina({ ...base, parada: "sem_parada" }, { ...base, parada: "so_estrada" }), true);
  assert.equal(combina({ ...base, parada: "so_estrada" }, { ...base, parada: "sem_parada" }), true);
});

test("cafe com mirante não combina", () => {
  assert.equal(combina(base, { ...base, parada: "mirante" }), false);
});

test("cafe com sem_parada não combina", () => {
  assert.equal(combina(base, { ...base, parada: "sem_parada" }), false);
});

test("valores fora das listas nunca combinam", () => {
  const x = { ritmo: "voando", distancia: "voando", parada: "voando" };
  assert.equal(combina(x, { ...x }), false);
  assert.equal(combina(base, null), false);
});
