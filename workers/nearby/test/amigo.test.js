// Marcas "quero ser amigo" na Conta (Tarefa 19): várias Contas em memória ligadas por um env.CONTA falso.
import test from "node:test";
import assert from "node:assert/strict";
import { Conta, MAX_MARCAS_AMIGO } from "../src/conta.js";
import { parseClientMessage } from "../src/protocol.js";

const H24 = 24 * 3_600_000;

function mundo() {
  const relogio = { t: 1_000_000 };
  const contas = new Map();
  const env = {
    CONTA: {
      idFromName: (n) => n,
      get: (n) => ({
        fetch: (url, init) => contas.get(n).fetch(new Request(url, init)),
      }),
    },
  };
  const conta = (uid) => {
    if (!contas.has(uid)) {
      const dados = new Map();
      const ctx = { storage: { async get(k) { return dados.get(k); }, async put(k, v) { dados.set(k, structuredClone(v)); } } };
      const c = new Conta(ctx, env);
      c.now = () => relogio.t;
      contas.set(uid, c);
    }
    return contas.get(uid);
  };
  const chamar = async (uid, caminho, metodo = "POST") => {
    const r = await conta(uid).fetch(new Request(`https://conta${caminho}`, { method: metodo }));
    return { status: r.status, corpo: await r.json() };
  };
  const confirmados = async (uid) => (await chamar(uid, `/amigos-confirmados?eu=${uid}`, "GET")).corpo.uids;
  conta("ana"); conta("bruno"); conta("carla");
  return { relogio, chamar, confirmados };
}

test("marca de um lado só não revela nada a ninguém", async () => {
  const { chamar, confirmados } = mundo();
  assert.equal((await chamar("ana", "/amigo?uid=bruno")).status, 200);
  assert.deepEqual(await confirmados("ana"), []);
  assert.deepEqual(await confirmados("bruno"), []);
});

test("as duas marcas revelam o uid de cada um só ao outro", async () => {
  const { chamar, confirmados } = mundo();
  await chamar("ana", "/amigo?uid=bruno");
  await chamar("bruno", "/amigo?uid=ana");
  assert.deepEqual(await confirmados("ana"), ["bruno"]);
  assert.deepEqual(await confirmados("bruno"), ["ana"]);
  assert.deepEqual(await confirmados("carla"), []);
});

test("marca vale 24 h: depois disso a marca antiga não conta", async () => {
  const { chamar, confirmados, relogio } = mundo();
  await chamar("ana", "/amigo?uid=bruno");
  relogio.t += H24 + 1;
  await chamar("bruno", "/amigo?uid=ana");
  assert.deepEqual(await confirmados("ana"), []);
  assert.deepEqual(await confirmados("bruno"), []); // a marca de ana expirou
  // Dentro das 24 h de cada uma, vale.
  relogio.t += 1000;
  await chamar("ana", "/amigo?uid=bruno");
  assert.deepEqual(await confirmados("ana"), ["bruno"]);
});

test("ack encerra a entrega; sem ack a confirmação continua pendente", async () => {
  const { chamar, confirmados } = mundo();
  await chamar("ana", "/amigo?uid=bruno");
  await chamar("bruno", "/amigo?uid=ana");
  assert.deepEqual(await confirmados("ana"), ["bruno"]);
  assert.deepEqual(await confirmados("ana"), ["bruno"]); // sem ack: reentrega
  await chamar("ana", "/amigo-entregue?uid=bruno");
  await chamar("ana", "/amigo-entregue?uid=bruno"); // idempotente
  assert.deepEqual(await confirmados("ana"), []);
  assert.deepEqual(await confirmados("bruno"), ["ana"]); // cada lado tem o seu ack
});

test("reciprocidade confirmada segue pendente por 72 h depois das 24 h da marca; depois cai", async () => {
  const { chamar, confirmados, relogio } = mundo();
  await chamar("ana", "/amigo?uid=bruno");
  await chamar("bruno", "/amigo?uid=ana");
  assert.deepEqual(await confirmados("ana"), ["bruno"]); // confirmada agora
  relogio.t += H24 + 1000; // marcas expiradas
  assert.deepEqual(await confirmados("ana"), ["bruno"]); // ainda pendente de ack
  assert.deepEqual(await confirmados("bruno"), ["ana"]); // a confirmação foi gravada nas DUAS Contas
  relogio.t += 72 * 3_600_000;
  assert.deepEqual(await confirmados("ana"), []);
  // Nova marca depois do ack não repete a entrega indevidamente.
  const m = mundo();
  await m.chamar("ana", "/amigo?uid=bruno");
  await m.chamar("bruno", "/amigo?uid=ana");
  await m.confirmados("ana");
  await m.chamar("ana", "/amigo-entregue?uid=bruno");
  m.relogio.t += H24 + 1000;
  assert.deepEqual(await m.confirmados("ana"), []);
});

test("teto vem antes do atalho de bloqueio: bloqueado recebe limite igual; bloquear no teto não apaga marcas", async () => {
  const { chamar } = mundo();
  await chamar("ana", "/amigo?uid=cara");
  for (let i = 1; i < MAX_MARCAS_AMIGO; i++) await chamar("ana", `/amigo?uid=u${i}`);
  await chamar("ana", "/bloquear?uid=quem&sentido=bloqueadoPor");
  const r = await chamar("ana", "/amigo?uid=quem");
  assert.equal(r.status, 409);
  assert.equal((await chamar("ana", "/amigo?uid=quem&simular=1")).status, 409);
  // Bloquear com a lista cheia: recusa por limite sem apagar a marca.
  for (let i = 0; i < 500; i++) await chamar("ana", `/bloquear?uid=b${i}`);
  assert.equal((await chamar("ana", "/bloquear?uid=cara")).status, 409);
  assert.equal((await chamar("ana", "/amigo-marca?uid=cara", "GET")).corpo.marcou, true); // a marca de ana em cara continua
});

test("bloqueio posterior (qualquer sentido) apaga marcas e confirmações pendentes", async () => {
  const { chamar, confirmados } = mundo();
  await chamar("ana", "/amigo?uid=bruno");
  await chamar("bruno", "/amigo?uid=ana");
  await chamar("bruno", "/bloquear?uid=ana");
  await chamar("ana", "/bloquear?uid=bruno&sentido=bloqueadoPor");
  assert.deepEqual(await confirmados("ana"), []);
  assert.deepEqual(await confirmados("bruno"), []);
  // Marca nova contra bloqueado não grava (responde ok igual).
  assert.equal((await chamar("ana", "/amigo?uid=bruno")).corpo.ok, true);
  await chamar("carla", "/amigo?uid=ana");
  assert.deepEqual((await chamar("ana", "/amigo-marca?uid=bruno", "GET")).corpo, { ok: true, bloqueado: true, marcou: false });
});

test("teto de 50 marcas ativas, simular não grava, uid inválido e método errado", async () => {
  const { chamar } = mundo();
  for (let i = 0; i < MAX_MARCAS_AMIGO; i++) assert.equal((await chamar("ana", `/amigo?uid=u${i}`)).status, 200);
  const r = await chamar("ana", "/amigo?uid=extra");
  assert.equal(r.status, 409);
  assert.equal(r.corpo.erro, "limite");
  assert.equal((await chamar("ana", "/amigo?uid=u0")).status, 200); // repetida continua ok
  assert.equal((await chamar("bruno", "/amigo?uid=x&simular=1")).status, 200);
  assert.equal((await chamar("bruno", "/amigo-marca?uid=x", "GET")).corpo.marcou, false);
  assert.equal((await chamar("ana", "/amigo?uid=a/b")).status, 400);
  assert.equal((await chamar("ana", "/amigos-confirmados", "GET")).status, 400);
  assert.equal((await chamar("ana", "/amigo?uid=x", "GET")).status, 405);
});

test("protocolo: amigo exige sessao de 16 hex", () => {
  assert.deepEqual(parseClientMessage('{"t":"amigo","sessao":"0123456789abcdef"}'), { ok: true, msg: { t: "amigo", sessao: "0123456789abcdef" } });
  assert.equal(parseClientMessage('{"t":"amigo","sessao":"x"}').ok, false);
  assert.equal(parseClientMessage('{"t":"amigo"}').ok, false);
});

test("protocolo: amigo_ack exige uid válido", () => {
  assert.deepEqual(parseClientMessage('{"t":"amigo_ack","uid":"abc.DEF_1:2-3"}'), { ok: true, msg: { t: "amigo_ack", uid: "abc.DEF_1:2-3" } });
  assert.equal(parseClientMessage('{"t":"amigo_ack","uid":"a/b"}').ok, false);
  assert.equal(parseClientMessage('{"t":"amigo_ack"}').ok, false);
});

test("A: a outra ponta volta depois de 24 h da marca do primeiro e ainda recebe", async () => {
  const { chamar, confirmados, relogio } = mundo();
  await chamar("ana", "/amigo?uid=bruno");
  relogio.t += 23 * 3_600_000;
  await chamar("bruno", "/amigo?uid=ana");
  assert.deepEqual(await confirmados("ana"), ["bruno"]); // ana confirma primeiro
  relogio.t += 2 * 3_600_000; // a marca de ana já venceu; a de bruno ainda vale
  assert.deepEqual(await confirmados("bruno"), ["ana"]);
});

test("B: bloqueio posterior barra confirmação guardada, mesmo se a gravação simétrica falhar", async () => {
  const { chamar, confirmados } = mundo();
  await chamar("ana", "/amigo?uid=bruno");
  await chamar("bruno", "/amigo?uid=ana");
  assert.deepEqual(await confirmados("ana"), ["bruno"]); // confirmada nas duas
  // bruno bloqueia ana; a gravação do lado de ana (bloqueadoPor) nunca chega.
  await chamar("bruno", "/bloquear?uid=ana");
  assert.deepEqual(await confirmados("ana"), []); // a consulta na entrega barra
  assert.deepEqual(await confirmados("bruno"), []);
  assert.equal((await chamar("ana", "/amigo-marca?uid=bruno", "GET")).corpo.marcou, false); // e a pendente foi apagada
});

test("C: marca nova renova a entrada pendente (sem duplicar) e o ack marca todas do uid", async () => {
  const { chamar, confirmados, relogio } = mundo();
  await chamar("ana", "/amigo?uid=bruno");
  await chamar("bruno", "/amigo?uid=ana");
  await confirmados("ana");
  relogio.t += H24 + 1000; // marca vencida, confirmação pendente
  await chamar("ana", "/amigo?uid=bruno");
  assert.deepEqual(await confirmados("ana"), ["bruno"]); // um só uid
  await chamar("ana", "/amigo-entregue?uid=bruno");
  assert.deepEqual(await confirmados("ana"), []);
});
