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
import { MAX_BLOQUEADOS } from "./conta.js";

const BLOQ_RENOVAR_MS = 300_000; // a lista de bloqueios de cada conexão é relida da Conta a cada 5 min (no alarme)
const PRAZO_CONTA_MS = 3000; // prazo de cada chamada à Conta nas leituras de bloqueio e na segurança
const SEGURANCA_INTERVALO_MS = 2000; // no máximo 1 bloquear/denunciar a cada 2 s por conexão
const DENUNCIA_DEDUP_MS = 24 * 3_600_000;
const PARES_MS = 24 * 3_600_000; // quem esteve no mesmo encontro pode bloquear/denunciar por 24 h
const MAX_PARES_PESSOAS = 500; // 500 pessoas x 30 pares x ~100 B < 1,5 MB; ao encher, descarta os mais antigos
const MAX_PARES_POR_PESSOA = 30;

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
    this.zumbis = new Set(); // encontros desfeitos por limparZumbis: a Conta fecha com "falha" (sem espera)
    this.sujo = false; // estrutura mudou fora de um fim (ex.: saiu um não-proponente): precisa gravar
    this.fechamentos = []; // avisos de fim à Conta que falharam: [{ uid, motivo, id, tentativas }]
    // connId -> { uid, bloqueou, bloqueadoPor, em, carregado }: só memória, nunca enviado.
    // Sem lista carregada (ainda ou a Conta falhou) = falha fechada: ninguém é mostrado.
    this.bloq = new Map();
    this.idxBloqueou = new Map(); // uid -> Set<uid> que ele bloqueou (união das conexões desta Celula)
    this.ritmoSeg = new Map(); // connId -> ms até poder bloquear/denunciar de novo
    this.denLocal = new Map(); // "uid>alvo" -> ms da denúncia já registrada (dedupe de 24 h sem chamar a Conta)
    this.reparos = []; // lado "bloqueadoPor" que falhou ao gravar: [{ de, para, tentativas }]
    this.pares = {}; // uid -> [{ sessao, uid, ate }]: outros participantes de encontros que terminaram (24 h)
    this.paresSujos = new Set(); // uids com par ainda não gravado (chave `par:<uid>`)
    this.falsos = new Map(); // "ref:uid>alvo" -> ms: respostas "ok" sem gravar (alvo que me bloqueou e foi meu par)
    ctx.blockConcurrencyWhile(async () => {
      await Promise.all(
        ctx.getWebSockets().map((ws) => {
          const a = ws.deserializeAttachment();
          return a ? this.carregarBloqueios(a.connId, a.uid, PRAZO_CONTA_MS) : null;
        }),
      );
      try {
        for (const [k, v] of await ctx.storage.list({ prefix: "par:" })) this.pares[k.slice(4)] = v;
      } catch (e) {
        console.error("falha ao ler os pares", e?.message);
      }
      this.reparos = (await ctx.storage.get("reparos")) ?? [];
      const salvo = await ctx.storage.get("enc");
      if (salvo) this.importarEnc(salvo);
      // Depois de reinício/deploy o estado voltou do storage, mas os sockets podem ter ido embora:
      // quem não tem socket vivo sai agora; o alarme logo em seguida entrega os fins e fecha as Contas.
      const mudou = this.limparZumbis(true);
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
    await this.carregarBloqueios(servidor.deserializeAttachment().connId, uid);
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
    if (!this.bloq.get(a.connId)?.carregado) await this.carregarBloqueios(a.connId, a.uid); // tenta de novo se a Conta falhou

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
    } else if (msg.t === "bloquear") {
      await this.bloquear(ws, a, msg);
    } else if (msg.t === "denunciar") {
      await this.denunciar(ws, a, msg);
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
      await this.fecharOuEnfileirar(a.uid, "falha", id);
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
    // Bloqueio (nos dois sentidos) com quem propôs ou com alguém que já está no encontro:
    // o mesmo erro de um encontro que não existe, sem revelar o motivo.
    const dono = this.donos.get(msg.id);
    if (dono !== undefined && this.oculto(a.connId, dono)) return this.erro(ws, "inexistente");
    for (const outro of this.ctx.getWebSockets()) {
      const o = outro.deserializeAttachment();
      if (o?.encId === msg.id && this.oculto(a.connId, o.uid)) return this.erro(ws, "inexistente");
    }
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
    this.parear(a.uid, sessao, msg.id);
    await this.salvarPares();
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

  async sairDoEncontro(ws, a, motivoFim = "saiu") {
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
      ws.send(JSON.stringify({ t: "fim", id, motivo: motivoFim }));
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
    const velhas = [];
    for (const ws of this.ctx.getWebSockets()) {
      const a = ws.deserializeAttachment();
      if (this.expirou(a)) this.fechar(ws, "token expirado");
      else if (a?.encId && this.now() - (a.visto ?? this.now()) > SEM_SINAL_MS) this.fechar(ws, "sem sinal");
      else if (a && this.now() - (this.bloq.get(a.connId)?.em ?? 0) > BLOQ_RENOVAR_MS) velhas.push(a);
    }
    // Pega bloqueios feitos por outra Celula: só conexões sem leitura recente, em paralelo e com prazo.
    await Promise.allSettled(velhas.map((a) => this.carregarBloqueios(a.connId, a.uid, PRAZO_CONTA_MS)));
    this.reindexarBloqueios();
    this.podarPares();
    await this.salvarPares(); // nunca lança: falha mantém o par sujo para o próximo alarme
    try {
      await this.tentarReparos();
    } catch (e) {
      console.error("falha nos reparos de bloqueio", e?.message);
    }
    this.limparZumbis();
    await this.finalizar(); // expirações de proposta/encontro e quem saiu
    await this.tentarFechamentos();
    this.mudou();
    if (this.ctx.getWebSockets().length > 0 || this.enc.encontros.size > 0 || this.fechamentos.length > 0 || this.paresSujos.size > 0 || this.reparos.length > 0) {
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
    if (a.encSessao) {
      this.enc.dropSession(a.encSessao);
      this.sujo = true;
    }
    this.ultimoEnvio.delete(a.connId);
    this.pendentes.delete(a.connId);
    this.cache.delete(a.connId);
    this.bloq.delete(a.connId);
    this.ritmoSeg.delete(a.connId);
    this.reindexarBloqueios();
    this.mudou();
  }

  fechar(ws, motivo) {
    if (!ws) return;
    const a = ws.deserializeAttachment();
    if (a) {
      this.presence.leave(a.connId);
      if (a.encSessao) {
        this.enc.dropSession(a.encSessao);
        this.sujo = true;
      }
      this.ultimoEnvio.delete(a.connId);
      this.pendentes.delete(a.connId);
      this.cache.delete(a.connId);
      this.bloq.delete(a.connId);
    this.ritmoSeg.delete(a.connId);
    this.reindexarBloqueios();
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
  // `imediato`: ignora o intervalo de 2 s (bloqueio tem de valer na hora).
  mudou(imediato = false) {
    for (const ws of this.ctx.getWebSockets()) {
      const a = ws.deserializeAttachment();
      if (a?.presence || a?.encId) {
        this.pendentes.add(a.connId);
        if (imediato) this.ultimoEnvio.delete(a.connId);
      }
    }
    this.despachar();
  }

  despachar() {
    const agora = this.now();
    // encontro -> uids de quem está nele, montado uma vez por rodada de envio
    const uidsPorEnc = new Map();
    if (this.pendentes.size > 0) {
      for (const ws of this.ctx.getWebSockets()) {
        const o = ws.deserializeAttachment();
        if (o?.encId) uidsPorEnc.set(o.encId, [...(uidsPorEnc.get(o.encId) ?? []), o.uid]);
      }
    }
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
            .neighborsFor(connId, (uid) => this.oculto(connId, uid))
            .then((lista) => {
              const w = this.socketDe(connId);
              if (w) this.enviar(w, lista);
            })
            .catch((e) => console.error("falha ao montar a lista de vizinhos", e));
          this.enviarSeMudou(ws, connId, "propostas", { t: "propostas", lista: this.propostasPara(connId, a, uidsPorEnc) });
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
  propostasPara(connId, a, uidsPorEnc) {
    const eu = this.presence.pessoas.get(connId);
    if (!eu?.pos) return [];
    const raioM = eu.rolê.raioKm * 1000;
    const lista = [];
    for (const enc of this.enc.encontros.values()) {
      const livres = enc.vagas - enc.participantes.size;
      if (livres <= 0 || a.encId === enc.id || this.pend.has(enc.id)) continue;
      const dono = this.donos.get(enc.id);
      if (dono !== undefined && this.oculto(connId, dono)) continue; // bloqueio nos dois sentidos
      if ((uidsPorEnc.get(enc.id) ?? []).some((u) => this.oculto(connId, u))) continue; // e com qualquer participante
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
    if (!a.encId || this.pend.has(a.encId)) return; // pendente: ainda sem resposta da Conta
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
    if (fins.length === 0) {
      if (this.sujo) {
        this.sujo = false;
        await this.salvarEnc();
      }
      return;
    }
    this.sujo = false;
    const contas = [];
    for (const f of fins) {
      for (const ws of this.ctx.getWebSockets()) {
        const a = ws.deserializeAttachment();
        if (a?.encId !== f.id) continue;
        try {
          ws.send(JSON.stringify({ t: "fim", id: f.id, motivo: f.motivo }));
        } catch {
          // socket já caiu
        }
        a.encId = null;
        this.guardar(ws, a);
        this.cache.delete(a.connId);
      }
      const dono = this.donos.get(f.id);
      this.donos.delete(f.id);
      const motivoConta = this.zumbis.delete(f.id) ? "falha" : f.motivo; // reinício não pune ninguém
      if (dono) {
        contas.push(
          this.tentarFechar(dono, motivoConta, f.id).then((ok) => {
            if (!ok) this.fechamentos.push({ uid: dono, motivo: motivoConta, id: f.id, tentativas: 1 });
          }),
        );
      }
    }
    await Promise.all(contas);
    await this.salvarEnc();
    await this.salvarPares();
    this.mudou();
  }

  // --- bloqueios e denúncias ---

  // Estado por conexão (só memória, nunca enviado): { uid, bloqueou: Set, bloqueadoPor: Set, em, carregado }.
  // Guardamos os dois conjuntos separados: quem foi bloqueado não pode descobrir quem o bloqueou.

  novaEntradaBloqueio(connId, uid) {
    let b = this.bloq.get(connId);
    if (!b) {
      b = { uid, bloqueou: new Set(), bloqueadoPor: new Set(), em: 0, carregado: false };
      this.bloq.set(connId, b);
    }
    return b;
  }

  // Índice uid -> quem esse uid bloqueou (união das conexões desta Celula): a simetria vale mesmo
  // quando a Conta do outro lado ainda não foi atualizada.
  reindexarBloqueios() {
    const idx = new Map();
    for (const b of this.bloq.values()) {
      if (!b.carregado) continue;
      const s = idx.get(b.uid) ?? new Set();
      for (const u of b.bloqueou) s.add(u);
      idx.set(b.uid, s);
    }
    this.idxBloqueou = idx;
  }

  // Relê da Conta. A entrada nasce ANTES da consulta e o que foi aplicado na memória durante ela
  // é preservado. Em falha mantém o que já havia (sem nada: falha fechada, ninguém aparece).
  async carregarBloqueios(connId, uid, prazoMs = PRAZO_CONTA_MS) {
    const entrada = this.novaEntradaBloqueio(connId, uid);
    try {
      const { status, corpo } = await this.chamarConta(uid, "/bloqueios", "GET", prazoMs);
      if (status !== 200 || corpo.ok !== true) throw new Error(`status ${status}`);
      if (this.bloq.get(connId) !== entrada) return; // a conexão fechou durante a consulta
      for (const u of corpo.bloqueados ?? []) entrada.bloqueou.add(u);
      for (const u of corpo.bloqueadoPor ?? []) entrada.bloqueadoPor.add(u);
      entrada.carregado = true;
      entrada.em = this.now();
      this.reindexarBloqueios();
      this.repararLadoFaltante(entrada);
    } catch (e) {
      console.error("falha ao consultar bloqueios", e?.message);
    }
  }

  // Se A bloqueou B e B está aqui sem o registro de A em bloqueadoPor, regrava o lado de B.
  repararLadoFaltante(entrada) {
    for (const o of this.bloq.values()) {
      if (o === entrada || !o.carregado || !entrada.bloqueou.has(o.uid) || o.bloqueadoPor.has(entrada.uid)) continue;
      o.bloqueadoPor.add(entrada.uid);
      this.enfileirarReparo(entrada.uid, o.uid);
    }
  }

  enfileirarReparo(de, para) {
    if (!this.reparos.some((r) => r.de === de && r.para === para)) this.reparos.push({ de, para, tentativas: 0 });
    this.salvarReparos().catch(() => {});
  }

  async salvarReparos() {
    await this.ctx.storage.put("reparos", this.reparos);
  }

  // No alarme: regrava o lado do bloqueado que falhou antes.
  async tentarReparos() {
    if (this.reparos.length === 0) return;
    const lista = this.reparos;
    this.reparos = [];
    for (const r of lista) {
      let feito = false;
      try {
        const x = await this.chamarConta(
          r.para,
          `/bloquear?uid=${encodeURIComponent(r.de)}&sentido=bloqueadoPor`,
          "POST",
          PRAZO_CONTA_MS,
        );
        feito = x.status === 200 || (x.status === 409 && x.corpo.erro === "limite");
      } catch {
        // tenta de novo no próximo alarme
      }
      if (!feito && r.tentativas + 1 < 20) this.reparos.push({ ...r, tentativas: r.tentativas + 1 });
    }
    await this.salvarReparos();
  }

  // true se `uid` não pode ser visto por esta conexão: bloqueio nos dois sentidos (inclusive o
  // que a outra pessoa fez por outra conexão desta Celula) ou lista ainda não carregada.
  oculto(connId, uid) {
    const b = this.bloq.get(connId);
    return !b || !b.carregado || b.bloqueou.has(uid) || b.bloqueadoPor.has(uid) || !!this.idxBloqueou.get(uid)?.has(b.uid);
  }

  // sessão opaca -> { uid, pos real } ou null. Vale para quem está agora na Celula (presença ou
  // encontro) e, por 24 h, para quem esteve no mesmo encontro que `meuUid`. O uid nunca sai daqui.
  resolverSessao(sessao, meuUid) {
    let achado = null;
    for (const o of this.presence.pessoas.values()) {
      if (o.sessao === sessao) achado = { uid: o.uid, pos: o.pos };
    }
    for (const ws of this.ctx.getWebSockets()) {
      const o = ws.deserializeAttachment();
      if (!o?.encSessao || o.encSessao !== sessao || !o.encId) continue;
      const p = this.enc.encontros.get(o.encId)?.participantes.get(sessao)?.pos ?? null;
      achado = { uid: o.uid, pos: achado?.pos ?? (p ? { lat: p.lat, lon: p.lon } : null) };
    }
    const par = (this.pares[meuUid] ?? []).find((x) => x.sessao === sessao && x.ate > this.now());
    if (achado) achado.par = !!par;
    else {
      if (par) {
        let pos = null;
        for (const o of this.presence.pessoas.values()) if (o.uid === par.uid && o.pos) pos = o.pos;
        achado = { uid: par.uid, pos, par: true };
      }
    }
    return achado;
  }

  // Sessão desconhecida e "quem me bloqueou" respondem igual (mesmo erro, mesma chamada à Conta).
  async inexistenteIgual(ws, a) {
    await this.chamarConta(a.uid, "/bloqueios", "GET", PRAZO_CONTA_MS).catch(() => {});
    this.erro(ws, "inexistente");
  }

  // Alvo (par de 24 h) que me bloqueou: "ok" idêntico ao de um alvo válido, sem gravar nada, com o
  // mesmo padrão de chamadas à Conta (leituras) e o mesmo atalho local nas repetições.
  async falsoOk(ws, a, ref, uidAlvo, chamadas) {
    const chave = `${ref}:${a.uid}>${uidAlvo}`;
    if (this.now() - (this.falsos.get(chave) ?? -Infinity) >= DENUNCIA_DEDUP_MS) {
      await Promise.allSettled(
        Array.from({ length: chamadas }, () => this.chamarConta(a.uid, "/bloqueios", "GET", PRAZO_CONTA_MS)),
      );
      this.falsos.set(chave, this.now());
    }
    this.ok(ws, ref);
  }

  // Limite de ritmo por conexão para bloquear/denunciar (1 a cada 2 s). true se pode seguir.
  ritmoOk(ws, a) {
    const agora = this.now();
    const ate = this.ritmoSeg.get(a.connId) ?? 0;
    if (agora < ate) {
      this.erro(ws, "espera", { restanteMs: ate - agora });
      return false;
    }
    this.ritmoSeg.set(a.connId, agora + SEGURANCA_INTERVALO_MS);
    return true;
  }

  // Quem me bloqueou (e eu não bloqueei de volta) não pode ser alvo: indistinguível de desconhecido.
  alvoInvisivel(a, uidAlvo) {
    const b = this.bloq.get(a.connId);
    if (!b) return false;
    if (b.bloqueou.has(uidAlvo)) return false;
    return b.bloqueadoPor.has(uidAlvo) || !!this.idxBloqueou.get(uidAlvo)?.has(a.uid);
  }

  async bloquear(ws, a, msg) {
    if (!this.ritmoOk(ws, a)) return;
    const alvo = this.resolverSessao(msg.sessao, a.uid);
    if (!alvo) return this.inexistenteIgual(ws, a);
    if (this.alvoInvisivel(a, alvo.uid)) {
      return alvo.par ? this.falsoOk(ws, a, "bloqueio", alvo.uid, 2) : this.inexistenteIgual(ws, a);
    }
    if (alvo.uid === a.uid) return this.erro(ws, "sessao_propria");
    const minha = this.bloq.get(a.connId);
    if (minha?.bloqueou.has(alvo.uid)) return this.ok(ws, "bloqueio"); // já bloqueado: nada a gravar nem a difundir
    const [eu, ele] = [encodeURIComponent(a.uid), encodeURIComponent(alvo.uid)];
    try {
      const r = await this.chamarConta(a.uid, `/bloquear?uid=${ele}&sentido=bloqueou`, "POST", PRAZO_CONTA_MS);
      if (r.status === 409 && r.corpo.erro === "limite") return this.erro(ws, "limite", { max: MAX_BLOQUEADOS });
      if (r.status !== 200 || r.corpo.ok !== true) throw new Error(`status ${r.status}`);
    } catch (e) {
      console.error("falha ao gravar bloqueio", e?.message);
      return this.erro(ws, "indisponivel");
    }
    // Vale já aqui, nas conexões desta Celula, nos dois sentidos.
    this.aplicarBloqueio(a.uid, alvo.uid);
    await this.expulsarDoMesmoEncontro(a.uid, alvo.uid);
    this.mudou(true);
    let simetrico = false;
    try {
      const r = await this.chamarConta(alvo.uid, `/bloquear?uid=${eu}&sentido=bloqueadoPor`, "POST", PRAZO_CONTA_MS);
      simetrico = r.status === 200 && r.corpo.ok === true;
    } catch (e) {
      console.error("falha ao gravar bloqueio do outro lado", e?.message);
    }
    if (!simetrico) this.enfileirarReparo(a.uid, alvo.uid); // o alarme regrava; o bloqueio já vale
    this.ok(ws, "bloqueio");
  }

  aplicarBloqueio(uidA, uidB) {
    for (const b of this.bloq.values()) {
      if (b.uid === uidA) b.bloqueou.add(uidB);
      else if (b.uid === uidB) b.bloqueadoPor.add(uidA);
    }
    this.reindexarBloqueios();
  }

  // Quem estava no mesmo encontro que a pessoa bloqueada sai dele: o bloqueado, ou quem bloqueou
  // se o bloqueado é o dono do encontro. Quem é removido recebe o fim neutro "cancelado".
  async expulsarDoMesmoEncontro(uidA, uidB) {
    const sockets = this.ctx.getWebSockets().map((ws) => ({ ws, a: ws.deserializeAttachment() }));
    for (const x of sockets.filter((s) => s.a?.uid === uidA && s.a.encId)) {
      for (const y of sockets.filter((s) => s.a?.uid === uidB && s.a.encId === x.a.encId)) {
        const donoEhB = this.donos.get(x.a.encId) === uidB;
        const sai = donoEhB ? x : y;
        await this.sairDoEncontro(sai.ws, sai.ws.deserializeAttachment(), donoEhB ? "saiu" : "cancelado");
      }
    }
  }

  async denunciar(ws, a, msg) {
    if (!this.ritmoOk(ws, a)) return;
    const alvo = this.resolverSessao(msg.sessao, a.uid);
    // Quem bloqueou pode denunciar quem bloqueou; quem foi bloqueado não "enxerga" o bloqueador:
    // se nunca foram pares, é "inexistente"; se foram, recebe o mesmo "ok" de um alvo válido (sem gravar).
    if (!alvo) return this.inexistenteIgual(ws, a);
    if (this.alvoInvisivel(a, alvo.uid)) {
      return alvo.par ? this.falsoOk(ws, a, "denuncia", alvo.uid, 3) : this.inexistenteIgual(ws, a);
    }
    if (alvo.uid === a.uid) return this.erro(ws, "sessao_propria");
    const chave = `${a.uid}>${alvo.uid}`;
    if (this.now() - (this.denLocal.get(chave) ?? -Infinity) < DENUNCIA_DEDUP_MS) return this.ok(ws, "denuncia"); // já coberta
    const ele = encodeURIComponent(alvo.uid);
    let reservou = false;
    try {
      // 1) reserva (provisória) a cota do denunciante: 5 por hora; repetir o mesmo alvo em 24 h não gasta cota
      const c = await this.chamarConta(a.uid, `/cota-denuncia?alvo=${ele}`, "POST", PRAZO_CONTA_MS);
      if (c.status === 409 && c.corpo.erro === "espera") return this.erro(ws, "espera", { restanteMs: c.corpo.restanteMs });
      if (c.status !== 200 || c.corpo.ok !== true) throw new Error(`status ${c.status}`);
      if (c.corpo.duplicada) {
        if (!c.corpo.pendente) this.denLocal.set(chave, this.now()); // reserva ainda pendente não vale
        return this.ok(ws, "denuncia");
      }
      reservou = true;
      // 2) grava na Conta do denunciado, com a posição já desviada (200 a 400 m)
      let q = "";
      if (alvo.pos) {
        const dia = new Date(this.now()).toISOString().slice(0, 10);
        const aprox = applyOffset(alvo.pos.lat, alvo.pos.lon, await fuzzOffset(alvo.uid, dia, this.env.NEARBY_SECRET));
        q = `&lat=${aprox.lat}&lon=${aprox.lon}`;
      }
      const d = await this.chamarConta(
        alvo.uid,
        `/denunciar?por=${encodeURIComponent(a.uid)}&motivo=${encodeURIComponent(msg.motivo)}${q}`,
        "POST",
        PRAZO_CONTA_MS,
      );
      if (d.status !== 200 || d.corpo.ok !== true) throw new Error(`status ${d.status}`);
      // 3) só agora a reserva vira definitiva (24 h); sem confirmação ela expira sozinha em 30 s
      const conf = await this.chamarConta(a.uid, `/cota-denuncia?alvo=${ele}&confirmar=1`, "POST", PRAZO_CONTA_MS).catch(() => null);
      if (conf?.status === 200) this.denLocal.set(chave, this.now());
    } catch (e) {
      console.error("falha ao registrar denúncia", e?.message);
      if (reservou) await this.chamarConta(a.uid, `/cota-denuncia?alvo=${ele}&desfazer=1`, "POST", PRAZO_CONTA_MS).catch(() => {});
      return this.erro(ws, "indisponivel");
    }
    this.ok(ws, "denuncia");
  }

  ok(ws, ref) {
    try {
      ws.send(JSON.stringify({ t: "ok", ref }));
    } catch {
      // socket já caiu
    }
  }

  // Pares por 24 h (ruling R5): quem esteve ao mesmo tempo num encontro pode bloquear/denunciar
  // depois (inclusive quem saiu, caiu ou foi removido). O par nasce quando alguém ENTRA: ele e cada
  // pessoa presente naquele momento ganham par um com o outro. pares[uid] = [{ sessao, uid, ate }]
  // dos outros; nunca enviado a ninguém. Fica em storage, uma chave `par:<uid>` por pessoa.
  parear(uidNovo, sessaoNova, idEncontro) {
    for (const ws of this.ctx.getWebSockets()) {
      const o = ws.deserializeAttachment();
      if (!o || o.encId !== idEncontro || !o.encSessao || o.uid === uidNovo) continue;
      this.registrarPar(uidNovo, o.encSessao, o.uid);
      this.registrarPar(o.uid, sessaoNova, uidNovo);
    }
  }

  registrarPar(uid, sessao, uidPar) {
    const agora = this.now();
    const lista = (this.pares[uid] ?? []).filter((x) => x.ate > agora && x.sessao !== sessao);
    if (!this.pares[uid] && Object.keys(this.pares).length >= MAX_PARES_PESSOAS) this.descartarParMaisAntigo();
    lista.push({ sessao, uid: uidPar, ate: agora + PARES_MS });
    this.pares[uid] = lista.slice(-MAX_PARES_POR_PESSOA);
    this.paresSujos.add(uid);
  }

  // Cheio: descarta a pessoa cujo par mais recente é o mais antigo (nunca recusa um novo).
  descartarParMaisAntigo() {
    let pior = null;
    let menor = Infinity;
    for (const [uid, lista] of Object.entries(this.pares)) {
      const recente = Math.max(...lista.map((x) => x.ate));
      if (recente < menor) {
        menor = recente;
        pior = uid;
      }
    }
    if (pior !== null) {
      delete this.pares[pior];
      this.paresSujos.add(pior);
    }
  }

  podarPares() {
    const agora = this.now();
    for (const [uid, lista] of Object.entries(this.pares)) {
      const viva = lista.filter((x) => x.ate > agora);
      if (viva.length === lista.length) continue;
      this.paresSujos.add(uid);
      if (viva.length === 0) delete this.pares[uid];
      else this.pares[uid] = viva;
    }
    for (const [k, t] of this.denLocal) if (agora - t >= DENUNCIA_DEDUP_MS) this.denLocal.delete(k);
    for (const [k, t] of this.falsos) if (agora - t >= DENUNCIA_DEDUP_MS) this.falsos.delete(k);
  }

  async gravarPar(uid) {
    const lista = this.pares[uid];
    if (lista) await this.ctx.storage.put(`par:${uid}`, lista);
    else await this.ctx.storage.delete(`par:${uid}`);
  }

  // Nunca lança: falha de uma chave mantém a pessoa "suja" e o alarme tenta de novo.
  async salvarPares() {
    for (const uid of [...this.paresSujos]) {
      try {
        await this.gravarPar(uid);
        this.paresSujos.delete(uid);
      } catch (e) {
        console.error("falha ao gravar os pares", e?.message);
      }
    }
  }

  // --- Conta (por uid) ---

  // `prazoMs` (opcional): desiste da chamada depois desse tempo (erro "prazo").
  async chamarConta(uid, caminho, method = "POST", prazoMs = null) {
    const ns = this.env.CONTA;
    if (!ns) throw new Error("binding CONTA ausente");
    const chamada = (async () => {
      const resp = await ns.get(ns.idFromName(uid)).fetch(`https://conta${caminho}`, { method });
      return { status: resp.status, corpo: await resp.json() };
    })();
    if (prazoMs === null) return chamada;
    let timer;
    const limite = new Promise((_, rej) => {
      timer = setTimeout(() => rej(new Error("prazo")), prazoMs);
    });
    chamada.catch(() => {});
    try {
      return await Promise.race([chamada, limite]);
    } finally {
      clearTimeout(timer);
    }
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
    await this.fecharOuEnfileirar(uid, "falha", id);
    return { ok: false, codigo: "indisponivel" };
  }

  // Tenta fechar; se falhar, entra na fila que o alarme repete.
  async fecharOuEnfileirar(uid, motivo, id) {
    if (await this.tentarFechar(uid, motivo, id)) return;
    this.fechamentos.push({ uid, motivo, id, tentativas: 1 });
    await this.salvarEnc();
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
  // `preservar` (reconstrução depois de reinício): não reinicia o prazo de expiração da proposta.
  limparZumbis(preservar = false) {
    const vivas = new Set();
    for (const ws of this.ctx.getWebSockets()) {
      const a = ws.deserializeAttachment();
      if (a?.encId && a.encSessao) vivas.add(a.encSessao);
    }
    let achou = false;
    for (const enc of [...this.enc.encontros.values()]) {
      if (this.pend.has(enc.id)) continue;
      const criadoEm = enc.criadoEm;
      for (const sessao of [...enc.participantes.keys()]) {
        if (!vivas.has(sessao)) {
          this.enc.dropSession(sessao);
          achou = true;
          this.sujo = true;
        }
      }
      if (this.enc.encontros.has(enc.id)) {
        if (preservar) enc.criadoEm = criadoEm;
      } else if (achou) {
        this.zumbis.add(enc.id);
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
