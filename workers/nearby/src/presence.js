// Presença do "Rolê por perto": quem está procurando num quadrado, sem nada de Cloudflare.
// Privacidade: o uid e a posição real ficam só aqui dentro; quem consulta recebe apenas o id
// aleatório da sessão e uma posição aproximada (desvio de 200 a 400 m, estável por uid e dia).

import { applyOffset, distanceM, fuzzOffset } from "./geo.js";
import { combina } from "./match.js";

const VEL_MAX_KMH = 250;
const SALTO_MAX_M = 50_000;
const SALTO_JANELA_MS = 60_000;
const SEM_SINAL_MS = 120_000;

// Id aleatório de 16 hex (64 bits) que os clientes usam para se referir a uma pessoa.
function novaSessao() {
  const bytes = crypto.getRandomValues(new Uint8Array(8));
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

export class Presence {
  // now: () => milissegundos (injetado para os testes); secret: segredo do desvio de posição.
  constructor({ now, secret }) {
    if (typeof secret !== "string" || secret === "") throw new Error("NEARBY_SECRET ausente");
    this.now = now;
    this.secret = secret;
    // connId -> { uid, sessao, rolê, pos, posEm, visto }
    this.pessoas = new Map();
  }

  // Entra (ou troca o rolê, mantendo sessão e posição) e devolve o id de sessão.
  join(connId, uid, rolê) {
    const atual = this.pessoas.get(connId);
    const r = { ritmo: rolê.ritmo, distancia: rolê.distancia, parada: rolê.parada, raioKm: rolê.raioKm };
    if (atual) {
      atual.rolê = r;
      atual.visto = this.now();
      return atual.sessao;
    }
    const sessao = novaSessao();
    this.pessoas.set(connId, { uid, sessao, rolê: r, pos: null, posEm: 0, visto: this.now() });
    return sessao;
  }

  leave(connId) {
    this.pessoas.delete(connId);
  }

  // Grava a posição real. Rejeita velocidade acima de 250 km/h e salto de mais de 50 km em
  // menos de 60 s. Não supõe magnitudes sãs: só compara com os limites.
  update(connId, { lat, lon, rumo, vel }) {
    const p = this.pessoas.get(connId);
    if (!p) throw new Error("conexão não está procurando");
    const agora = this.now();
    if (vel !== null && vel !== undefined && vel > VEL_MAX_KMH) {
      throw new Error("posição implausível");
    }
    if (p.pos && agora - p.posEm < SALTO_JANELA_MS && distanceM(p.pos, { lat, lon }) > SALTO_MAX_M) {
      throw new Error("posição implausível");
    }
    p.pos = { lat, lon, rumo: rumo ?? null };
    p.posEm = agora;
    p.visto = agora;
  }

  // Quem está dentro do raio de quem pergunta (pela posição real), com posição aproximada.
  async neighborsFor(connId) {
    const eu = this.pessoas.get(connId);
    if (!eu || !eu.pos) return [];
    const dia = new Date(this.now()).toISOString().slice(0, 10);
    const raioM = eu.rolê.raioKm * 1000;
    const proximos = [];
    for (const [id, o] of this.pessoas) {
      if (id === connId || !o.pos) continue;
      if (distanceM(eu.pos, o.pos) <= raioM) proximos.push(o);
    }
    return Promise.all(
      proximos.map(async (o) => {
        const off = await fuzzOffset(o.uid, dia, this.secret);
        const aprox = applyOffset(o.pos.lat, o.pos.lon, off);
        return {
          sessao: o.sessao,
          lat: aprox.lat,
          lon: aprox.lon,
          rumo: o.pos.rumo,
          ritmo: o.rolê.ritmo,
          distancia: o.rolê.distancia,
          parada: o.rolê.parada,
          combina: combina(eu.rolê, o.rolê),
        };
      }),
    );
  }

  // Remove quem está sem sinal há mais de 120 s e devolve as conexões removidas.
  expire() {
    const limite = this.now() - SEM_SINAL_MS;
    const removidas = [];
    for (const [id, p] of this.pessoas) {
      if (p.visto < limite) removidas.push(id);
    }
    for (const id of removidas) this.pessoas.delete(id);
    return removidas;
  }

  // Estado de uma conexão em forma serializável (para o anexo do WebSocket) ou null.
  export(connId) {
    const p = this.pessoas.get(connId);
    return p ? structuredClone(p) : null;
  }

  // Restaura uma conexão a partir de export() depois da hibernação.
  import(connId, dados) {
    this.pessoas.set(connId, structuredClone(dados));
  }
}
