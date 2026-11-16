// Integração de bloqueios e denúncias na Celula (Durable Object de verdade) com a Conta real.
// Rodar com: npm run test:int
import { describe, it, expect } from "vitest";
import { env, runInDurableObject, runDurableObjectAlarm } from "cloudflare:test";

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
// Zera o limite de ritmo (1 bloquear/denunciar a cada 2 s) para não esperar nos testes.
const livre = (stub) => runInDurableObject(stub, (inst) => { inst.ritmoSeg.clear(); });

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
    // B propõe; A e C veem a proposta. Depois B bloqueia uma das sessões vizinhas e vê-se quem sumiu.
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
    await livre(stub);
    // B manda a própria sessão (a que A enxerga dele): recusado como sessao_propria.
    const bSess = unicaSessao(a);
    b.enviar({ t: "bloquear", sessao: bSess });
    expect(await esperar(() => erro(b, "sessao_propria"))).toBe(true);
    await livre(stub);
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
    // Quem é removido recebe o fim neutro "cancelado" (nunca "saiu"), sem revelar o bloqueio.
    expect(await esperar(() => tipo(b, "fim").some((m) => m.motivo === "cancelado"))).toBe(true);
    expect(tipo(b, "fim").some((m) => m.motivo === "saiu")).toBe(false);
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
    await livre(stub);
    a.enviar({ t: "denunciar", sessao: s, motivo: "falso" });
    expect(await esperar(() => ok(a, "denuncia"))).toBe(true);
    await livre(stub);
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
    for (const s of sessoes) {
      await livre(stub);
      a.enviar({ t: "denunciar", sessao: s, motivo: "incomodo" });
      await dormir(150);
    }
    const feito = await esperar(() => a.recebidas.filter((m) => m.t === "ok").length === 5 && erro(a, "espera"));
    if (!feito) console.log(JSON.stringify(a.recebidas.filter((m) => m.t !== "vizinhos" && m.t !== "propostas")));
    expect(feito).toBe(true);
    const e = a.recebidas.find((m) => m.codigo === "espera");
    expect(e.restanteMs).toBeGreaterThan(0);
  });
});

describe("bloqueio: privacidade, encontros e propagação", { timeout: 25000 }, () => {
  const conta = (uid) => env.CONTA.get(env.CONTA.idFromName(uid));
  const bloqueios = async (uid) => (await conta(uid).fetch("https://conta/bloqueios")).json();

  it("quem foi bloqueado não descobre quem bloqueou (inexistente, igual a desconhecida); quem bloqueou ainda denuncia", async () => {
    const stub = novaCelula();
    const uA = unico("uid-a"), uB = unico("uid-b");
    const a = await pessoa(stub, uA);
    const b = await pessoa(stub, uB, 0.001);
    expect(await esperar(() => lista(a)?.length === 1 && lista(b)?.length === 1)).toBe(true);
    const sessB = unicaSessao(a);
    const sessA = unicaSessao(b); // a sessão de A como B a enxergava
    a.enviar({ t: "bloquear", sessao: sessB });
    expect(await esperar(() => ok(a, "bloqueio"))).toBe(true);
    await livre(stub);

    // B tenta bloquear/denunciar A: mesmo erro de uma sessão desconhecida.
    b.enviar({ t: "bloquear", sessao: sessA });
    expect(await esperar(() => erro(b, "inexistente"))).toBe(true);
    await livre(stub);
    b.enviar({ t: "denunciar", sessao: sessA, motivo: "outro" });
    expect(await esperar(() => b.recebidas.filter((m) => m.codigo === "inexistente").length === 2)).toBe(true);
    expect(b.recebidas.filter((m) => m.t === "erro").map((m) => Object.keys(m).sort().join())).toEqual(["codigo,t", "codigo,t"]);
    expect((await conta(uA).fetch("https://conta/denuncias").then((r) => r.json())).denuncias).toEqual([]);
    // A, que bloqueou, ainda pode denunciar B.
    a.enviar({ t: "denunciar", sessao: sessB, motivo: "comportamento" });
    expect(await esperar(() => ok(a, "denuncia"))).toBe(true);
  });

  it("participante bloqueado impede entrar; a proposta some da lista de quem é bloqueado por um participante", async () => {
    const stub = novaCelula();
    const uC = unico("uid-c"), uA = unico("uid-a"), uB = unico("uid-b");
    const c = await pessoa(stub, uC);
    c.enviar({ t: "proposta", lat: BASE.lat, lon: BASE.lon, vagas: 4 });
    expect(await esperar(() => ultimo(c, "encontro"))).toBeTruthy();
    const id = ultimo(c, "encontro").id;
    const a = await conectar(stub, uA, { "x-rumo-enc": id });
    a.enviar({ t: "entrar", id });
    expect(await esperar(() => ultimo(a, "encontro"))).toBeTruthy();
    const b = await pessoa(stub, uB, 0.001);
    expect(await esperar(() => ultimo(b, "propostas")?.lista.length === 1)).toBe(true);
    // A (participante) bloqueou B por outra Celula: só as Contas sabem; a releitura do alarme aplica.
    await conta(uA).fetch(`https://conta/bloquear?uid=${uB}`, { method: "POST" });
    await conta(uB).fetch(`https://conta/bloquear?uid=${uA}&sentido=bloqueadoPor`, { method: "POST" });
    await runInDurableObject(stub, async (inst) => {
      for (const b2 of inst.bloq.values()) b2.em = 0;
    });
    await runDurableObjectAlarm(stub);
    expect(await esperar(() => ultimo(b, "propostas")?.lista.length === 0)).toBe(true);
    const bx = await conectar(stub, uB, { "x-rumo-enc": id });
    bx.enviar({ t: "entrar", id });
    expect(await esperar(() => erro(bx, "inexistente"))).toBe(true);
  });

  it("propagação entre Celulas: a outra Celula passa a valer na releitura do alarme (5 min)", async () => {
    const stub = novaCelula();
    const uA = unico("uid-a"), uB = unico("uid-b");
    const a = await pessoa(stub, uA);
    const b = await pessoa(stub, uB, 0.001);
    expect(await esperar(() => lista(a)?.length === 1 && lista(b)?.length === 1)).toBe(true);
    // O bloqueio foi feito noutra Celula (só chegou às Contas).
    await conta(uA).fetch(`https://conta/bloquear?uid=${uB}`, { method: "POST" });
    await conta(uB).fetch(`https://conta/bloquear?uid=${uA}&sentido=bloqueadoPor`, { method: "POST" });
    // Antes de vencer os 5 min, o alarme não relê.
    await runDurableObjectAlarm(stub);
    await dormir(300);
    expect(lista(a).length).toBe(1);
    await runInDurableObject(stub, async (inst) => {
      for (const b2 of inst.bloq.values()) b2.em = 0;
    });
    await runDurableObjectAlarm(stub);
    expect(await esperar(() => lista(a).length === 0 && lista(b).length === 0)).toBe(true);
  });

  it("falha na segunda gravação: o bloqueio vale, A recebe ok e o alarme repara o lado do bloqueado", async () => {
    const stub = novaCelula();
    const uA = unico("uid-a"), uB = unico("uid-b");
    const a = await pessoa(stub, uA);
    const b = await pessoa(stub, uB, 0.001);
    expect(await esperar(() => lista(a)?.length === 1 && lista(b)?.length === 1)).toBe(true);
    await runInDurableObject(stub, (inst) => {
      const orig = inst.chamarConta.bind(inst);
      inst.origChamar = orig;
      inst.chamarConta = (uid, caminho, ...r) => {
        if (caminho.includes("sentido=bloqueadoPor")) return Promise.reject(new Error("falhou"));
        return orig(uid, caminho, ...r);
      };
    });
    a.enviar({ t: "bloquear", sessao: unicaSessao(a) });
    expect(await esperar(() => ok(a, "bloqueio"))).toBe(true);
    expect(await esperar(() => lista(a).length === 0 && lista(b).length === 0)).toBe(true);
    expect((await bloqueios(uB)).bloqueadoPor).toEqual([]);
    await runInDurableObject(stub, (inst) => { inst.chamarConta = inst.origChamar; });
    await runDurableObjectAlarm(stub);
    expect((await bloqueios(uB)).bloqueadoPor).toEqual([uA]);
    expect((await bloqueios(uA)).bloqueados).toEqual([uB]);
  });
});

describe("pares de encontros terminados (24 h)", { timeout: 25000 }, () => {
  const conta = (uid) => env.CONTA.get(env.CONTA.idFromName(uid));

  it("após o fim, A ainda bloqueia/denuncia B; C (de fora) não resolve a sessão; expira em 24 h", async () => {
    const stub = novaCelula();
    const uA = unico("uid-a"), uB = unico("uid-b"), uC = unico("uid-c");
    const a = await pessoa(stub, uA);
    a.enviar({ t: "proposta", lat: BASE.lat, lon: BASE.lon, vagas: 3 });
    expect(await esperar(() => ultimo(a, "encontro"))).toBeTruthy();
    const id = ultimo(a, "encontro").id;
    const b = await conectar(stub, uB, { "x-rumo-enc": id });
    b.enviar({ t: "entrar", id });
    expect(await esperar(() => ultimo(b, "encontro") && ultimo(a, "encontro")?.participantes.length === 2)).toBe(true);
    const sessB = ultimo(b, "encontro").eu;
    const sessA = ultimo(a, "encontro").eu;
    const c = await pessoa(stub, uC, 0.002);

    a.enviar({ t: "cancelar" });
    expect(await esperar(() => tipo(a, "fim").length === 1 && tipo(b, "fim").length === 1)).toBe(true);
    // A sessão de encontro deixou de existir, mas os dois ainda se resolvem na tela de fim.
    await livre(stub);
    a.enviar({ t: "denunciar", sessao: sessB, motivo: "falso" });
    expect(await esperar(() => ok(a, "denuncia"))).toBe(true);
    expect((await (await conta(uB).fetch("https://conta/denuncias")).json()).denuncias[0]).toMatchObject({ por: uA, motivo: "falso" });
    await livre(stub);
    b.enviar({ t: "bloquear", sessao: sessA });
    expect(await esperar(() => ok(b, "bloqueio"))).toBe(true);
    // C, de fora, não resolve nenhuma das duas.
    await livre(stub);
    c.enviar({ t: "bloquear", sessao: sessB });
    expect(await esperar(() => erro(c, "inexistente"))).toBe(true);
    await livre(stub);
    c.enviar({ t: "denunciar", sessao: sessA, motivo: "outro" });
    expect(await esperar(() => c.recebidas.filter((m) => m.codigo === "inexistente").length === 2)).toBe(true);
    expect(JSON.stringify([a.recebidas, b.recebidas, c.recebidas])).not.toContain("uid-");

    // Depois de 24 h o alarme apaga os pares.
    await runInDurableObject(stub, async (inst) => {
      const t0 = Date.now();
      inst.now = () => t0 + 25 * 3_600_000;
      inst.podarPares();
      expect(Object.keys(inst.pares).length).toBe(0);
    });
  });
});
