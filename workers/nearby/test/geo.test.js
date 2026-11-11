import test from 'node:test';
import assert from 'node:assert/strict';
import { cellId, cellsInRadius, distanceM, fuzzOffset, applyOffset } from '../src/geo.js';

test('cellId usa o piso de latitude e longitude', () => {
  assert.equal(cellId(-20.31, -40.31), '-21:-41');
  assert.equal(cellId(0.5, 0.5), '0:0');
});

test('cellsInRadius a 20 km do centro de um quadrado devolve 1 id', () => {
  const ids = cellsInRadius(-20.5, -40.5, 20);
  assert.deepEqual(ids, ['-21:-41']);
});

test('cellsInRadius a 5 km da quina entre quatro quadrados devolve 4 ids distintos', () => {
  const ids = cellsInRadius(-21, -41, 5);
  assert.equal(ids.length, 4);
  assert.equal(new Set(ids).size, 4);
  for (const id of ['-22:-42', '-22:-41', '-21:-42', '-21:-41']) assert.ok(ids.includes(id), id);
});

test('distanceM entre dois pontos de Vitória a ~1 km fica entre 990 e 1010 m', () => {
  // Pontos a ~1 km: mesmo meridiano, 0,009° de latitude (~1.000 m).
  const a = { lat: -20.3155, lon: -40.3128 };
  const b = { lat: -20.3065, lon: -40.3128 };
  const d = distanceM(a, b);
  assert.ok(d > 990 && d < 1010, `distância ${d}`);
  assert.equal(distanceM(a, a), 0);
});

test('fuzzOffset é determinístico por uid, dia e segredo', async () => {
  const a = await fuzzOffset('uid1', '2026-11-11', 'segredo');
  const b = await fuzzOffset('uid1', '2026-11-11', 'segredo');
  assert.deepEqual(a, b);
  const c = await fuzzOffset('uid1', '2026-11-12', 'segredo');
  assert.notDeepEqual(a, c);
});

test('fuzzOffset tem módulo entre 200 e 400 m para 200 uids', async () => {
  for (let i = 0; i < 200; i++) {
    const { eastM, northM } = await fuzzOffset(`uid-${i}`, '2026-11-11', 'segredo');
    const m = Math.sqrt(eastM ** 2 + northM ** 2);
    assert.ok(m >= 200 - 1e-6 && m <= 400 + 1e-6, `módulo ${m}`);
  }
});

test('applyOffset converte metros em graus', () => {
  const p = applyOffset(-20, -40, { eastM: 0, northM: 111320 });
  assert.ok(Math.abs(p.lat - -19) < 1e-9);
  assert.ok(Math.abs(p.lon - -40) < 1e-9);
  const q = applyOffset(0, -40, { eastM: 111320, northM: 0 });
  assert.ok(Math.abs(q.lon - -39) < 1e-9);
});
