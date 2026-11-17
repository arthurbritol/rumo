// Integração das marcas "quero ser amigo" (Tarefa 19) na Celula, com Contas reais.
// Rodar com: npm run test:int
import { describe, it, expect } from "vitest";
import { env, runInDurableObject } from "cloudflare:test";

const ROLE = { t: "procurar", ritmo: "moderado", distancia: "50a150", parada: "cafe", raioKm: 5 };
const BASE = { lat: -20.3, lon: -40.3 };
const CELL = "-21:-41";
const posMsg = (lat, lon) => ({ t: "posicao", lat, lon, rumo: 90, vel: 40, distKm: null, etaMin: null });

let contador = 0;
const unico = (p) => `${p}-${++contador}-${Math.random().toString(36).slice(2, 8)}`;
const novaCelula = () => env.CELULA.get(env.CELULA.idFromName(unico("ami")));
const conta = (uid) => env.CONTA.get(env.CONTA.idFromName(uid));

async function conectar(stub, uid, extra = {}) {
  const resp = await stub.fetch("https://celula/ws", {
    headers: { Upgrade: "websocket", "x-rumo-uid": uid, "x-rumo-cell": CELL, ...extra },
  });
  expect(resp.status).toBe(101);
  const ws = resp.webSocket;
  ws.accept();
  const c = { ws, uid, recebidas: [] };
  ws.addEventListener("message", (e) => { c.recebidas.push(JSON.parse(e.data)); });
  c.enviar = (m) => ws.send(JSON.stringify(m));
  c.ack = (uidOutro) => c.enviar({ t: "amigo_ack", uid: uidOutro });
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
const ok = (c, ref) => c.recebidas.some((m) => m.t === "ok" && m.ref === ref);
const confirmados = (c) => tipo(c, "amigo_confirmado").map((m) => m.uid);
const livre = (stub) => runInDurableObject(stub, (inst) => { inst.ritmoSeg.clear(); });

// A propõe, B entra: devolve as duas conexões e a sessão de encontro de cada uma.
async function dupla(stub, uA, uB) {
  const a = await conectar(stub, uA);
  a.enviar(ROLE);
  a.enviar(posMsg(BASE.lat, BASE.lon));
  a.enviar({ t: "proposta", lat: BASE.lat, lon: BASE.lon, vagas: 3 });
  expect(await esperar(() => ultimo(a, "encontro"))).toBeTruthy();
  const id = ultimo(a, "encontro").id;
  const b = await conectar(stub, uB, { "x-rumo-enc": id });
  b.enviar({ t: "entrar", id });
  expect(await esperar(() => ultimo(b, "encontro") && ultimo(a, "encontro")?.participantes.length === 2)).toBe(true);
  return { a, b, sA: ultimo(a, "encontro").eu, sB: ultimo(b, "encontro").eu };
}

describe("marcas de amizade na Celula", { timeout: 25000 }, () => {
  it("marca de um lado só: ok sem revelar nada; as duas marcas revelam o uid só a cada um, uma vez", async () => {
    const stub = novaCelula();
    const uA = unico("uid-ana"), uB = unico("uid-bruno");
    const { a, b, sA, sB } = await dupla(stub, uA, uB);
    a.enviar({ t: "amigo", sessao: sB });
    expect(await esperar(() => ok(a, "amigo"))).toBe(true);
    await dormir(300);
    expect(confirmados(a)).toEqual([]);
    expect(confirmados(b)).toEqual([]);
    expect(b.recebidas.some((m) => m.t === "ok" || m.t === "erro")).toBe(false); // B nem sabe que foi marcado

    a.enviar({ t: "amigo_ack", uid: uB }); // ack de uid que nunca foi enviado: descartado
    await dormir(200);
    await livre(stub);
    b.enviar({ t: "amigo", sessao: sA });
    expect(await esperar(() => ok(b, "amigo"))).toBe(true);
    expect(await esperar(() => confirmados(a).length === 1 && confirmados(b).length === 1)).toBe(true);
    expect(confirmados(a)).toEqual([uB]); // cada um recebe o uid do OUTRO
    expect(confirmados(b)).toEqual([uA]);
    // Nada além disso vaza o uid, e a marca repetida não entrega de novo.
    await livre(stub);
    a.enviar({ t: "amigo", sessao: sB });
    await livre(stub);
    await dormir(400);
    expect(confirmados(a)).toEqual([uB]);
    expect(confirmados(b)).toEqual([uA]);
    expect(JSON.stringify([...a.recebidas, ...b.recebidas].filter((m) => m.t !== "amigo_confirmado"))).not.toContain("uid-");
  });

  it("entrega na próxima conexão de quem estava fora quando a marca recíproca aconteceu, e só uma vez", async () => {
    const stub = novaCelula();
    const uA = unico("uid-ana"), uB = unico("uid-bruno");
    const { a, b, sA, sB } = await dupla(stub, uA, uB);
    a.enviar({ t: "amigo", sessao: sB });
    expect(await esperar(() => ok(a, "amigo"))).toBe(true);
    a.ws.close(1000, "x");
    await dormir(300);
    await livre(stub);
    b.enviar({ t: "amigo", sessao: sA });
    expect(await esperar(() => confirmados(b).length === 1)).toBe(true);
    expect(confirmados(b)).toEqual([uA]);

    const a2 = await conectar(stub, uA);
    expect(await esperar(() => confirmados(a2).length === 1)).toBe(true);
    expect(confirmados(a2)).toEqual([uB]);
    // Sem ack, a próxima conexão reenvia (entrega perdida); com ack, encerra.
    a2.ws.close(1000, "x");
    await dormir(200);
    const a3 = await conectar(stub, uA);
    expect(await esperar(() => confirmados(a3).length === 1)).toBe(true);
    expect(confirmados(a3)).toEqual([uB]);
    a3.ack(uB);
    await dormir(400);
    a3.ws.close(1000, "x");
    await dormir(200);
    const a4 = await conectar(stub, uA);
    await dormir(600);
    expect(confirmados(a4)).toEqual([]); // ack encerrou
    // C, de fora, nada recebe.
    const c = await conectar(stub, unico("uid-carla"));
    await dormir(400);
    expect(confirmados(c)).toEqual([]);
  });

  it("recusa (inexistente) quem não esteve no encontro, sessão desconhecida e presença comum; sessao_propria; ritmo", async () => {
    const stub = novaCelula();
    const uA = unico("uid-ana"), uB = unico("uid-bruno"), uC = unico("uid-carla");
    const { a, sA } = await dupla(stub, uA, uB);
    const c = await conectar(stub, uC);
    c.enviar(ROLE);
    c.enviar(posMsg(BASE.lat + 0.001, BASE.lon));
    expect(await esperar(() => ultimo(c, "vizinhos")?.lista.length >= 1)).toBe(true);
    c.enviar({ t: "amigo", sessao: ultimo(c, "vizinhos").lista[0].sessao }); // sessão de presença, não de encontro
    expect(await esperar(() => erro(c, "inexistente"))).toBe(true);
    await livre(stub);
    c.enviar({ t: "amigo", sessao: sA }); // sessão de um encontro de que C não fez parte
    expect(await esperar(() => c.recebidas.filter((m) => m.codigo === "inexistente").length === 2)).toBe(true);
    await livre(stub);
    c.enviar({ t: "amigo", sessao: "0000000000000001" });
    expect(await esperar(() => c.recebidas.filter((m) => m.codigo === "inexistente").length === 3)).toBe(true);
    expect(ok(c, "amigo")).toBe(false);
    expect((await (await conta(uC).fetch("https://conta/amigo-marca?uid=" + uA)).json()).marcou).toBe(false);

    a.enviar({ t: "amigo", sessao: sA });
    expect(await esperar(() => erro(a, "sessao_propria"))).toBe(true);
    // Ritmo: duas seguidas, a segunda recebe espera.
    a.enviar({ t: "amigo", sessao: "0000000000000002" });
    expect(await esperar(() => erro(a, "espera"))).toBe(true);
  });

  it("marca depois de 24 h não vale (par expirado e marca antiga expirada)", async () => {
    const stub = novaCelula();
    const uA = unico("uid-ana"), uB = unico("uid-bruno");
    const { a, b, sA, sB } = await dupla(stub, uA, uB);
    // A marca agora; a Conta de A passa 25 h: a marca dela expirou, a de B (nova) não basta.
    a.enviar({ t: "amigo", sessao: sB });
    expect(await esperar(() => ok(a, "amigo"))).toBe(true);
    await runInDurableObject(conta(uA), (inst) => { const t0 = Date.now(); inst.now = () => t0 + 25 * 3_600_000; });
    await livre(stub);
    b.enviar({ t: "amigo", sessao: sA });
    expect(await esperar(() => ok(b, "amigo"))).toBe(true);
    await dormir(400);
    expect(confirmados(a)).toEqual([]);
    expect(confirmados(b)).toEqual([]);
    // Par expirado na Celula: inexistente.
    await runInDurableObject(stub, (inst) => { const t0 = Date.now(); inst.now = () => t0 + 25 * 3_600_000; inst.ritmoSeg.clear(); });
    b.enviar({ t: "amigo", sessao: sA });
    expect(await esperar(() => erro(b, "inexistente"))).toBe(true);
  });

  it("bloqueio posterior: quem foi bloqueado marca e recebe o mesmo ok, sem gravar; marcas anteriores caem", async () => {
    const stub = novaCelula();
    const uA = unico("uid-ana"), uB = unico("uid-bruno");
    const { a, b, sA, sB } = await dupla(stub, uA, uB);
    b.enviar({ t: "amigo", sessao: sA }); // B marcou antes
    expect(await esperar(() => ok(b, "amigo"))).toBe(true);
    await livre(stub);
    a.enviar({ t: "bloquear", sessao: sB });
    expect(await esperar(() => ok(a, "bloqueio"))).toBe(true);
    expect((await (await conta(uB).fetch("https://conta/amigo-marca?uid=" + uA)).json()).marcou).toBe(false); // marca de B apagada
    await livre(stub);
    b.enviar({ t: "amigo", sessao: sA }); // B foi bloqueado: mesmo ok, nada gravado
    expect(await esperar(() => b.recebidas.filter((m) => m.t === "ok" && m.ref === "amigo").length === 2)).toBe(true);
    expect(erro(b, "inexistente")).toBe(false);
    expect((await (await conta(uB).fetch("https://conta/amigo-marca?uid=" + uA)).json()).marcou).toBe(false);
    // A tenta marcar quem ela bloqueou: inexistente.
    await livre(stub);
    a.enviar({ t: "amigo", sessao: sB });
    expect(await esperar(() => erro(a, "inexistente"))).toBe(true);
    await dormir(300);
    expect(confirmados(a)).toEqual([]);
    expect(confirmados(b)).toEqual([]);
  });

  it("caminho falso faz uma única ida à Conta (POST /amigo simulado) e o teto vale igual", async () => {
    const stub = novaCelula();
    const uA = unico("uid-ana"), uB = unico("uid-bruno");
    const { a, b, sA, sB } = await dupla(stub, uA, uB);
    a.enviar({ t: "bloquear", sessao: sB });
    expect(await esperar(() => ok(a, "bloqueio"))).toBe(true);
    await livre(stub);
    await runInDurableObject(stub, (inst) => {
      inst.chamadas = [];
      const orig = inst.chamarConta.bind(inst);
      inst.chamarConta = (uid, caminho, ...r) => { inst.chamadas.push(caminho.split("?")[0] + (caminho.includes("simular") ? "+s" : "")); return orig(uid, caminho, ...r); };
    });
    b.enviar({ t: "amigo", sessao: sA });
    expect(await esperar(() => ok(b, "amigo"))).toBe(true);
    await dormir(300);
    expect(await runInDurableObject(stub, (inst) => inst.chamadas)).toEqual(["/amigo+s"]);
    // Teto cheio: mesmo erro de um alvo normal.
    for (let i = 0; i < 50; i++) await conta(uB).fetch(`https://conta/amigo?uid=x${i}`, { method: "POST" });
    await livre(stub);
    b.enviar({ t: "amigo", sessao: sA });
    expect(await esperar(() => erro(b, "limite"))).toBe(true);
  });

  it("bloqueio depois das duas marcas, antes da entrega: nada é entregue", async () => {
    const stub = novaCelula();
    const uA = unico("uid-ana"), uB = unico("uid-bruno");
    const { a, b, sA, sB } = await dupla(stub, uA, uB);
    // A marca; B marca com a entrega impedida (B sem conexão ativa aqui para entregar), depois A bloqueia.
    a.enviar({ t: "amigo", sessao: sB });
    expect(await esperar(() => ok(a, "amigo"))).toBe(true);
    await livre(stub);
    await runInDurableObject(stub, (inst) => { inst.entregarAmigos = async () => {}; });
    b.enviar({ t: "amigo", sessao: sA });
    expect(await esperar(() => ok(b, "amigo"))).toBe(true);
    await livre(stub);
    a.enviar({ t: "bloquear", sessao: sB });
    expect(await esperar(() => ok(a, "bloqueio"))).toBe(true);
    for (const u of [uA, uB]) {
      const r = await (await conta(u).fetch(`https://conta/amigos-confirmados?eu=${u}`)).json();
      expect(r.uids).toEqual([]);
    }
    expect(confirmados(a)).toEqual([]);
    expect(confirmados(b)).toEqual([]);
  });

  it("pessoa suspensa não pode marcar", async () => {
    const stub = novaCelula();
    const uA = unico("uid-ana"), uB = unico("uid-bruno");
    const { a, sB } = await dupla(stub, uA, uB);
    await conta(uA).fetch("https://conta/suspensao?acao=por", { method: "POST" });
    await runInDurableObject(stub, (inst) => { inst.susp.clear(); inst.ritmoSeg.clear(); });
    a.enviar({ t: "amigo", sessao: sB });
    expect(await esperar(() => erro(a, "suspenso"))).toBe(true);
    expect((await (await conta(uB).fetch("https://conta/amigo-marca?uid=" + uA)).json()).marcou).toBe(false);
  });

  it("teto de 50 marcas ativas: a 51ª recebe limite", async () => {
    const stub = novaCelula();
    const uA = unico("uid-ana"), uB = unico("uid-bruno");
    const { a, sB } = await dupla(stub, uA, uB);
    for (let i = 0; i < 50; i++) await conta(uA).fetch(`https://conta/amigo?uid=x${i}`, { method: "POST" });
    await livre(stub);
    a.enviar({ t: "amigo", sessao: sB });
    expect(await esperar(() => erro(a, "limite"))).toBe(true);
  });
});
