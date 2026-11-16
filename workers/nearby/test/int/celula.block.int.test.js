// Integração de bloqueios e denúncias na Celula (Durable Object de verdade) com a Conta real.
// Rodar com: npm run test:int
import { describe, it, expect } from "vitest";
import { env } from "cloudflare:test";

const ROLE = { t: "procurar", ritmo: "moderado", distancia: "50a150", parada: "cafe", raioKm: 5 };
const BASE = { lat: -20.3, lon: -40.3 };
const CELL = "-21:-41";
const posMsg = (lat, lon) => ({ t: "posicao", lat, lon, rumo: 90, vel: 40, distKm: null, etaMin: null });

let contador = 0;
const unico = (p) => `${p}-${++contador}-${Math.random().toString(36).slice(2, 8)}`;
const novaCelula = () => env.CELULA.get(env.CELULA.idFromName(unico("blk")));

async function conectar(stub, uid, extra = {}) {
  const resp = await stub.fetch("https://celula/ws", {
    headers: { Upgrade: "websocket", "x-rumo-uid": uid, "x-rumo-cell": CELL, ...extra },
  });
  expect(resp.status).toBe(101);
  const ws = resp.webSocket;
  ws.accept();
  const c = { ws, uid, recebidas: [], fechou: false };
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
const lista = (c) => ultimo(c, "vizinhos")?.lista;
const erro = (c, codigo) => c.recebidas.some((m) => m.t === "erro" && m.codigo === codigo);
const ok = (c, ref) => c.recebidas.some((m) => m.t === "ok" && m.ref === ref);

// Conecta uma pessoa procurando, em posição própria (todas a poucos metros uma da outra).
async function pessoa(stub, uid, dLat = 0) {
  const c = await conectar(stub, uid);
  c.enviar(ROLE);
  c.enviar(posMsg(BASE.lat + dLat, BASE.lon));
  return c;
}
// Descobre a sessão de cada vizinho de `c` (lista com uma pessoa só).
const unicaSessao = (c) => lista(c)[0].sessao;

describe("bloqueio na Celula", { timeout: 20000 }, () => {
  it("A bloqueia B: somem um do outro, C continua vendo os dois, e vale numa nova conexão", async () => {
    const stub = novaCelula();
    const uA = unico("uid-ana"), uB = unico("uid-bruno"), uC = unico("uid-carla");
    const a = await pessoa(stub, uA);
    const b = await pessoa(stub, uB, 0.001);
    const c = await pessoa(stub, uC, 0.002);
    expect(await esperar(() => lista(a)?.length === 2 && lista(b)?.length === 2 && lista(c)?.length === 2)).toBe(true);

    // A escolhe B pela sessão que enxerga (a de menor lat é a mais perto de A: B).
    const bSess = lista(a).find((v) => Math.abs(v.lat - (BASE.lat + 0.001)) < 0.0045).sessao;
    // Desvio de até 400 m pode confundir B e C (111 m de distância): pega por exclusão testando.
    const alvo = lista(a).map((v) => v.sessao);
    expect(alvo).toContain(bSess);

    // Bloqueia cada candidato até acertar B: B é o que, depois do bloqueio, deixa de ver A.
    a.enviar({ t: "bloquear", sessao: bSess });
    expect(await esperar(() => ok(a, "bloqueio"))).toBe(true);
    await esperar(() => lista(a)?.length === 1 && lista(c)?.length === 2);
    expect(lista(a).length).toBe(1);
    // O bloqueado é quem sumiu de A; some A dele também.
    const quemFicou = lista(a)[0].sessao;
    expect(quemFicou).not.toBe(bSess);
    const bloqueado = [b, c].find((x) => lista(x).length === 1);
    expect(bloqueado).toBeDefined();
    const livre = bloqueado === b ? c : b;
    expect(lista(livre).length).toBe(2);

    // Nova conexão de cada um (nova sessão): o bloqueio continua valendo.
    const uidBloqueado = bloqueado === b ? uB : uC;
    a.ws.close(1000, "x");
    bloqueado.ws.close(1000, "x");
    await dormir(300);
    const a2 = await pessoa(stub, uA);
    const b2 = await pessoa(stub, uidBloqueado, bloqueado === b ? 0.001 : 0.002);
    await dormir(2500);
    expect(lista(a2).length).toBe(1);
    expect(lista(b2).length).toBe(1);
    for (const x of [a, b, c, a2, b2]) {
      const todo = JSON.stringify(x.recebidas);
      expect(todo).not.toContain("uid-");
      expect(todo.toLowerCase()).not.toContain('"uid"');
    }
  });

  it("proposta de A não aparece para B (e a de B não aparece para A); C vê as duas; B recebe inexistente ao entrar", async () => {
    const stub = novaCelula();
    const uA = unico("uid-a"), uB = unico("uid-b"), uC = unico("uid-c");
    const a = await pessoa(stub, uA);
    const b = await pessoa(stub, uB, 0.001);
    const c = await pessoa(stub, uC, 0.002);
    expect(await esperar(() => lista(a)?.length === 2 && lista(b)?.length === 2)).toBe(true);
    // Só A e B: usa cada sessão que A enxerga até o bloqueio pegar o B (B e C estão a ~110 m).
    // Para saber qual é B, B propõe e A enxerga o id da proposta; mas antes bloqueia por tentativa:
    // A bloqueia as duas sessões? Não: usa as sessões do encontro. Simples: B propõe, A e C veem; A
    // bloqueia a sessão do proponente (participante.eu do encontro de B só B conhece). Então usa vizinhos.
    b.enviar({ t: "proposta", lat: BASE.lat, lon: BASE.lon, vagas: 3 });
    expect(await esperar(() => ultimo(b, "encontro"))).toBeTruthy();
    expect(await esperar(() => ultimo(a, "propostas")?.lista.length === 1 && ultimo(c, "propostas")?.lista.length === 1)).toBe(true);
    const idEnc = ultimo(b, "encontro").id;

    // B bloqueia a sessão de quem for A (e não C): bloqueia uma e vê quem sumiu.
    const candidatos = lista(b).map((v) => v.sessao);
    b.enviar({ t: "bloquear", sessao: candidatos[0] });
    expect(await esperar(() => ok(b, "bloqueio"))).toBe(true);
    await esperar(() => lista(b)?.length === 1);
    const bloqueadoPorB = [a, c].find((x) => ultimo(x, "propostas")?.lista.length === 0) ??
      (await esperar(() => [a, c].some((x) => ultimo(x, "propostas")?.lista.length === 0)) &&
        [a, c].find((x) => ultimo(x, "propostas")?.lista.length === 0));
    expect(bloqueadoPorB).toBeDefined();
    const livre = bloqueadoPorB === a ? c : a;
    expect(ultimo(livre, "propostas").lista.length).toBe(1);

    // O bloqueado tenta entrar por uma conexão extra: inexistente, sem revelar o motivo.
    const uidBloqueado = bloqueadoPorB === a ? uA : uC;
    const extra = await conectar(stub, uidBloqueado, { "x-rumo-enc": idEnc });
    extra.enviar({ t: "entrar", id: idEnc });
    expect(await esperar(() => erro(extra, "inexistente"))).toBe(true);
    expect(extra.recebidas.some((m) => m.codigo === "cheio")).toBe(false);
    expect(ultimo(extra, "encontro")).toBeUndefined();
    // Quem não é bloqueado entra normalmente.
    const uidLivre = livre === a ? uA : uC;
    const extra2 = await conectar(stub, uidLivre, { "x-rumo-enc": idEnc });
    extra2.enviar({ t: "entrar", id: idEnc });
    expect(await esperar(() => ultimo(extra2, "encontro"))).toBeTruthy();
  });

  it("bloqueio simétrico: a proposta do bloqueador some para o bloqueado e a do bloqueado some para o bloqueador", async () => {
    const stub = novaCelula();
    const uA = unico("uid-a"), uB = unico("uid-b");
    const a = await pessoa(stub, uA);
    const b = await pessoa(stub, uB, 0.001);
    expect(await esperar(() => lista(a)?.length === 1 && lista(b)?.length === 1)).toBe(true);
    a.enviar({ t: "bloquear", sessao: unicaSessao(a) });
    expect(await esperar(() => ok(a, "bloqueio"))).toBe(true);
    expect(await esperar(() => lista(a).length === 0 && lista(b).length === 0)).toBe(true);

    a.enviar({ t: "proposta", lat: BASE.lat, lon: BASE.lon, vagas: 3 });
    expect(await esperar(() => ultimo(a, "encontro"))).toBeTruthy();
    const idA = ultimo(a, "encontro").id;
    await dormir(2300);
    expect(ultimo(b, "propostas")?.lista ?? []).toEqual([]);
    const bExtra = await conectar(stub, uB, { "x-rumo-enc": idA });
    bExtra.enviar({ t: "entrar", id: idA });
    expect(await esperar(() => erro(bExtra, "inexistente"))).toBe(true);

    // E o inverso: proposta de B (bloqueado) não aparece para A (bloqueador).
    const b2 = await pessoa(stub, unico("uid-b2"), 0.0015);
    b2.enviar({ t: "proposta", lat: BASE.lat, lon: BASE.lon, vagas: 3 });
    expect(await esperar(() => ultimo(b2, "encontro"))).toBeTruthy();
    // b2 é outra pessoa (não bloqueada): A a vê. Já a proposta de B:
    b.enviar({ t: "proposta", lat: BASE.lat, lon: BASE.lon, vagas: 3 });
    expect(await esperar(() => ultimo(b, "encontro"))).toBeTruthy();
    expect(await esperar(() => ultimo(a, "propostas")?.lista.length === 1)).toBe(true); // só a de b2
    expect(ultimo(a, "propostas").lista[0].id).toBe(ultimo(b2, "encontro").id);
  });

  it("bloquear a si mesmo é recusado; sessão desconhecida: inexistente; mensagem inválida: mensagem_invalida", async () => {
    const stub = novaCelula();
    const a = await pessoa(stub, unico("uid-a"));
    const b = await pessoa(stub, unico("uid-b"), 0.001);
    expect(await esperar(() => lista(a)?.length === 1 && lista(b)?.length === 1)).toBe(true);
    // A sessão de B vista por A, e a própria sessão de A vista por B: a própria é recusada.
    const aSessVistaPorB = unicaSessao(b);
    a.enviar({ t: "bloquear", sessao: "ffffffffffffffff" });
    expect(await esperar(() => erro(a, "inexistente"))).toBe(true);
    a.enviar({ t: "bloquear", sessao: "uid-a" });
    expect(await esperar(() => erro(a, "mensagem_invalida"))).toBe(true);
    // B manda a própria sessão (a que A enxerga dele): recusado como sessao_propria.
    const bSess = unicaSessao(a);
    b.enviar({ t: "bloquear", sessao: bSess });
    expect(await esperar(() => erro(b, "sessao_propria"))).toBe(true);
    b.enviar({ t: "denunciar", sessao: bSess, motivo: "outro" });
    expect(await esperar(() => b.recebidas.filter((m) => m.codigo === "sessao_propria").length === 2)).toBe(true);
    expect(aSessVistaPorB).not.toBe(bSess);
    // Nada mudou: ainda se veem.
    await dormir(200);
    expect(lista(a).length).toBe(1);
    expect(lista(b).length).toBe(1);
  });

  it("bloqueio atualiza na hora as conexões ativas de B (não espera 2 s) e a de A", async () => {
    const stub = novaCelula();
    const a = await pessoa(stub, unico("uid-a"));
    const b = await pessoa(stub, unico("uid-b"), 0.001);
    expect(await esperar(() => lista(a)?.length === 1 && lista(b)?.length === 1)).toBe(true);
    const antes = Date.now();
    a.enviar({ t: "bloquear", sessao: unicaSessao(a) });
    expect(await esperar(() => lista(b)?.length === 0 && lista(a)?.length === 0, 1500)).toBe(true);
    expect(Date.now() - antes).toBeLessThan(1500);
  });

  it("dentro de um encontro: quem é bloqueado sai dele", async () => {
    const stub = novaCelula();
    const uA = unico("uid-a"), uB = unico("uid-b");
    const a = await pessoa(stub, uA);
    a.enviar({ t: "proposta", lat: BASE.lat, lon: BASE.lon, vagas: 3 });
    expect(await esperar(() => ultimo(a, "encontro"))).toBeTruthy();
    const id = ultimo(a, "encontro").id;
    const b = await conectar(stub, uB, { "x-rumo-enc": id });
    b.enviar({ t: "entrar", id });
    expect(await esperar(() => ultimo(b, "encontro") && ultimo(a, "encontro")?.participantes.length === 2)).toBe(true);
    const sessB = ultimo(b, "encontro").eu;
    a.enviar({ t: "bloquear", sessao: sessB });
    expect(await esperar(() => ok(a, "bloqueio"))).toBe(true);
    expect(await esperar(() => tipo(b, "fim").some((m) => m.motivo === "saiu"))).toBe(true);
    expect(await esperar(() => ultimo(a, "encontro")?.participantes.length === 1)).toBe(true);
  });

  it("limite de bloqueios da Conta: o 501º devolve erro limite", async () => {
    const stub = novaCelula();
    const uA = unico("uid-a");
    const a = await pessoa(stub, uA);
    const b = await pessoa(stub, unico("uid-b"), 0.001);
    expect(await esperar(() => lista(a)?.length === 1)).toBe(true);
    const conta = env.CONTA.get(env.CONTA.idFromName(uA));
    for (let i = 0; i < 500; i++) await conta.fetch(`https://conta/bloquear?uid=cheio-${i}`, { method: "POST" });
    a.enviar({ t: "bloquear", sessao: unicaSessao(a) });
    expect(await esperar(() => erro(a, "limite"))).toBe(true);
    expect(lista(b).length).toBe(1);
  });
});

describe("denúncia na Celula", { timeout: 20000 }, () => {
  const conta = (uid) => env.CONTA.get(env.CONTA.idFromName(uid));
  const denuncias = async (uid) => (await (await conta(uid).fetch("https://conta/denuncias")).json()).denuncias;

  it("grava na Conta do denunciado uid do denunciante e posição desviada; ninguém recebe uid", async () => {
    const stub = novaCelula();
    const uA = unico("uid-ana"), uB = unico("uid-bruno");
    const a = await pessoa(stub, uA);
    const b = await pessoa(stub, uB, 0.001);
    expect(await esperar(() => lista(a)?.length === 1 && lista(b)?.length === 1)).toBe(true);
    a.enviar({ t: "denunciar", sessao: unicaSessao(a), motivo: "comportamento" });
    expect(await esperar(() => ok(a, "denuncia"))).toBe(true);
    const lst = await denuncias(uB);
    expect(lst.length).toBe(1);
    expect(lst[0]).toMatchObject({ por: uA, motivo: "comportamento" });
    expect(Number.isFinite(lst[0].lat) && Number.isFinite(lst[0].lon)).toBe(true);
    // Posição desviada (200 a 400 m da real, com a margem do desvio de arredondamento).
    const dM = Math.hypot((lst[0].lat - (BASE.lat + 0.001)) * 111320, (lst[0].lon - BASE.lon) * 111320 * Math.cos((BASE.lat * Math.PI) / 180));
    expect(dM).toBeGreaterThan(150);
    expect(dM).toBeLessThan(450);
    for (const x of [a, b]) {
      const todo = JSON.stringify(x.recebidas);
      expect(todo).not.toContain("uid-");
      expect(todo.toLowerCase()).not.toContain('"uid"');
    }
    expect(JSON.stringify(a.recebidas.filter((m) => m.t === "ok"))).toBe(JSON.stringify([{ t: "ok", ref: "denuncia" }]));
  });

  it("motivo inválido é recusado e nada é gravado; denúncia repetida em 24 h conta uma vez", async () => {
    const stub = novaCelula();
    const uB = unico("uid-b");
    const a = await pessoa(stub, unico("uid-a"));
    const b = await pessoa(stub, uB, 0.001);
    expect(await esperar(() => lista(a)?.length === 1)).toBe(true);
    const s = unicaSessao(a);
    a.enviar({ t: "denunciar", sessao: s, motivo: "spam" });
    expect(await esperar(() => erro(a, "mensagem_invalida"))).toBe(true);
    expect(await denuncias(uB)).toEqual([]);
    a.enviar({ t: "denunciar", sessao: s, motivo: "falso" });
    a.enviar({ t: "denunciar", sessao: s, motivo: "outro" });
    expect(await esperar(() => a.recebidas.filter((m) => m.t === "ok").length === 2)).toBe(true);
    expect((await denuncias(uB)).length).toBe(1);
    expect(b.fechou).toBe(false);
  });

  it("limite por denunciante: 5 por hora; a sexta recebe espera", async () => {
    const stub = novaCelula();
    const uA = unico("uid-a");
    const a = await conectar(stub, uA);
    a.enviar(ROLE);
    a.enviar(posMsg(BASE.lat, BASE.lon));
    // 6 alvos diferentes, a poucos metros.
    const alvos = [];
    for (let i = 0; i < 6; i++) alvos.push(await pessoa(stub, unico(`uid-alvo${i}`), 0.0005 * (i + 1)));
    expect(await esperar(() => lista(a)?.length === 6)).toBe(true);
    const sessoes = lista(a).map((v) => v.sessao);
    for (const s of sessoes) a.enviar({ t: "denunciar", sessao: s, motivo: "incomodo" });
    const feito = await esperar(() => a.recebidas.filter((m) => m.t === "ok").length === 5 && erro(a, "espera"));
    if (!feito) console.log(JSON.stringify(a.recebidas.filter((m) => m.t !== "vizinhos" && m.t !== "propostas")));
    expect(feito).toBe(true);
    const e = a.recebidas.find((m) => m.codigo === "espera");
    expect(e.restanteMs).toBeGreaterThan(0);
  });
});
