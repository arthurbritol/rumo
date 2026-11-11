// Integração da Celula: Durable Object de verdade (workerd) com WebSockets reais.
// Rodar com: npm run test:int
import { describe, it, expect } from "vitest";
import { env, runInDurableObject, runDurableObjectAlarm, evictDurableObject } from "cloudflare:test";
import { Celula } from "../../src/celula.js";

const ROLE = { t: "procurar", ritmo: "moderado", distancia: "50a150", parada: "cafe", raioKm: 5 };
const BASE = { lat: -20.3, lon: -40.3 };
const posMsg = (lat, lon, extra = {}) => ({ t: "posicao", lat, lon, rumo: 90, vel: 40, distKm: null, etaMin: null, ...extra });

let contador = 0;
// Uma Celula nova por teste (nome único) para um teste não enxergar a presença do outro.
function novaCelula() {
  return env.CELULA.get(env.CELULA.idFromName(`teste-${++contador}`));
}

// Conecta um cliente falso. Quem autentica (o Worker, Tarefa 5) é que põe os cabeçalhos x-rumo-*.
async function conectar(stub, uid, extraHeaders = {}) {
  const resp = await stub.fetch("https://celula/ws", {
    headers: { Upgrade: "websocket", "x-rumo-uid": uid, ...extraHeaders },
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
async function esperar(pred, ms = 3000) {
  const fim = Date.now() + ms;
  while (Date.now() < fim) {
    if (pred()) return true;
    await dormir(25);
  }
  return pred();
}
const vizinhos = (c) => c.recebidas.filter((m) => m.t === "vizinhos");
const ultimaLista = (c) => vizinhos(c).at(-1)?.lista;

describe("Celula", () => {
  it("dois clientes próximos se enxergam, sem uid", async () => {
    const stub = novaCelula();
    const a = await conectar(stub, "uid-secreto-da-ana-111");
    const b = await conectar(stub, "uid-secreto-do-bruno-222");
    a.enviar(ROLE);
    b.enviar(ROLE);
    a.enviar(posMsg(BASE.lat, BASE.lon));
    b.enviar(posMsg(BASE.lat + 0.01, BASE.lon));

    expect(await esperar(() => ultimaLista(a)?.length === 1 && ultimaLista(b)?.length === 1)).toBe(true);
    for (const c of [a, b]) {
      const todo = JSON.stringify(c.recebidas);
      expect(todo).not.toContain("uid-secreto");
      expect(todo.toLowerCase()).not.toContain('"uid"');
      const v = ultimaLista(c)[0];
      expect(Object.keys(v).sort()).toEqual(
        ["combina", "distancia", "lat", "lon", "parada", "ritmo", "rumo", "sessao"],
      );
      expect(v.sessao).toMatch(/^[0-9a-f]{16}$/);
      expect(v.combina).toBe(true);
    }
    // A posição enviada é aproximada, nunca a real.
    expect(ultimaLista(a)[0].lat).not.toBe(BASE.lat + 0.01);
  });

  it("fechar um socket faz o outro receber lista vazia em até 3 s", async () => {
    const stub = novaCelula();
    const a = await conectar(stub, "uid-a");
    const b = await conectar(stub, "uid-b");
    a.enviar(ROLE);
    b.enviar(ROLE);
    a.enviar(posMsg(BASE.lat, BASE.lon));
    b.enviar(posMsg(BASE.lat + 0.01, BASE.lon));
    expect(await esperar(() => ultimaLista(a)?.length === 1)).toBe(true);

    b.ws.close(1000, "tchau");
    expect(await esperar(() => ultimaLista(a)?.length === 0, 3000)).toBe(true);
  });

  it("parar tira a pessoa da lista dos outros e devolve lista vazia a ela", async () => {
    const stub = novaCelula();
    const a = await conectar(stub, "uid-a");
    const b = await conectar(stub, "uid-b");
    a.enviar(ROLE);
    b.enviar(ROLE);
    a.enviar(posMsg(BASE.lat, BASE.lon));
    b.enviar(posMsg(BASE.lat + 0.01, BASE.lon));
    expect(await esperar(() => ultimaLista(a)?.length === 1)).toBe(true);
    b.enviar({ t: "parar" });
    expect(await esperar(() => ultimaLista(b)?.length === 0)).toBe(true);
    expect(await esperar(() => ultimaLista(a)?.length === 0, 3000)).toBe(true);
  });

  it("velocidade implausível vira erro posicao_implausivel; mensagem inválida vira erro", async () => {
    const stub = novaCelula();
    const a = await conectar(stub, "uid-a");
    a.enviar(ROLE);
    a.enviar(posMsg(BASE.lat, BASE.lon, { vel: 300 }));
    expect(await esperar(() => a.recebidas.some((m) => m.t === "erro" && m.codigo === "posicao_implausivel"))).toBe(true);
    a.ws.send("isto não é json");
    expect(await esperar(() => a.recebidas.some((m) => m.t === "erro" && m.codigo === "mensagem_invalida"))).toBe(true);
  });

  it("posicao antes de procurar é erro nao_procurando", async () => {
    const stub = novaCelula();
    const a = await conectar(stub, "uid-a");
    a.enviar(posMsg(BASE.lat, BASE.lon));
    expect(await esperar(() => a.recebidas.some((m) => m.t === "erro" && m.codigo === "nao_procurando"))).toBe(true);
  });

  it("limita a no máximo uma lista a cada 2 s por conexão", async () => {
    const stub = novaCelula();
    const a = await conectar(stub, "uid-a");
    const b = await conectar(stub, "uid-b");
    a.enviar(ROLE);
    b.enviar(ROLE);
    b.enviar(posMsg(BASE.lat + 0.01, BASE.lon));
    for (let i = 0; i < 10; i++) a.enviar(posMsg(BASE.lat + i * 1e-5, BASE.lon));
    await dormir(800);
    expect(vizinhos(b).length).toBeLessThanOrEqual(1);
    // ...mas o estado final chega depois (envio pendente)
    expect(await esperar(() => vizinhos(b).length >= 2, 3000)).toBe(true);
  });

  it("alarm expira quem está sem sinal e avisa os outros", async () => {
    const stub = novaCelula();
    const a = await conectar(stub, "uid-a");
    const b = await conectar(stub, "uid-b");
    a.enviar(ROLE);
    b.enviar(ROLE);
    a.enviar(posMsg(BASE.lat, BASE.lon));
    b.enviar(posMsg(BASE.lat + 0.01, BASE.lon));
    expect(await esperar(() => ultimaLista(a)?.length === 1 && ultimaLista(b)?.length === 1)).toBe(true);

    // Avança o relógio da Celula em 121 s e mantém só o "a" vivo.
    await runInDurableObject(stub, (inst) => {
      const t0 = Date.now();
      inst.now = () => t0 + 121_000;
      inst.presence.pessoas.forEach((p, id) => {
        if (p.uid === "uid-a") p.visto = t0 + 121_000;
      });
    });
    expect(await runDurableObjectAlarm(stub)).toBe(true);
    expect(await esperar(() => b.fechou)).toBe(true);
    expect(await esperar(() => ultimaLista(a)?.length === 0, 3000)).toBe(true);
    expect(a.fechou).toBe(false);
  });

  it("alarm fecha a conexão cujo token passou do exp (+300 s)", async () => {
    const stub = novaCelula();
    const velho = Math.floor(Date.now() / 1000) - 1000;
    const a = await conectar(stub, "uid-a", { "x-rumo-exp": String(velho) });
    const b = await conectar(stub, "uid-b", { "x-rumo-exp": String(Math.floor(Date.now() / 1000) + 3000) });
    a.enviar(ROLE);
    b.enviar(ROLE);
    await dormir(100);
    expect(await runDurableObjectAlarm(stub)).toBe(true);
    expect(await esperar(() => a.fechou)).toBe(true);
    expect(b.fechou).toBe(false);
  });

  it("hibernação: o estado é reconstruído dos anexos dos WebSockets", async () => {
    const stub = novaCelula();
    const a = await conectar(stub, "uid-a");
    const b = await conectar(stub, "uid-b");
    a.enviar(ROLE);
    b.enviar(ROLE);
    a.enviar(posMsg(BASE.lat, BASE.lon));
    b.enviar(posMsg(BASE.lat + 0.01, BASE.lon));
    expect(await esperar(() => ultimaLista(a)?.length === 1 && ultimaLista(b)?.length === 1)).toBe(true);

    // Uma instância nova sobre o mesmo estado (como depois de acordar) já conhece os dois.
    const recontagem = await runInDurableObject(stub, async (inst, state) => {
      const acordada = new Celula(state, env);
      const lista = await acordada.presence.neighborsFor(
        [...acordada.presence.pessoas.keys()][0],
      );
      return { pessoas: acordada.presence.pessoas.size, vizinhos: lista.length };
    });
    expect(recontagem).toEqual({ pessoas: 2, vizinhos: 1 });

    // E de ponta a ponta: despeja a instância da memória e o serviço continua funcionando.
    await evictDurableObject(stub);
    a.enviar(posMsg(BASE.lat + 0.0001, BASE.lon));
    const antes = vizinhos(b).length;
    expect(await esperar(() => vizinhos(b).length > antes, 4000)).toBe(true);
    expect(ultimaLista(b).length).toBe(1);
  });

  it("sem NEARBY_SECRET a Celula falha com mensagem clara", async () => {
    const stub = novaCelula();
    await runInDurableObject(stub, (inst, state) => {
      expect(() => new Celula(state, {})).toThrow("NEARBY_SECRET ausente");
    });
  });

  it("recusa conexão sem upgrade ou sem uid", async () => {
    const stub = novaCelula();
    expect((await stub.fetch("https://celula/ws")).status).toBe(426);
    const r = await stub.fetch("https://celula/ws", { headers: { Upgrade: "websocket" } });
    expect(r.status).toBe(400);
  });
});
