// Suspensão automática por 3 denúncias (Tarefa 22): Contas em memória ligadas por um env.CONTA falso.
import test from "node:test";
import assert from "node:assert/strict";
import { Conta } from "../src/conta.js";

const D1 = 24 * 3_600_000;

function mundo() {
  const relogio = { t: 10_000_000_000 };
  const contas = new Map();
  const env = { CONTA: { idFromName: (n) => n, get: (n) => ({ fetch: (url, init) => contas.get(n).fetch(new Request(url, init)) }) } };
  const conta = (uid) => {
    if (!contas.has(uid)) {
      const dados = new Map();
      const ctx = { storage: { async get(k) { return dados.get(k); }, async put(k, v) { dados.set(k, structuredClone(v)); } } };
      const c = new Conta(ctx, env);
      // Emula os input gates do Durable Object: uma requisição por vez por Conta.
      let fila = Promise.resolve();
      const fetchReal = c.fetch.bind(c);
      c.fetch = (req) => { const p = fila.then(() => fetchReal(req)); fila = p.catch(() => {}); return p; };
      c.now = () => relogio.t;
      contas.set(uid, c);
    }
    return contas.get(uid);
  };
  const chamar = async (uid, caminho, metodo = "POST") => {
    const r = await conta(uid).fetch(new Request(`https://conta${caminho}`, { method: metodo }));
    return { status: r.status, corpo: await r.json() };
  };
  const denunciar = (alvo, por, { encontro = true } = {}) =>
    chamar(alvo, `/denunciar?por=${por}&motivo=incomodo&uid=${alvo}${encontro ? "&encontro=1" : ""}`);
  const suspenso = async (uid) => (await chamar(uid, "/suspensao", "GET")).corpo.suspenso;
  return { relogio, chamar, denunciar, suspenso };
}

test("2 denúncias não suspendem", async () => {
  const { denunciar, suspenso } = mundo();
  await denunciar("alvo", "a"); await denunciar("alvo", "b");
  assert.equal(await suspenso("alvo"), false);
});

test("3 denúncias do mesmo denunciante não suspendem", async () => {
  const { denunciar, suspenso, relogio } = mundo();
  for (let i = 0; i < 3; i++) { await denunciar("alvo", "a"); relogio.t += D1 + 1; }
  assert.equal(await suspenso("alvo"), false);
});

test("3 de pessoas diferentes suspendem; resposta a quem denuncia é igual (sem pista)", async () => {
  const { denunciar, suspenso } = mundo();
  const r1 = await denunciar("alvo", "a");
  const r2 = await denunciar("alvo", "b");
  const r3 = await denunciar("alvo", "c");
  assert.equal(await suspenso("alvo"), true);
  assert.deepEqual(r1.corpo, { ok: true });
  assert.deepEqual(r2.corpo, { ok: true });
  assert.equal(r3.corpo.suspendeu, true); // só a Celula usa `suspendeu`; nunca é repassado ao cliente
});

test("3ª denúncia com a 1ª fora dos 7 dias não suspende", async () => {
  const { denunciar, suspenso, relogio } = mundo();
  await denunciar("alvo", "a");
  relogio.t += 7 * D1 + 1;
  await denunciar("alvo", "b"); await denunciar("alvo", "c");
  assert.equal(await suspenso("alvo"), false);
});

test("denúncia sem encontro (só mapa) fica registrada mas não conta", async () => {
  const { denunciar, suspenso, chamar } = mundo();
  await denunciar("alvo", "a"); await denunciar("alvo", "b");
  await denunciar("alvo", "c", { encontro: false });
  await denunciar("alvo", "d", { encontro: false });
  assert.equal(await suspenso("alvo"), false);
  const lista = (await chamar("alvo", "/denuncias", "GET")).corpo.denuncias;
  assert.equal(lista.length, 4);
  assert.deepEqual(lista.map((d) => d.encontro === true), [true, true, false, false]);
});

test("denunciado que bloqueia depois da denúncia continua suspenso (sem veto)", async () => {
  const { denunciar, suspenso, chamar } = mundo();
  await denunciar("alvo", "a"); await denunciar("alvo", "b");
  await chamar("alvo", "/bloquear?uid=c&sentido=bloqueou");
  await denunciar("alvo", "c");
  assert.equal(await suspenso("alvo"), true);
});

test("3ª denúncia devolve suspendeu:true só a ela; 3 simultâneas suspendem uma única vez", async () => {
  const m = mundo();
  const rs = await Promise.all(["a", "b", "c"].map((p) => m.denunciar("alvo", p)));
  assert.equal(rs.filter((r) => r.corpo.suspendeu === true).length, 1);
  assert.equal(await m.suspenso("alvo"), true);
  const r4 = await m.denunciar("alvo", "d");
  assert.equal(r4.corpo.suspendeu, undefined);
});

test("`por` manual preserva `desde`; automática não sobrescreve manual ativa", async () => {
  const { denunciar, suspenso, chamar, relogio } = mundo();
  await chamar("alvo", "/suspensao?acao=tirar");
  relogio.t += 1000;
  await chamar("alvo", "/suspensao?acao=por");
  await denunciar("alvo", "a"); await denunciar("alvo", "b");
  const r3 = await denunciar("alvo", "c");
  assert.equal(r3.corpo.suspendeu, undefined); // já suspensa manualmente
  await chamar("alvo", "/suspensao?acao=tirar");
  assert.equal(await suspenso("alvo"), false);
  relogio.t += 1000;
  await denunciar("alvo", "d");
  assert.equal(await suspenso("alvo"), false);
});

test("tirar do dono reverte e denúncias antigas não re-suspendem na hora", async () => {
  const { denunciar, suspenso, chamar, relogio } = mundo();
  await denunciar("alvo", "a"); await denunciar("alvo", "b"); await denunciar("alvo", "c");
  assert.equal(await suspenso("alvo"), true);
  relogio.t += 1000;
  await chamar("alvo", "/suspensao?acao=tirar");
  assert.equal(await suspenso("alvo"), false);
  relogio.t += 1000;
  await denunciar("alvo", "d"); // as 3 antigas não contam mais
  assert.equal(await suspenso("alvo"), false);
  await denunciar("alvo", "e"); await denunciar("alvo", "f");
  assert.equal(await suspenso("alvo"), true);
});
