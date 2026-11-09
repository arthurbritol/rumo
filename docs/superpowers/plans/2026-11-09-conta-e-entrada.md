# Conta e entrada — plano de implementação

> **Para quem executa:** use superpowers:subagent-driven-development (recomendado) ou superpowers:executing-plans, tarefa por tarefa. Os passos usam caixas `- [ ]`.

**Meta:** primeira abertura com apresentação, login opcional (Google e e-mail/senha), permissões e cadastro da moto, mais sincronização dos dados do usuário com o Firebase, de modo que reinstalar e entrar de novo recupera tudo (menos o trajeto completo das viagens).

**Arquitetura:** um módulo JVM novo `sync-core` concentra o que precisa estar certo e ser testável sem aparelho (modelo de documento, mesclagem sem duplicar, motor de sincronização sobre as interfaces `LocalStore` e `RemoteStore`, fluxo da primeira abertura). O Android entra no `app`: adaptadores Room e Firestore, `AuthRepository` (Firebase Auth + Credential Manager) e as telas Compose. O Room continua sendo a base local; o Firestore guarda uma cópia por usuário.

**Stack:** Kotlin 2.2.20, Compose, Room 2.8.5, Firebase Auth e Firestore (BoM), Credential Manager + Google ID, `kotlin("test")` e coroutines-test na JVM, emulador do Firebase para as regras.

**Spec:** `docs/superpowers/specs/2026-11-09-conta-e-entrada-design.md` (mockups em `.superpowers/brainstorm/*/content/onboarding-fluxo.html`).

## Restrições globais

- Firebase no plano **Spark**: só Authentication e Firestore. Sem Cloud Storage e sem Cloud Functions. Se algum dia for preciso o plano Blaze, o dono topa, mas este plano não precisa.
- Versões fixas do projeto: Kotlin 2.2.20, AGP 8.13.2, Gradle 8.14.3, Compose BOM 2025.10.00, Room 2.8.5, compileSdk/targetSdk 36, minSdk 26. Nada de AGP 9 / Kotlin 2.4 agora; as bibliotecas do Firebase e de login entram na versão estável mais nova que compilar com isso.
- `applicationId` continua `app.hunter.companion`. Textos da interface em português do Brasil.
- Sem analytics e sem anúncios. Sem localização em segundo plano. Uma moto por conta.
- Sair da conta só limpa o celular se tudo o que sincroniza já subiu; nunca apaga a nuvem. Apagar a conta grava antes uma marca de exclusão pendente e continua na próxima abertura se o app fechar.
- O trajeto completo (`track_points`) nunca vai para a nuvem. A conta guarda só o resumo da viagem.
- O app inteiro funciona sem login e sem internet. `google-services.json` entra no repositório; sem ele o app compila e o login aparece como indisponível.
- O CI (`ci.yml`) e a release (`release.yml`) continuam funcionando sem segredos novos.

## Revisão: o que o spec implica e nenhum teste de tarefa cobre sozinho

1. **Primeiro login sem internet:** a tela mostra erro claro em pouco tempo e a pessoa segue como visitante (teste na Tarefa 8).
2. **Relógio errado do celular:** um `updatedAtMs` no futuro faria um registro vencer para sempre; limitar a `agora + 1 dia` ao aplicar (Tarefa 3).
3. **Viagem restaurada sem trajeto:** a tela de detalhe da viagem não pode quebrar com zero pontos (Tarefa 6).
4. **Exclusão da conta interrompida ou com login antigo demais:** retoma sozinha; se o Firebase pedir nova autenticação, a pessoa a refaz sem perder o estado (Tarefas 4 e 5).
5. **Permissão negada para sempre:** "Agora não" e "negar e não perguntar de novo" não travam o fluxo e levam às configurações quando o recurso for usado (Tarefa 10).

---

### Tarefa 1: Firebase compila no projeto (e o app segue funcionando sem a configuração)

**Arquivos:**
- Modificar: `gradle/libs.versions.toml`, `build.gradle.kts` (raiz), `app/build.gradle.kts`, `settings.gradle.kts`
- Criar: `docs/FIREBASE.md`, `firebase/firebase.json`, `firebase/.firebaserc.example`
- Criar: `app/src/main/kotlin/app/hunter/app/account/AccountConfig.kt`

**Interfaces:**
- Produz: `object AccountConfig { fun isAvailable(context: Context): Boolean }`, verdadeiro só se o Firebase foi inicializado (existe `google-services.json`).

- [ ] **Passo 1:** Acrescentar ao catálogo: plugin `com.google.gms.google-services` (4.4.4 ou a mais nova compatível), `firebase-bom`, `firebase-auth`, `firebase-firestore`, `androidx.credentials:credentials` e `credentials-play-services-auth`, `googleid`. Começar por `firebase-bom` 34.x e `credentials` 1.6.0 (estável) e subir só se compilar.
- [ ] **Passo 2:** Aplicar o plugin do Google Services em `app/build.gradle.kts` **somente se** `app/google-services.json` existir, e adicionar as dependências.
- [ ] **Passo 3:** Escrever `AccountConfig.isAvailable` (usa `FirebaseApp.getApps(context).isNotEmpty()`).
- [ ] **Passo 4:** Escrever `docs/FIREBASE.md` com o passo a passo do dono: criar o projeto Firebase, registrar o app `app.hunter.companion`, cadastrar o SHA-1 e o SHA-256 da chave `rumo` (comando `keytool -list -v -keystore C:\Users\arthur.lucio\.rumo\rumo-release.keystore -alias rumo`), ativar Google e E-mail/senha, criar o Firestore em modo produção, baixar `google-services.json` para `app/`, publicar as regras (Tarefa 5). Incluir o aviso: "a conta de e-mail com o mesmo endereço do Google é vinculada".
- [ ] **Passo 5:** Rodar `./gradlew.bat test :app:assembleRelease --console=plain -q` sem o `google-services.json`.
  Esperado: compila e passa, e `AccountConfig.isAvailable` fica falso. Se o BoM ou o plugin não compilar com AGP 8.13.2/Kotlin 2.2.20, baixar a versão até compilar e anotar a escolhida em `docs/FIREBASE.md`.
- [ ] **Passo 6:** Commit `Firebase: dependências e guia de configuração`.

---

### Tarefa 2: Módulo `sync-core` com o modelo de documento e o catálogo de motos

**Arquivos:**
- Modificar: `settings.gradle.kts` (incluir `:sync-core`)
- Criar: `sync-core/build.gradle.kts` (JVM, mesmas opções do `navigation`), `sync-core/src/main/kotlin/app/hunter/sync/Model.kt`, `.../BikeCatalog.kt`
- Testar: `sync-core/src/test/kotlin/app/hunter/sync/BikeCatalogTest.kt`

**Interfaces:**
- Produz:
  - `enum class Kind(val path: String) { FUELUP("abastecimentos"), MAINTENANCE("manutencao"), PLACE("locais"), TRIP("viagens") }`
  - `data class SyncDoc(val kind: Kind, val gid: String, val updatedAtMs: Long, val deleted: Boolean, val fields: Map<String, Any?>)`
  - `data class OdometerDoc(val offsetKm: Double, val tankLiters: Double, val updatedAtMs: Long)`
  - `data class UserProfile(val displayName: String, val brand: String?, val model: String?, val year: Int?, val voice: Boolean = true)`
  - `object BikeCatalog { val brands: List<BikeBrand>; fun models(brand: String): List<String> }` e `data class BikeBrand(val name: String, val models: List<String>)`

- [ ] **Passo 1:** Escrever `BikeCatalogTest` com: `contém as marcas do spec` (Royal Enfield, Yamaha, Honda, Kawasaki, Triumph, BMW, KTM, TVS, Bajaj, Harley-Davidson e "Outra"); `Royal Enfield lista Hunter 350, Meteor 350, Classic 350 e Himalayan`; `Outra não tem modelos fixos`; `models de marca desconhecida é vazio`; `nomes de modelos são únicos por marca`.
- [ ] **Passo 2:** Rodar `./gradlew.bat :sync-core:test --console=plain` e ver falhar.
- [ ] **Passo 3:** Implementar os tipos e o catálogo (lista fixa, sem servidor).
- [ ] **Passo 4:** Rodar de novo e ver passar. Commit `sync-core: modelo de documento e catálogo de motos`.

---

### Tarefa 3: Mesclagem sem duplicar

**Arquivos:**
- Criar: `sync-core/src/main/kotlin/app/hunter/sync/Merge.kt`
- Testar: `sync-core/src/test/kotlin/app/hunter/sync/MergeTest.kt`

**Interfaces:**
- Consome: `SyncDoc`, `Kind`, `OdometerDoc` (Tarefa 2).
- Produz:
  - `fun dedupKey(d: SyncDoc): String?` (nulo para registros apagados). Chaves: abastecimento = `dateMs|odometerKm|liters|totalCost`; manutenção = `name|intervalKm|intervalDays`; local = `lat(4 casas)|lon(4 casas)|name`; viagem = `startedAtMs|distanceM` arredondado ao metro.
  - `data class MergePlan(val applyLocal: List<SyncDoc>, val pushRemote: List<SyncDoc>)`
  - `fun merge(local: List<SyncDoc>, remote: List<SyncDoc>, nowMs: Long): MergePlan`
  - `fun mergeOdometer(local: OdometerDoc?, remote: OdometerDoc?, nowMs: Long): OdometerDoc?`

- [ ] **Passo 1:** Testes: `mesmo gid vence o updatedAtMs mais novo`; `empate vence o remoto`; `mesmo conteúdo com gids diferentes vira um só (fica o menor gid) e o outro é marcado apagado dos dois lados`; `registro só local sobe`; `registro só remoto desce`; `apagado mais novo vence edição mais antiga`; `edição mais nova revive o apagado`; `updatedAtMs mais de um dia no futuro é limitado a agora`; `dois abastecimentos legítimos de mesma data mas litros diferentes não são duplicata`; `hodômetro: vence a última escrita, nulo de um lado devolve o outro`.
- [ ] **Passo 2:** Rodar e ver falhar. **Passo 3:** Implementar. **Passo 4:** Ver passar. Commit `sync-core: mesclagem por id e por conteúdo`.

---

### Tarefa 4: Motor de sincronização, sair e apagar conta

**Arquivos:**
- Criar: `sync-core/src/main/kotlin/app/hunter/sync/SyncEngine.kt`, `Stores.kt`
- Testar: `sync-core/src/test/kotlin/app/hunter/sync/SyncEngineTest.kt` (com `FakeLocalStore` e `FakeRemoteStore` no próprio teste)

**Interfaces:**
- Consome: `merge`, `mergeOdometer`, tipos da Tarefa 2.
- Produz:
  ```kotlin
  interface LocalStore {
    suspend fun all(): List<SyncDoc>            // inclui apagados (lápides)
    suspend fun dirty(): List<SyncDoc>
    suspend fun apply(docs: List<SyncDoc>)      // cria/atualiza/apaga por gid e marca como sincronizado
    suspend fun markClean(docs: List<SyncDoc>)
    suspend fun odometer(): OdometerDoc?
    suspend fun applyOdometer(o: OdometerDoc)
    suspend fun profile(): UserProfile?
    suspend fun applyProfile(p: UserProfile)
    suspend fun hasDirtyOdometerOrProfile(): Boolean
    suspend fun clearAll()                      // sair da conta
  }
  interface RemoteStore {
    suspend fun fetchAll(): RemoteSnapshot      // docs, odometer, profile
    suspend fun push(docs: List<SyncDoc>)
    suspend fun pushOdometer(o: OdometerDoc)
    suspend fun pushProfile(p: UserProfile)
    suspend fun markDeletionPending()
    suspend fun deletionPending(): Boolean
    suspend fun deleteAllUserData()             // subcoleções e perfil; idempotente
  }
  data class RemoteSnapshot(val docs: List<SyncDoc>, val odometer: OdometerDoc?, val profile: UserProfile?)
  class SyncEngine(local: LocalStore, remote: RemoteStore, clock: () -> Long = System::currentTimeMillis) {
    suspend fun firstSync()                     // baixa, mescla, sobe o que só existe local
    suspend fun syncNow()                       // sobe o sujo, baixa o remoto
    suspend fun canSignOut(): Boolean           // nada sujo
    suspend fun signOutWipe()                   // exige canSignOut(); senão IllegalStateException
    suspend fun deleteAccountData()             // marca pendente, apaga, idempotente
    suspend fun resumePendingDeletion(): Boolean
  }
  ```
- [ ] **Passo 1:** Testes: `primeiro login em aparelho com dados e conta com dados une sem duplicar`; `primeiro login em aparelho vazio traz tudo da conta`; `syncNow sobe só o sujo e marca limpo`; `canSignOut é falso com registro sujo, hodômetro sujo ou perfil sujo`; `signOutWipe limpa o local e não toca no remoto`; `signOutWipe com pendência lança`; `deleteAccountData marca pendente antes de apagar`; `exclusão interrompida (falha no meio) é retomada por resumePendingDeletion`; `firstSync falha de rede não altera o local` (remoto lança IOException).
- [ ] **Passo 2:** Ver falhar. **Passo 3:** Implementar o motor. **Passo 4:** Ver passar. Commit `sync-core: motor de sincronização`.

---

### Tarefa 5: Regras do Firestore e estrutura de dados

**Arquivos:**
- Criar: `firebase/firestore.rules`, `firebase/firestore.indexes.json`, `firebase/test/rules.test.js`, `firebase/package.json`
- Modificar: `firebase/firebase.json`, `.github/workflows/ci.yml` (job opcional com Node 20: `npm ci` e `npx firebase emulators:exec --only firestore "npm test"` dentro de `firebase/`)

**Interfaces:**
- Produz o caminho dos dados: `usuarios/{uid}` (perfil: `displayName`, `displayNameLower`, `brand`, `model`, `year`, `voice`, `deletionPending`, `updatedAtMs`) e subcoleções `abastecimentos`, `manutencao`, `locais`, `viagens` (documentos com `gid` como id, `updatedAtMs`, `deleted` e os campos da Tarefa 3), mais `usuarios/{uid}/config/hodometro`.

- [ ] **Passo 1:** Testes (`rules-unit-testing`): `dono lê e escreve o próprio perfil e subcoleções`; `outro usuário não lê nem escreve`; `visitante (sem login) não acessa nada`; `campo desconhecido no perfil é recusado`; `displayName vazio ou maior que 60 caracteres é recusado`; `documento de subcoleção maior que o limite de campos é recusado`; `deletionPending só aceita booleano`.
- [ ] **Passo 2:** Rodar contra o emulador e ver falhar. **Passo 3:** Escrever as regras. **Passo 4:** Ver passar. Commit `Firestore: regras e testes`.
- [ ] **Passo 5 (dono):** publicar as regras pelo console ou `firebase deploy --only firestore:rules`; registrado em `docs/FIREBASE.md`.

---

### Tarefa 6: Room v3 (id global, data de alteração, lápides) e adaptador `LocalStore`

**Arquivos:**
- Modificar: `maintenance/.../MaintenanceEntities.kt`, `maintenance/.../MaintenanceDao.kt`, `trips/.../TripEntities.kt`, `trips/.../TripDao.kt`, `app/.../data/PlaceEntity.kt` (e `PlaceDao`), `app/.../data/AppDatabase.kt` (versão 3, `MIGRATION_2_3`, entidade `TombstoneEntity`, preferência do perfil), `HunterApp.kt`
- Criar: `app/.../data/Migration2To3.kt` (lista de comandos SQL em Kotlin puro), `app/.../sync/RoomLocalStore.kt`, `app/.../ui/TripDetail` (ajuste para viagem sem pontos)
- Testar: `app/src/test/.../Migration2To3Test.kt`, `app/src/test/.../RoomLocalStoreMappingTest.kt`

**Interfaces:**
- Consome: `LocalStore`, `SyncDoc`, `OdometerDoc`, `UserProfile` (Tarefas 2 e 4).
- Produz: as quatro entidades sincronizáveis ganham `gid: String` (UUID), `updatedAtMs: Long` e `dirty: Boolean`; `OdometerEntity` ganha `updatedAtMs` e `dirty`; tabela `tombstones(kind, gid, deletedAtMs, dirty)`. Toda alteração e remoção feita pela interface (ViewModel/DAO) preenche `updatedAtMs = agora`, `dirty = true`, e exclusões gravam lápide. `class RoomLocalStore(db: AppDatabase, prefs: SharedPreferences) : LocalStore`.

- [ ] **Passo 1:** Escrever `Migration2To3Test` na JVM com `sqlite-jdbc` em memória: cria o esquema v2 a partir de `app/schemas/.../2.json`, insere abastecimentos, itens, locais, viagens, pontos e hodômetro, aplica os comandos de `Migration2To3` e confere: nenhum registro perdido; `gid` não vazio e único por linha; `dirty = 1`; pontos de trajeto intactos. Rodar e ver falhar.
- [ ] **Passo 2:** Escrever `RoomLocalStoreMappingTest` para o mapeamento entidade ↔ `SyncDoc` (campos exatos das chaves da Tarefa 3; só locais favoritos sincronizam, recentes não; a preferência `voice` vai no perfil e as demais preferências do aparelho (`bottomMode`, `mac`, `update_*`, `seeded`) não; viagem em andamento, `endedAtMs == null`, não sincroniza; viagem recebida da nuvem vira `TripEntity` sem pontos).
- [ ] **Passo 3:** Implementar entidades, migração e `RoomLocalStore`. O build precisa gerar `app/schemas/.../3.json`; conferir que o KSP não reclama.
- [ ] **Passo 4:** A tela de detalhe da viagem mostra "O trajeto desta viagem só existe no aparelho em que ela foi gravada" quando há zero pontos, sem quebrar (cobre a Revisão 3). Teste de lógica da mensagem em arquivo de teste do módulo `app`.
- [ ] **Passo 5:** `./gradlew.bat test :app:assembleRelease --console=plain -q` passa. Commit `Room v3: id global, lápides e adaptador de sincronização`.

---

### Tarefa 7: Adaptador `RemoteStore` do Firestore e agendamento da sincronização

**Arquivos:**
- Criar: `app/.../sync/FirestoreRemoteStore.kt`, `app/.../sync/SyncScheduler.kt`
- Testar: `app/src/test/.../SyncSchedulerTest.kt` (lógica de atraso/lote em Kotlin puro)

**Interfaces:**
- Consome: `RemoteStore` (Tarefa 4) e o caminho de dados (Tarefa 5).
- Produz: `class FirestoreRemoteStore(db: FirebaseFirestore, uid: String) : RemoteStore` (escritas em lote de até 400 documentos; `deleteAllUserData` apaga as subcoleções em páginas e por fim o documento do usuário). `class SyncScheduler(engine: SyncEngine, scope: CoroutineScope, debounceMs: Long = 5_000)` com `fun requestSync()` e `fun syncOnConnectivity()`.

- [ ] **Passo 1:** `SyncSchedulerTest`: várias chamadas seguidas viram uma sincronização; erro de rede mantém tudo sujo e tenta de novo quando a conexão volta; não sincroniza sem usuário logado.
- [ ] **Passo 2:** Ver falhar, implementar, ver passar.
- [ ] **Passo 3:** Implementar `FirestoreRemoteStore` seguindo as chaves da Tarefa 5 (sem teste na JVM; coberto pelo roteiro manual da Tarefa 13 e pelas regras da Tarefa 5). Commit `Sincronização com o Firestore`.

---

### Tarefa 8: `AuthRepository` e estado de login

**Arquivos:**
- Criar: `app/.../account/AuthGateway.kt` (interface), `FirebaseAuthGateway.kt`, `AuthRepository.kt`, `AccountViewModel.kt`
- Testar: `app/src/test/.../AuthRepositoryTest.kt` (com `FakeAuthGateway`)

**Interfaces:**
- Produz:
  ```kotlin
  sealed interface AuthState { object Guest : AuthState; data class SignedIn(val uid: String, val name: String, val email: String?, val verified: Boolean) : AuthState }
  sealed interface AuthResult { object Ok : AuthResult; data class Error(val kind: AuthError, val message: String) : AuthResult }
  enum class AuthError { NETWORK, WRONG_CREDENTIALS, EMAIL_IN_USE, WEAK_PASSWORD, NEEDS_RECENT_LOGIN, UNAVAILABLE, CANCELLED, OTHER }
  interface AuthGateway { suspend fun signInGoogle(): AuthResult; suspend fun signUpEmail(name: String, email: String, password: String): AuthResult; suspend fun signInEmail(email: String, password: String): AuthResult; suspend fun resetPassword(email: String): AuthResult; suspend fun sendVerification(): AuthResult; suspend fun reauthenticate(password: String?): AuthResult; suspend fun deleteAuthUser(): AuthResult; fun signOut(); val state: StateFlow<AuthState> }
  ```
  `AuthRepository(gateway, engine)`: `signInGoogle`/`signInEmail`/`signUpEmail` chamam `engine.firstSync()` após o sucesso (se falhar por rede, a conta fica logada e a sincronização tenta depois); `signOut()` só chama `gateway.signOut()` depois de `engine.signOutWipe()` e devolve `AuthError`-like `SignOutBlocked` se `canSignOut()` for falso; `deleteAccount(password: String?)`.
- [ ] **Passo 1:** Testes: `login com sucesso dispara a primeira sincronização`; `primeiro login sem internet continua logado e não trava (resultado em poucos segundos)`; `cadastro com e-mail envia verificação e define o nome`; `Google e senha com o mesmo e-mail pedem a senha uma vez e vinculam`; `sair com pendência é bloqueado e não limpa`; `apagar conta pede reautenticação quando o Firebase exige (NEEDS_RECENT_LOGIN) e continua depois`; `apagar conta: ordem é marcar pendente, apagar dados, apagar o usuário`; `Firebase indisponível devolve UNAVAILABLE sem travar`.
- [ ] **Passo 2:** Ver falhar, implementar, ver passar. Implementar `FirebaseAuthGateway` com Credential Manager (`GetGoogleIdOption`, sem pedir contas autorizadas na primeira vez) e Firebase Auth. Commit `Conta: login Google e e-mail/senha`.

---

### Tarefa 9: Fluxo da primeira abertura e tela de apresentação

**Arquivos:**
- Criar: `sync-core/.../OnboardingFlow.kt`, `app/.../ui/onboarding/OnboardingScreen.kt`, `app/.../ui/onboarding/IntroPages.kt`
- Testar: `sync-core/src/test/.../OnboardingFlowTest.kt`

**Interfaces:**
- Produz: `enum class Step { INTRO, LOGIN, PERMISSIONS, BIKE, DONE }` e `class OnboardingFlow(private val seen: Boolean, private val hasBikeInAccount: Boolean)` com `fun first(): Step`, `fun next(from: Step, signedIn: Boolean, skippedLogin: Boolean): Step`, `fun shortBike(signedIn: Boolean): Boolean`.

- [ ] **Passo 1:** Testes: `primeira vez começa na apresentação`; `quem já viu vai direto a DONE`; `Pular na apresentação vai ao login`; `Pular o login vai às permissões e depois ao cadastro curto`; `login com conta que já tem moto pula o cadastro`; `login sem moto leva ao cadastro completo`; `reabrir a apresentação pelo menu de ajuda não repete o login se já está logado`.
- [ ] **Passo 2:** Ver falhar, implementar, ver passar.
- [ ] **Passo 3:** Telas de apresentação conforme os mockups: quatro páginas (navegação; viagens e garagem; rolê com amigos; conexão com o painel), "Pular" e "Continuar", indicador de página. Preferência `onboarding_seen` gravada ao chegar em DONE. Commit `Apresentação do app`.

---

### Tarefa 10: Tela de login e tela de permissões

**Arquivos:**
- Criar: `app/.../ui/onboarding/LoginScreen.kt`, `PermissionsScreen.kt`, `PermissionPlan.kt`
- Testar: `app/src/test/.../PermissionPlanTest.kt`

**Interfaces:**
- Consome: `AccountViewModel`, `AuthResult` (Tarefa 8); `rememberPermissionAction` de `Components.kt`.
- Produz: `object PermissionPlan { fun steps(sdk: Int): List<PermStep> }` com `data class PermStep(val id: String, val permissions: List<String>, val title: String, val reason: String)`: localização precisa/aproximada; Bluetooth (`BLUETOOTH_SCAN` e `BLUETOOTH_CONNECT` só do Android 12 em diante); notificações (`POST_NOTIFICATIONS` só do Android 13 em diante). Nunca inclui localização em segundo plano.

- [ ] **Passo 1:** `PermissionPlanTest`: `Android 8 pede só localização`; `Android 12 inclui Bluetooth`; `Android 13 inclui notificações`; `nenhuma etapa contém ACCESS_BACKGROUND_LOCATION`.
- [ ] **Passo 2:** Ver falhar, implementar, ver passar.
- [ ] **Passo 3:** Tela de login: "Continuar com Google", e-mail e senha (com criar conta, nome obrigatório, "esqueci a senha", mensagem de e-mail de confirmação enviado) e "Pular". Erros mostrados em português por `AuthError`; sem internet, mensagem curta e botão de tentar de novo, sem travar (Revisão 1).
- [ ] **Passo 4:** Tela de permissões em sequência, com "Agora não" em cada uma e, quando o Android negar sem poder perguntar de novo, um botão que abre as configurações do app; nada disso bloqueia o fluxo (Revisão 5). Commit `Login e permissões`.

---

### Tarefa 11: Cadastro da moto (completo e curto)

**Arquivos:**
- Criar: `app/.../ui/onboarding/BikeSetupScreen.kt`, `BikeForm.kt`
- Testar: `app/src/test/.../BikeFormTest.kt`

**Interfaces:**
- Consome: `BikeCatalog`, `UserProfile`, `OdometerEntity`/`LocalStore.applyOdometer`.
- Produz: `class BikeForm(val short: Boolean)` com campos `brand`, `model`, `year`, `odometerKm`, `tankLiters` e `fun validate(): List<FormError>`; `fun toProfile(name: String): UserProfile`; `fun toOdometer(nowMs: Long): OdometerDoc`.

- [ ] **Passo 1:** `BikeFormTest`: `completo exige marca, modelo e km`; `curto exige só km e tanque`; `Outra aceita modelo em texto livre`; `km negativo ou vazio é recusado`; `tanque padrão da marca/modelo sugerido (Hunter 350 = 13 L) e editável`; `ano fora de 1950 até o ano atual é recusado`.
- [ ] **Passo 2:** Ver falhar, implementar, ver passar.
- [ ] **Passo 3:** Tela com seletor de marca, modelo, km atual, ano (opcional) e tanque; ao salvar grava o hodômetro e o perfil como sujos e pede sincronização. Commit `Cadastro da moto`.

---

### Tarefa 12: Perfil, sair e apagar conta

**Arquivos:**
- Criar: `app/.../ui/ProfileScreen.kt`
- Modificar: `app/.../MainActivity.kt` (acesso ao perfil, por exemplo pelo cabeçalho da aba Garagem), `app/.../ui/GarageScreen.kt` ou onde couber o aviso
- Testar: `app/src/test/.../ProfileUiStateTest.kt`

**Interfaces:**
- Consome: `AuthRepository`, `SyncEngine`.
- Produz: `fun profileUi(state: AuthState, pendingSync: Boolean): ProfileUi` com o que mostrar: visitante com aviso "sem conta, desinstalar apaga tudo" e botão Entrar; logado com nome, e-mail, aviso de e-mail não confirmado e reenvio; `Sair` (confirmação com botão principal "Cancelar" e o texto sobre os trajetos só locais; se houver pendência, mostra "ainda sincronizando, tente de novo com internet" e desabilita); `Apagar meus dados e minha conta` (confirmação forte, digitar "APAGAR", reautenticação quando preciso, progresso, retoma se interrompido); "Rever apresentação".

- [ ] **Passo 1:** `ProfileUiStateTest` cobre os estados: visitante; logado sem pendência; logado com pendência (sair desabilitado); e-mail não confirmado; exclusão pendente retomando.
- [ ] **Passo 2:** Ver falhar, implementar, ver passar.
- [ ] **Passo 3:** Telas e confirmações. Ao abrir o app, `resumePendingDeletion()` roda antes de tudo (Revisão 4). Commit `Perfil, sair e apagar conta`.

---

### Tarefa 13: Ligar tudo, documentação e roteiro de teste manual

**Arquivos:**
- Modificar: `app/.../MainActivity.kt` (rotear pela `OnboardingFlow` antes do `HunterRoot`), `app/.../HunterViewModel.kt` (disparar `requestSync()` após alterações e ao terminar viagem), `README.md`, `CHANGELOG.md`, `docs/ARQUITETURA.md`, `version.txt` (para `0.4`, pois muda o app inteiro)
- Criar: `docs/TESTES-MANUAIS-CONTA.md`

- [ ] **Passo 1:** Rotear a primeira abertura. Quem já usa o app (instalação anterior com dados) **não** vê apresentação nem login forçado: entra direto no mapa com um aviso discreto para criar conta e proteger os dados. Detectar pela existência de dados locais ou de `seeded` nas preferências.
- [ ] **Passo 2:** Disparar `requestSync()` nas alterações de abastecimento, manutenção, hodômetro, favoritos e ao final de cada viagem; nada disso pode bloquear a interface.
- [ ] **Passo 3:** Escrever `docs/TESTES-MANUAIS-CONTA.md` com o roteiro do spec: instalar do zero; Google; e-mail; usar sem conta e depois criar conta; desinstalar e reinstalar e recuperar; modo avião; sair com e sem pendência; apagar conta; atualizar por cima da versão atual sem perder nada; dois aparelhos editando o mesmo abastecimento.
- [ ] **Passo 4:** Atualizar README, CHANGELOG e arquitetura (novo módulo `sync-core`, Firebase, versões 0.4).
- [ ] **Passo 5:** `./gradlew.bat test :app:assembleRelease --console=plain -q` passa; subir e conferir o CI verde. Commit `Conta e sincronização: integração e documentação`.

---

## Verificação do plano contra o spec

| Requisito do spec | Tarefa |
|---|---|
| Quatro telas de apresentação, "Pular", só na primeira vez, revisável | 9, 12 |
| Login Google e e-mail/senha, esqueci a senha, confirmação | 8, 10 |
| "Pular" leva ao mapa; entrar depois | 9, 12, 13 |
| Cadastro da moto completo e curto | 11 |
| Dados na conta (perfil, moto, hodômetro, abastecimentos, manutenção, locais, resumo de viagem, voz) | 4, 5, 6, 7 |
| Trajeto completo só no aparelho; viagem restaurada sem mapa | 6 |
| Funciona sem internet | 4, 7, 8 |
| Apagar conta, exclusão retomável | 4, 5, 8, 12 |
| Sair da conta sem apagar a nuvem; só limpa se tudo subiu | 4, 8, 12 |
| Visitante que cria conta mantém os dados; mescla sem duplicar | 3, 4 |
| Aviso claro para visitante | 12, 13 |
| Regras do Firestore e testes | 5 |
| Plano gratuito; sem Storage/Functions | restrições, 5, 7 |
| Permissões sem segundo plano | 10 |
| Migração sem perder dados dos usuários atuais | 6, 13 |
