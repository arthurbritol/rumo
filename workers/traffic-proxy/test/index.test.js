import test from 'node:test';
import assert from 'node:assert/strict';
import worker, { intersectsBrazil, tileBounds } from '../src/index.js';

const env = { TOMTOM_KEY: 'KEY123', APP_TOKEN: 'tok' };

// Imagem que contém um ponto (lat, lon) no zoom z.
function tileOf(lat, lon, z) {
  const n = 2 ** z;
  const x = Math.floor(((lon + 180) / 360) * n);
  const r = (lat * Math.PI) / 180;
  const y = Math.floor(((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2) * n);
  return { x, y };
}

function req(path) {
  return new Request(`https://rumo-traffic.example.workers.dev${path}`);
}

test('imagem de Vitória/ES é aceita e pede à TomTom com chave, estilo relative0 e 512 px', async () => {
  const { x, y } = tileOf(-20.31, -40.31, 12);
  let upstreamUrl = null;
  globalThis.fetch = async (u) => {
    upstreamUrl = String(u);
    return new Response('PNGDATA', { status: 200 });
  };
  const res = await worker.fetch(req(`/flow/12/${x}/${y}.png?t=tok`), env);
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('content-type'), 'image/png');
  assert.match(res.headers.get('cache-control'), /max-age=120/);
  assert.equal(await res.text(), 'PNGDATA');
  assert.match(upstreamUrl, /^https:\/\/api\.tomtom\.com\/traffic\/map\/4\/tile\/flow\/relative0\/12\/\d+\/\d+\.png\?key=KEY123&tileSize=512$/);
});

test('sem token ou com token errado: 403, e a TomTom não é chamada', async () => {
  let called = false;
  globalThis.fetch = async () => ((called = true), new Response('x'));
  const { x, y } = tileOf(-20.31, -40.31, 12);
  assert.equal((await worker.fetch(req(`/flow/12/${x}/${y}.png`), env)).status, 403);
  assert.equal((await worker.fetch(req(`/flow/12/${x}/${y}.png?t=errado`), env)).status, 403);
  assert.equal(called, false);
});

test('imagem fora do Brasil, fora do zoom ou fora da grade: 404 sem chamar a TomTom', async () => {
  let called = false;
  globalThis.fetch = async () => ((called = true), new Response('x'));
  const nyc = tileOf(40.7, -74.0, 12);
  const br = tileOf(-20.31, -40.31, 12);
  assert.equal((await worker.fetch(req(`/flow/12/${nyc.x}/${nyc.y}.png?t=tok`), env)).status, 404);
  assert.equal((await worker.fetch(req(`/flow/5/1/1.png?t=tok`), env)).status, 404); // zoom 5 < 8
  assert.equal((await worker.fetch(req(`/flow/17/1/1.png?t=tok`), env)).status, 404); // zoom 17 > 16
  assert.equal((await worker.fetch(req(`/flow/12/${2 ** 12}/${br.y}.png?t=tok`), env)).status, 404);
  assert.equal((await worker.fetch(req(`/outra/coisa?t=tok`), env)).status, 404);
  assert.equal(called, false);
});

test('erro da TomTom vira 502 sem cache e sem vazar a chave', async () => {
  globalThis.fetch = async () => new Response('forbidden KEY123', { status: 403 });
  const { x, y } = tileOf(-20.31, -40.31, 12);
  const res = await worker.fetch(req(`/flow/12/${x}/${y}.png?t=tok`), env);
  assert.equal(res.status, 502);
  assert.equal(res.headers.get('cache-control'), 'no-store');
  assert.doesNotMatch(await res.text(), /KEY123/);
});

test('sem TOMTOM_KEY configurada: 500; métodos que não são GET: 405', async () => {
  const { x, y } = tileOf(-20.31, -40.31, 12);
  assert.equal((await worker.fetch(req(`/flow/12/${x}/${y}.png?t=tok`), { APP_TOKEN: 'tok' })).status, 500);
  assert.equal((await worker.fetch(new Request('https://x.dev/flow/12/1/1.png', { method: 'POST' }), env)).status, 405);
});

test('cobertura: capitais brasileiras dentro, Buenos Aires e Nova York fora', () => {
  const cities = { sp: [-23.55, -46.63], manaus: [-3.12, -60.02], porto_alegre: [-30.03, -51.22] };
  for (const [lat, lon] of Object.values(cities)) {
    const { x, y } = tileOf(lat, lon, 10);
    assert.equal(intersectsBrazil(10, x, y), true);
  }
  for (const [lat, lon] of [[-34.6, -58.4], [40.7, -74.0]]) {
    const { x, y } = tileOf(lat, lon, 10);
    assert.equal(intersectsBrazil(10, x, y), false);
  }
  const b = tileBounds(1, 0, 0);
  assert.ok(b.west === -180 && b.east === 0 && b.north > 80 && b.south === 0 - 0);
});
