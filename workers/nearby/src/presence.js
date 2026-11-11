// Presença do "Rolê por perto": quem está procurando num quadrado, sem nada de Cloudflare.
// Privacidade: o uid e a posição real ficam só aqui dentro; quem consulta recebe apenas o id
// aleatório da sessão e uma posição aproximada (desvio de 200 a 400 m, estável por uid e dia).

import { applyOffset, distanceM, fuzzOffset } from "./geo.js";
import { combina } from "./match.js";

const VEL_MAX_KMH = 250;
const SALTO_MAX_M = 50_000;
const SALTO_JANELA_MS = 60_000;
const SEM_SINAL_MS = 120_000;
const PISO_UPDATE_MS = 10_000; // intervalo mínimo entre posições aceitas por conexão
const MARGEM_DESVIO_M = 500; // o desvio é de no máximo 400 m; folga só para descartar quem está longe

// Id aleatório de 16 hex (64 bits) que os clientes usam para se referir a uma pessoa.
function novaSessao() {
  const bytes = crypto.getRandomValues(new Uint8Array(8));
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

export class Presence {
  // now: () => milissegundos (injetado para os testes); secret: segredo do desvio de posição.
  constructor({ now, secret, minUpdateMs = PISO_UPDATE_MS }) {
    if (typeof secret !== "string" || secret === "") throw new Error("NEARBY_SECRET ausente");
    this.now = now;
    this.secret = secret;
    this.minUpdateMs = minUpdateMs;
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

  // Grava a posição real e devolve true; devolve false (em silêncio) se veio antes do piso de
  // 10 s desde a última aceita. Rejeita velocidade acima de 250 km/h, salto de mais de 50 km em
  // menos de 60 s e velocidade implícita (distância / tempo) acima de 250 km/h, mesmo que a
  // `vel` declarada seja baixa. Não supõe magnitudes sãs: só compara com os limites.
  update(connId, { lat, lon, rumo, vel }) {
    const p = this.pessoas.get(connId);
    if (!p) throw new Error("conexão não está procurando");
    const agora = this.now();
    if (p.pos && agora - p.posEm < this.minUpdateMs) return false;
    if (vel !== null && vel !== undefined && vel > VEL_MAX_KMH) {
      throw new Error("posição implausível");
    }
    if (p.pos) {
      const dt = agora - p.posEm;
      const d = distanceM(p.pos, { lat, lon });
      if (dt < SALTO_JANELA_MS && d > SALTO_MAX_M) throw new Error("posição implausível");
      // km/h = (m / 1000) / (ms / 3 600 000); com dt = 0 qualquer deslocamento é implausível.
      if (d > 0 && (dt <= 0 || (d / 1000) / (dt / 3_600_000) > VEL_MAX_KMH)) {
        throw new Error("posição implausível");
      }
    }
    p.pos = { lat, lon, rumo: rumo ?? null };
    p.posEm = agora;
    p.visto = agora;
    return true;
  }

  // Quem está no raio de quem pergunta, com posição aproximada. O pertencimento compara a
  // posição REAL de quem pergunta com a posição APROXIMADA do alvo: senão, mexer a própria
  // posição e o raio revelaria a posição real do alvo por busca binária.
  async neighborsFor(connId) {
    const eu = this.pessoas.get(connId);
    if (!eu || !eu.pos) return [];
    const agora = this.now();
    const limite = agora - SEM_SINAL_MS;
    const dia = new Date(agora).toISOString().slice(0, 10);
    const raioM = eu.rolê.raioKm * 1000;
    const candidatos = [];
    for (const [id, o] of this.pessoas) {
      if (id === connId || !o.pos || o.visto < limite) continue;
      // Descarte grosseiro de quem está claramente longe (não vaza nada: só poupa trabalho).
      if (distanceM(eu.pos, o.pos) > raioM + MARGEM_DESVIO_M) continue;
      candidatos.push(o);
    }
    const lista = await Promise.all(
      candidatos.map(async (o) => {
        const off = await fuzzOffset(o.uid, dia, this.secret);
        const aprox = applyOffset(o.pos.lat, o.pos.lon, off);
        if (distanceM(eu.pos, aprox) > raioM) return null;
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
    return lista.filter(Boolean);
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
