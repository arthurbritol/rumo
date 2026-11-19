// Teste de carga LOCAL do servidor de rolê por perto.
//
//   cd workers/nearby
//   node loadtest/simulate.js                          # 100 conexões, 5 min (padrão)
//   node loadtest/simulate.js --conexoes 100 --duracao 300 --encontros 3
//
// O que faz: sobe `wrangler dev` (local, workerd) com loadtest/worker.js, que é o roteador de produção
// aceitando uma chave de teste; gera um par RSA, assina ID tokens RS256 de mentira (um uid por conexão)
// e abre N WebSockets no MESMO quadrado, cada um mandando `procurar` e `posicao` como o app (a cada
// 30 s, deslocamento pequeno), mais alguns encontros (propor, entrar, posição a cada 10 s, cancelar).
// NUNCA fala com o servidor de produção: a produção exige token real do Firebase e o plano gratuito
// tem cota diária. Opção --url usa um servidor já no ar (só serve para um `wrangler dev` do loadtest).
//
// Opções: --conexoes (100) --duracao em segundos (300) --encontros (3) --intervalo em s entre posições
// de quem procura (30) --porta (8799) --url http://host:porta (não sobe o wrangler; precisa de
// loadtest/.dev.vars compatível) --limite-diario (100000, requisições de DO do plano gratuito) --json arquivo.

import { spawn, spawnSync } from "node:child_process";
import { generateKeyPairSync, createSign, randomBytes } from "node:crypto";
import { writeFileSync, rmSync, mkdtempSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const AQUI = path.dirname(fileURLToPath(import.meta.url));
const RAIZ = path.resolve(AQUI, "..");

function args() {
  const o = { conexoes: 100, duracao: 300, encontros: 3, intervalo: 30, porta: 8799, url: null, limiteDiario: 100000, json: null };
  const a = process.argv.slice(2);
  for (let i = 0; i < a.length; i++) {
    const k = a[i].replace(/^--/, "");
    const v = a[i + 1];
    const mapa = { conexoes: "conexoes", duracao: "duracao", encontros: "encontros", intervalo: "intervalo", porta: "porta", url: "url", "limite-diario": "limiteDiario", json: "json" };
    if (!(k in mapa) || v === undefined) throw new Error(`argumento desconhecido ou sem valor: ${a[i]}`);
    o[mapa[k]] = ["url", "json"].includes(k) ? v : Number(v);
    i++;
  }
  return o;
}

const opt = args();
const PROJETO = "projeto-de-teste";
const CELULA = "-21:-41"; // lat -20,3 / lon -40,3 caem no quadrado "-21:-41"
const BASE = { lat: -20.3, lon: -40.3 };
const t0 = () => performance.now();
const dormir = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------- token de teste ----------
const { publicKey, privateKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
const KID = "chave-de-carga";
const jwk = { ...publicKey.export({ format: "jwk" }), kid: KID, alg: "RS256", use: "sig" };
const b64 = (x) => Buffer.from(x).toString("base64url");
function token(uid) {
  const agora = Math.floor(Date.now() / 1000);
  const h = b64(JSON.stringify({ alg: "RS256", kid: KID, typ: "JWT" }));
  const p = b64(JSON.stringify({ iss: `https://securetoken.google.com/${PROJETO}`, aud: PROJETO, sub: uid, iat: agora - 10, exp: agora + 3600 }));
  const s = createSign("RSA-SHA256").update(`${h}.${p}`).sign(privateKey).toString("base64url");
  return `${h}.${p}.${s}`;
}

// ---------- métricas ----------
const m = {
  inicio: 0,
  enviadas: 0, // mensagens de aplicativo enviadas (sem as sondas)
  enviadasPorTipo: {},
  sondas: 0,
  porMinuto: [],
  recebidas: 0,
  recebidasPorTipo: {},
  bytesRecebidos: 0,
  bytesEnviados: 0,
  erros: { socket: 0, fechouAntes: 0, abrir: 0, protocolo: {} },
  aberturas: [], // ms para abrir o WebSocket (inclui conferir o token)
  primeiraLista: [], // ms da abertura até o primeiro `vizinhos`
  rtt: [], // ms de uma sonda (mensagem inválida) até o `erro` de volta
  tamanhosLista: [], // tamanho da última lista de vizinhos de cada conexão
  conexoesAbertas: 0,
  extras: 0,
};
const conta = (obj, k) => (obj[k] = (obj[k] ?? 0) + 1);
function registraEnvio(tipo, sonda = false) {
  if (sonda) {
    m.sondas++;
    return;
  }
  m.enviadas++;
  conta(m.enviadasPorTipo, tipo);
  const min = Math.floor((t0() - m.inicio) / 60000);
  m.porMinuto[min] = (m.porMinuto[min] ?? 0) + 1;
}
function pct(v, p) {
  if (v.length === 0) return null;
  const s = [...v].sort((a, b) => a - b);
  return s[Math.min(s.length - 1, Math.ceil((p / 100) * s.length) - 1)];
}

// ---------- cliente simulado ----------
let encerrando = false;
function abrir(url) {
  return new Promise((resolve) => {
    const ini = t0();
    const ws = new WebSocket(url);
    const c = { ws, vivo: false, sondaEm: null, abertoEm: 0, primeira: false, ultimaLista: null, encontro: null };
    ws.addEventListener("open", () => {
      c.vivo = true;
      c.abertoEm = t0();
      m.aberturas.push(c.abertoEm - ini);
      m.conexoesAbertas++;
      resolve(c);
    });
    ws.addEventListener("message", (e) => {
      const txt = typeof e.data === "string" ? e.data : String(e.data);
      m.recebidas++;
      m.bytesRecebidos += txt.length;
      let msg;
      try {
        msg = JSON.parse(txt);
      } catch {
        return;
      }
      conta(m.recebidasPorTipo, msg.t);
      if (msg.t === "vizinhos") {
        if (!c.primeira) {
          c.primeira = true;
          m.primeiraLista.push(t0() - c.abertoEm);
        }
        c.ultimaLista = msg.lista.length;
      } else if (msg.t === "encontro") {
        c.encontro = msg;
      } else if (msg.t === "erro") {
        if (msg.codigo === "mensagem_invalida" && c.sondaEm !== null) {
          m.rtt.push(t0() - c.sondaEm);
          c.sondaEm = null;
        } else {
          conta(m.erros.protocolo, msg.codigo);
        }
      }
    });
    ws.addEventListener("error", () => {
      if (!c.vivo) {
        m.erros.abrir++;
        resolve(null);
      } else if (!encerrando) m.erros.socket++;
    });
    ws.addEventListener("close", () => {
      if (c.vivo && !encerrando && !c.fechamentoPlanejado) m.erros.fechouAntes++;
      c.vivo = false;
    });
  });
}
function enviar(c, obj, sonda = false) {
  if (!c?.vivo) return;
  const s = JSON.stringify(obj);
  c.ws.send(s);
  m.bytesEnviados += s.length;
  registraEnvio(obj.t, sonda);
}

const PREFS = {
  ritmo: ["tranquilo", "moderado", "animado"],
  distancia: ["ate50", "50a150", "mais150"],
  parada: ["cafe", "mirante", "so_estrada", "sem_parada"],
};
const sorteia = (l) => l[Math.floor(Math.random() * l.length)];
const jitter = (g) => (Math.random() * 2 - 1) * g;

async function pessoa(i, urlBase, fim) {
  const uid = `carga-${i}-${randomBytes(3).toString("hex")}`;
  const c = await abrir(`${urlBase}/ws?cell=${CELULA}&t=${token(uid)}`);
  if (!c) return null;
  const p = { lat: BASE.lat + jitter(0.02), lon: BASE.lon + jitter(0.02) };
  let rumo = Math.random() * 360;
  const posMsg = () => ({ t: "posicao", lat: p.lat, lon: p.lon, rumo, vel: 20, distKm: null, etaMin: null });
  c.p = p;
  c.posMsg = posMsg;
  enviar(c, { t: "procurar", ritmo: sorteia(PREFS.ritmo), distancia: sorteia(PREFS.distancia), parada: sorteia(PREFS.parada), raioKm: 5 });
  enviar(c, posMsg());
  c.loop = (async () => {
    // Começa desfasado dentro do intervalo, como pessoas que ligaram em momentos diferentes.
    await dormir(Math.random() * opt.intervalo * 1000);
    while (!encerrando && t0() < fim && c.vivo) {
      // deslocamento pequeno (até ~50 m), sem sair da região
      rumo = (rumo + jitter(30) + 360) % 360;
      p.lat = Math.max(BASE.lat - 0.02, Math.min(BASE.lat + 0.02, p.lat + 0.0004 * Math.cos((rumo * Math.PI) / 180)));
      p.lon = Math.max(BASE.lon - 0.02, Math.min(BASE.lon + 0.02, p.lon + 0.0004 * Math.sin((rumo * Math.PI) / 180)));
      enviar(c, c.emEncontro ? { ...posMsg(), distKm: 0.5, etaMin: 3 } : posMsg());
      await dormir((c.emEncontro ? 10 : opt.intervalo) * 1000);
    }
  })();
  return c;
}

// Sonda de tempo de resposta (5 conexões, a cada 10 s): mensagem inválida volta como `erro` na hora (não mexe no estado).
function sondador(clientes, fim) {
  const amostra = clientes.slice(0, Math.min(5, clientes.length));
  return (async () => {
    while (!encerrando && t0() < fim) {
      for (const c of amostra) {
        if (c.vivo && c.sondaEm === null) {
          c.sondaEm = t0();
          enviar(c, { t: "sonda" }, true);
        }
      }
      await dormir(10000);
    }
  })();
}

async function encontro(e, proponente, urlBase, fim) {
  // propõe no ponto BASE (mesmo quadrado), 4 vagas; dois entram por conexão extra de encontro
  await dormir(15000 + e * 3000);
  if (!proponente?.vivo) return;
  enviar(proponente, { t: "proposta", lat: BASE.lat, lon: BASE.lon, vagas: 4 });
  const limite = t0() + 10000;
  while (!proponente.encontro && t0() < limite) await dormir(50);
  const id = proponente.encontro?.id;
  if (!id) {
    conta(m.erros.protocolo, "proposta_sem_resposta");
    return;
  }
  proponente.emEncontro = true;
  const extras = [];
  const passeio = []; // posição de cada participante extra: deslocamento pequeno, como o app (sem saltos)
  for (let k = 0; k < 2; k++) {
    const uid = `carga-enc-${e}-${k}-${randomBytes(3).toString("hex")}`;
    const c = await abrir(`${urlBase}/ws?cell=${CELULA}&enc=${id}&t=${token(uid)}`);
    if (!c) continue;
    m.extras++;
    extras.push(c);
    passeio.push({ lat: BASE.lat + jitter(0.01), lon: BASE.lon + jitter(0.01) });
    enviar(c, { t: "entrar", id });
  }
  // posição a cada 10 s (ritmo de encontro), perto do ponto
  while (!encerrando && t0() < fim - 10000) {
    extras.forEach((c, k) => {
      passeio[k].lat += jitter(0.0003); // ~30 m a cada 10 s
      passeio[k].lon += jitter(0.0003);
      enviar(c, { t: "posicao", lat: passeio[k].lat, lon: passeio[k].lon, rumo: 90, vel: 25, distKm: 0.8, etaMin: 4 });
    });
    await dormir(10000);
  }
  if (proponente.vivo) enviar(proponente, { t: "cancelar" });
  proponente.emEncontro = false;
  await dormir(500);
  for (const c of extras) {
    c.fechamentoPlanejado = true;
    try {
      c.ws.close(1000, "fim");
    } catch {
      // já fechado
    }
  }
}

// ---------- servidor local ----------
async function sobeServidor() {
  if (opt.url) return { url: opt.url, parar() {} };
  writeFileSync(path.join(AQUI, ".dev.vars"), `LOADTEST_JWK=${JSON.stringify(jwk)}\n`);
  const persist = mkdtempSync(path.join(tmpdir(), "nearby-loadtest-"));
  const wrangler = path.join(RAIZ, "node_modules", "wrangler", "bin", "wrangler.js");
  if (!existsSync(wrangler)) throw new Error("wrangler não encontrado: rode `npm install` em workers/nearby");
  const proc = spawn(process.execPath, [wrangler, "dev", "-c", path.join(AQUI, "wrangler.toml"), "--port", String(opt.porta), "--ip", "127.0.0.1", "--persist-to", persist, "--log-level", "warn"], {
    cwd: AQUI,
    env: { ...process.env, WRANGLER_SEND_METRICS: "false", CI: "1" },
    stdio: ["ignore", "pipe", "pipe"],
  });
  let log = "";
  proc.stdout.on("data", (d) => (log += d));
  proc.stderr.on("data", (d) => (log += d));
  const url = `http://127.0.0.1:${opt.porta}`;
  const ate = Date.now() + 90000;
  for (;;) {
    if (Date.now() > ate || proc.exitCode !== null) {
      proc.kill();
      throw new Error(`wrangler dev não subiu:\n${log.slice(-2000)}`);
    }
    try {
      const r = await fetch(`${url}/health`);
      if (r.ok) break;
    } catch {
      // ainda subindo
    }
    await dormir(500);
  }
  return {
    url,
    log: () => log,
    parar() {
      if (process.platform === "win32") spawnSync("taskkill", ["/pid", String(proc.pid), "/T", "/F"]);
      else proc.kill("SIGKILL");
      try {
        rmSync(persist, { recursive: true, force: true });
        rmSync(path.join(AQUI, ".dev.vars"), { force: true });
      } catch {
        // limpeza best-effort
      }
    },
  };
}

// ---------- execução ----------
async function main() {
  console.log(`Teste de carga LOCAL: ${opt.conexoes} conexões, ${opt.duracao} s, ${opt.encontros} encontros, posição a cada ${opt.intervalo} s`);
  const srv = await sobeServidor();
  const httpUrl = srv.url;
  const wsUrl = httpUrl.replace(/^http/, "ws");
  try {
    m.inicio = t0();
    const fim = m.inicio + opt.duracao * 1000;
    // abre em lotes de 20 para não estourar o limite de conexões simultâneas de abertura
    const clientes = [];
    for (let i = 0; i < opt.conexoes; i += 20) {
      const lote = await Promise.all(Array.from({ length: Math.min(20, opt.conexoes - i) }, (_, k) => pessoa(i + k, wsUrl, fim)));
      clientes.push(...lote.filter(Boolean));
    }
    console.log(`  ${clientes.length}/${opt.conexoes} conexões abertas em ${((t0() - m.inicio) / 1000).toFixed(1)} s`);
    const vivos = clientes.filter(Boolean);
    const tarefas = [sondador(vivos, fim)];
    for (let e = 0; e < Math.min(opt.encontros, vivos.length); e++) tarefas.push(encontro(e, vivos[e], wsUrl, fim));

    const timer = setInterval(() => {
      const seg = Math.round((t0() - m.inicio) / 1000);
      console.log(`  t=${seg}s enviadas=${m.enviadas} recebidas=${m.recebidas} erros=${m.erros.socket + m.erros.fechouAntes + m.erros.abrir}`);
    }, 30000);
    await dormir(Math.max(0, fim - t0()));
    encerrando = true;
    clearInterval(timer);
    for (const c of vivos) m.tamanhosLista.push(c.ultimaLista ?? 0);
    for (const c of vivos) {
      c.fechamentoPlanejado = true;
      try {
        c.ws.close(1000, "fim");
      } catch {
        // já fechado
      }
    }
    await Promise.allSettled(tarefas);
    await dormir(500);
    relatorio(vivos.length, (t0() - m.inicio) / 1000);
  } finally {
    srv.parar();
  }
}

function relatorio(abertas, segundos) {
  const minutos = segundos / 60;
  const f = (x, d = 1) => (x === null || x === undefined ? "n/d" : Number(x).toFixed(d));
  const taxaMin = m.enviadas / minutos;
  // custo: 20 mensagens de WebSocket = 1 requisição de Durable Object; cada conexão aberta = 1 requisição
  const aberturas = m.conexoesAbertas;
  const reqMsgs = m.enviadas / 20;
  const reqTotal = reqMsgs + aberturas;
  const porPessoaMin = m.enviadas / (abertas || 1) / minutos;
  // projeção: 100 pessoas, 3 h por dia procurando (spec 8.9), com a taxa por pessoa medida (inclui encontros)
  const diaMsgs = porPessoaMin * 100 * 180;
  const diaReq = diaMsgs / 20 + 100; // + uma conexão por pessoa
  const limite = opt.limiteDiario;
  const r = {
    parametros: { conexoes: opt.conexoes, duracaoS: opt.duracao, encontros: opt.encontros, intervaloS: opt.intervalo },
    conexoesAbertas: abertas,
    conexoesExtrasDeEncontro: m.extras,
    duracaoMedidaS: Number(f(segundos)),
    mensagensEnviadas: m.enviadas,
    enviadasPorTipo: m.enviadasPorTipo,
    sondasDeLatencia: m.sondas,
    mensagensPorMinuto: m.porMinuto,
    mediaMensagensPorMinuto: Number(f(taxaMin)),
    mensagensRecebidas: m.recebidas,
    recebidasPorTipo: m.recebidasPorTipo,
    kbEnviados: Number(f(m.bytesEnviados / 1024)),
    kbRecebidos: Number(f(m.bytesRecebidos / 1024)),
    erros: m.erros,
    aberturaMs: { p50: pct(m.aberturas, 50), p95: pct(m.aberturas, 95), max: pct(m.aberturas, 100) },
    primeiraListaMs: { p50: pct(m.primeiraLista, 50), p95: pct(m.primeiraLista, 95) },
    rttMs: { amostras: m.rtt.length, p50: pct(m.rtt, 50), p95: pct(m.rtt, 95), max: pct(m.rtt, 100) },
    vizinhosVistosMedia: Number(f(m.tamanhosLista.reduce((a, b) => a + b, 0) / (m.tamanhosLista.length || 1))),
    custo: {
      requisicoesDeMensagens: Number(f(reqMsgs, 1)),
      requisicoesDeConexao: aberturas,
      requisicoesTotaisNoTeste: Number(f(reqTotal, 1)),
      projecaoDia_100pessoas_3h: { mensagens: Math.round(diaMsgs), requisicoes: Math.round(diaReq), limiteDiario: limite, percentualDoLimite: Number(f((diaReq / limite) * 100)) },
    },
  };
  console.log("\n===== RESULTADO =====");
  console.log(`Conexões abertas: ${abertas} (+${m.extras} extras de encontro) em ${f(segundos)} s`);
  console.log(`Mensagens enviadas (como o app): ${m.enviadas}  (${f(taxaMin)} por minuto; ${f(porPessoaMin, 2)} por pessoa por minuto)`);
  console.log(`  por tipo: ${JSON.stringify(m.enviadasPorTipo)}   sondas de latência (à parte): ${m.sondas}`);
  console.log(`  por minuto: ${m.porMinuto.map((x) => x ?? 0).join(", ")}`);
  console.log(`Mensagens recebidas: ${m.recebidas} (${JSON.stringify(m.recebidasPorTipo)}); ${f(m.bytesRecebidos / 1024)} KB recebidos, ${f(m.bytesEnviados / 1024)} KB enviados`);
  console.log(`Erros: aberturas=${m.erros.abrir} socket=${m.erros.socket} fechou-antes-da-hora=${m.erros.fechouAntes} protocolo=${JSON.stringify(m.erros.protocolo)}`);
  console.log(`Abrir conexão (inclui conferir o token) p50=${f(r.aberturaMs.p50)} ms p95=${f(r.aberturaMs.p95)} ms`);
  console.log(`Primeira lista de vizinhos após abrir p50=${f(r.primeiraListaMs.p50)} ms p95=${f(r.primeiraListaMs.p95)} ms`);
  console.log(`Tempo de resposta (sonda, ida e volta) p50=${f(r.rttMs.p50)} ms p95=${f(r.rttMs.p95)} ms (${m.rtt.length} amostras)`);
  console.log(`Vizinhos vistos por conexão (média, última lista): ${r.vizinhosVistosMedia}`);
  console.log("\nCusto estimado (20 mensagens de WebSocket = 1 requisição de Durable Object):");
  console.log(`  no teste: ${f(reqMsgs)} requisições por mensagens + ${aberturas} por conexões = ${f(reqTotal)}`);
  console.log(`  projeção para 100 pessoas, 3 h por dia, com a taxa medida: ${Math.round(diaMsgs)} mensagens = ${Math.round(diaReq)} requisições/dia = ${f((diaReq / limite) * 100)}% do limite de ${limite}`);
  console.log("  Não medido aqui: alarmes do servidor e chamadas Célula->Conta (contam como requisições na Cloudflare) e tempo de CPU/duração.");
  if (opt.json) writeFileSync(opt.json, JSON.stringify(r, null, 2));
}

main().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
