// Testes da Conta (uma por pessoa): proposta aberta e espera de 5 min. Storage falso em memória.
import test from "node:test";
import assert from "node:assert/strict";
import { Conta } from "../src/conta.js";

function nova() {
  const dados = new Map();
  const ctx = {
    storage: {
      async get(k) { return dados.get(k); },
      async put(k, v) { dados.set(k, structuredClone(v)); },
    },
  };
  const c = new Conta(ctx, {});
  const relogio = { t: 1_000_000 };
  c.now = () => relogio.t;
  const chamar = async (caminho, metodo = "POST") => {
    const r = await c.fetch(new Request(`https://conta${caminho}`, { method: metodo }));
    return { status: r.status, corpo: await r.json() };
  };
  return { c, relogio, chamar };
}

test("abrir duas vezes seguidas: a segunda falha com ja_tem_proposta", async () => {
  const { chamar } = nova();
  assert.deepEqual((await chamar("/abrir")).corpo, { ok: true });
  const r = await chamar("/abrir");
  assert.equal(r.status, 409);
  assert.deepEqual(r.corpo, { ok: false, erro: "ja_tem_proposta" });
});

test("fechar cancelado: abrir falha com espera por 5 min e passa em 301 s", async () => {
  const { chamar, relogio } = nova();
  await chamar("/abrir");
  assert.equal((await chamar("/fechar?motivo=cancelado")).status, 200);
  const r = await chamar("/abrir");
  assert.equal(r.status, 409);
  assert.equal(r.corpo.erro, "espera");
  assert.equal(r.corpo.restanteMs, 300_000);
  relogio.t += 299_000;
  const quase = await chamar("/abrir");
  assert.equal(quase.corpo.erro, "espera");
  assert.equal(quase.corpo.restanteMs, 1000);
  relogio.t += 2000; // 301 s no total
  assert.deepEqual((await chamar("/abrir")).corpo, { ok: true });
});

test("expirou e recusa também geram espera; chegaram e ninguem_chegou não", async () => {
  for (const motivo of ["expirou", "recusa"]) {
    const { chamar } = nova();
    await chamar("/abrir");
    await chamar(`/fechar?motivo=${motivo}`);
    assert.equal((await chamar("/abrir")).corpo.erro, "espera", motivo);
  }
  for (const motivo of ["chegaram", "ninguem_chegou", "falha"]) {
    const { chamar } = nova();
    await chamar("/abrir");
    await chamar(`/fechar?motivo=${motivo}`);
    assert.deepEqual((await chamar("/abrir")).corpo, { ok: true }, motivo);
  }
});

test("proposta que ficou aberta por engano é liberada depois de 2 h", async () => {
  const { chamar, relogio } = nova();
  await chamar("/abrir");
  relogio.t += 119 * 60_000;
  assert.equal((await chamar("/abrir")).corpo.erro, "ja_tem_proposta");
  relogio.t += 2 * 60_000;
  assert.deepEqual((await chamar("/abrir")).corpo, { ok: true });
});

test("pedidos inválidos: motivo desconhecido ou ausente, método e caminho errados", async () => {
  const { chamar } = nova();
  assert.equal((await chamar("/fechar?motivo=qualquer")).status, 400);
  assert.equal((await chamar("/fechar")).status, 400);
  assert.equal((await chamar("/abrir", "GET")).status, 405);
  assert.equal((await chamar("/outra")).status, 404);
  assert.deepEqual((await chamar("/abrir")).corpo, { ok: true }); // nada acima mexeu no estado
});
