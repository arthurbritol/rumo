// Integração da suspensão na Celula (Durable Object de verdade) com a Conta real.
// Rodar com: npm run test:int
import { describe, it, expect } from "vitest";
import { env, runInDurableObject, runDurableObjectAlarm } from "cloudflare:test";

const ROLE = { t: "procurar", ritmo: "moderado", distancia: "50a150", parada: "cafe", raioKm: 5 };
const posMsg = (lat, lon) => ({ t: "posicao", lat, lon, rumo: 90, vel: 40, distKm: null, etaMin: null });
const CELL = "-21:-41";
let n = 0;
const unico = (p) => `${p}-${++n}-${Math.random().toString(36).slice(2, 8)}`;
const dormir = (ms) => new Promise((r) => setTimeout(r, ms));
async function esperar(pred, ms = 4000) {
  const fim = Date.now() + ms;
  while (Date.now() < fim) {
    if (pred()) return true;
    await dormir(25);
  }
  return pred();
}
async function conectar(stub, uid) {
  const resp = await stub.fetch("https://celula/ws", { headers: { Upgrade: "websocket", "x-rumo-uid": uid, "x-rumo-cell": CELL } });
  expect(resp.status).toBe(101);
  const ws = resp.webSocket;
  ws.accept();
  const c = { recebidas: [], enviar: (m) => ws.send(JSON.stringify(m)) };
  ws.addEventListener("message", (e) => c.recebidas.push(JSON.parse(e.data)));
  return c;
}
const erro = (c, codigo) => c.recebidas.some((m) => m.t === "erro" && m.codigo === codigo);
const lista = (c) => c.recebidas.filter((m) => m.t === "vizinhos").at(-1)?.lista;
const suspender = (uid, acao = "por") => env.CONTA.get(env.CONTA.idFromName(uid)).fetch(`https://conta/suspensao?acao=${acao}`, { method: "POST" });

describe("suspensão na Celula", { timeout: 20000 }, () => {
  it("procurar de pessoa suspensa dá erro suspenso e ela não aparece; proposta e entrar idem", async () => {
    const stub = env.CELULA.get(env.CELULA.idFromName(unico("susp")));
    const uS = unico("uid-sus"), uB = unico("uid-bia");
    await suspender(uS);
    const s = await conectar(stub, uS);
    const b = await conectar(stub, uB);
    b.enviar(ROLE); b.enviar(posMsg(-20.3, -40.3));
    s.enviar(ROLE); s.enviar(posMsg(-20.3, -40.3));
    expect(await esperar(() => erro(s, "suspenso"))).toBe(true);
    await dormir(300);
    expect(lista(b) ?? []).toEqual([]); // B não vê ninguém
    expect(b.recebidas.some((m) => m.t === "erro")).toBe(false); // só a suspensa percebe
    s.enviar({ t: "proposta", lat: -20.3, lon: -40.3, vagas: 3 });
    s.enviar({ t: "entrar", id: "0123456789abcdef" });
    expect(await esperar(() => s.recebidas.filter((m) => m.codigo === "suspenso").length >= 3)).toBe(true);
  });

  it("tirar a suspensão libera; suspensa enquanto conectada perde a presença no alarme", async () => {
    const stub = env.CELULA.get(env.CELULA.idFromName(unico("susp")));
    const uA = unico("uid-ana"), uB = unico("uid-bia");
    const a = await conectar(stub, uA);
    const b = await conectar(stub, uB);
    a.enviar(ROLE); a.enviar(posMsg(-20.3, -40.3));
    b.enviar(ROLE); b.enviar(posMsg(-20.3001, -40.3));
    expect(await esperar(() => lista(a)?.length === 1 && lista(b)?.length === 1)).toBe(true);

    await suspender(uA);
    await runInDurableObject(stub, (inst) => { for (const v of inst.susp.values()) v.em = 0; }); // força a releitura
    await runDurableObjectAlarm(stub);
    expect(await esperar(() => erro(a, "suspenso"))).toBe(true);
    expect(await esperar(() => lista(b)?.length === 0)).toBe(true);
    expect(b.recebidas.some((m) => m.t === "erro")).toBe(false);

    await suspender(uA, "tirar");
    const a2 = await conectar(stub, uA);
    a2.enviar(ROLE); a2.enviar(posMsg(-20.3, -40.3));
    expect(await esperar(() => lista(a2)?.length === 1)).toBe(true);
    expect(erro(a2, "suspenso")).toBe(false);
  });

  it("suspenso com proposta aberta some dos vizinhos e não aceita entrar; em encontro com gente sai sem motivo", async () => {
    const stub = env.CELULA.get(env.CELULA.idFromName(unico("susp")));
    const uA = unico("uid-ana"), uB = unico("uid-bia"), uC = unico("uid-cai");
    const a = await conectar(stub, uA);
    const b = await conectar(stub, uB);
    const c = await conectar(stub, uC);
    for (const [x, d] of [[a, 0], [b, 0.0001], [c, 0.0002]]) { x.enviar(ROLE); x.enviar(posMsg(-20.3 + d, -40.3)); }
    expect(await esperar(() => lista(a)?.length === 2)).toBe(true);
    a.enviar({ t: "proposta", lat: -20.3, lon: -40.3, vagas: 3 });
    expect(await esperar(() => b.recebidas.some((m) => m.t === "propostas" && m.lista.length === 1))).toBe(true);
    const id = b.recebidas.filter((m) => m.t === "propostas").at(-1).lista[0].id;

    await suspender(uA);
    await runInDurableObject(stub, (inst) => { for (const v of inst.susp.values()) v.em = 0; });
    await runDurableObjectAlarm(stub);
    expect(await esperar(() => b.recebidas.filter((m) => m.t === "propostas").at(-1)?.lista.length === 0)).toBe(true);
    b.enviar({ t: "entrar", id });
    expect(await esperar(() => erro(b, "inexistente"))).toBe(true);
    expect(b.recebidas.some((m) => m.t === "erro" && m.codigo === "suspenso")).toBe(false);

    // Encontro com participante: o dono suspenso sai; o outro não vê motivo de suspensão.
    const uD = unico("uid-dan");
    const a2 = await conectar(stub, uD);
    a2.enviar(ROLE); a2.enviar(posMsg(-20.3, -40.3));
    expect(await esperar(() => lista(a2)?.length === 2)).toBe(true);
    a2.enviar({ t: "proposta", lat: -20.3, lon: -40.3, vagas: 3 });
    expect(await esperar(() => b.recebidas.filter((m) => m.t === "propostas").at(-1)?.lista.length === 1)).toBe(true);
    const id2 = b.recebidas.filter((m) => m.t === "propostas").at(-1).lista[0].id;
    b.enviar({ t: "entrar", id: id2 });
    expect(await esperar(() => b.recebidas.some((m) => m.t === "encontro" && m.id === id2))).toBe(true);
    await suspender(uD);
    await runInDurableObject(stub, (inst) => { for (const v of inst.susp.values()) v.em = 0; });
    await runDurableObjectAlarm(stub);
    expect(await esperar(() => erro(a2, "suspenso"))).toBe(true);
    expect(await esperar(() => b.recebidas.some((m) => m.t === "fim" && m.id === id2))).toBe(true);
    const fimB = b.recebidas.filter((m) => m.t === "fim" && m.id === id2).at(-1);
    expect(fimB.motivo).toBe("cancelado");
    expect(JSON.stringify(b.recebidas)).not.toContain("suspens");
  });
});
