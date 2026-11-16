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
const PROPOSTA_INTERVALO_MS = 5000; // no máximo 1 proposta a cada 5 s por conexão
const ENTRAR_INTERVALO_MS = 2000; // no máximo 1 entrar a cada 2 s por conexão
const RECUSA_CURTA_MS = 5000; // depois de ja_tem_proposta/indisponivel, nem pergunta à Conta por 5 s
const MAX_TENTATIVAS_FECHAR = 20;
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
    this.pend = new Set(); // encontros criados mas ainda sem a resposta da Conta (invisíveis a todos)
    this.fechamentos = []; // avisos de fim à Conta que falharam: [{ uid, motivo, id, tentativas }]
    ctx.blockConcurrencyWhile(async () => {
      const salvo = await ctx.storage.get("enc");
      if (salvo) this.importarEnc(salvo);
      // Depois de reinício/deploy o estado voltou do storage, mas os sockets podem ter ido embora:
      // quem não tem socket vivo sai agora; o alarme logo em seguida entrega os fins e fecha as Contas.
      const mudou = this.limparZumbis();
      const pendente = this.enc.encontros.size > 0 || this.enc.pendentes.length > 0 || this.fechamentos.length > 0;
      if (pendente && (mudou || (await ctx.storage.getAlarm()) === null)) {
        await ctx.storage.setAlarm(this.now() + (mudou ? 1000 : ALARME_MS));
      }
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
      encSessao: null, // id opaco novo a cada propor/entrar; vale só enquanto encId existe
      encId: null, // encontro em que a conexão está agora
      lim: null, // limites de taxa e última recusa da Conta (propostaAte, entrarAte, recusaAte, recusaCodigo)
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
    if (a.encId) a.visto = this.now(); // só posição aceita conta como sinal de vida no encontro
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
    const antes = enc.participantes.get(a.encSessao)?.chegou;
    try {
      this.enc.position(a.encSessao, a.encId, { lat, lon, distKm, etaMin: etaMin ?? null, aprox });
    } catch {
      return; // o encontro acabou ou a pessoa saiu durante a espera
    }
    // Posições ficam só em memória (não regravam o blob a cada 10 s); só a chegada é mudança de
    // estrutura. Se o DO reiniciar, o próximo `posicao` de cada pessoa repõe a posição.
    if (enc.participantes.get(a.encSessao)?.chegou !== antes) await this.salvarEnc();
  }

  async proposta(ws, a, msg) {
    // Só o quadrado do ponto marcado é dono do encontro.
    if (!a.cell || cellId(msg.lat, msg.lon) !== a.cell) return this.erro(ws, "ponto_invalido");
    const eu = this.presence.pessoas.get(a.connId);
    if (!eu) return this.erro(ws, "nao_procurando"); // a proposta leva as preferências de quem procura
    if (a.encId) return this.erro(ws, this.donos.get(a.encId) === a.uid ? "ja_tem_proposta" : "ja_em_encontro");

    // Sem chamar a Conta: recusa recente guardada na conexão e no máximo 1 proposta a cada 5 s.
    const agora = this.now();
    const lim = a.lim ?? {};
    if (agora < (lim.recusaAte ?? 0)) {
      return this.erro(ws, lim.recusaCodigo, lim.recusaCodigo === "espera" ? { restanteMs: lim.recusaAte - agora } : {});
    }
    if (agora < (lim.propostaAte ?? 0)) return this.erro(ws, "espera", { restanteMs: lim.propostaAte - agora });

    // O encontro nasce já (síncrono, com a sessão nova) mas fica invisível até a Conta responder.
    const sessao = novaSessao();
    let id;
    try {
      ({ id } = this.enc.propose(sessao, { lat: msg.lat, lon: msg.lon }, msg.vagas, {
        ritmo: eu.rolê.ritmo,
        distancia: eu.rolê.distancia,
        parada: eu.rolê.parada,
      }));
    } catch {
      return this.erro(ws, "ja_tem_proposta");
    }
    this.pend.add(id);
    this.donos.set(id, a.uid);
    a.encSessao = sessao;
    a.encId = id;
    a.lim = { ...lim, propostaAte: agora + PROPOSTA_INTERVALO_MS };
    this.guardar(ws, a);

    const ab = await this.contaAbrir(a.uid, id);
    this.pend.delete(id);
    if (!ab.ok) {
      try {
        this.enc.cancel(sessao, id);
      } catch {
        // já tinha acabado
      }
      this.donos.delete(id);
      const t = this.now();
      const recusaAte = ab.codigo === "espera" ? t + (ab.extra?.restanteMs ?? 0) : t + RECUSA_CURTA_MS;
      const at = ws.deserializeAttachment();
      if (at?.encId === id) {
        at.encId = null;
        at.encSessao = null;
        at.lim = { ...(at.lim ?? {}), recusaAte, recusaCodigo: ab.codigo };
        this.guardar(ws, at);
      }
      return this.erro(ws, ab.codigo, ab.extra);
    }
    if (!this.enc.encontros.has(id)) {
      // A conexão caiu durante a consulta: a Conta acabou de abrir uma proposta que não existe.
      await this.tentarFechar(a.uid, "falha", id);
      return;
    }
    const at = ws.deserializeAttachment();
    at.visto = this.now();
    this.guardar(ws, at);
    this.cache.delete(a.connId);
    if (eu.pos) await this.posicaoNoEncontro(at, eu.pos.lat, eu.pos.lon, null);
    await this.salvarEnc();
    this.enviarEncontro(ws, at);
    this.mudou();
  }

  async entrar(ws, a, msg) {
    if (a.enc && a.enc !== msg.id) return this.erro(ws, "inexistente");
    if (a.encId) return a.encId === msg.id ? undefined : this.erro(ws, "ja_em_encontro");
    // No máximo 1 entrar a cada 2 s por conexão.
    const agora = this.now();
    const lim = a.lim ?? {};
    if (agora < (lim.entrarAte ?? 0)) return this.erro(ws, "espera", { restanteMs: lim.entrarAte - agora });
    a.lim = { ...lim, entrarAte: agora + ENTRAR_INTERVALO_MS };
    this.guardar(ws, a);
    if (this.pend.has(msg.id)) return this.erro(ws, "inexistente"); // ainda sem confirmação da Conta
    // A mesma pessoa não ocupa duas vagas (nem entra na própria proposta por outra conexão).
    for (const outro of this.ctx.getWebSockets()) {
      const o = outro.deserializeAttachment();
      if (o?.encId === msg.id && o.uid === a.uid) return this.erro(ws, "ja_em_encontro");
    }
    const sessao = novaSessao();
    try {
      this.enc.join(sessao, msg.id); // síncrono: só uma pessoa pega a última vaga
    } catch (e) {
      return this.erro(ws, e.message === "cheio" ? "cheio" : "inexistente");
    }
    const at = ws.deserializeAttachment();
    at.encSessao = sessao;
    at.encId = msg.id;
    at.visto = this.now();
    this.guardar(ws, at);
    this.cache.delete(a.connId);
    await this.salvarEnc();
    this.enviarEncontro(ws, at);
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
    // Só quem saiu recebe "saiu"; se ele propôs, os demais recebem o fim "cancelado" no finalizar.
    a.encId = null;
    a.encSessao = null;
    this.guardar(ws, a);
    this.cache.delete(a.connId);
    try {
      ws.send(JSON.stringify({ t: "fim", id, motivo: "saiu" }));
    } catch {
      // socket já caiu
    }
    await this.finalizar();
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
    this.limparZumbis();
    await this.finalizar(); // expirações de proposta/encontro e quem saiu
    await this.tentarFechamentos();
    this.mudou();
    if (this.ctx.getWebSockets().length > 0 || this.enc.encontros.size > 0 || this.fechamentos.length > 0) {
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
      if (livres <= 0 || a.encId === enc.id || this.pend.has(enc.id)) continue;
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
      if (dono) {
        contas.push(
          this.tentarFechar(dono, f.motivo, f.id).then((ok) => {
            if (!ok) this.fechamentos.push({ uid: dono, motivo: f.motivo, id: f.id, tentativas: 1 });
          }),
        );
      }
    }
    await Promise.all(contas);
    await this.salvarEnc();
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

  // Falha fechada: sem resposta clara da Conta, a proposta não é aceita. Se a chamada falhar
  // depois de a Conta talvez ter gravado, pede o fechamento "falha" (sem espera), melhor esforço.
  async contaAbrir(uid, id) {
    try {
      const { corpo } = await this.chamarConta(uid, `/abrir?enc=${id}`);
      if (corpo.ok === true) return { ok: true };
      if (corpo.erro === "ja_tem_proposta") return { ok: false, codigo: "ja_tem_proposta" };
      if (corpo.erro === "espera") return { ok: false, codigo: "espera", extra: { restanteMs: corpo.restanteMs } };
    } catch (e) {
      console.error("falha ao consultar a conta", e?.message);
    }
    await this.tentarFechar(uid, "falha", id);
    return { ok: false, codigo: "indisponivel" };
  }

  // Uma tentativa de fechar na Conta; true só com resposta 200.
  async tentarFechar(uid, motivo, id) {
    try {
      const { status } = await this.chamarConta(uid, `/fechar?motivo=${encodeURIComponent(motivo)}&enc=${id}`);
      return status === 200;
    } catch (e) {
      console.error("falha ao fechar a conta", e?.message);
      return false;
    }
  }

  // No alarme: repete os fechamentos que falharam; desiste depois de 20 tentativas.
  async tentarFechamentos() {
    if (this.fechamentos.length === 0) return;
    const lista = this.fechamentos;
    this.fechamentos = [];
    for (const f of lista) {
      if (await this.tentarFechar(f.uid, f.motivo, f.id)) continue;
      if (f.tentativas + 1 < MAX_TENTATIVAS_FECHAR) this.fechamentos.push({ ...f, tentativas: f.tentativas + 1 });
    }
    await this.salvarEnc();
  }

  // Tira dos encontros toda sessão sem socket vivo. Devolve true se havia alguma.
  limparZumbis() {
    const vivas = new Set();
    for (const ws of this.ctx.getWebSockets()) {
      const a = ws.deserializeAttachment();
      if (a?.encId && a.encSessao) vivas.add(a.encSessao);
    }
    let achou = false;
    for (const enc of [...this.enc.encontros.values()]) {
      if (this.pend.has(enc.id)) continue;
      for (const sessao of [...enc.participantes.keys()]) {
        if (!vivas.has(sessao)) {
          this.enc.dropSession(sessao);
          achou = true;
        }
      }
    }
    return achou;
  }

  // --- persistência do estado dos encontros ---

  exportarEnc() {
    return {
      encontros: [...this.enc.encontros.values()].map((e) => ({ ...e, participantes: [...e.participantes] })),
      pendentes: this.enc.pendentes,
      donos: [...this.donos],
      fechamentos: this.fechamentos,
    };
  }

  importarEnc(s) {
    for (const e of s.encontros ?? []) {
      this.enc.encontros.set(e.id, { ...e, participantes: new Map(e.participantes) });
    }
    this.enc.pendentes = s.pendentes ?? [];
    this.donos = new Map(s.donos ?? []);
    this.fechamentos = s.fechamentos ?? [];
  }

  async salvarEnc() {
    await this.ctx.storage.put("enc", this.exportarEnc());
  }
}
