// Integração dos encontros na Celula (Durable Object de verdade) e da Conta via binding CONTA.
// Rodar com: npm run test:int
import { describe, it, expect } from "vitest";
import { env, runInDurableObject, runDurableObjectAlarm, evictDurableObject } from "cloudflare:test";
import { Celula } from "../../src/celula.js";

const ROLE = { t: "procurar", ritmo: "moderado", distancia: "50a150", parada: "cafe", raioKm: 5 };
const BASE = { lat: -20.3, lon: -40.3 }; // quadrado "-21:-41"
const CELL = "-21:-41";
const posMsg = (lat, lon, extra = {}) => ({ t: "posicao", lat, lon, rumo: 90, vel: 40, distKm: null, etaMin: null, ...extra });
const M = 1 / 111320; // 1 m em graus de latitude

let contador = 0;
const unico = (p) => `${p}-${++contador}-${Math.random().toString(36).slice(2, 8)}`;
function novaCelula() {
  return env.CELULA.get(env.CELULA.idFromName(unico("enc")));
}

async function conectar(stub, uid, extraHeaders = {}) {
  const resp = await stub.fetch("https://celula/ws", {
    headers: { Upgrade: "websocket", "x-rumo-uid": uid, "x-rumo-cell": CELL, ...extraHeaders },
  });
  expect(resp.status).toBe(101);
  const ws = resp.webSocket;
  ws.accept();
  const c = { ws, recebidas: [], fechou: false };
  ws.addEventListener("message", (e) => { c.recebidas.push(JSON.parse(e.data)); });
  ws.addEventListener("close", () => { c.fechou = true; });
  c.enviar = (m) => ws.send(JSON.stringify(m));
  return c;
}

const dormir = (ms) => new Promise((r) => setTimeout(r, ms));
async function esperar(pred, ms = 4000) {
  const fim = Date.now() + ms;
  while (Date.now() < fim) {
    if (pred()) return true;
    await dormir(25);
  }
  return pred();
}
const tipo = (c, t) => c.recebidas.filter((m) => m.t === t);
const ultimo = (c, t) => tipo(c, t).at(-1);
const erro = (c, codigo) => c.recebidas.some((m) => m.t === "erro" && m.codigo === codigo);

// A (proponente) procura, manda posição e propõe; devolve o id do encontro.
async function proporComA(stub, uid, { vagas = 2, pos = BASE, ponto = BASE } = {}) {
  const a = await conectar(stub, uid);
  a.enviar(ROLE);
  a.enviar(posMsg(pos.lat, pos.lon));
  a.enviar({ t: "proposta", lat: ponto.lat, lon: ponto.lon, vagas });
  expect(await esperar(() => ultimo(a, "encontro"))).toBeTruthy();
  return { a, id: ultimo(a, "encontro").id };
}

const participante = (c, sessao) => ultimo(c, "encontro")?.participantes.find((p) => p.sessao === sessao);

describe("encontros na Celula", () => {
  it("A propõe com 2 vagas, B entra por conexão extra, C recebe cheio; ninguém recebe uid", async () => {
    const stub = novaCelula();
    const { a, id } = await proporComA(stub, unico("uid-ana"));
    expect(id).toMatch(/^[0-9a-f]{16}$/);

    const b = await conectar(stub, unico("uid-bruno"), { "x-rumo-cell": CELL, "x-rumo-enc": id });
    b.enviar({ t: "entrar", id });
    expect(await esperar(() => ultimo(b, "encontro") && ultimo(a, "encontro")?.participantes.length === 2)).toBe(true);
    expect(ultimo(b, "encontro").id).toBe(id);
    expect(ultimo(b, "encontro").participantes.length).toBe(2);

    const c = await conectar(stub, unico("uid-carla"), { "x-rumo-enc": id });
    c.enviar({ t: "entrar", id });
    expect(await esperar(() => erro(c, "cheio"))).toBe(true);
    expect(ultimo(c, "encontro")).toBeUndefined();

    for (const x of [a, b, c]) {
      const todo = JSON.stringify(x.recebidas);
      expect(todo).not.toContain("uid-");
      expect(todo.toLowerCase()).not.toContain('"uid"');
    }
    // Cada participante só conhece ids opacos de 16 hex.
    for (const p of ultimo(b, "encontro").participantes) expect(p.sessao).toMatch(/^[0-9a-f]{16}$/);
  });

  it("posição a 5 km do ponto chega aproximada; a 800 m chega real", async () => {
    const stub = novaCelula();
    // A a 5 km ao norte do ponto: B vê posição aproximada (diferente da real).
    const longe = { lat: BASE.lat + 5000 * M, lon: BASE.lon };
    const { a, id } = await proporComA(stub, unico("uid-a"), { pos: longe });
    const b = await conectar(stub, unico("uid-b"), { "x-rumo-enc": id });
    b.enviar({ t: "entrar", id });
    expect(await esperar(() => ultimo(b, "encontro"))).toBeTruthy();
    const sessaoA = ultimo(a, "encontro").eu;
    expect(await esperar(() => participante(b, sessaoA)?.lat !== null && participante(b, sessaoA)?.lat !== undefined)).toBe(true);
    let pa = participante(b, sessaoA);
    expect(pa.real).toBe(false);
    expect(pa.lat).not.toBe(longe.lat);
    expect(Math.abs(pa.lat - longe.lat) * 111320).toBeLessThan(450);

    // Outra pessoa a 800 m: vem com a posição real.
    const stub2 = novaCelula();
    const p2 = await proporComA(stub2, unico("uid-a2"), { pos: { lat: BASE.lat + 800 * M, lon: BASE.lon } });
    const b2 = await conectar(stub2, unico("uid-b2"), { "x-rumo-enc": p2.id });
    b2.enviar({ t: "entrar", id: p2.id });
    expect(await esperar(() => participante(b2, ultimo(p2.a, "encontro").eu)?.lat != null)).toBe(true);
    pa = participante(b2, ultimo(p2.a, "encontro").eu);
    expect(pa.real).toBe(true);
    expect(pa.lat).toBe(BASE.lat + 800 * M);
  });

  it("A cancela: B recebe fim cancelado e A fica de espera de 5 min na Conta", async () => {
    const stub = novaCelula();
    const uidA = unico("uid-a");
    const { a, id } = await proporComA(stub, uidA);
    const b = await conectar(stub, unico("uid-b"), { "x-rumo-enc": id });
    b.enviar({ t: "entrar", id });
    expect(await esperar(() => ultimo(b, "encontro"))).toBeTruthy();

    a.enviar({ t: "cancelar" });
    expect(await esperar(() => tipo(b, "fim").some((m) => m.id === id && m.motivo === "cancelado"))).toBe(true);
    expect(await esperar(() => tipo(a, "fim").length === 1)).toBe(true);

    // Nova conexão da mesma pessoa: a Conta responde espera; a repetição nem chega à Conta.
    const a2 = await conectar(stub, uidA);
    a2.enviar(ROLE);
    let chamadas = 0;
    await runInDurableObject(stub, (inst) => {
      const orig = inst.chamarConta.bind(inst);
      inst.chamarConta = (...args) => { chamadas++; return orig(...args); };
    });
    a2.enviar({ t: "proposta", lat: BASE.lat, lon: BASE.lon, vagas: 3 });
    expect(await esperar(() => erro(a2, "espera"))).toBe(true);
    const e = a2.recebidas.find((m) => m.codigo === "espera");
    expect(e.restanteMs).toBeGreaterThan(290_000);
    for (let i = 0; i < 5; i++) a2.enviar({ t: "proposta", lat: BASE.lat, lon: BASE.lon, vagas: 3 });
    expect(await esperar(() => a2.recebidas.filter((m) => m.codigo === "espera").length === 6)).toBe(true);
    expect(chamadas).toBe(1);
    expect(JSON.stringify(b.recebidas)).not.toContain(uidA);
  });

  it("segunda proposta aberta da mesma pessoa falha com ja_tem_proposta (mesmo por outro quadrado/conexão)", async () => {
    const stub = novaCelula();
    const uid = unico("uid-a");
    const { a } = await proporComA(stub, uid);
    a.enviar({ t: "proposta", lat: BASE.lat, lon: BASE.lon, vagas: 3 });
    expect(await esperar(() => erro(a, "ja_tem_proposta"))).toBe(true);

    const outra = await conectar(novaCelula(), uid);
    outra.enviar(ROLE);
    outra.enviar({ t: "proposta", lat: BASE.lat, lon: BASE.lon, vagas: 3 });
    expect(await esperar(() => erro(outra, "ja_tem_proposta"))).toBe(true);
  });

  it("proposta com ponto em outro quadrado é recusada (erro de protocolo); sem procurar também", async () => {
    const stub = novaCelula();
    const a = await conectar(stub, unico("uid-a"));
    a.enviar(ROLE);
    a.enviar({ t: "proposta", lat: BASE.lat + 2, lon: BASE.lon, vagas: 3 });
    expect(await esperar(() => erro(a, "ponto_invalido"))).toBe(true);
    a.enviar({ t: "proposta", lat: BASE.lat, lon: BASE.lon, vagas: 9 });
    expect(await esperar(() => erro(a, "mensagem_invalida"))).toBe(true);

    const solto = await conectar(stub, unico("uid-b"));
    solto.enviar({ t: "proposta", lat: BASE.lat, lon: BASE.lon, vagas: 3 });
    expect(await esperar(() => erro(solto, "nao_procurando"))).toBe(true);
  });

  it("entrar com id que não existe, de conexão com outro enc, e cancelar/sair sem estar no encontro: inexistente", async () => {
    const stub = novaCelula();
    const { id } = await proporComA(stub, unico("uid-a"));
    const b = await conectar(stub, unico("uid-b"), { "x-rumo-enc": id });
    b.enviar({ t: "entrar", id: "ffffffffffffffff" }); // conexão destinada a outro encontro
    expect(await esperar(() => erro(b, "inexistente"))).toBe(true);
    const c = await conectar(stub, unico("uid-c"));
    c.enviar({ t: "entrar", id: "ffffffffffffffff" });
    c.enviar({ t: "cancelar" });
    c.enviar({ t: "sair" });
    expect(await esperar(() => c.recebidas.filter((m) => m.codigo === "inexistente").length === 3)).toBe(true);
    // Quem não propôs não cancela.
    const d = await conectar(stub, unico("uid-d"), { "x-rumo-enc": id });
    d.enviar({ t: "entrar", id });
    expect(await esperar(() => ultimo(d, "encontro"))).toBeTruthy();
    d.enviar({ t: "cancelar" });
    expect(await esperar(() => erro(d, "inexistente"))).toBe(true);
    expect(tipo(d, "fim").length).toBe(0);
  });

  it("a mesma pessoa não ocupa duas vagas", async () => {
    const stub = novaCelula();
    const uid = unico("uid-a");
    const { id } = await proporComA(stub, uid, { vagas: 3 });
    const dupla = await conectar(stub, uid, { "x-rumo-enc": id });
    dupla.enviar({ t: "entrar", id });
    expect(await esperar(() => erro(dupla, "ja_em_encontro"))).toBe(true);
  });

  it("propostas: só dentro do raio da conexão, com combina pelas preferências, sem proponente", async () => {
    const stub = novaCelula();
    const { a, id } = await proporComA(stub, unico("uid-proponente"), { vagas: 4 });

    const perto = await conectar(stub, unico("uid-perto"));
    perto.enviar(ROLE);
    perto.enviar(posMsg(BASE.lat + 1000 * M, BASE.lon));
    const longe = await conectar(stub, unico("uid-longe"));
    longe.enviar({ ...ROLE, parada: "mirante" });
    longe.enviar(posMsg(BASE.lat + 0.5, BASE.lon));
    const outro = await conectar(stub, unico("uid-outro"));
    outro.enviar({ ...ROLE, ritmo: "animado" });
    outro.enviar(posMsg(BASE.lat - 1000 * M, BASE.lon));

    expect(await esperar(() => ultimo(perto, "propostas")?.lista.length === 1)).toBe(true);
    expect(await esperar(() => ultimo(outro, "propostas")?.lista.length === 1)).toBe(true);
    const p = ultimo(perto, "propostas").lista[0];
    expect(Object.keys(p).sort()).toEqual(
      ["combina", "distancia", "id", "lat", "lon", "parada", "ritmo", "vagasLivres", "vagasTotal"],
    );
    expect(p).toMatchObject({ id, vagasLivres: 3, vagasTotal: 4, ritmo: "moderado", combina: true });
    expect(ultimo(outro, "propostas").lista[0].combina).toBe(false);
    await dormir(300);
    expect(ultimo(longe, "propostas")?.lista ?? []).toEqual([]);
    expect(JSON.stringify(perto.recebidas)).not.toContain("uid-");
    // Quem propôs não vê a própria proposta na lista de abertas.
    expect(ultimo(a, "propostas")?.lista ?? []).toEqual([]);
  });

  it("sair: quem entrou sai e o encontro segue; se o proponente sai, todos recebem fim", async () => {
    const stub = novaCelula();
    const { a, id } = await proporComA(stub, unico("uid-a"), { vagas: 3 });
    const b = await conectar(stub, unico("uid-b"), { "x-rumo-enc": id });
    const c = await conectar(stub, unico("uid-c"), { "x-rumo-enc": id });
    b.enviar({ t: "entrar", id });
    c.enviar({ t: "entrar", id });
    expect(await esperar(() => ultimo(a, "encontro")?.participantes.length === 3)).toBe(true);
    const sessaoAntiga = ultimo(b, "encontro").eu;
    b.enviar({ t: "sair" });
    expect(await esperar(() => tipo(b, "fim").some((m) => m.id === id && m.motivo === "saiu"))).toBe(true);
    expect(await esperar(() => ultimo(a, "encontro")?.participantes.length === 2, 5000)).toBe(true);
    expect(tipo(a, "fim").length).toBe(0);
    expect(tipo(c, "fim").length).toBe(0);
    expect(sessaoAntiga).toMatch(/^[0-9a-f]{16}$/);
    a.enviar({ t: "sair" });
    expect(await esperar(() => tipo(c, "fim").some((m) => m.motivo === "cancelado"))).toBe(true);
  });

  it("fechar a conexão de B tira B do encontro e fechar a de A cancela para os outros", async () => {
    const stub = novaCelula();
    const { a, id } = await proporComA(stub, unico("uid-a"), { vagas: 3 });
    const b = await conectar(stub, unico("uid-b"), { "x-rumo-enc": id });
    b.enviar({ t: "entrar", id });
    expect(await esperar(() => ultimo(a, "encontro")?.participantes.length === 2)).toBe(true);
    a.ws.close(1000, "tchau");
    expect(await esperar(() => tipo(b, "fim").some((m) => m.motivo === "cancelado"))).toBe(true);
  });

  it("proposta sem ninguém entrando expira pelo alarme (30 min) e a Conta fica de espera", async () => {
    const stub = novaCelula();
    const uid = unico("uid-a");
    const { a } = await proporComA(stub, uid);
    await runInDurableObject(stub, async (inst) => {
      const t0 = Date.now();
      inst.now = () => t0 + 31 * 60_000;
      // mantém a conexão viva para não cair por falta de sinal
      for (const ws of inst.ctx.getWebSockets()) {
        const at = ws.deserializeAttachment();
        at.visto = t0 + 31 * 60_000;
        inst.guardar(ws, at);
      }
      inst.presence.pessoas.forEach((p) => { p.visto = t0 + 31 * 60_000; });
      await inst.alarm();
    });
    expect(await esperar(() => tipo(a, "fim").some((m) => m.motivo === "expirou"))).toBe(true);
    const conta = env.CONTA.get(env.CONTA.idFromName(uid));
    const r = await conta.fetch("https://conta/abrir", { method: "POST" });
    expect(r.status).toBe(409);
    expect((await r.json()).erro).toBe("espera");
  });

  it("quem entra e some por 2 min sai do encontro sem travar os demais", async () => {
    const stub = novaCelula();
    const { a, id } = await proporComA(stub, unico("uid-a"), { vagas: 3 });
    const b = await conectar(stub, unico("uid-b"), { "x-rumo-enc": id });
    b.enviar({ t: "entrar", id });
    expect(await esperar(() => ultimo(a, "encontro")?.participantes.length === 2)).toBe(true);
    await runInDurableObject(stub, async (inst) => {
      const t0 = Date.now();
      inst.now = () => t0 + 121_000;
      for (const ws of inst.ctx.getWebSockets()) {
        const at = ws.deserializeAttachment();
        if (at.enc === null) { at.visto = t0 + 121_000; inst.guardar(ws, at); }
      }
      inst.presence.pessoas.forEach((p) => { p.visto = t0 + 121_000; });
      await inst.alarm();
    });
    expect(await esperar(() => b.fechou)).toBe(true);
    expect(await esperar(() => ultimo(a, "encontro")?.participantes.length === 1, 5000)).toBe(true);
    expect(a.fechou).toBe(false);
  });

  it("hibernação: o estado dos encontros sobrevive (storage) e a Celula acordada o conhece", async () => {
    const stub = novaCelula();
    const { a, id } = await proporComA(stub, unico("uid-a"), { vagas: 3 });
    const r = await runInDurableObject(stub, async (inst, state) => {
      const acordada = new Celula(state, env);
      await state.blockConcurrencyWhile(async () => {});
      return { n: acordada.enc.encontros.size, ids: [...acordada.enc.encontros.keys()], donos: acordada.donos.size };
    });
    expect(r).toEqual({ n: 1, ids: [id], donos: 1 });

    await evictDurableObject(stub);
    const b = await conectar(stub, unico("uid-b"), { "x-rumo-enc": id });
    b.enviar({ t: "entrar", id });
    expect(await esperar(() => ultimo(b, "encontro")?.participantes.length === 2, 5000)).toBe(true);
    expect(await esperar(() => ultimo(a, "encontro")?.participantes.length === 2, 5000)).toBe(true);
  });

  it("o armazenamento dos encontros não guarda o uid em nenhuma mensagem enviada; viewFor idem após chegada", async () => {
    const stub = novaCelula();
    const { a, id } = await proporComA(stub, unico("uid-a"), { vagas: 2, pos: BASE, ponto: BASE });
    const b = await conectar(stub, unico("uid-b"), { "x-rumo-enc": id });
    b.enviar({ t: "entrar", id });
    b.enviar(posMsg(BASE.lat + 20 * M, BASE.lon));
    // A propôs de dentro do ponto (<100 m) e B está a 20 m: os dois chegaram -> fim "chegaram".
    expect(await esperar(() => tipo(a, "fim").some((m) => m.motivo === "chegaram"), 6000)).toBe(true);
    expect(tipo(b, "fim").some((m) => m.motivo === "chegaram")).toBe(true);
    expect(JSON.stringify([...a.recebidas, ...b.recebidas])).not.toContain("uid-");
    // chegaram não gera espera
    a.enviar({ t: "proposta", lat: BASE.lat, lon: BASE.lon, vagas: 2 });
    expect(await esperar(() => ultimo(a, "encontro"))).toBeTruthy();
  });

  it("sessão do encontro é nova a cada entrada (não vale a da entrada anterior)", async () => {
    const stub = novaCelula();
    const { id } = await proporComA(stub, unico("uid-a"), { vagas: 3 });
    const b = await conectar(stub, unico("uid-b"), { "x-rumo-enc": id });
    b.enviar({ t: "entrar", id });
    expect(await esperar(() => ultimo(b, "encontro"))).toBeTruthy();
    const primeira = ultimo(b, "encontro").eu;
    b.enviar({ t: "sair" });
    expect(await esperar(() => tipo(b, "fim").length === 1)).toBe(true);
    await dormir(2100); // limite de 1 entrar a cada 2 s
    b.enviar({ t: "entrar", id });
    expect(await esperar(() => tipo(b, "encontro").some((m) => m.eu !== primeira))).toBe(true);
  }, 15000);

  it("entrar em excesso (menos de 2 s) recebe espera", async () => {
    const stub = novaCelula();
    const c = await conectar(stub, unico("uid-c"));
    c.enviar({ t: "entrar", id: "ffffffffffffffff" });
    c.enviar({ t: "entrar", id: "eeeeeeeeeeeeeeee" });
    expect(await esperar(() => erro(c, "espera"))).toBe(true);
    expect(c.recebidas.filter((m) => m.codigo === "inexistente").length).toBe(1);
  });

  it("encontros zumbis: depois de reinício sem sockets, o alarme os remove e libera a Conta com espera", async () => {
    const stub = novaCelula();
    const uid = unico("uid-fantasma");
    const conta = env.CONTA.get(env.CONTA.idFromName(uid));
    // Estado gravado como se um socket já tivesse existido, mas nenhum está vivo agora.
    const id = await runInDurableObject(stub, async (inst) => {
      const { id: real } = inst.enc.propose("sessaofantasma01", BASE, 2, { ritmo: "moderado", distancia: "50a150", parada: "cafe" });
      inst.donos.set(real, uid);
      await inst.salvarEnc();
      return real;
    });
    expect((await (await conta.fetch(`https://conta/abrir?enc=${id}`, { method: "POST" })).json()).ok).toBe(true);
    await evictDurableObject(stub);
    // Acorda: o construtor remove a sessão sem socket e arma o alarme.
    const n = await runInDurableObject(stub, (inst) => ({ encontros: inst.enc.encontros.size, pend: inst.enc.pendentes.length }));
    expect(n).toEqual({ encontros: 0, pend: 1 });
    expect(await runDurableObjectAlarm(stub)).toBe(true);
    const r = await conta.fetch("https://conta/abrir", { method: "POST" });
    expect((await r.json()).erro).toBe("espera");
    const resto = await runInDurableObject(stub, (inst) => inst.enc.pendentes.length + inst.donos.size);
    expect(resto).toBe(0);
  });

  it("o alarme remove zumbis sem socket e mantém o encontro da conexão viva", async () => {
    const stub = novaCelula();
    const { a } = await proporComA(stub, unico("uid-a"));
    await runInDurableObject(stub, async (inst) => {
      inst.enc.propose("sessaofantasma02", { lat: BASE.lat + 0.001, lon: BASE.lon }, 2, { ritmo: "moderado", distancia: "50a150", parada: "cafe" });
      await inst.alarm();
      expect(inst.enc.encontros.size).toBe(1); // só o da conexão viva
    });
    expect(a.fechou).toBe(false);
  });

  it("fechar da Conta que falha é repetido no alarme", async () => {
    const stub = novaCelula();
    const uid = unico("uid-a");
    const { a } = await proporComA(stub, uid);
    await runInDurableObject(stub, (inst) => {
      inst.chamarConta = async () => { throw new Error("conta fora do ar"); };
    });
    a.enviar({ t: "cancelar" });
    expect(await esperar(() => tipo(a, "fim").length === 1)).toBe(true);
    const falhas = await runInDurableObject(stub, (inst) => inst.fechamentos.length);
    expect(falhas).toBe(1);
    await runInDurableObject(stub, async (inst) => {
      delete inst.chamarConta; // volta ao método real
      await inst.alarm();
      expect(inst.fechamentos.length).toBe(0);
    });
    const conta = env.CONTA.get(env.CONTA.idFromName(uid));
    const r = await conta.fetch("https://conta/abrir", { method: "POST" });
    expect((await r.json()).erro).toBe("espera"); // cancelado gerou espera mesmo com o atraso
  });

  it("Conta que falha ao abrir: indisponivel, sem encontro criado e sem espera", async () => {
    const stub = novaCelula();
    const uid = unico("uid-a");
    const a = await conectar(stub, uid);
    a.enviar(ROLE);
    await runInDurableObject(stub, (inst) => {
      inst.chamarConta = async () => { throw new Error("fora do ar"); };
    });
    a.enviar({ t: "proposta", lat: BASE.lat, lon: BASE.lon, vagas: 2 });
    expect(await esperar(() => erro(a, "indisponivel"))).toBe(true);
    const n = await runInDurableObject(stub, (inst) => inst.enc.encontros.size);
    expect(n).toBe(0);
    const r = await env.CONTA.get(env.CONTA.idFromName(uid)).fetch("https://conta/abrir", { method: "POST" });
    expect((await r.json()).ok).toBe(true);
  });

  it("posições não regravam o estado dos encontros no storage", async () => {
    const stub = novaCelula();
    const { a, id } = await proporComA(stub, unico("uid-a"), { vagas: 3, pos: { lat: BASE.lat + 5000 * M, lon: BASE.lon } });
    const b = await conectar(stub, unico("uid-b"), { "x-rumo-enc": id });
    b.enviar({ t: "entrar", id });
    expect(await esperar(() => ultimo(b, "encontro"))).toBeTruthy();
    await runInDurableObject(stub, (inst) => {
      inst.escritas = 0;
      const orig = inst.salvarEnc.bind(inst);
      inst.salvarEnc = () => { inst.escritas++; return orig(); };
    });
    b.enviar(posMsg(BASE.lat + 4000 * M, BASE.lon));
    expect(await esperar(() => participante(a, ultimo(b, "encontro").eu)?.lat != null)).toBe(true);
    expect(await esperar(() => participante(a, ultimo(a, "encontro").participantes.find((p) => p.sessao !== ultimo(a, "encontro").eu)?.sessao)?.lat != null)).toBe(true);
    expect(await runInDurableObject(stub, (inst) => inst.escritas)).toBe(0);
  });

  it("mensagem que dá inexistente não conta como sinal de vida", async () => {
    const stub = novaCelula();
    const { id } = await proporComA(stub, unico("uid-a"), { vagas: 3 });
    const b = await conectar(stub, unico("uid-b"), { "x-rumo-enc": id });
    const antes = await runInDurableObject(stub, (inst) => {
      const ws = inst.ctx.getWebSockets().find((w) => w.deserializeAttachment().enc === id);
      return ws.deserializeAttachment().visto;
    });
    await runInDurableObject(stub, (inst) => { const t = Date.now() + 100_000; inst.now = () => t; });
    b.enviar({ t: "cancelar" });
    expect(await esperar(() => erro(b, "inexistente"))).toBe(true);
    const visto = await runInDurableObject(stub, (inst) => {
      const ws = inst.ctx.getWebSockets().find((w) => w.deserializeAttachment().enc === id);
      return ws.deserializeAttachment().visto;
    });
    expect(visto).toBe(antes);
  });
});
