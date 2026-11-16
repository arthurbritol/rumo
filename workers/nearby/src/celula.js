// Durable Object "Celula": uma por quadrado de 1° x 1°. Aceita WebSockets com a API de
// hibernação e usa Presence para dizer a cada pessoa quem está por perto.
//
// Contrato com o Worker (Tarefa 5): só o Worker alcança este objeto, e ele já verificou o token.
// Cabeçalhos internos do pedido de upgrade:
//   x-rumo-uid  (obrigatório) uid do Firebase de quem conectou. Nunca sai daqui.
//   x-rumo-exp  (opcional) exp do token, em segundos; a conexão é fechada 300 s depois dele.
//   x-rumo-cell (opcional) id canônico do quadrado desta Celula; sem ele a Celula não aceita proposta.
//   x-rumo-enc  (opcional) id do encontro desta conexão extra (a pessoa está em outro quadrado).
//
// Hibernação: o estado de cada pessoa (Presence.export) é gravado no anexo do próprio WebSocket
// (serializeAttachment, limite de 2 KB) a cada mudança, e o construtor reconstrói a Presence
// dos anexos de ctx.getWebSockets().
//
// Encontros: esta Celula é dona dos encontros cujo ponto marcado cai no seu quadrado. O estado
// (Encounters + dono de cada proposta) é gravado em ctx.storage ("enc") a cada mudança e relido no
// construtor (blockConcurrencyWhile), então sobrevive à hibernação. Cada conexão tem uma `encSessao`
// aleatória própria (nunca o id de presença nem o uid) e `encId` no anexo quando está num encontro.
// Antes de aceitar uma `proposta` a Celula pergunta à Conta da pessoa (binding CONTA, nome = uid);
// ao fim do encontro, avisa a Conta do proponente (`fechar`) e entrega `fim` a quem estava nele.
//
// O servidor nunca manda uid, posição real fora das regras ou qualquer identificação: só o que
// Presence e Encounters devolvem.

import { Presence } from "./presence.js";
import { Encounters } from "./encounter.js";
import { parseClientMessage, ID_ENCONTRO_RE } from "./protocol.js";
import { applyOffset, cellId, distanceM, fuzzOffset } from "./geo.js";
import { combina } from "./match.js";

const INTERVALO_MIN_ENVIO_MS = 2000; // no máximo uma lista a cada 2 s por conexão
const ALARME_MS = 30_000; // varredura de quem ficou sem sinal
const FOLGA_EXP_MS = 300_000; // tolerância de relógio no exp do token
const SEM_SINAL_MS = 120_000; // quem está num encontro e some por 2 min sai dele
const PISO_POSICAO_MS = 10_000; // piso entre posições aceitas (conexão sem Presence)
const VEL_MAX_KMH = 250;
const SALTO_MAX_M = 50_000;
const SALTO_JANELA_MS = 60_000;
const CELL_RE = /^-?\d+:-?\d+$/;

// Id aleatório de 16 hex que identifica a conexão dentro de um encontro.
function novaSessao() {
  const bytes = crypto.getRandomValues(new Uint8Array(8));
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

const arred1 = (x) => Math.round(x * 10) / 10;

export class Celula {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
    this.now = () => Date.now(); // trocável nos testes
    this.presence = new Presence({ now: () => this.now(), secret: env.NEARBY_SECRET });
    this.ultimoEnvio = new Map(); // connId -> ms do último envio
    this.pendentes = new Set(); // connIds com lista nova esperando o intervalo
    this.timer = null;
    this.enc = new Encounters({ now: () => this.now() });
    this.donos = new Map(); // id do encontro -> uid de quem propôs (só aqui dentro, nunca enviado)
    this.cache = new Map(); // connId -> { propostas, encontro }: último JSON enviado
    ctx.blockConcurrencyWhile(async () => {
      const salvo = await ctx.storage.get("enc");
      if (salvo) this.importarEnc(salvo);
    });
    // Depois de acordar da hibernação: reconstrói a presença dos anexos.
    for (const ws of ctx.getWebSockets()) {
      const a = ws.deserializeAttachment();
      if (a?.presence) this.presence.import(a.connId, a.presence);
    }
  }

  async fetch(request) {
    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("esperava WebSocket", { status: 426 });
    }
    const uid = request.headers.get("x-rumo-uid");
    if (!uid) return new Response("uid ausente", { status: 400 });
    const exp = Number(request.headers.get("x-rumo-exp"));
    const enc = request.headers.get("x-rumo-enc");
    if (enc !== null && !ID_ENCONTRO_RE.test(enc)) return new Response("encontro inválido", { status: 400 });
    const cell = request.headers.get("x-rumo-cell");

    const { 0: cliente, 1: servidor } = new WebSocketPair();
    this.ctx.acceptWebSocket(servidor);
    servidor.serializeAttachment({
      connId: crypto.randomUUID(),
      uid,
      exp: Number.isFinite(exp) && exp > 0 ? exp : null,
      presence: null,
      cell: cell !== null && CELL_RE.test(cell) ? cell : null,
      enc, // encontro a que esta conexão extra se destina (ou null)
      encSessao: novaSessao(),
      encId: null, // encontro em que a conexão está agora
      visto: this.now(),
      ultima: null, // última posição aceita (conexão sem Presence)
    });
    if ((await this.ctx.storage.getAlarm()) === null) {
      await this.ctx.storage.setAlarm(this.now() + ALARME_MS);
    }
    return new Response(null, { status: 101, webSocket: cliente });
  }

  async webSocketMessage(ws, mensagem) {
    const a = ws.deserializeAttachment();
    if (this.expirou(a)) {
      this.fechar(ws, "token expirado");
      await this.finalizar();
      this.mudou();
      return;
    }

    const r = parseClientMessage(mensagem);
    if (!r.ok) return this.erro(ws, "mensagem_invalida");
    const msg = r.msg;

    // Sinal de vida de quem está num encontro (para sumir depois de 2 min sem mensagem).
    a.visto = this.now();
    if (a.encId) this.guardar(ws, a);

    if (msg.t === "procurar") {
      this.presence.join(a.connId, a.uid, msg);
      this.guardar(ws, a);
      this.mudou();
    } else if (msg.t === "parar") {
      this.presence.leave(a.connId);
      this.guardar(ws, a);
      this.enviar(ws, []);
      this.mudou();
    } else if (msg.t === "posicao") {
      await this.posicao(ws, a, msg);
    } else if (msg.t === "proposta") {
      await this.proposta(ws, a, msg);
    } else if (msg.t === "entrar") {
      await this.entrar(ws, a, msg);
    } else if (msg.t === "cancelar") {
      await this.cancelar(ws, a);
    } else if (msg.t === "sair") {
      await this.sairDoEncontro(ws, a);
    }
  }

  async posicao(ws, a, msg) {
    if (this.presence.pessoas.has(a.connId)) {
      try {
        // Posição antes do piso de 10 s é ignorada em silêncio (sem guardar nem notificar).
        if (!this.presence.update(a.connId, msg)) return;
      } catch (e) {
        if (e.message === "posição implausível") return this.erro(ws, "posicao_implausivel");
        throw e;
      }
    } else if (a.encId) {
      // Conexão extra do encontro: sem Presence, as mesmas regras de piso e plausibilidade.
      try {
        if (!this.aceitarPosicao(a, msg)) return;
      } catch (e) {
        if (e.message === "posição implausível") return this.erro(ws, "posicao_implausivel");
        throw e;
      }
    } else {
      return this.erro(ws, "nao_procurando");
    }
    this.guardar(ws, a);
    if (a.encId) {
      await this.posicaoNoEncontro(a, msg.lat, msg.lon, msg.etaMin);
      await this.finalizar();
    }
    this.mudou();
  }

  // Piso de 10 s, velocidade e salto implausíveis (igual à Presence), para quem não tem Presence.
  aceitarPosicao(a, { lat, lon, vel }) {
    const agora = this.now();
    const u = a.ultima;
    if (u && agora - u.t < PISO_POSICAO_MS) return false;
    if (vel !== null && vel !== undefined && vel > VEL_MAX_KMH) throw new Error("posição implausível");
    if (u) {
      const dt = agora - u.t;
      const d = distanceM(u, { lat, lon });
      if (dt < SALTO_JANELA_MS && d > SALTO_MAX_M) throw new Error("posição implausível");
      if (d > 0 && (dt <= 0 || d / 1000 / (dt / 3_600_000) > VEL_MAX_KMH)) throw new Error("posição implausível");
    }
    a.ultima = { lat, lon, t: agora };
    return true;
  }

  // Grava a posição no encontro: distância ao ponto calculada AQUI (o servidor decide a chegada) e
  // posição aproximada com o desvio de uid/dia; a Encounters só mostra a real a menos de 1 km.
  async posicaoNoEncontro(a, lat, lon, etaMin) {
    const enc = this.enc.encontros.get(a.encId);
    if (!enc) return;
    const distKm = distanceM({ lat, lon }, enc.ponto) / 1000;
    const dia = new Date(this.now()).toISOString().slice(0, 10);
    const off = await fuzzOffset(a.uid, dia, this.env.NEARBY_SECRET);
    const aprox = applyOffset(lat, lon, off);
    try {
      this.enc.position(a.encSessao, a.encId, { lat, lon, distKm, etaMin: etaMin ?? null, aprox });
    } catch {
      return; // o encontro acabou ou a pessoa saiu durante a espera
    }
    await this.salvarEnc();
  }

  async proposta(ws, a, msg) {
    // Só o quadrado do ponto marcado é dono do encontro.
    if (!a.cell || cellId(msg.lat, msg.lon) !== a.cell) return this.erro(ws, "mensagem_invalida");
    const eu = this.presence.pessoas.get(a.connId);
    if (!eu) return this.erro(ws, "nao_procurando"); // a proposta leva as preferências de quem procura
    if (a.encId) return this.erro(ws, "ja_tem_proposta");

    const ab = await this.contaAbrir(a.uid);
    if (!ab.ok) return this.erro(ws, ab.codigo, ab.extra);

    // Durante o await outros eventos podem ter rodado: confere de novo antes de criar.
    const a2 = ws.deserializeAttachment();
    const eu2 = this.presence.pessoas.get(a.connId);
    let id = null;
    if (a2 && !a2.encId && eu2) {
      try {
        ({ id } = this.enc.propose(
          a2.encSessao,
          { lat: msg.lat, lon: msg.lon },
          msg.vagas,
          { ritmo: eu2.rolê.ritmo, distancia: eu2.rolê.distancia, parada: eu2.rolê.parada },
        ));
      } catch {
        id = null;
      }
    }
    if (id === null) {
      await this.contaFechar(a.uid, "falha");
      return this.erro(ws, "ja_tem_proposta");
    }
    this.donos.set(id, a.uid);
    a2.encId = id;
    a2.visto = this.now();
    this.guardar(ws, a2);
    this.cache.delete(a.connId);
    if (eu2.pos) await this.posicaoNoEncontro(a2, eu2.pos.lat, eu2.pos.lon, null);
    await this.salvarEnc();
    this.enviarEncontro(ws, a2);
    this.mudou();
  }

  async entrar(ws, a, msg) {
    if (a.enc && a.enc !== msg.id) return this.erro(ws, "inexistente");
    if (a.encId) return a.encId === msg.id ? undefined : this.erro(ws, "ja_em_encontro");
    // A mesma pessoa não ocupa duas vagas (nem entra na própria proposta por outra conexão).
    for (const outro of this.ctx.getWebSockets()) {
      const o = outro.deserializeAttachment();
      if (o?.encId === msg.id && o.uid === a.uid) return this.erro(ws, "ja_em_encontro");
    }
    try {
      this.enc.join(a.encSessao, msg.id); // síncrono: só uma pessoa pega a última vaga
    } catch (e) {
      return this.erro(ws, e.message === "cheio" ? "cheio" : "inexistente");
    }
    a.encId = msg.id;
    a.visto = this.now();
    this.guardar(ws, a);
    this.cache.delete(a.connId);
    await this.salvarEnc();
    this.enviarEncontro(ws, a);
    this.mudou();
  }

  async cancelar(ws, a) {
    if (!a.encId) return this.erro(ws, "inexistente");
    let fim;
    try {
      fim = this.enc.cancel(a.encSessao, a.encId);
    } catch {
      return this.erro(ws, "inexistente"); // não é quem propôs
    }
    await this.finalizar([fim]);
    this.mudou();
  }

  async sairDoEncontro(ws, a) {
    if (!a.encId) return this.erro(ws, "inexistente");
    const id = a.encId;
    try {
      this.enc.leave(a.encSessao, id);
    } catch {
      return this.erro(ws, "inexistente");
    }
    await this.finalizar(); // se quem saiu propôs, o encontro acabou e todos recebem o fim
    const atual = ws.deserializeAttachment();
    if (atual?.encId === id) {
      atual.encId = null;
      this.guardar(ws, atual);
    }
    this.cache.delete(a.connId);
    await this.salvarEnc();
    this.mudou();
  }

  async webSocketClose(ws) {
    this.sair(ws);
    await this.finalizar();
    try {
      ws.close();
    } catch {
      // já fechado
    }
  }

  async webSocketError(ws) {
    this.sair(ws);
    await this.finalizar();
  }

  // Varredura periódica: remove quem está sem sinal há mais de 2 min e conexões com token vencido.
  async alarm() {
    for (const connId of this.presence.expire()) {
      this.fechar(this.socketDe(connId), "sem sinal");
    }
    for (const ws of this.ctx.getWebSockets()) {
      const a = ws.deserializeAttachment();
      if (this.expirou(a)) this.fechar(ws, "token expirado");
      else if (a?.encId && this.now() - (a.visto ?? this.now()) > SEM_SINAL_MS) this.fechar(ws, "sem sinal");
    }
    await this.finalizar(); // expirações de proposta/encontro e quem saiu
    this.mudou();
    if (this.ctx.getWebSockets().length > 0) {
      await this.ctx.storage.setAlarm(this.now() + ALARME_MS);
    }
  }

  // --- auxiliares ---

  expirou(a) {
    return a?.exp != null && this.now() > a.exp * 1000 + FOLGA_EXP_MS;
  }

  socketDe(connId) {
    return this.ctx.getWebSockets().find((ws) => ws.deserializeAttachment()?.connId === connId);
  }

  guardar(ws, a) {
    ws.serializeAttachment({ ...a, presence: this.presence.export(a.connId) });
  }

  sair(ws) {
    const a = ws.deserializeAttachment();
    if (!a) return;
    this.presence.leave(a.connId);
    if (a.encSessao) this.enc.dropSession(a.encSessao);
    this.ultimoEnvio.delete(a.connId);
    this.pendentes.delete(a.connId);
    this.cache.delete(a.connId);
    this.mudou();
  }

  fechar(ws, motivo) {
    if (!ws) return;
    const a = ws.deserializeAttachment();
    if (a) {
      this.presence.leave(a.connId);
      if (a.encSessao) this.enc.dropSession(a.encSessao);
      this.ultimoEnvio.delete(a.connId);
      this.pendentes.delete(a.connId);
      this.cache.delete(a.connId);
    }
    try {
      ws.close(1000, motivo);
    } catch {
      // já fechado
    }
  }

  erro(ws, codigo, extra = {}) {
    try {
      ws.send(JSON.stringify({ t: "erro", codigo, ...extra }));
    } catch {
      // socket já caiu
    }
  }

  enviar(ws, lista) {
    try {
      ws.send(JSON.stringify({ t: "vizinhos", lista }));
    } catch {
      // socket já caiu
    }
  }

  // Algo mudou: todos que estão procurando (ou num encontro) precisam de dados novos (respeitando os 2 s).
  mudou() {
    for (const ws of this.ctx.getWebSockets()) {
      const a = ws.deserializeAttachment();
      if (a?.presence || a?.encId) this.pendentes.add(a.connId);
    }
    this.despachar();
  }

  despachar() {
    const agora = this.now();
    let espera = null;
    for (const connId of [...this.pendentes]) {
      const falta = (this.ultimoEnvio.get(connId) ?? -Infinity) + INTERVALO_MIN_ENVIO_MS - agora;
      if (falta <= 0) {
        this.pendentes.delete(connId);
        this.ultimoEnvio.set(connId, agora);
        const ws = this.socketDe(connId);
        if (!ws) continue;
        const a = ws.deserializeAttachment();
        if (a.presence) {
          this.presence
            .neighborsFor(connId)
            .then((lista) => {
              const w = this.socketDe(connId);
              if (w) this.enviar(w, lista);
            })
            .catch((e) => console.error("falha ao montar a lista de vizinhos", e));
          this.enviarSeMudou(ws, connId, "propostas", { t: "propostas", lista: this.propostasPara(connId, a) });
        }
        this.enviarEncontro(ws, a);
      } else if (espera === null || falta < espera) {
        espera = falta;
      }
    }
    if (espera !== null && this.timer === null) {
      this.timer = setTimeout(() => {
        this.timer = null;
        this.despachar();
      }, espera);
    }
  }

  // --- encontros ---

  // Propostas abertas desta Celula dentro do raio da conexão (posição real dela x ponto, que é
  // público). Nunca leva o id de quem propôs.
  propostasPara(connId, a) {
    const eu = this.presence.pessoas.get(connId);
    if (!eu?.pos) return [];
    const raioM = eu.rolê.raioKm * 1000;
    const lista = [];
    for (const enc of this.enc.encontros.values()) {
      const livres = enc.vagas - enc.participantes.size;
      if (livres <= 0 || a.encId === enc.id) continue;
      if (distanceM(eu.pos, enc.ponto) > raioM) continue;
      lista.push({
        id: enc.id,
        lat: enc.ponto.lat,
        lon: enc.ponto.lon,
        vagasLivres: livres,
        vagasTotal: enc.vagas,
        ritmo: enc.pref.ritmo,
        distancia: enc.pref.distancia,
        parada: enc.pref.parada,
        combina: combina(eu.rolê, enc.pref),
      });
    }
    return lista;
  }

  // Visão do encontro para esta conexão: só ids opacos; posições aproximadas até 1 km do ponto.
  visaoDoEncontro(a) {
    const v = this.enc.viewFor(a.encSessao, a.encId);
    const participantes = v.participantes.map((p) =>
      !p.real && p.lat !== null
        ? { ...p, distKm: arred1(distanceM(v.ponto, p) / 1000) } // distância da posição mostrada, não da real
        : p,
    );
    return { t: "encontro", id: a.encId, ponto: v.ponto, vagasLivres: v.vagasLivres, eu: a.encSessao, participantes };
  }

  enviarEncontro(ws, a) {
    if (!a.encId) return;
    let msg;
    try {
      msg = this.visaoDoEncontro(a);
    } catch {
      return; // o encontro já acabou
    }
    this.enviarSeMudou(ws, a.connId, "encontro", msg);
  }

  enviarSeMudou(ws, connId, tipo, msg) {
    const texto = JSON.stringify(msg);
    const c = this.cache.get(connId) ?? {};
    if (c[tipo] === texto) return;
    c[tipo] = texto;
    this.cache.set(connId, c);
    try {
      ws.send(texto);
    } catch {
      // socket já caiu
    }
  }

  // Encerra o que venceu (tick) mais os `extra` já conhecidos: entrega `fim`, avisa a Conta de
  // quem propôs e grava o estado.
  async finalizar(extra = []) {
    const fins = [...extra, ...this.enc.tick()];
    if (fins.length === 0) return;
    const contas = [];
    for (const f of fins) {
      const uids = [];
      for (const ws of this.ctx.getWebSockets()) {
        const a = ws.deserializeAttachment();
        if (a?.encId !== f.id) continue;
        uids.push(a.uid);
        try {
          ws.send(JSON.stringify({ t: "fim", id: f.id, motivo: f.motivo }));
        } catch {
          // socket já caiu
        }
        a.encId = null;
        this.guardar(ws, a);
        this.cache.delete(a.connId);
      }
      this.aoFimDoEncontro(f, uids);
      const dono = this.donos.get(f.id);
      this.donos.delete(f.id);
      if (dono) contas.push(this.contaFechar(dono, f.motivo));
    }
    await this.salvarEnc();
    await Promise.all(contas);
    this.mudou();
  }

  // Gancho da Tarefa 19 (ruling R5): aqui entrarão os pares (sessão -> uid dos outros) por 24 h.
  aoFimDoEncontro(/* fim, uids */) {}

  // --- Conta (por uid) ---

  async chamarConta(uid, caminho) {
    const ns = this.env.CONTA;
    if (!ns) throw new Error("binding CONTA ausente");
    const resp = await ns.get(ns.idFromName(uid)).fetch(`https://conta${caminho}`, { method: "POST" });
    return { status: resp.status, corpo: await resp.json() };
  }

  // Falha fechada: sem resposta clara da Conta, a proposta não é aceita.
  async contaAbrir(uid) {
    try {
      const { corpo } = await this.chamarConta(uid, "/abrir");
      if (corpo.ok === true) return { ok: true };
      if (corpo.erro === "ja_tem_proposta") return { ok: false, codigo: "ja_tem_proposta" };
      if (corpo.erro === "espera") return { ok: false, codigo: "espera", extra: { restanteMs: corpo.restanteMs } };
    } catch (e) {
      console.error("falha ao consultar a conta", e?.message);
    }
    return { ok: false, codigo: "indisponivel" };
  }

  async contaFechar(uid, motivo) {
    try {
      await this.chamarConta(uid, `/fechar?motivo=${encodeURIComponent(motivo)}`);
    } catch (e) {
      console.error("falha ao fechar a conta", e?.message);
    }
  }

  // --- persistência do estado dos encontros ---

  exportarEnc() {
    return {
      encontros: [...this.enc.encontros.values()].map((e) => ({ ...e, participantes: [...e.participantes] })),
      pendentes: this.enc.pendentes,
      donos: [...this.donos],
    };
  }

  importarEnc(s) {
    for (const e of s.encontros ?? []) {
      this.enc.encontros.set(e.id, { ...e, participantes: new Map(e.participantes) });
    }
    this.enc.pendentes = s.pendentes ?? [];
    this.donos = new Map(s.donos ?? []);
  }

  async salvarEnc() {
    await this.ctx.storage.put("enc", this.exportarEnc());
  }
}
