// Máquina de estados dos encontros do "Rolê por perto" (lógica pura, sem Cloudflare).
// Só conhece ids de sessão: nunca uid, nome ou posição exata de quem não deve ver. A posição de
// cada participante chega pronta de quem chama; se vier `aprox`, é ela que aparece até a pessoa
// chegar a menos de 1 km do ponto, quando passa a valer a real (lat/lon).

const VAGAS_MIN = 2;
const VAGAS_MAX = 8;
const EXPIRA_MS = 30 * 60_000; // proposta sem ninguém entrando
const SEM_CHEGADA_MS = 60 * 60_000; // em andamento sem chegada nova
const CHEGADA_KM = 0.1; // chegou: a menos de 100 m
const REAL_KM = 1; // posição real: a menos de 1 km

function novoId() {
  const bytes = crypto.getRandomValues(new Uint8Array(8));
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

export class Encounters {
  // now: () => milissegundos (injetado para os testes).
  constructor({ now }) {
    this.now = now;
    // id -> { id, proponente, ponto, vagas, pref, criadoEm, andamento, marco, participantes }
    // participantes: Map sessao -> { pos, chegou }
    this.encontros = new Map();
  }

  propose(sessionId, ponto, vagas, pref) {
    if (!Number.isInteger(vagas) || vagas < VAGAS_MIN || vagas > VAGAS_MAX) {
      throw new Error("vagas inválidas");
    }
    for (const enc of this.encontros.values()) {
      if (enc.proponente === sessionId && enc.participantes.has(sessionId)) {
        throw new Error("já tem proposta");
      }
    }
    const id = novoId();
    const agora = this.now();
    this.encontros.set(id, {
      id,
      proponente: sessionId,
      ponto: { lat: ponto.lat, lon: ponto.lon },
      vagas,
      pref,
      criadoEm: agora,
      andamento: false,
      marco: agora,
      participantes: new Map([[sessionId, { pos: null, chegou: false }]]),
    });
    return { id };
  }

  // Propostas abertas com vaga (entrada direta, então "aberta" = ainda cabe gente).
  openProposals() {
    const lista = [];
    for (const enc of this.encontros.values()) {
      const livres = enc.vagas - enc.participantes.size;
      if (livres > 0) {
        lista.push({ id: enc.id, ponto: enc.ponto, vagasLivres: livres, pref: enc.pref, proponente: enc.proponente });
      }
    }
    return lista;
  }

  join(sessionId, id) {
    const enc = this.encontros.get(id);
    if (!enc) throw new Error("inexistente");
    if (enc.participantes.has(sessionId)) return;
    if (enc.participantes.size >= enc.vagas) throw new Error("cheio");
    enc.participantes.set(sessionId, { pos: null, chegou: false });
    if (!enc.andamento) {
      enc.andamento = true;
      enc.marco = this.now();
    }
  }

  leave(sessionId, id) {
    const enc = this.encontros.get(id);
    if (!enc) throw new Error("inexistente");
    enc.participantes.delete(sessionId);
    if (enc.participantes.size === 0) this.encontros.delete(id);
  }

  cancel(sessionId, id) {
    const enc = this.encontros.get(id);
    if (!enc) throw new Error("inexistente");
    if (enc.proponente !== sessionId) throw new Error("só quem propôs pode cancelar");
    this.encontros.delete(id);
    return { id, motivo: "cancelado" };
  }

  // Remove a sessão (sem sinal ou desconectada) de todos os encontros, sem afetar os outros.
  dropSession(sessionId) {
    for (const [id, enc] of this.encontros) {
      if (!enc.participantes.delete(sessionId)) continue;
      if (enc.participantes.size === 0) this.encontros.delete(id);
    }
  }

  // pos: { lat, lon, distKm, etaMin, aprox?: { lat, lon } }
  position(sessionId, id, pos) {
    const enc = this.encontros.get(id);
    if (!enc) throw new Error("inexistente");
    const p = enc.participantes.get(sessionId);
    if (!p) throw new Error("não participa");
    p.pos = { lat: pos.lat, lon: pos.lon, distKm: pos.distKm, etaMin: pos.etaMin, aprox: pos.aprox ?? null };
    if (!p.chegou && pos.distKm * 1000 < CHEGADA_KM * 1000) {
      p.chegou = true;
      enc.marco = this.now();
    }
  }

  viewFor(sessionId, id) {
    const enc = this.encontros.get(id);
    if (!enc) throw new Error("inexistente");
    if (!enc.participantes.has(sessionId)) throw new Error("não participa");
    const participantes = [];
    for (const [sessao, p] of enc.participantes) {
      const pos = p.pos;
      if (!pos) {
        participantes.push({ sessao, lat: null, lon: null, real: false, distKm: null, etaMin: null, chegou: p.chegou });
        continue;
      }
      const real = pos.distKm < REAL_KM;
      const ponto = real || !pos.aprox ? pos : pos.aprox;
      participantes.push({
        sessao,
        lat: ponto.lat,
        lon: ponto.lon,
        real,
        distKm: pos.distKm,
        etaMin: pos.etaMin,
        chegou: p.chegou,
      });
    }
    return { ponto: enc.ponto, vagasLivres: enc.vagas - enc.participantes.size, participantes };
  }

  // Encerra o que venceu e devolve [{ id, motivo }].
  tick() {
    const agora = this.now();
    const fins = [];
    for (const [id, enc] of this.encontros) {
      let motivo = null;
      if (enc.andamento && [...enc.participantes.values()].every((p) => p.chegou)) motivo = "chegaram";
      else if (!enc.andamento && agora - enc.criadoEm >= EXPIRA_MS) motivo = "expirou";
      else if (enc.andamento && agora - enc.marco >= SEM_CHEGADA_MS) motivo = "ninguem_chegou";
      if (motivo) fins.push({ id, motivo });
    }
    for (const f of fins) this.encontros.delete(f.id);
    return fins;
  }
}
