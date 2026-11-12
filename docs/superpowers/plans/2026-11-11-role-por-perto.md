# Rolê por perto — Plano de implementação

> **Para quem executa:** REQUIRED SUB-SKILL: use superpowers:subagent-driven-development (recomendado) ou superpowers:executing-plans para executar este plano tarefa por tarefa. Os passos usam caixas de seleção (`- [x]`).

> **Status (2026-11-12):** as Tarefas 1 a 9 estão concluídas (passos marcados abaixo) e a 10 em diante estão pendentes; veja [2026-11-11-role-por-perto-progresso.md](2026-11-11-role-por-perto-progresso.md).

**Objetivo:** motociclistas logados ligam "procurando rolê", se veem como motos anônimas num raio de 5 a 20 km, propõem e entram em encontros num ponto do mapa, e terminam como amigos, com bloqueio e denúncia.

**Arquitetura:** Worker da Cloudflare (`workers/nearby`) com Durable Objects: um `Celula` por quadrado de 1° x 1° (presença, propostas e encontros) e uma `Conta` por `uid` (bloqueios, denúncias, espera, suspensão, marcas de amizade). O app fala com ele por WebSocket autenticado com o ID token do Firebase. Firestore só entra no fim (convite de amizade que já existe).

**Tecnologias:** JavaScript (ES modules, `node --test`, Web Crypto) no Worker; Kotlin/Compose/MapLibre no app; OkHttp (novo) para WebSocket; kotlinx.serialization no módulo `sync-core`.

**Spec:** `docs/superpowers/specs/2026-11-11-role-por-perto-design.md` (aprovado pelo dono). Quando este plano e a spec discordarem, vale a spec.

## Restrições globais

Valem para toda tarefa.

- Textos de tela em pt-BR. Nada que exista hoje (amigos, rolês, localização compartilhada, postos, trânsito) pode mudar de comportamento; os testes antigos continuam passando.
- **O servidor nunca envia `uid`, nome, modelo, e-mail ou posição exata a quem não deve.** Os clientes só conhecem o id aleatório da sessão.
- Valores fixos: quadrado 1° x 1°; raio 5 km (padrão), 5 a 20 km; atualização de posição a cada 30 s procurando e 10 s em encontro (piso de 10 s; antes se andou mais de 100 m); pessoa sem sinal por 2 min some; desvio de posição de 200 a 400 m, estável por `uid` e por dia; vagas de 2 a 8 (incluindo quem propõe); proposta sem ninguém entrando expira em 30 min; encontro sem ninguém chegando termina em 60 min; chegada a menos de 100 m do ponto; posição real só a menos de 1 km do ponto; 1 proposta aberta por pessoa e espera de 5 min depois de recusa/expiração/cancelamento; rejeitar posição com velocidade acima de 250 km/h; marca de amizade dura 24 h; 3 denúncias de pessoas diferentes em 7 dias suspendem.
- Listas: ritmo = `tranquilo`, `moderado`, `animado`; distância = `ate50`, `50a150`, `mais150`; parada = `cafe`, `mirante`, `so_estrada`, `sem_parada`. "Combina" = mesmo ritmo **e** distância igual ou vizinha (`ate50`~`50a150`~`mais150`) **e** parada igual (ou `sem_parada` com `so_estrada`).
- **Processo do repositório**: cada mudança no app termina com a release publicada e conferida (push na `main` dispara; o APK vai para a Área de Trabalho). **Nunca altere `.github/workflows/*` no mesmo commit que mexe no app**: o GitHub recusa criar a release (403). Faça o workflow num commit só dele e publique a release com um commit seguinte que não toque em workflow.
- Ambiente Windows: `JAVA_HOME=C:\\Program Files\\Android\\Android Studio\\jbr`; Gradle: `./gradlew.bat <tarefas> --console=plain -q` da raiz. Para criar arquivos use a ferramenta de escrita, não heredoc com aspas misturadas no Bash.
- Ao terminar cada etapa: testes do Worker (`cd workers/nearby && npm test`) e do app (`./gradlew.bat :sync-core:test :app:testDebugUnitTest`) verdes; CHANGELOG atualizado; `docs/TESTES-MANUAIS-ROLE-POR-PERTO.md` com o roteiro da etapa.

## Pontos de atenção (o que o texto da spec não diz e costuma quebrar)

Cada linha tem um teste na tarefa dona do código.

1. **Token expira com a conexão aberta** (o ID token do Firebase vale 1 h): a conexão não pode ficar autenticada para sempre nem cair sem se refazer. Esperado: o app renova o token e reconecta sozinho; o servidor fecha a conexão que passar do `exp` (Tarefas 2 e 8).
2. **Duas pessoas pegam a última vaga ao mesmo tempo**: só uma entra; a outra recebe `erro: cheio` (Tarefa 11).
3. **App morto ou celular sem sinal**: a pessoa some do mapa dos outros em 2 min e o encontro dela não trava os demais (Tarefas 4 e 11).
4. **Pessoa perto da divisa de quadrados**: vê os dois lados sem aparecer duas vezes e sem uma proposta duplicada (Tarefas 5 e 13).
5. **Relógio errado no celular ou no servidor** (diferença de minutos): o token não pode ser aceito ou recusado por engano em até 5 min de diferença (Tarefa 2).
6. **Sem internet ao ligar** ou limite diário da Cloudflare esgotado: mensagem clara, nada de travar a tela (Tarefas 8 e 9).

---

# Etapa 1 — Procurar rolê e ver as motos anônimas

### Tarefa 1: Worker `workers/nearby` — esqueleto e geometria

**Arquivos:**
- Criar: `workers/nearby/package.json` (`"type": "module"`, script `test`: `node --test test/`), `workers/nearby/wrangler.toml`, `workers/nearby/src/geo.js`, `workers/nearby/test/geo.test.js`

**Interfaces:**
- Produz (`src/geo.js`):
  - `cellId(lat: number, lon: number): string` → `"<lat inteiro>:<lon inteiro>"` com `Math.floor` (ex.: Vitória → `"-21:-41"`).
  - `cellsInRadius(lat, lon, radiusKm): string[]` → ids dos quadrados que o círculo toca (1 a 4, sem repetir).
  - `distanceM(a: {lat,lon}, b: {lat,lon}): number` → haversine em metros.
  - `fuzzOffset(uid: string, dayIso: string, secret: string): Promise<{eastM: number, northM: number}>` → desvio determinístico em metros (HMAC-SHA256 de `uid|dayIso` com `secret`), módulo entre 200 e 400 m e direção derivada do hash.
  - `applyOffset(lat, lon, off: {eastM, northM}): {lat, lon}` → converte metros em graus (`dLat = northM / 111320`, `dLon = eastM / (111320 * cos(lat))`).

- [x] **Passo 1: escrever os testes** em `test/geo.test.js`:
  - `cellId(-20.31, -40.31) === "-21:-41"` e `cellId(0.5, 0.5) === "0:0"`.
  - `cellsInRadius` a 20 km do centro de um quadrado devolve 1 id; a 5 km da quina entre quatro quadrados devolve 4 ids distintos.
  - `distanceM` entre dois pontos a ~1 km de distância fica entre 990 e 1010 m (use pontos reais da Grande Vitória).
  - `fuzzOffset` com os mesmos `uid`, dia e segredo devolve o mesmo desvio; com dia diferente devolve outro; o módulo `sqrt(eastM²+northM²)` fica em `[200, 400]` para 200 `uid`s gerados.
- [x] **Passo 2:** rodar `cd workers/nearby && npm test` → falha (módulo não existe).
- [x] **Passo 3:** implementar `geo.js` com as assinaturas acima (Web Crypto `crypto.subtle` para o HMAC; módulo = `200 + (byte0/255)*200`; ângulo = `(bytes 1..2)/65535 * 2π`).
- [x] **Passo 4:** `wrangler.toml` com `name = "rumo-nearby"`, `main = "src/index.js"`, `compatibility_date = "2026-01-01"`, `workers_dev = true`, bindings de Durable Objects `CELULA` (classe `Celula`) e `CONTA` (classe `Conta`) e `[[migrations]]` `tag = "v1"`, `new_sqlite_classes = ["Celula", "Conta"]`.
- [x] **Passo 5:** rodar `npm test` → passa. Commit.

### Tarefa 2: Verificação do ID token do Firebase

**Arquivos:**
- Criar: `workers/nearby/src/auth.js`, `workers/nearby/test/auth.test.js`

**Interfaces:**
- Produz: `verifyIdToken(token: string, opts: {projectId: string, now?: number, getKeys: () => Promise<Record<string, CryptoKey|JsonWebKey>>}): Promise<{uid: string, exp: number}>` — lança `Error("token inválido: <motivo>")`. Confere: 3 partes; cabeçalho `alg=RS256` e `kid` conhecido; assinatura RS256; `iss === "https://securetoken.google.com/<projectId>"`; `aud === projectId`; `sub` não vazio; `exp` no futuro e `iat` no passado, **ambos com tolerância de 300 s** para relógio.
- Produz: `fetchGoogleKeys(fetchFn = fetch): Promise<Record<string, JsonWebKey>>` — baixa `https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com`, guarda em cache pelo `max-age` da resposta (mínimo 60 s, padrão 3600 s).

- [x] **Passo 1: testes** (gere um par RSA com `crypto.subtle.generateKey` no próprio teste e assine tokens de verdade):
  - token válido devolve `{uid, exp}`.
  - rejeita: assinatura de outra chave, `aud` errado, `iss` errado, `alg=none`, `kid` desconhecido, token com 2 partes, `exp` vencido há mais de 300 s.
  - **aceita** token com `exp` vencido há 200 s (relógio atrasado) e com `iat` 200 s no futuro (ponto de atenção 5).
  - `fetchGoogleKeys` chama a rede uma vez em duas chamadas seguidas (cache) e de novo após o prazo.
- [x] **Passo 2:** falha. **Passo 3:** implementar. **Passo 4:** `npm test` verde. Commit.

### Tarefa 3: Protocolo e regra de "combina"

**Arquivos:**
- Criar: `workers/nearby/src/protocol.js`, `workers/nearby/src/match.js`, `workers/nearby/test/protocol.test.js`, `workers/nearby/test/match.test.js`

**Interfaces:**
- `protocol.js`: `parseClientMessage(raw: string): {ok: true, msg} | {ok: false, error: string}`. Mensagens aceitas (campo `t`):
  - `{t:"procurar", ritmo, distancia, parada, raioKm}` (ritmo/distância/parada nas listas da seção de restrições; `raioKm` número entre 5 e 20)
  - `{t:"parar"}`
  - `{t:"posicao", lat, lon, rumo|null, vel|null, distKm|null, etaMin|null}` (lat −90..90, lon −180..180, rumo 0..360, vel ≥ 0, distKm/etaMin ≥ 0 ou null)
  - Etapas seguintes acrescentam `proposta`, `cancelar`, `entrar`, `sair`, `bloquear`, `denunciar`, `amigo` (Etapas 2 a 4). Mensagem com `t` desconhecido, JSON inválido, tamanho > 2 KB ou número `NaN` é recusada.
- `match.js`: `combina(a: {ritmo,distancia,parada}, b: {ritmo,distancia,parada}): boolean` com a regra das restrições globais.

- [x] **Passo 1: testes**: cada mensagem válida passa; cada campo fora de faixa ou lista é recusado; 2 KB + 1 byte recusado; `combina` cobre: tudo igual → true; ritmo diferente → false; `ate50` com `mais150` → false (não vizinhas); `ate50` com `50a150` → true; parada `sem_parada` com `so_estrada` → true; `cafe` com `mirante` → false.
- [x] **Passos 2 a 4:** falhar, implementar, passar. Commit.

### Tarefa 4: Presença (lógica pura) e Durable Object `Celula`

**Arquivos:**
- Criar: `workers/nearby/src/presence.js`, `workers/nearby/src/celula.js`, `workers/nearby/test/presence.test.js`, `workers/nearby/test/celula.integration.test.js`

**Interfaces:**
- `presence.js` (lógica pura, sem Cloudflare):
  - `class Presence { constructor(opts: {now: () => number, secret: string}) }`
  - `join(connId: string, uid: string, rolê: {ritmo,distancia,parada,raioKm}): string` → devolve o id de sessão aleatório (16 hex), um por `connId`.
  - `leave(connId)`, `update(connId, pos: {lat,lon,rumo,vel}): void` (rejeita velocidade > 250 km/h ou salto > 50 km em < 60 s por `Error("posição implausível")`).
  - `neighborsFor(connId): Promise<Array<{sessao: string, lat: number, lon: number, rumo: number|null, ritmo, distancia, parada, combina: boolean}>>` → só quem está dentro do `raioKm` de quem pergunta (distância pela posição **real**), com posição **aproximada** (`applyOffset` com `fuzzOffset(uid, diaUTC, secret)`), nunca o próprio, nunca `uid`.
  - `expire(): string[]` → remove quem está sem `update` há mais de 120 s e devolve as conexões removidas.
- `celula.js`: classe Durable Object `Celula` que aceita WebSockets com a API de hibernação, usa `Presence`, atende `procurar`, `parar`, `posicao`, reenvia `{t:"vizinhos", lista:[...]}` a cada conexão quando algo muda (no máximo a cada 2 s por conexão), usa `alarm()` a cada 30 s para `expire()`.

- [x] **Passo 1: testes de `presence.test.js`**:
  - **nenhum campo `uid` aparece** em `JSON.stringify(await neighborsFor(...))` (a string do uid usado no teste não pode aparecer).
  - a posição enviada difere da real entre 200 e 400 m e é **a mesma** em duas chamadas e depois de `leave`+`join` no mesmo dia (anti-média); em outro dia muda.
  - pessoa fora do raio (6 km com `raioKm: 5`) não aparece; a 4 km aparece; ao aumentar `raioKm` para 10, a de 6 km aparece.
  - `combina` vem `true`/`false` conforme `match.js`.
  - sem `update` por 121 s `expire()` remove e a pessoa some de `neighborsFor` dos outros (ponto de atenção 3); com `update` a 119 s continua.
  - velocidade 300 km/h → erro `posição implausível`.
- [x] **Passo 2:** `celula.integration.test.js`: abre 2 WebSockets falsos contra a classe `Celula` (use o pool de testes de Workers da Cloudflare, `@cloudflare/vitest-pool-workers`, ou `unstable_dev` do `wrangler`; escolha o que funcionar e documente em `workers/nearby/README.md`), ambos `procurar` + `posicao` próximos; cada um recebe `vizinhos` com o outro, sem `uid`; fechar um socket faz o outro receber lista vazia em até 3 s.
- [x] **Passos 3 a 5:** falhar, implementar, passar. Commit.

### Tarefa 5: Roteador, publicação e documentação do servidor

**Arquivos:**
- Criar: `workers/nearby/src/index.js`, `workers/nearby/README.md`, `workers/nearby/test/index.test.js`, `.github/workflows/deploy-nearby.yml`, `docs/ROLE-POR-PERTO.md` (rascunho: arquitetura e como configurar)

**Interfaces:**
- `GET /ws?cell=<id>&t=<idToken>` com `Upgrade: websocket`: confere o token (`verifyIdToken` com `FIREBASE_PROJECT_ID` do ambiente), repassa ao `Celula` do `id` (só aceita `id` com formato `^-?\d+:-?\d+$` e dentro do Brasil, como no Worker de trânsito), guardando o `uid` apenas no objeto, nunca na resposta. Token inválido → 401. Sem `Upgrade` → 426. `GET /health` → 200 `ok`.
- O app abre **uma conexão por quadrado** que o raio toca (`cellsInRadius`).
- Variáveis/segredos: `FIREBASE_PROJECT_ID` (variável pública do `wrangler.toml`), `NEARBY_SECRET` (segredo do desvio), `ADMIN_TOKEN` (usado na Etapa 3).

- [x] **Passo 1: testes** `index.test.js`: `/health` 200; sem token 401; token inválido 401; `cell` mal formada ou fora do Brasil 404; sem `Upgrade` 426 (use token falso via `getKeys` injetável).
- [x] **Passo 2:** implementar o roteador. **Passo 3:** `npm test` verde.
- [x] **Passo 4:** criar `deploy-nearby.yml` (**commit separado, só do workflow e do worker, sem tocar no app**) no padrão de `deploy-traffic-proxy.yml`: testes → verificação dos segredos `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`, `NEARBY_SECRET`, `ADMIN_TOKEN` → `wrangler deploy` com `secrets: NEARBY_SECRET`, `ADMIN_TOKEN`. Gatilho: `workers/nearby/**` e o próprio workflow.
- [x] **Passo 5:** documentar em `docs/ROLE-POR-PERTO.md` os segredos que o dono precisa criar (`NEARBY_SECRET` e `ADMIN_TOKEN`: textos longos aleatórios) e a variável `NEARBY_URL`. Parar aqui e **pedir ao dono** para criar os segredos e rodar o workflow; só seguir quando o endereço do servidor existir (o código do app da Tarefa 8 em diante só precisa da variável `NEARBY_URL` na hora de publicar).

### Tarefa 6: O app consegue o ID token

**Arquivos:**
- Modificar: `app/src/main/kotlin/app/hunter/app/account/AuthGateway.kt`, `FirebaseAuthGateway.kt`; todas as implementações falsas de `AuthGateway` nos testes (busque por `: AuthGateway`).
- Testar: um teste novo em `app/src/test/kotlin/app/hunter/app/account/` da implementação falsa e do uso.

**Interfaces:**
- Produz: `suspend fun idToken(forceRefresh: Boolean = false): String?` em `AuthGateway` (null se não há usuário logado). Em `FirebaseAuthGateway`: `auth?.currentUser?.getIdToken(forceRefresh)?.await()?.token`, capturando exceção e devolvendo null.

- [x] **Passo 1:** teste: o gateway falso devolve null deslogado e o token configurado logado; `forceRefresh` é repassado. **Passos 2 a 4:** implementar, compilar o app inteiro (`:app:testDebugUnitTest`), passar. Commit.

### Tarefa 7: Modelos e estado do rolê por perto no `sync-core`

**Arquivos:**
- Criar: `sync-core/src/main/kotlin/app/hunter/nearby/NearbyProtocol.kt`, `NearbyState.kt`, `sync-core/src/test/kotlin/app/hunter/nearby/NearbyStateTest.kt`

**Interfaces:**
- `enum class Ritmo { TRANQUILO, MODERADO, ANIMADO }`, `enum class Distancia { ATE50, DE50A150, MAIS150 }`, `enum class Parada { CAFE, MIRANTE, SO_ESTRADA, SEM_PARADA }` com `wire: String` igual aos textos da lista (`tranquilo`, `ate50`, `50a150`, `cafe`...).
- `data class Preferencia(val ritmo: Ritmo, val distancia: Distancia, val parada: Parada, val raioKm: Int)` (valida `raioKm in 5..20`).
- `data class Vizinho(val sessao: String, val lat: Double, val lon: Double, val rumo: Double?, val preferencia: PreferenciaVista, val combina: Boolean)`.
- Mensagens do servidor como `sealed interface ServerMessage` (`Vizinhos(lista)`, `Erro(codigo)`, mais as da Etapa 2) e `ClientMessage` (`Procurar`, `Parar`, `Posicao`), com `fun ClientMessage.toJson(): String` e `fun parseServerMessage(json: String): ServerMessage?` (null para JSON/tipo desconhecido). Usar `kotlinx.serialization`.
- `class NearbyState` imutável com `fun reduce(state, msg): NearbyState` que guarda a lista de vizinhos ordenada com os que combinam primeiro e remove duplicados pela `sessao` (ponto de atenção 4: o mesmo vizinho vindo de dois quadrados).

- [x] **Passo 1: testes**: `toJson` de `Procurar` produz o JSON exato do protocolo; `parseServerMessage` de lista com 2 vizinhos; JSON inválido → null; `reduce` ordena `combina` primeiro; vizinho repetido (mesma `sessao`) aparece uma vez com a posição mais nova; `Preferencia(raioKm = 4)` lança `IllegalArgumentException`.
- [x] **Passos 2 a 4:** falhar, implementar, passar (`./gradlew.bat :sync-core:test`). Commit.

### Tarefa 8: Cliente WebSocket do app

**Arquivos:**
- Modificar: `gradle/libs.versions.toml` e `app/build.gradle.kts` (dependência `com.squareup.okhttp3:okhttp` na versão estável mais recente com no mínimo 2 semanas; `buildConfigField` `NEARBY_URL` como o do trânsito, vindo de `-PnearbyUrl` / variável `NEARBY_URL`), `.github/workflows/release.yml` **não** nesta tarefa (ver Tarefa 10).
- Criar: `app/src/main/kotlin/app/hunter/app/nearby/NearbyClient.kt`, `PositionThrottle.kt`, `Backoff.kt`, testes em `app/src/test/kotlin/app/hunter/app/nearby/`

**Interfaces:**
- `class PositionThrottle(val floorMs: Long = 10_000, val intervalMs: Long = 30_000, val distanceM: Double = 100.0)` com `fun shouldSend(nowMs: Long, lat: Double, lon: Double, inEncounter: Boolean): Boolean` (em encontro o intervalo é 10 000 ms; nunca abaixo do piso; envia antes se andou ≥ `distanceM` respeitando o piso).
- `class Backoff(val baseMs: Long = 1_000, val maxMs: Long = 30_000)` com `fun nextDelayMs(attempt: Int): Long` (dobra até o máximo).
- `class NearbyClient(url: String, tokenProvider: suspend (Boolean) -> String?, scope: CoroutineScope)` com `fun connect(cells: Set<String>)`, `fun send(msg: ClientMessage)`, `val events: SharedFlow<ServerMessage>`, `val status: StateFlow<NearbyStatus>` (`DESCONECTADO`, `CONECTANDO`, `CONECTADO`, `INDISPONIVEL`). Reconecta com `Backoff`; em 401 ou ao se aproximar de 55 min de conexão, pede `tokenProvider(true)` (token novo) e reconecta (ponto de atenção 1); sem internet ou resposta 5xx vira `INDISPONIVEL` com mensagem "Rolê por perto indisponível agora" (ponto de atenção 6). Usa uma conexão por quadrado em `cells`.

- [x] **Passo 1: testes** (JVM, sem rede): `PositionThrottle` — primeira posição envia; 5 s depois não; 31 s depois sim; em encontro 11 s depois sim; andou 150 m em 12 s envia, em 5 s não; `Backoff` — 1 s, 2 s, 4 s ... 30 s, sem passar de 30 s.
- [x] **Passo 2:** `NearbyClient` testado com um `MockWebServer` do OkHttp (dependência de teste `mockwebserver`): conecta com `?t=<token>`, recebe `vizinhos`, reconecta após queda com `Backoff`, renova o token em 401.
- [x] **Passos 3 a 5:** implementar, passar, commit.

### Tarefa 9: Serviço, ViewModel, tela "procurando rolê" e motos no mapa

**Arquivos:**
- Criar: `app/src/main/kotlin/app/hunter/app/nearby/NearbyService.kt` (serviço em primeiro plano `foregroundServiceType="location"`, notificação "Procurando rolê"; declarar no `AndroidManifest.xml` do app), `NearbyViewModel.kt`, `app/src/main/kotlin/app/hunter/app/ui/NearbyScreens.kt`, `app/src/main/kotlin/app/hunter/app/ui/NearbyMarkers.kt`, testes do mapeamento em `app/src/test/kotlin/app/hunter/app/nearby/`
- Modificar: `HunterMap.kt` (camada `nearby-riders`), `MapScreen.kt` (botão e folha), `HunterViewModel.kt` só se for necessário expor a posição ao vivo (`live`).

**Interfaces:**
- `NearbyViewModel` expõe `val estado: StateFlow<NearbyUiState>` (`desligado`, `ligando`, `procurando(vizinhos)`, `indisponivel(mensagem)`), `fun ligar(p: Preferencia)`, `fun desligar()`, `fun mudarRaio(km: Int)`. Só liga se `AuthGateway.state` for logado (senão mostra o aviso de entrar na conta). Publica a posição conforme `PositionThrottle` e fecha tudo ao desligar.
- `NearbyMarkers.kt`: `fun riderBitmap(density: Float, destaque: Boolean, proprio: Boolean): Bitmap` — **ícone A aprovado**: moto vista de cima (duas rodas, tanque oval com gradiente, guidão, ombros e capacete); cinza para outros (`#7f93b2` a `#c7d3e6`), laranja para o próprio (`#d9600a` a `#ffa14d`), com anel laranja claro quando `destaque`. A imagem gira com o `rumo` pela propriedade do MapLibre (`iconRotate` + `iconRotationAlignment("map")`).
- Folha "Procurando rolê": três listas (ritmo, distância, parada, textos em pt-BR), controle de raio 5 a 20 km (padrão 5), botão "Procurar rolê" / "Parar de procurar rolê"; faixa no mapa "Procurando rolê · N pessoas por perto".

- [x] **Passo 1: testes**: o mapeamento `Vizinho` → propriedades do mapa (`id`, `rumo`, `destaque`) e a contagem "N pessoas por perto" (0 → "ninguém por perto ainda"); `NearbyViewModel` com `NearbyClient` falso: `ligar` envia `procurar` e depois `posicao`; `desligar` envia `parar` e encerra; sem login não liga; `INDISPONIVEL` vira estado com mensagem.
- [x] **Passo 2:** implementar. **Passo 3:** verificação visual no emulador **se disponível** (`docs/TESTES-MANUAIS-ROLE-POR-PERTO.md` descreve o roteiro de 2 aparelhos).
- [x] **Passo 4:** suíte inteira do app verde. Commit.

### Tarefa 10: Publicar a Etapa 1

- [ ] Pedir ao dono: segredos `NEARBY_SECRET` e `ADMIN_TOKEN`, rodar o workflow "Servidor de rolê por perto", criar a variável `NEARBY_URL` no GitHub com o endereço `https://rumo-nearby.<subdominio>.workers.dev`.
- [ ] Commit só do workflow `release.yml` acrescentando as variáveis `ORG_GRADLE_PROJECT_nearbyUrl: ${{ vars.NEARBY_URL }}` (**sem mudar o app neste commit**).
- [ ] Commit seguinte (qualquer mudança no app, por exemplo CHANGELOG da etapa e `docs/TESTES-MANUAIS-ROLE-POR-PERTO.md`) dispara a release; conferir a release, baixar o APK para a Área de Trabalho e conferir que o endereço do servidor está dentro do APK.
- [ ] Teste de ponta a ponta com dois aparelhos conforme o roteiro; só então seguir para a Etapa 2.

---

# Etapa 2 — Propor, entrar e o encontro

### Tarefa 11: Máquina de estados do encontro (lógica pura)

**Arquivos:**
- Criar: `workers/nearby/src/encounter.js`, `workers/nearby/test/encounter.test.js`

**Interfaces:**
- `class Encounters { constructor(opts: {now: () => number}) }`
- `propose(sessionId: string, ponto: {lat,lon}, vagas: number, pref): {id: string}` — erro `"vagas inválidas"` fora de 2..8; erro `"já tem proposta"` se a sessão já tem uma aberta.
- `join(sessionId, id): void` — erro `"cheio"` sem vaga; erro `"inexistente"`; quem já está não entra duas vezes. Entrada **direta** (sem aprovação).
- `leave(sessionId, id)`, `cancel(sessionId, id)` (só quem propôs; fim com motivo `cancelado`).
- `tick(): Array<{id: string, motivo: "expirou" | "ninguem_chegou" | "chegaram"}>` — fim por: proposta sem ninguém entrando há 30 min (`expirou`); em andamento sem nenhuma chegada nova há 60 min (`ninguem_chegou`); todos os que ainda estão dentro chegaram (`chegaram`).
- `position(sessionId, id, pos: {lat,lon,distKm,etaMin}): void` — marca chegada a menos de 100 m.
- `viewFor(sessionId, id): {ponto, vagasLivres, participantes: Array<{sessao, lat, lon, real: boolean, distKm, etaMin, chegou: boolean}>}` — a posição de cada participante vem **aproximada** (já com desvio, fornecida por quem chama) até ele chegar a menos de 1 km do ponto, quando passa a ser a **real** (`real: true`).

- [ ] **Passo 1: testes**: vagas 1 e 9 recusadas, 2 e 8 aceitas; segunda proposta da mesma sessão recusada; entrada direta; **última vaga disputada por duas sessões em sequência: uma entra, a outra recebe `"cheio"`** (ponto de atenção 2); cheio some das propostas abertas (`openProposals()` devolve sem ela); `cancel` por quem não propôs recusado; proposta sem ninguém por 30 min → `expirou`; em andamento 60 min sem chegada → `ninguem_chegou`; sessão que some (`dropSession`) sai sem travar os outros e o encontro continua se restar alguém (ponto de atenção 3); chegada a 99 m marca `chegou`, 101 m não; `viewFor` mostra `real: false` a 1,2 km e `real: true` a 900 m; fim `chegaram` quando todos que ficaram chegaram.
- [ ] **Passos 2 a 4:** falhar, implementar, passar. Commit.

### Tarefa 12: Encontros e Conta mínima no servidor

**Arquivos:**
- Criar: `workers/nearby/src/conta.js` (Durable Object `Conta`, por `uid`: `proposalOpen`, `cooldownUntil`), testes `workers/nearby/test/conta.test.js`, `workers/nearby/test/celula.encounter.test.js`
- Modificar: `workers/nearby/src/celula.js`, `protocol.js` (mensagens `proposta`, `cancelar`, `entrar`, `sair`), `index.js` (rota `/ws?cell=<id>&enc=<id>` para o quadrado dono do encontro)

**Interfaces:**
- Mensagens de cliente: `{t:"proposta", lat, lon, vagas}`, `{t:"cancelar"}`, `{t:"entrar", id}`, `{t:"sair"}`. Do servidor: `{t:"propostas", lista:[{id, lat, lon, vagasLivres, vagasTotal, ritmo, distancia, parada, combina}]}`, `{t:"encontro", id, ponto, participantes:[...]}` (o de `viewFor`), `{t:"fim", id, motivo}`, `{t:"erro", codigo}` (`cheio`, `ja_tem_proposta`, `espera`, `inexistente`, `posicao_implausivel`).
- O encontro vive no `Celula` do quadrado **do ponto marcado**; quem entra abre uma conexão extra com esse quadrado (`enc=<id do encontro>`), mesmo que esteja em outro (ponto de atenção 4).
- `Conta`: `POST /abrir` (falha `"ja_tem_proposta"` se aberta ou `"espera"` com o tempo restante se `now < cooldownUntil`), `POST /fechar?motivo=...` (marca `cooldownUntil = now + 5 min` quando o motivo é `cancelado`, `expirou` ou `recusa`; `chegaram` não gera espera).
- A lista `propostas` enviada a cada conexão do quadrado inclui só propostas abertas dentro do raio dela; as que combinam trazem `combina: true`.

- [ ] **Passo 1: testes**: `Conta` — `abrir` duas vezes seguidas falha; depois de `fechar` com `cancelado`, `abrir` falha com `espera` por 5 min e passa em 301 s; `chegaram` não gera espera. `celula.encounter` (integração): A propõe (2 vagas), B (de outro quadrado, simulado) entra e ambos recebem `encontro`; C tenta entrar e recebe `erro cheio`; posição de A a 5 km chega a B **aproximada** e a 800 m do ponto chega **real**; A cancela e B recebe `fim cancelado`; nenhuma mensagem contém `uid`.
- [ ] **Passos 2 a 4:** falhar, implementar, passar. Commit.

### Tarefa 13: Propor, entrar e acompanhar o encontro no app

**Arquivos:**
- Criar/Modificar: `sync-core/.../nearby/NearbyProtocol.kt` e `NearbyState.kt` (mensagens `Propostas`, `Encontro`, `Fim`, `Erro` e `Proposta`, `Entrar`, `Cancelar`, `Sair`; estado de encontro), `app/.../nearby/NearbyViewModel.kt`, `EtaCalculator.kt` (distância e tempo até o ponto), `app/.../ui/NearbyScreens.kt` (criar proposta, cartão de proposta, tela de encontro, fim), `HunterMap.kt` (camada `nearby-proposals` com texto "2 de 4" e o ponto do encontro), testes.

**Interfaces:**
- `EtaCalculator(router: suspend (from: LatLon, to: LatLon) -> Route?)` com `suspend fun eta(from: LatLon, to: LatLon): Eta` (`distKm`, `etaMin`): usa o `ValhallaClient` já existente; se falhar, **linha reta** com 40 km/h de velocidade presumida.
- Criar proposta: segurar no mapa (gesto que o app já usa) escolhe o ponto; seletor de vagas 2 a 8; botão "Propor encontro". A proposta e o ponto aparecem para os vizinhos.
- Entrar: tocar na proposta mostra o cartão (ritmo, distância, parada, vagas livres) com "Entrar". Ao entrar, o app abre a conexão extra ao quadrado do encontro (ponto de atenção 4: sem duplicar proposta nem vizinho).
- Tela de encontro: ponto marcado, participantes (motos que se movem) e, por pessoa, "X km · Y min"; envia `posicao` a cada 10 s com `distKm` e `etaMin` próprios.

- [ ] **Passo 1: testes**: `EtaCalculator` usa a rota quando existe e a reta de 40 km/h quando o roteador falha (ex.: 20 km em reta → 30 min); reducer de `Encontro` atualiza participantes e fecha em `Fim`; `NearbyViewModel` com cliente falso: `proporPonto` envia `proposta`; `entrar(id)` abre conexão extra uma só vez; `Erro cheio` mostra "Esse encontro já está cheio"; sem mudança da lista de vizinhos ao entrar.
- [ ] **Passos 2 a 4:** implementar, suíte verde, commit.

### Tarefa 14: Publicar a Etapa 2

- [ ] CHANGELOG, roteiro manual (dois aparelhos: A propõe, B entra, caminham até o ponto, chegam, encontro termina), release publicada e conferida, APK na Área de Trabalho. Commit do app sem workflow. Teste de ponta a ponta antes de seguir.

---

# Etapa 3 — Bloquear e denunciar

### Tarefa 15: Bloqueios e denúncias na `Conta`

**Arquivos:**
- Modificar: `workers/nearby/src/conta.js`, `celula.js`, `protocol.js` (mensagens `bloquear {sessao}`, `denunciar {sessao, motivo}`); criar `workers/nearby/test/conta.block.test.js`, `celula.block.test.js`

**Interfaces:**
- `Conta` guarda `bloqueados: Set<uid>` e `bloqueadoPor: Set<uid>` (SQLite do Durable Object): `POST /bloquear?uid=<alvo>` grava nas duas Contas (a de quem bloqueia e a do bloqueado); `GET /bloqueios` devolve os dois conjuntos. A `Celula` consulta `GET /bloqueios` ao conectar a pessoa e guarda o resultado só na memória da conexão.
- `denunciar`: grava na Conta do denunciado `{por: uid, em: now, motivo, lat, lon aproximados}`; motivos aceitos: `incomodo`, `comportamento`, `falso`, `outro`.
- O app **só envia a sessão**; a `Celula` resolve `sessao → uid`.

- [ ] **Passo 1: testes**: A bloqueia B → B some de `neighborsFor` de A **e** A some de B; propostas de A não aparecem para B; B não consegue `entrar` em encontro de A e recebe `erro inexistente`; o bloqueio vale depois de A e B ligarem de novo (nova sessão); outra pessoa C continua vendo os dois; `denunciar` com motivo inválido é recusado; a denúncia grava uid do denunciante e do denunciado e **nunca** devolve uid a ninguém; bloquear a si mesmo é recusado.
- [ ] **Passos 2 a 4:** implementar e passar. Commit.

### Tarefa 16: Rotas do dono (listar denúncias, tirar suspensão)

**Arquivos:**
- Modificar: `workers/nearby/src/index.js`; criar `workers/nearby/test/admin.test.js`

**Interfaces:**
- `GET /admin/denuncias?uid=<opcional>` e `POST /admin/suspensao?uid=<uid>&acao=tirar|por`, todas exigindo `Authorization: Bearer <ADMIN_TOKEN>` (comparação em tempo constante); sem o cabeçalho ou com valor errado → 401. Devolvem JSON; nada disso vai ao app.

- [ ] **Passo 1: testes**: 401 sem token e com token errado; lista com 2 denúncias; `por` e `tirar` mudam o estado; a resposta de uma pessoa suspensa ao tentar `procurar` é `erro suspenso`. **Passos 2 a 4:** implementar e passar. Commit.

### Tarefa 17: Bloquear e denunciar no app

**Arquivos:**
- Modificar: `NearbyProtocol.kt`, `NearbyViewModel.kt`, `NearbyScreens.kt`; testes.

**Interfaces:**
- No cartão da moto e na tela de encontro: "Bloquear" (pede confirmação "Você não verá mais essa pessoa e ela não verá você") e "Denunciar" (lista curta de motivos). O app envia só `sessao`. Mensagem `erro suspenso` vira "Recurso indisponível por denúncias. Fale com o dono do Rumo." e desliga a busca.

- [ ] **Passo 1: testes**: o ViewModel envia `bloquear` e `denunciar` só com `sessao`; a moto some da lista logo após o bloqueio (sem esperar o servidor); `erro suspenso` desliga a busca e mostra a mensagem. **Passos 2 a 4:** implementar, suíte verde, commit.

### Tarefa 18: Publicar a Etapa 3

- [ ] CHANGELOG, roteiro manual (bloquear e conferir invisibilidade nos dois lados), release publicada e conferida. Commit do app sem workflow.

---

# Etapa 4 — Virar amigo no fim do encontro

### Tarefa 19: Marcas "quero ser amigo" no servidor

**Arquivos:**
- Modificar: `workers/nearby/src/conta.js`, `celula.js`, `protocol.js` (mensagem `amigo {sessao}`); criar `workers/nearby/test/amigo.test.js`

**Interfaces:**
- `POST /amigo?uid=<alvo>` grava `{de, para, ate: now + 24h}` na Conta de quem marca; `GET /amigos-confirmados` devolve os `uid`s com marca **recíproca** ainda válida; só então aparece o `uid` (mensagem do servidor `{t:"amigo_confirmado", uid}`). A `Celula` só aceita `amigo` para quem **esteve no mesmo encontro** (guardar os pares de sessões do encontro por 24 h na Conta).

- [ ] **Passo 1: testes**: marca de um lado só não revela nada; as duas marcas revelam o `uid` de cada um para o outro (e só a ele); marca depois de 24 h não vale; marcar alguém que não esteve no encontro é recusado; marca de quem foi bloqueado depois é recusada. **Passos 2 a 4:** implementar e passar. Commit.

### Tarefa 20: "Quero ser amigo" no app

**Arquivos:**
- Modificar: `NearbyViewModel.kt`, `NearbyScreens.kt`, `social/SocialViewModel.kt` (usar `invite(uid)` que já existe), testes.

**Interfaces:**
- Fim do encontro: lista dos participantes **sem nome** ("Pessoa 1", "Pessoa 2"...) com "Quero ser amigo". Ao abrir o app, e ao fim de um encontro, consulta `amigo_confirmado`; ao receber, chama `SocialViewModel.invite(uid)` e mostra "Convite de amizade enviado". Uma pessoa já amiga ou bloqueada não gera convite.

- [ ] **Passo 1: testes**: `amigo_confirmado` aciona `invite` uma vez; segunda mensagem igual não repete; amigo já existente é ignorado. **Passos 2 a 4:** implementar, suíte verde, commit.

### Tarefa 21: Publicar a Etapa 4

- [ ] CHANGELOG, roteiro manual completo (dois aparelhos viram amigos), release publicada e conferida. Commit do app sem workflow.

---

# Etapa 5 — Suspensão automática e fechamento

### Tarefa 22: Suspensão por denúncias

**Arquivos:**
- Modificar: `workers/nearby/src/conta.js`, `celula.js`; criar `workers/nearby/test/suspensao.test.js`

**Interfaces:**
- Ao gravar denúncia, se a Conta do denunciado tem 3 denúncias de `uid`s **diferentes** nos últimos 7 dias, marca `suspensa = true`. Suspensa: `procurar`, `proposta` e `entrar` recebem `erro suspenso`; quem está em encontro é retirado com `fim`.

- [ ] **Passo 1: testes**: 2 denúncias não suspendem; 3 do **mesmo** denunciante não suspendem; 3 de pessoas diferentes suspendem; a 3ª denúncia com a 1ª já fora dos 7 dias não suspende; `tirar` do dono reverte. **Passos 2 a 4:** implementar, passar, commit.

### Tarefa 23: Teste de carga, documentação e fechamento

**Arquivos:**
- Criar: `workers/nearby/loadtest/simulate.js` (100 conexões simuladas na mesma região mandando posição como o app: 30 s procurando), `docs/ROLE-POR-PERTO.md` (versão final para a faculdade), `docs/TESTES-MANUAIS-ROLE-POR-PERTO.md`
- Modificar: `docs/ARQUITETURA.md` (nova seção), `CHANGELOG.md`, `README.md` (lista de recursos).

- [ ] **Passo 1:** `simulate.js` contra o servidor publicado (ou `wrangler dev`) com 100 conexões por 5 min; registra mensagens enviadas por minuto, mensagens recebidas, erros e o tempo de resposta; imprime o custo estimado com a regra 20 para 1. Resultado entra na documentação.
- [ ] **Passo 2:** `ROLE-POR-PERTO.md` com diagrama de arquitetura, protocolo, decisões e por que o Firestore não serve (conta de N²), custo e capacidade medidos, **modelo de ameaças** (posição falsa por GPS falso não é impedida; seguimento por repetição reduzido por desvio estável e espera; bloqueio e denúncia), limites do plano gratuito e como foi testado.
- [ ] **Passo 3:** conferir a lista "Como saber que está pronto" da spec, item por item, com a evidência (teste automatizado ou roteiro manual) ao lado de cada um.
- [ ] **Passo 4:** release final conferida; commit sem workflow.

---

## Autoavaliação do plano

- **Cobertura da spec** (seções 2, 4, 8 e 10): objetivos 1 a 3 (Tarefas 3, 4, 7, 9); 4 e 5 (11, 12, 13); 6 (11, 12, 13); 7 (19, 20); 8 (15, 17); anti-objetivos de vazamento de `uid`/posição exata (testes nas Tarefas 4, 11, 12, 15, 19); sumir ao desligar ou sem sinal (Tarefas 4 e 11); anti-seguimento (Tarefas 4, 12); regras para a Cloudflare e limites (Tarefas 5, 8, 23); "não quebrar o que existe" (restrição global e suíte inteira em cada tarefa do app).
- **Consistência de nomes:** `Presence`, `Encounters`, `Conta`, `Celula`, `NearbyClient`, `NearbyViewModel`, `PositionThrottle`, `EtaCalculator` e os tipos de mensagem (`procurar`, `parar`, `posicao`, `vizinhos`, `proposta`, `propostas`, `entrar`, `sair`, `cancelar`, `encontro`, `fim`, `erro`, `bloquear`, `denunciar`, `amigo`, `amigo_confirmado`) aparecem com o mesmo nome em todas as tarefas.
- **Ordem e cortes:** as etapas 1 a 3 formam o mínimo; as 4 e 5 podem ficar para depois sem quebrar nada, como o dono permitiu.
- **Pendência com o dono dentro do plano:** na Tarefa 5 e na 10 há pontos em que o dono precisa criar segredos e rodar o workflow; o plano para ali e pede.
