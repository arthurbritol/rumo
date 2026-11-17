# Rolê por perto — progresso da execução

Plano: [2026-11-11-role-por-perto.md](2026-11-11-role-por-perto.md) · Spec: [../specs/2026-11-11-role-por-perto-design.md](../specs/2026-11-11-role-por-perto-design.md)

Publicado em 2026-11-16: etapas 1 e 2 na `main` (release v0.7.23, servidor no ar). A etapa 3 (T15 a T18) foi publicada em 2026-11-17: release v0.7.24, servidor com bloqueios/denúncias/rotas do dono no ar (/admin sem token responde 401). Estado em 2026-11-12: **Tarefas 1 a 9, 11 a 13 15 a 17 e 19 concluídas; etapas 1 a 3 publicadas (v0.7.24) e aprovadas; Tarefa 10 publicada (falta o teste com dois aparelhos); Tarefa 14 publicada (falta teste com dois aparelhos); 20 a 23 pendentes**, por pedido do dono. Nada disto aparece no app instalado até a Tarefa 10 (publicação) ser feita.

## Concluídas (cada uma passou por revisão de spec e qualidade)

| Tarefa | O que é | Commits | Testes |
|---|---|---|---|
| 1 | Worker `workers/nearby`: geometria (quadrados 1°x1°, distância, desvio de posição) | `6d0d1f7` | 7 |
| 2 | Verificação do ID token do Firebase (RS256, tolerância de relógio de 300 s) | `c53a8c0` | 17 |
| 3 | Validação das mensagens do app e regra de "combina" | `34be59f` | 24 |
| 4 | Presença das motos + Durable Object `Celula` (hibernação, alarme, limite de 2 s); privacidade corrigida na revisão | `9774cf4`, `501f01d`, `5d159e8` | 67 unitários + 14 de integração |
| 5 | Roteador (token, cabeçalhos confiáveis, quadrado válido) + workflow `deploy-nearby.yml` + `docs/ROLE-POR-PERTO.md` | `b50fd73`, `89fb620` | 79 unitários + 14 de integração |
| 6 | O app obtém o ID token (`AuthGateway.idToken`) | `50031a1` | suíte do app |
| 7 | Modelos e estado do rolê por perto no `sync-core` (pacote `app.hunter.nearby`) | `9da29ac` | 11 |
| 8 | Cliente WebSocket do app (OkHttp 5.4.0), `PositionThrottle`, `Backoff` | `b9e2080`, `5ecaa39` | 27 |
| 9 | Serviço em primeiro plano, ViewModel, tela "procurando rolê", motos anônimas no mapa | `188eb44`, `393aa29`, `b85d855` | 24+ novos, suíte do app e `assembleDebug` verdes |
| 11 | Máquina de estados dos encontros (`encounter.js`, lógica pura; falha fechada sem posição aproximada) | `769ceb7`, `66fafd0` | 21 de encontro (100 unitários no total) |
| 12 | Encontros no servidor (`Celula` dona do encontro, `Conta` com cooldown, rota `enc`, quadrado canônico, limpeza de zumbis, limite de taxa) | `925a3a6`, `4d7eb84`, `7b1011c` | 113 unitários + 39 de integração |
| 13 | App: propor, entrar e acompanhar o encontro (protocolo, `EtaCalculator`, ViewModel, camada `nearby-proposals`, cartões) | `3300be3`, `61373e5`, `cd61503`, `4b2f733`, `c359035` | suíte do sync-core e do app verdes, `assembleDebug` ok |
| 15 | Bloqueios e denúncias na `Conta`/`Celula` (simétrico, sem revelar quem bloqueou, pares de 24 h para denunciar no fim do encontro) | `4b7a553`, `1838657`, `b5f2984`, `748987c` | 126 unitários + 61 de integração |
| 16 | Rotas do dono (`/admin/denuncias`, `/admin/suspensao`) e suspensão manual (`suspenso` ao procurar/propor/entrar) | `6366ab9`, `491a1f4` | 134 unitários + 64 de integração |
| 17 | App: bloquear e denunciar (cartão da moto, encontro e fim), `suspenso` desliga a busca | `3b3b983`, `92d84ff` | suíte do sync-core e do app verdes, `assembleDebug` ok |
| 19 | Marcas "quero ser amigo" recíprocas no servidor (`amigo`, `amigo_confirmado`, `amigo_ack`) | `9e4f8b3`, `8de298a`, `a56ab0b` | 147 unitários + 73 de integração |

**Importante:** nada do lado do app foi visto rodando em tela (não há emulador funcionando); só compila e passa nos testes. O servidor também nunca foi publicado.

## Pendentes (nesta ordem; os briefs estão no ledger local, mas o plano tem todo o texto)

- **Etapa 1 restante:** Tarefa 10 — publicar (o dono cria os segredos e a variável; rodar o workflow do servidor; ligar `NEARBY_URL` no `release.yml` em commit separado; release do app; teste com 2 aparelhos).
- **Etapa 2 (propor, entrar, encontro):** Tarefas 11 a 14.
- **Etapa 3 (bloquear e denunciar):** Tarefas 15 a 18.
- **Etapa 4 (virar amigo):** Tarefas 19 a 21.
- **Etapa 5 (suspensão automática, carga e documentação):** Tarefas 22 e 23.

As etapas 1 a 3 são o mínimo; as 4 e 5 podem ficar para depois.

## Ações do dono que ainda faltam

1. Criar no GitHub os segredos `NEARBY_SECRET` e `ADMIN_TOKEN` (textos longos e aleatórios).
2. Rodar o workflow "Servidor de rolê por perto" (precisa do código na `main` ou da branch enviada) e criar a variável `NEARBY_URL` com o endereço, sem barra no final.
3. Autorizar o envio ao GitHub, a junção na `main` e a release de cada etapa.

## Decisões tomadas durante a execução (rulings)

- **R1/R7:** `Conta` começa como stub (responde 501) e `src/index.js` mínimo, para o deploy e o teste de integração funcionarem; a Tarefa 12 preenche a `Conta`.
- **R2:** a `Celula` traduz o erro de posição impossível para `{t:"erro", codigo:"posicao_implausivel"}`.
- **R3:** o cliente tem `connect(cells)` e `connectEncounter(cell, encId)`.
- **R4:** `npm test` = unitários com `node --test "test/*.test.js"`; testes do objeto durável em `npm run test:int` (vitest + pool da Cloudflare).
- **R5:** ao fim de um encontro a `Celula` grava os pares de sessões na `Conta` por 24 h (usado pela Tarefa 19).
- **R6:** implementadores só fazem commits locais; envio, merge, release e segredos são do dono.
- **R8 (privacidade):** o raio filtra pela posição real de quem pergunta contra a posição **aproximada** de quem é visto; piso de 10 s entre posições; velocidade implícita acima de 250 km/h é rejeitada; expiração exata de 120 s.
- **R9/R10:** `NearbyState.reduce(state, msg, celula)` e `events` emite `NearbyEvent(celula, msg)`, para deduplicar vizinhos vistos por dois quadrados.
- **R11:** OkHttp 5.4.0 (a 5.5.x exige compileSdk 37 e um AGP mais novo).
- **R12:** `send(msg, cell: String? = null)`: posição/procurar/parar vão a todos os quadrados; proposta/entrar/cancelar/sair só ao quadrado indicado.
- **R13:** sinal `opened` por quadrado: cada quadrado novo ou reconectado é avisado da busca na hora.
- **R14:** a folha mostra a preferência ativa; escolhas travadas enquanto procura.
- **R15:** enquanto procura, o próprio usuário usa o ícone da moto laranja.

## Itens abertos para as próximas tarefas (obrigatórios nas indicadas)

- **Tarefa 12:** o roteador deve **normalizar o id do quadrado** (`-021:-41` e `-21:-41` não podem ser objetos diferentes), tratar o parâmetro `enc` e preencher a `Conta`.
- **Tarefa 12:** a `Celula` só aceita as mensagens de encontro com a `Conta` consultada; usar `send(msg, cell)` do cliente para propostas.
- **Tarefa 13:** o cliente envia proposta ao quadrado do ponto marcado.
- **Tarefa 23:** servidor que abre e fecha na hora é retentado a cada ~1 s no cliente (considerar um piso); mostrar a contagem real de mensagens no relatório de capacidade.
- Menores adiados (qualidade): cache do desvio por uid/dia na `Celula`, dependências de desenvolvimento do Worker com avisos de segurança, `Upgrade` sem diferenciar caixa, oscilação visual do status "indisponível", teste mais forte de dois 401 seguidos.

## Como retomar

1. Estar na branch `feat/role-por-perto` (`git switch feat/role-por-perto`); a árvore deve estar limpa. Se houver alteração solta do dono (por exemplo em `MainActivity.kt`), ela não é desta tarefa: não commitar junto.
2. Ler este arquivo e o plano; dispatchar a Tarefa 10 (ou pular para a 11, que não depende de publicar) seguindo o fluxo de subagentes: implementador, revisão de spec e qualidade, correções, revisão restrita.
3. Regra do repositório: commits que mexem em `.github/workflows/*` nunca junto de mudança no app (a release falha com erro 403).

## Pendências registradas na Tarefa 12 (decididas, não corrigidas)

- Desvio de posição estável por dia: quem passa a menos de 1 km do ponto revela o desvio do dia (a posição aproximada fica calculável). Tratar no modelo de ameaças ou na T23.
- Blob `enc` único por quadrado sem teto de tamanho (risco baixo).
- `distKm` é calculado no servidor (linha reta, a partir da posição aproximada acima de 1 km), não pelo cliente: desvio aceito da spec 8.3.
- `ninguem_chegou` não gera espera de 5 min.
- Gancho `aoFimDoEncontro(f, uids)` precisa mudar na T19 (par sessão para uid).
- Erros novos: `ja_em_encontro`, `indisponivel`, `ponto_invalido`; `espera` traz `restanteMs`; `sair` responde `fim` com motivo `saiu`.
- `this.zumbis`/`this.sujo` não persistem (janela rara); `fechamentos` sem teto global.

## Pendências registradas na Tarefa 13 (decididas, não corrigidas)

- Carência de reconexão do proponente: qualquer queda do proponente cancela o encontro de todos (servidor) e aplica espera de 5 min; tratar na T14/T23.
- Moto do participante duplicada (vizinho desviado + participante), sem rumo; vazamento do desvio diário (mesmo item da T12).
- `cancelar` após 15 s sem resposta da Conta pode punir sem culpa; desligar a busca durante o encontro não pede confirmação.
- Nada visto em tela: cartões, posições (200, 220, 430 dp), pinos "N de M", gesto de segurar no mapa.

## Pendências registradas na Tarefa 15

- T22: suspensão por 3 denunciantes cai com contas descartáveis; exigir idade mínima da conta ou só contar quem esteve no mesmo encontro com o denunciado.
- Denúncia de quem foi bloqueado contra quem bloqueou é descartada em silêncio (decisão da spec).
- Bloqueio em outra Celula (divisa) vale em até 5 min ou na próxima conexão; o app (T17) deve mandar `bloquear` pelo socket da Celula onde a sessão vive.
- Mensagens `bloquear {sessao}` e `denunciar {sessao, motivo}`; erros `inexistente`, `sessao_propria`, `espera`, `limite`.

## Pendências registradas na Tarefa 16

- Falha aberta da suspensão (se a Conta não responde, a pessoa pode procurar) e cache de 5 min: `por`/`tirar` só valem em conexão aberta após até 5 min.
- Uid na query string das rotas do dono (aparece em logs da Cloudflare).
- Para publicar a etapa 3 faltam: T17 (bloquear/denunciar no app) e T18 (publicar). Servidor das T15/T16 ainda não está no ar.

## Pendências registradas na Tarefa 19 (servidor só na branch local `feat/role-por-perto-etapa4`, não publicado)

- Marca e bloquear/denunciar compartilham o limite de ritmo (só UX); prazo da marca conta a partir da marca, não do fim do encontro; `/amigos-confirmados` faz até 50 chamadas de Conta para Conta por conexão.
- Corrida de leitura e gravação em `/amigos-confirmados` (janela de ~2 s; pode entregar uid de quem acabou de ser bloqueado em uma resposta já em andamento); `/amigo-confirmar` sem prazo próprio.
- A entrega imediata só vale na mesma Celula do encontro; o app (T20) deve mandar `amigo` pelo quadrado do encontro e responder `amigo_ack {uid}` a cada `amigo_confirmado`.
