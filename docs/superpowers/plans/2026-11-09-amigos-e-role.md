# Amigos e rolê em grupo — plano de implementação

> **Para quem executa:** use superpowers:subagent-driven-development (recomendado) ou superpowers:executing-plans, tarefa por tarefa. Os passos usam caixas `- [ ]`.

**Meta:** aba "Amigos" com busca por nome, convites, amizades, bloqueio, km da semana dos amigos e rolê em grupo com orçamento por pessoa, média do rolê e alcance sugerido.

**Arquitetura:** a lógica que precisa estar certa (semana, alcance do rolê, regras de convite/busca/rolê) é Kotlin puro em `sync-core` (pacote `app.hunter.social`), testada na JVM contra um `SocialStore` falso. O Android entra no `app`: `FirestoreSocialStore` (Firestore), publicação do perfil/atividade, ViewModel e telas Compose. Toda a segurança está nas regras do Firestore (`firebase/firestore.rules`), com testes no emulador. Dados sociais são online primeiro: não entram no Room nem no motor de sincronização do projeto 1.

**Tech Stack:** Kotlin 2.2.20, Compose, Firebase Firestore, `kotlin("test")` + coroutines-test, `@firebase/rules-unit-testing` no emulador.

**Spec:** `docs/superpowers/specs/2026-11-09-amigos-e-role-design.md` (inclui "Decisões fechadas depois da revisão do desenho"). Projeto anterior: `docs/superpowers/plans/2026-11-09-conta-e-entrada.md`.

## Restrições globais

- Firebase **Spark**: sem Cloud Functions e sem FCM com servidor; segurança só nas regras; atomicidade por lote + `getAfter()`.
- Kotlin 2.2.20, AGP 8.13.2, Gradle 8.14.3, Compose BOM 2025.10.00, Room 2.8.5: não trocar versões. Textos em português do Brasil. Sem permissões novas e sem localização em segundo plano.
- Coleções (nomes exatos): `busca/{uid}`, `perfisPublicos/{uid}`, `atividade/{uid}`, `convites/{deUid_paraUid}`, `amizades/{uidA_uidB}` (ids em ordem alfabética), `bloqueios/{quemBloqueia_bloqueado}`, `roles/{id}` (`participantes`, `criadorUid`, `nome`, `expiraEm`), `roles/{id}/membros/{uid}` (`estado`, `orcamento`, `custoPorKm`, `atualizadoEm`).
- Limites: 50 amigos, 10 pessoas por rolê (com o criador), busca com mínimo de 3 letras e 20 resultados, rolê expira 7 dias após a última atividade.
- Consultas baratas: busca por prefixo (índice), listeners só no que é do usuário (`convites` recebidos, `amizades`, `roles` com `participantes array-contains uid`); nunca listar coleções inteiras.
- Quem não tem conta vê na aba um convite para entrar; nada social roda sem usuário logado nem sem `AccountConfig.isAvailable`.
- O app inteiro continua funcionando sem a aba Amigos, sem internet e sem conta. O CI (`ci.yml`) e a release continuam funcionando sem segredos novos.
- `version.txt` vai para `0.5` na última tarefa.

## Revisão: o que o spec implica e nenhum teste de tarefa cobre sozinho

1. **Conta apagada no meio:** convites, amizades e rolês de alguém que apaga a conta e o app é fechado no meio; a retomada precisa terminar a limpeza social (Task 4).
2. **Semana virando:** o amigo abre o app na segunda e o `weekKm` publicado é da semana passada: mostrar 0 km, não o número velho (Task 1).
3. **Convite cruzado e repetido:** A convida B enquanto B convida A; dois toques seguidos em "Convidar"; convite a quem já é amigo ou bloqueou (Task 2).
4. **Rolê com dados faltando:** ninguém com consumo, orçamento zero, só uma pessoa aceita, convidado pendente tentando ler orçamentos (Tasks 1 e 3).
5. **Sem internet na aba:** erro claro, sem travar e sem apagar o que já estava na tela (Tasks 5 e 6).

---

### Task 1: Semana e alcance do rolê (lógica pura)

**Arquivos:**
- Criar: `sync-core/src/main/kotlin/app/hunter/social/WeekMath.kt`, `RolePlanner.kt`, `SocialModels.kt`
- Testar: `sync-core/src/test/kotlin/app/hunter/social/WeekMathTest.kt`, `RolePlannerTest.kt`

**Interfaces:**
- Produz:
  - `data class TripSlice(val startedAtMs: Long, val distanceM: Double)`; `data class Activity(val weekStartMs: Long, val weekKm: Double)`.
  - `object WeekMath { fun weekStartMs(nowMs: Long, zone: java.time.ZoneId): Long; fun weekKm(trips: List<TripSlice>, weekStartMs: Long, zone: java.time.ZoneId): Double; fun visibleWeekKm(a: Activity?, nowMs: Long, zone: java.time.ZoneId): Double }` (semana de segunda 00:00 a domingo 23:59:59 no fuso; viagem conta pela data de início; `visibleWeekKm` devolve 0 quando `weekStartMs` não é o da semana atual de quem lê).
  - `enum class MemberState { INVITED, ACCEPTED }`; `data class Member(val uid: String, val name: String, val state: MemberState, val budget: Double?, val costPerKm: Double?)`.
  - `enum class MemberWarning { OVER_BUDGET, NO_CONSUMPTION_DATA }`; `data class MemberPlan(val uid: String, val budget: Double?, val costPerKm: Double?, val warning: MemberWarning?)`; `enum class NoReach { NO_ACCEPTED_MEMBERS, NO_BUDGETS, NO_CONSUMPTION_DATA }`; `data class RolePlan(val averageBudget: Double?, val averageCostPerKm: Double?, val suggestedReachKm: Double?, val noReach: NoReach?, val members: List<MemberPlan>)`; `object RolePlanner { fun plan(members: List<Member>): RolePlan }`.
- Regras do `plan`: só entram `ACCEPTED`; `averageBudget` = média simples dos orçamentos informados (zero conta; nulo fica fora); `averageCostPerKm` = média simples dos `costPerKm > 0` informados; alcance = `averageBudget / averageCostPerKm` quando os dois existem, senão `noReach` explica; aviso `OVER_BUDGET` quando `alcance × (custoPorKm do membro ?: averageCostPerKm) > orçamento` (com tolerância de 0,01); `NO_CONSUMPTION_DATA` para quem aceitou sem custo por km.

- [ ] **Step 1:** Testes de `WeekMath`: `segunda 00:00 do fuso é o início da semana`; `domingo 23:59 ainda é a mesma semana`; `viagem que começa no domingo 23:50 e termina na segunda conta na semana do domingo`; `virada de semana: weekKm publicado na semana passada aparece como 0`; `fuso diferente muda a semana (America/Recife x UTC)`; `soma só as viagens da semana e converte metros em km`.
- [ ] **Step 2:** Testes de `RolePlanner`: `média simples dos orçamentos e do custo por km`; `orçamento zero entra na média`; `orçamento ainda não informado fica fora`; `convidado pendente não entra`; `ninguém com consumo -> sem alcance e motivo NO_CONSUMPTION_DATA`; `só um aceito`; `aviso OVER_BUDGET para quem não cobre o alcance`; `membro sem consumo usa o custo médio e recebe NO_CONSUMPTION_DATA`; `ninguém aceitou -> NO_ACCEPTED_MEMBERS`.
- [ ] **Step 3:** Rodar `./gradlew.bat :sync-core:test --console=plain -q` e ver falhar; implementar; ver passar. Commit `Amigos: semana e alcance do rolê`.

---

### Task 2: Regras de convite, busca, bloqueio e rolê sobre um `SocialStore`

**Arquivos:**
- Criar: `sync-core/src/main/kotlin/app/hunter/social/SocialStore.kt`, `SocialEngine.kt`
- Testar: `sync-core/src/test/kotlin/app/hunter/social/SocialEngineTest.kt` (com `FakeSocialStore` no próprio teste)

**Interfaces:**
- Consome: modelos da Task 1.
- Produz:
  ```kotlin
  data class PublicProfile(val uid: String, val displayName: String, val brand: String?, val model: String?, val year: Int?, val searchable: Boolean)
  data class SearchHit(val uid: String, val displayName: String, val brand: String?, val model: String?)
  data class Invite(val fromUid: String, val toUid: String, val createdAtMs: Long)
  data class Friend(val profile: PublicProfile, val activity: Activity?)
  data class Role(val id: String, val name: String, val creatorUid: String, val participants: List<String>, val expiresAtMs: Long)
  interface SocialStore {
    suspend fun publishProfile(p: PublicProfile); suspend fun publishActivity(uid: String, a: Activity)
    suspend fun search(prefixLower: String, limit: Int): List<SearchHit>
    suspend fun sendInvite(from: String, to: String); suspend fun cancelInvite(from: String, to: String)
    suspend fun acceptInvite(from: String, to: String)            // lote: cria amizade e apaga o convite
    suspend fun declineInvite(from: String, to: String)
    suspend fun unfriend(uid: String, other: String)
    suspend fun block(uid: String, other: String)                 // lote: bloqueio + apaga amizade e convites entre os dois
    suspend fun unblock(uid: String, other: String)
    suspend fun invitesReceived(uid: String): List<Invite>; suspend fun invitesSent(uid: String): List<Invite>
    suspend fun friends(uid: String): List<Friend>; suspend fun blocked(uid: String): Set<String>
    suspend fun createRole(role: Role, creator: Member, invited: List<Member>)
    suspend fun acceptRole(roleId: String, uid: String); suspend fun leaveRole(roleId: String, uid: String)
    suspend fun closeRole(roleId: String); suspend fun setMemberFigures(roleId: String, uid: String, budget: Double?, costPerKm: Double?)
    suspend fun roles(uid: String): List<Role>; suspend fun members(roleId: String): List<Member>
    suspend fun deleteAllSocial(uid: String)
  }
  sealed interface SocialResult { object Ok : SocialResult; data class Refused(val reason: Refusal) : SocialResult }
  enum class Refusal { SELF, ALREADY_FRIENDS, FRIEND_LIMIT, BLOCKED_BY_ME, TOO_SHORT, ROLE_FULL, NOT_FRIENDS, NOT_CREATOR, ROLE_EXPIRED }
  class SocialEngine(private val store: SocialStore, private val me: String, private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun search(text: String): List<SearchHit>            // minúsculas, >= 3 letras, limite 20, sem eu, sem bloqueados nem amigos
    suspend fun invite(to: String): SocialResult                  // cruzado -> aceita; repetido -> Ok sem duplicar
    suspend fun visibleInvites(): List<Invite>                    // recebidos, sem os de bloqueados (que também são apagados)
    suspend fun createRole(name: String, friendUids: List<String>, myFigures: Member): SocialResult
    suspend fun leaveRole(roleId: String): Unit                   // criador saindo encerra o rolê
  }
  ```
- [ ] **Step 1:** Testes: `busca ignora menos de 3 letras, a si mesmo, bloqueados e amigos e limita a 20`; `convidar a si mesmo é recusado`; `convidar amigo é recusado`; `convite cruzado aceita o convite recebido`; `convite repetido não duplica`; `convidar com 50 amigos é recusado`; `convidar alguém que bloqueei é recusado`; `visibleInvites esconde e apaga convites de bloqueados`; `bloquear amigo apaga amizade e convites`; `criar rolê só com amigos`; `rolê com mais de 10 pessoas é recusado`; `criador saindo encerra o rolê`; `participante saindo só remove a si mesmo`; `rolê expirado é recusado ao aceitar`.
- [ ] **Step 2:** Ver falhar; implementar `SocialEngine` (sem Android); ver passar. Commit `Amigos: regras de convite, busca e rolê`.

---

### Task 3: Regras do Firestore para o social

**Arquivos:**
- Modificar: `firebase/firestore.rules`, `firebase/test/rules.test.js`, `firebase/firestore.indexes.json`

**Interfaces:**
- Produz (acesso): `busca/{uid}` — qualquer conta lê, só o dono escreve (campos `displayName`, `displayNameLower`, `brand`, `model`); `perfisPublicos/{uid}` — dono escreve, lê dono, amigos (existe `amizades/{par}`) e quem divide um rolê aceito; `atividade/{uid}` — dono escreve (`weekStartMs`, `weekKm` numéricos), lê dono e amigos; `convites/{de_para}` — cria quem é `de` (campo `de == auth.uid`), lê e apaga `de` e `para`; `amizades/{par}` — criada só por um membro do par, no mesmo lote que apaga um convite do outro para ele (`getAfter` confere que o convite deixou de existir e que o criador é o destinatário), lida e apagada por qualquer membro; `bloqueios/{quem_alvo}` — só quem bloqueia lê, cria e apaga; `roles/{id}` — lê quem está em `participantes`; cria o criador (`criadorUid == auth.uid`, até 10 participantes, todos amigos do criador); apaga o criador; participante pode se remover do array; `roles/{id}/membros/{uid}` — convidado lê/atualiza só o próprio documento; quem está `aceito` lê os demais; ninguém além do dono do documento muda `orcamento`/`custoPorKm`; o criador pode apagar qualquer membro; o dono apaga o próprio.
- [ ] **Step 1:** Testes no emulador (`node:test`, mesmo padrão dos existentes), para cada caminho com `assertSucceeds`/`assertFails`: estranho, amigo, bloqueado, visitante, dono, criador e convidado: dono lê/escreve o próprio `busca`; estranho lê `busca` mas não `perfisPublicos` nem `atividade`; amigo lê `atividade`; amizade só nasce com o convite aceito pelo destinatário (remetente não consegue forjar); convite com `de` diferente do usuário é negado; bloqueio ilegível para o bloqueado; convidado pendente não lê orçamentos de outros; aceito lê; participante não edita orçamento alheio; criador apaga o rolê; usuário não lista coleções inteiras (`busca` só por consulta com prefixo e `limit <= 20`); apagar documentos próprios em todos os caminhos (necessário para apagar conta) funciona.
- [ ] **Step 2:** Rodar `npx firebase-tools emulators:exec --only firestore --project demo-rumo "npm test"` em `firebase/` (JAVA_HOME = JBR do Android Studio) e ver falhar; escrever as regras; ver passar. Os 22 testes existentes continuam passando. Commit `Firestore: regras do social`.

---

### Task 4: `FirestoreSocialStore` e limpeza social ao apagar a conta

**Arquivos:**
- Criar: `app/src/main/kotlin/app/hunter/app/social/FirestoreSocialStore.kt`, `SocialMapping.kt`
- Modificar: `app/src/main/kotlin/app/hunter/app/sync/FirestoreRemoteStore.kt` (`deleteAllUserData` chama a limpeza social), `docs/FIREBASE.md` (índice da busca e TTL de `roles.expiraEm`)
- Testar: `app/src/test/kotlin/app/hunter/app/social/SocialMappingTest.kt`

**Interfaces:**
- Consome: `SocialStore` e modelos (Tasks 1-2), caminhos e campos das regras (Task 3).
- Produz: `class FirestoreSocialStore(db: FirebaseFirestore) : SocialStore` (todas as leituras com `Source.SERVER`; escritas em lote onde o `SocialStore` diz "lote"; paginação de 300 ao apagar; `deleteAllSocial(uid)` idempotente: apaga convites enviados e recebidos, amizades do usuário, bloqueios feitos, participação em rolês e rolês criados com todos os `membros`, perfil público, atividade e `busca`). Funções puras em `SocialMapping.kt` entre modelos e mapas do Firestore (ids ordenados de amizade/par, `displayNameLower` com `lowercase(Locale.ROOT)` apenas quando as regras o aceitam, limite de 20 chaves).
- [ ] **Step 1:** Testes de `SocialMapping`: `id de amizade é igual nos dois sentidos`; `perfil vira mapa só com os campos das regras`; `displayNameLower omitido quando difere do lower() das regras`; `weekKm e weekStartMs numéricos`; `membro INVITED não leva orçamento`.
- [ ] **Step 2:** Ver falhar; implementar mapeamento e `FirestoreSocialStore`; `FirestoreRemoteStore.deleteAllUserData` passa a chamar `deleteAllSocial(uid)` antes de apagar o perfil (resumível: o marcador permanente continua sendo gravado antes). Ver passar. `./gradlew.bat test :app:assembleRelease --console=plain -q` verde sem `google-services.json` também. Commit `Amigos: adaptador do Firestore e limpeza ao apagar a conta`.

---

### Task 5: Publicação do perfil e da atividade, e estado da aba

**Arquivos:**
- Criar: `app/src/main/kotlin/app/hunter/app/social/SocialPublisher.kt`, `SocialGraph.kt`, `SocialViewModel.kt`, `app/src/main/kotlin/app/hunter/app/social/SearchPref.kt`
- Modificar: `app/.../sync/AccountGraph.kt` (cria o `SocialGraph` quando há usuário), `app/.../HunterViewModel.kt` (publicar ao terminar viagem e ao salvar abastecimento)
- Testar: `app/src/test/kotlin/app/hunter/app/social/SocialPublisherTest.kt`, `SearchPromptTest.kt`

**Interfaces:**
- Produz: `object SearchPrompt { fun shouldAsk(askedBefore: Boolean, signedIn: Boolean): Boolean }` (pergunta única "Quer aparecer na busca por nome?"; resposta guardada em `SearchPref`: `ask`, `searchable`); `class SocialPublisher(store: SocialStore, uid: String, readProfile: () -> UserProfile?, readTrips: () -> List<TripSlice>, readCostPerKm: () -> Double?, searchable: () -> Boolean, zone: ZoneId, clock: () -> Long)` com `suspend fun publishAll()` (perfil público/`busca` conforme a opção, `atividade` com `WeekMath`, e `custoPorKm` nos rolês em que participa) e `fun shouldPublish(lastPublishedAtMs: Long, nowMs: Long): Boolean` (no máximo uma vez a cada 60 s); `SocialViewModel` expõe `state: StateFlow<SocialUi>` (amigos com `WeekMath.visibleWeekKm`, convites visíveis, resultados da busca, rolês, erro/offline) e ações: `search(text)`, `invite(uid)`, `accept/decline/cancel`, `unfriend`, `block`, `createRole`, `acceptRole`, `leaveRole`, `setBudget`.
- Gatilhos de publicação: abrir a aba Amigos, editar o perfil, terminar uma viagem, salvar abastecimento, voltar o app para a frente (respeitando `shouldPublish`).
- [ ] **Step 1:** Testes: `publica km da semana só de viagens terminadas e pega o custo por km do resumo de abastecimentos (FuelMath.summarize(...).avgCostPerKm)`; `busca desligada não escreve em busca e apaga o documento existente`; `no máximo uma publicação por minuto`; `sem custo por km publica nulo`; `pergunta de busca só uma vez e só logado`.
- [ ] **Step 2:** Ver falhar; implementar; ver passar. Falha de rede em qualquer chamada vira estado `offline` no `SocialUi`, nunca exceção para a interface (teste com store falso que lança IOException). Commit `Amigos: publicação do perfil e estado da aba`.

---

### Task 6: Aba Amigos (lista, busca, convites, bloqueio)

**Arquivos:**
- Modificar: `app/src/main/kotlin/app/hunter/app/ui/Tab.kt` (nova `Friends("Amigos", ...)` antes de `Pod`), `app/.../MainActivity.kt` (tela e marcador de convites pendentes na barra)
- Criar: `app/src/main/kotlin/app/hunter/app/ui/FriendsScreen.kt`

**Interfaces:**
- Consome: `SocialViewModel`/`SocialUi` (Task 5).
- [ ] **Step 1:** Tela com: visitante vê "Entre para usar amigos" e botão que abre o login existente; pergunta única de busca (`SearchPrompt`); campo de busca (3+ letras) com resultados e botão Convidar; convites recebidos (Aceitar/Recusar) e enviados (Cancelar); lista de amigos com nome, moto e "X km na semana" (`visibleWeekKm`); menu do amigo com Desfazer amizade e Bloquear (com confirmação); estado offline com mensagem curta e "Tentar de novo". Ícone básico por enquanto (a estilização vem depois).
- [ ] **Step 2:** Verificar compilando e com um teste puro da função que monta o texto de km/convites pendentes (`friendsBadge(count)` e `weekKmLabel(km)`). `./gradlew.bat test :app:assembleRelease --console=plain -q` verde. Commit `Amigos: aba de amigos`.

---

### Task 7: Rolê em grupo (telas)

**Arquivos:**
- Criar: `app/src/main/kotlin/app/hunter/app/ui/RoleScreens.kt`
- Modificar: `app/.../ui/FriendsScreen.kt` (seção "Rolês")

**Interfaces:**
- Consome: `RolePlanner`, `SocialViewModel` (Tasks 1 e 5).
- [ ] **Step 1:** Telas: criar rolê (nome + escolher amigos até 9, com aviso do limite); convites de rolê (Aceitar/Recusar); detalhe do rolê: lista de participantes com orçamento de cada (R$, editável só o próprio), **média do rolê** (média dos orçamentos), **alcance sugerido** em km, aviso "passa do que você pode gastar" em quem não cobre, "sem dados de consumo" para quem aceitou sem custo, e texto explicando quando não há alcance (`NoReach`); sair do rolê; criador pode encerrar e remover participantes. Valores em reais com duas casas e vírgula.
- [ ] **Step 2:** Teste puro dos textos (`reachLabel`, `warningLabel`, `noReachMessage`) e build verde. Commit `Amigos: rolê em grupo`.

---

### Task 8: Integração final, documentação e roteiro de teste

**Arquivos:**
- Modificar: `version.txt` (`0.5`), `README.md`, `CHANGELOG.md`, `docs/ARQUITETURA.md`, `docs/FIREBASE.md`, `.github/workflows/release.yml` se algum caminho novo precisar entrar no filtro
- Criar: `docs/TESTES-MANUAIS-AMIGOS.md`

- [ ] **Step 1:** Garantir a ordem de partida: o `SocialGraph` só existe com usuário logado e `AccountConfig.isAvailable`; sem eles a aba mostra o convite de entrada; build sem `google-services.json` verde.
- [ ] **Step 2:** `docs/TESTES-MANUAIS-AMIGOS.md` para o dono com duas contas em dois aparelhos: buscar, convidar, aceitar, ver km da semana, convite cruzado, bloquear, desfazer amizade, criar rolê, informar orçamentos, ver média e avisos, sair do rolê, o criador encerra, apagar conta de um e ver que some do outro, busca desligada, pergunta única da busca, aba sem internet, virada de semana. Em `docs/FIREBASE.md`: publicar as regras novas e o índice, e **ativar a política de TTL** do campo `expiraEm` em `roles` (passo a passo no console).
- [ ] **Step 3:** README (seção Amigos curta), CHANGELOG 0.5.0, arquitetura (pacote `app.hunter.social`, coleções). `./gradlew.bat test :app:assembleRelease --console=plain -q` e a suíte de regras verdes. Commit `Amigos e rolê: integração e documentação`.

---

## Verificação do plano contra o spec

| Requisito do spec | Tarefa |
|---|---|
| Aba Amigos, visitante vê convite para entrar | 5, 6 |
| Busca por nome (prefixo, 3 letras, 20), "Aparecer na busca", pergunta única | 2, 3, 5, 6 |
| Convites, aceitar/recusar/cancelar, desfazer amizade, bloquear | 2, 3, 4, 6 |
| Amigo vê nome, moto e km da semana (segunda a domingo) | 1, 3, 5, 6 |
| Rolê: orçamento de cada um, média, alcance sugerido, avisos | 1, 2, 3, 7 |
| Custo por km do aparelho, "sem dados de consumo" | 1, 5, 7 |
| Apagar conta apaga o social (retomável) | 4 |
| Sem internet: último dado e aviso, nada no Room | 5, 6 |
| Segurança só por regras e testes por caminho | 3 |
| TTL de 7 dias, 50 amigos, 10 por rolê | 2, 3, 8 |
| Roteiro manual com duas contas | 8 |
