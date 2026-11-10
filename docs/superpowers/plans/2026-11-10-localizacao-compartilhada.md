# Localização Compartilhada com Amigos — Plano de Implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Permitir que motociclistas compartilhem a localização em tempo real da moto com amigos autorizados seletivamente, transmitindo exclusivamente enquanto a moto estiver conectada via Bluetooth (Tripper Pod) e exibindo a posição no mapa e na aba Amigos sob as cotas gratuitas do plano Spark do Firestore.

**Architecture:** A autorização seletiva é mantida na coleção `permissoesLocalizacao/{deUid_paraUid}` e a coordenada ativa em `localizacoes/{uid}`, blindadas por regras do Firestore sem Cloud Functions. Um publicador reativo (`LiveLocationPublisher`) no módulo `app` monitora `PodStatus` e GPS (`LocationSource`), emitindo atualizações espaçadas (>= 30 s ou 100 m) apenas quando o Pod está conectado e removendo o registro na desconexão. O `HunterMap` e a `FriendsScreen` observam os amigos com permissão ativa em primeiro plano.

**Tech Stack:** Kotlin 2.2.20, Jetpack Compose, Coroutines/Flow, Room v3, MapLibre, Firebase Firestore (Spark), Node.js `@firebase/rules-unit-testing`.

**Spec:** `docs/superpowers/specs/2026-11-10-localizacao-compartilhada-design.md`

## Global Constraints

- Kotlin 2.2.20, AGP 8.13.2, Compose BOM 2025.10.00.
- JAVA_HOME configurado para `C:\Program Files\Android\Android Studio\jbr`.
- Idioma obrigatório de todas as strings e mensagens de interface: `pt-BR`.
- Sem Cloud Functions e sem FCM/push em background; segurança 100% garantida em `firebase/firestore.rules`.
- Sem permissão de localização em segundo plano (`ACCESS_BACKGROUND_LOCATION`); reuso de serviços em primeiro plano (`PodService` e `TrackingService`) com notificação ativa.
- Taxa de escrita no Firestore limitada a no máximo 1 a cada 30 segundos ou 100 metros de deslocamento.
- Sem listeners em coleções inteiras (`localizacoes/**`); leitura restrita aos documentos pontuais dos amigos que concederam permissão ao usuário.
- Exclusão de conta (`deleteAllUserData`) deve remover a localização ativa e todas as permissões enviadas e recebidas.

## Review Focus

1. **Desconexão abrupta do Pod ou desligamento do celular:** Se o celular desligar antes de chamar `clearLocation()`, o leitor deve ignorar documentos com `updatedAtMs` superior a 5 minutos, não exibindo a moto no mapa. (Testado na Task 2).
2. **Revogação imediata de amizade:** Se o usuário desfizer a amizade enquanto a moto do outro está transmitindo, a regra do Firestore deve bloquear imediatamente leituras subsequentes, mesmo se a permissão individual ainda constar no banco. (Testado na Task 1).
3. **Moto desconectada com permissão concedida:** Se o usuário autorizar o amigo mas o Pod estiver desconectado (`PodPhase != CONNECTED`), o `LiveLocationPublisher` não deve publicar nenhuma coordenada. (Testado na Task 4).
4. **Tentativa de concessão para quem não é amigo aceito:** A regra do Firestore deve rejeitar a criação de `permissoesLocalizacao/{de_para}` se não existir amizade mútua confirmada em `amizades/{par}`. (Testado na Task 1).
5. **Apagar a conta com dados ativos de localização:** O método `deleteAllUserData` deve limpar `localizacoes/{uid}` e remover as permissões onde o usuário é remetente ou destinatário, sem deixar registros órfãos. (Testado na Task 3).

---

### Task 1: Regras do Firestore para Localização e Permissões

**Files:**
- Modify: `firebase/firestore.rules:200-260`
- Create: `firebase/test/location.rules.test.js`

**Interfaces:**
- Consumes: funções existentes `dono(uid)`, `logado()`, `amigos(a, b)`, `semExclusao(uid)`, `epochMsOk(x)`.
- Produces: regras de segurança para `permissoesLocalizacao/{id}` e `localizacoes/{uid}`.

- [ ] **Step 1: Escrever teste falhando das regras do Firestore no emulador**

Criar `firebase/test/location.rules.test.js` com cenários:
- Dono lê, cria e apaga a própria localização em `localizacoes/{uid}` com dados válidos.
- Estranho tenta ler `localizacoes/{uid}` -> falha.
- Amigo tenta ler `localizacoes/{uid}` sem permissão em `permissoesLocalizacao/{uid_amigo}` -> falha.
- Dono cria permissão para quem não é amigo -> falha.
- Dono cria permissão para amigo -> sucesso.
- Amigo com permissão e amizade ativa lê `localizacoes/{uid}` -> sucesso.
- Remetente ou destinatário apaga a permissão -> sucesso; leitura subsequente da localização falha.
- Desfazer amizade -> leitura de `localizacoes/{uid}` falha imediatamente.
- Usuário com `usuarios/{uid}/config/exclusao` tenta gravar localização -> falha.

- [ ] **Step 2: Executar teste para verificar que falha**

Run: `cd firebase && npx firebase-tools emulators:exec --only firestore --project demo-rumo "node --test test/location.rules.test.js"`
Expected: FAIL (regras não existem / permission-denied).

- [ ] **Step 3: Implementar regras em `firebase/firestore.rules`**

Adicionar regras para:
```
match /permissoesLocalizacao/{id} {
  allow create: if logado()
    && request.resource.data.keys().hasAll(['de', 'para'])
    && request.resource.data.keys().hasOnly(['de', 'para', 'criadoEm'])
    && request.resource.data.de == request.auth.uid
    && request.resource.data.de != request.resource.data.para
    && amigos(request.resource.data.de, request.resource.data.para)
    && id == request.resource.data.de + '_' + request.resource.data.para
    && epochMsOpcional(request.resource.data, 'criadoEm');
  allow read, delete: if logado()
    && (request.auth.uid == resource.data.de || request.auth.uid == resource.data.para);
}

match /localizacoes/{uid} {
  allow read: if dono(uid)
    || (logado() && amigos(uid, request.auth.uid)
        && exists(/databases/$(database)/documents/permissoesLocalizacao/$(uid + '_' + request.auth.uid)));
  allow create, update: if dono(uid) && semExclusao(uid) && localizacaoValida(request.resource.data);
  allow delete: if dono(uid);
}
```
Com validação de `localizacaoValida(d)` checando `latitude` (-90..90), `longitude` (-180..180), `bearing` (0..360 ou nulo), `speedKmh` (>=0 ou nulo) e `updatedAtMs` (epoch recente).

- [ ] **Step 4: Executar testes de regras para verificar que passam**

Run: `cd firebase && npx firebase-tools emulators:exec --only firestore --project demo-rumo "npm test"`
Expected: PASS (todos os testes de regras, incluindo `location.rules.test.js`, passam).

- [ ] **Step 5: Commit**

```bash
git add firebase/firestore.rules firebase/test/location.rules.test.js
git commit -m "feat(firebase): regras e testes para localizacao e permissoes"
```

---

### Task 2: Modelos e Contrato em `sync-core` (JVM Puro)

**Files:**
- Modify: `sync-core/src/main/kotlin/app/hunter/social/SocialModels.kt`
- Modify: `sync-core/src/main/kotlin/app/hunter/social/SocialStore.kt`
- Create: `sync-core/src/test/kotlin/app/hunter/social/LiveLocationTest.kt`

**Interfaces:**
- Consumes: classes base de `app.hunter.social`.
- Produces: data classes `LiveLocation`, `LocationPermission` e métodos no `SocialStore`:
  - `grantLocationPermission(friendUid: String)`
  - `revokeLocationPermission(friendUid: String)`
  - `observeGrantedPermissions(myUid: String): Flow<Set<String>>`
  - `observeReceivedPermissions(myUid: String): Flow<Set<String>>`
  - `publishLocation(loc: LiveLocation)`
  - `clearLocation()`
  - `observeFriendLocation(friendUid: String): Flow<LiveLocation?>`

- [ ] **Step 1: Escrever teste falhando para `LiveLocation` em `sync-core`**

Criar `sync-core/src/test/kotlin/app/hunter/social/LiveLocationTest.kt`:
- Testar cálculo de validade/expiração: `loc.isExpired(nowMs, maxAgeMs = 5 * 60 * 1000L)`.
- Testar validação de coordenadas (`isValidCoordinates()` para latitude e longitude).
- Testar formação do ID do par de permissão `LocationPermission.docId(de, para)`.

- [ ] **Step 2: Executar teste para verificar que falha**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; ./gradlew.bat :sync-core:test --tests app.hunter.social.LiveLocationTest`
Expected: FAIL (classes ou métodos não encontrados).

- [ ] **Step 3: Implementar data classes e assinaturas em `SocialModels.kt` e `SocialStore.kt`**

- Em `SocialModels.kt`:
  ```kotlin
  data class LiveLocation(
      val latitude: Double,
      val longitude: Double,
      val bearing: Float? = null,
      val speedKmh: Float? = null,
      val updatedAtMs: Long = System.currentTimeMillis(),
  ) {
      fun isExpired(nowMs: Long = System.currentTimeMillis(), maxAgeMs: Long = 5 * 60 * 1000L): Boolean =
          nowMs - updatedAtMs > maxAgeMs
      fun isValid(): Boolean =
          latitude in -90.0..90.0 && longitude in -180.0..180.0
  }

  data class LocationPermission(
      val de: String,
      val para: String,
      val criadoEm: Long = System.currentTimeMillis(),
  ) {
      companion object {
          fun docId(de: String, para: String): String = "${de}_$para"
      }
  }
  ```
- Em `SocialStore.kt`:
  Adicionar as novas operações de permissão e localização no contrato.

- [ ] **Step 4: Executar testes de `sync-core` para verificar que passam**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; ./gradlew.bat :sync-core:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add sync-core/src/main/kotlin/app/hunter/social/SocialModels.kt sync-core/src/main/kotlin/app/hunter/social/SocialStore.kt sync-core/src/test/kotlin/app/hunter/social/LiveLocationTest.kt
git commit -m "feat(sync-core): modelos e contrato de localizacao compartilhada"
```

---

### Task 3: Implementação no Firestore (`FirestoreSocialStore` e exclusão de conta)

**Files:**
- Modify: `app/src/main/kotlin/app/hunter/app/social/FirestoreSocialStore.kt`
- Modify: `app/src/main/kotlin/app/hunter/app/account/FirestoreRemoteStore.kt`
- Create/Modify: `app/src/test/kotlin/app/hunter/app/social/FirestoreSocialStoreMappingTest.kt`

**Interfaces:**
- Consumes: `SocialStore`, `LiveLocation`, `LocationPermission` de `sync-core`.
- Produces: implementação concreta das operações no Firestore e limpeza em `deleteAllUserData`.

- [ ] **Step 1: Escrever teste de mapeamento e serialização de `LiveLocation`**

Criar teste unitário em `app/src/test/kotlin/app/hunter/app/social/FirestoreSocialStoreMappingTest.kt` verificando serialização de `LiveLocation` para Map do Firestore e desserialização a partir de Map, garantindo tipos seguros de números (`Double`, `Float`, `Long`).

- [ ] **Step 2: Executar teste para verificar que falha**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; ./gradlew.bat :app:testDebugUnitTest --tests app.hunter.app.social.FirestoreSocialStoreMappingTest`
Expected: FAIL.

- [ ] **Step 3: Implementar métodos no `FirestoreSocialStore` e `FirestoreRemoteStore`**

- Em `FirestoreSocialStore.kt`:
  - `grantLocationPermission(friendUid)`: grava doc em `permissoesLocalizacao/${myUid}_$friendUid`.
  - `revokeLocationPermission(friendUid)`: deleta doc em `permissoesLocalizacao/${myUid}_$friendUid`.
  - `observeGrantedPermissions(myUid)`: `collection("permissoesLocalizacao").whereEqualTo("de", myUid).snapshots()` mapeado para `Set<String>` com UIDs dos amigos.
  - `observeReceivedPermissions(myUid)`: `collection("permissoesLocalizacao").whereEqualTo("para", myUid).snapshots()` mapeado para `Set<String>` com UIDs de quem nos autorizou.
  - `publishLocation(loc)`: `collection("localizacoes").document(myUid).set(map)`.
  - `clearLocation()`: `collection("localizacoes").document(myUid).delete()`.
  - `observeFriendLocation(friendUid)`: `collection("localizacoes").document(friendUid).snapshots()`.
- Em `FirestoreRemoteStore.kt` (`deleteAllUserData`):
  - Adicionar exclusão do documento `localizacoes/{uid}`.
  - Adicionar consulta e exclusão em lote de `permissoesLocalizacao` onde `de == uid` ou `para == uid`.

- [ ] **Step 4: Executar testes para verificar que passam**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; ./gradlew.bat :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/app/hunter/app/social/FirestoreSocialStore.kt app/src/main/kotlin/app/hunter/app/account/FirestoreRemoteStore.kt app/src/test/kotlin/app/hunter/app/social/FirestoreSocialStoreMappingTest.kt
git commit -m "feat(app): integracao do FirestoreSocialStore e limpeza na exclusao de conta"
```

---

### Task 4: Publicador Reativo de Localização (`LiveLocationPublisher`)

**Files:**
- Create: `app/src/main/kotlin/app/hunter/app/social/LiveLocationPublisher.kt`
- Modify: `app/src/main/kotlin/app/hunter/app/social/SocialGraph.kt`
- Create: `app/src/test/kotlin/app/hunter/app/social/LiveLocationPublisherTest.kt`

**Interfaces:**
- Consumes: `PodStatus.state`, `SocialStore`, `LocationSource` (`AndroidLocationSource`).
- Produces: componente `LiveLocationPublisher` que gerencia o ciclo de vida da publicação de localização.

- [ ] **Step 1: Escrever teste unitário para `LiveLocationPublisher`**

Criar `app/src/test/kotlin/app/hunter/app/social/LiveLocationPublisherTest.kt`:
- Quando `PodStatus` for `IDLE` ou `SCANNING`: não publica coordenadas mesmo com permissões concedidas.
- Quando `PodStatus` for `CONNECTED` e `grantedPermissions` estiver vazio: não publica coordenadas.
- Quando `PodStatus` for `CONNECTED` e `grantedPermissions` contiver amigos: emite coordenadas com intervalo mínimo de 30s.
- Quando `PodStatus` transitar de `CONNECTED` para `IDLE`: dispara `clearLocation()`.

- [ ] **Step 2: Executar teste para verificar que falha**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; ./gradlew.bat :app:testDebugUnitTest --tests app.hunter.app.social.LiveLocationPublisherTest`
Expected: FAIL.

- [ ] **Step 3: Implementar `LiveLocationPublisher` e integrar ao `SocialGraph`**

- `LiveLocationPublisher`:
  - Recebe `scope`, `socialStore`, `locationSource`, `podStatusFlow`, `grantedPermissionsFlow`, `myUidProvider`.
  - Combina o estado do Pod e as permissões. Ao detectar condição ativa, coleta pontos de GPS e filtra: só envia se `now - lastSentMs >= 30_000L` ou distância percorrida > 100m.
  - Ao sair da condição ativa (desconexão ou revogação): cancela job e executa `socialStore.clearLocation()`.
- Em `SocialGraph.kt`: instanciar e inicializar o `LiveLocationPublisher` no ciclo da sessão do usuário autenticado.

- [ ] **Step 4: Executar testes para verificar que passam**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; ./gradlew.bat :app:testDebugUnitTest --tests app.hunter.app.social.LiveLocationPublisherTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/app/hunter/app/social/LiveLocationPublisher.kt app/src/main/kotlin/app/hunter/app/social/SocialGraph.kt app/src/test/kotlin/app/hunter/app/social/LiveLocationPublisherTest.kt
git commit -m "feat(app): publicador reativo de localizacao atrelado ao Pod BLE"
```

---

### Task 5: Interface na Aba Amigos (`FriendsScreen` e `SocialViewModel`)

**Files:**
- Modify: `app/src/main/kotlin/app/hunter/app/social/SocialViewModel.kt`
- Modify: `app/src/main/kotlin/app/hunter/app/social/SocialUiMapper.kt`
- Modify: `app/src/main/kotlin/app/hunter/app/ui/FriendsScreen.kt`
- Create: `app/src/test/kotlin/app/hunter/app/social/SocialViewModelLocationTest.kt`

**Interfaces:**
- Consumes: `SocialStore.observeGrantedPermissions`, `SocialStore.observeReceivedPermissions`, `SocialStore.observeFriendLocation`.
- Produces: estado na UI de permissão compartilhada por amigo e status "Na estrada" com ação de focar no mapa.

- [ ] **Step 1: Escrever teste para o ViewModel e mapeador de UI**

Criar `app/src/test/kotlin/app/hunter/app/social/SocialViewModelLocationTest.kt`:
- Verificar que `toggleLocationSharing(friendUid)` chama `grantLocationPermission` ou `revokeLocationPermission`.
- Verificar que amigos que nos concederam permissão e têm localização recente (< 5 min) aparecem com `isLiveOnRoad == true`.

- [ ] **Step 2: Executar teste para verificar que falha**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; ./gradlew.bat :app:testDebugUnitTest --tests app.hunter.app.social.SocialViewModelLocationTest`
Expected: FAIL.

- [ ] **Step 3: Implementar lógica no `SocialViewModel` e UI em `FriendsScreen`**

- Em `SocialViewModel.kt`:
  - Expor `grantedPermissions: StateFlow<Set<String>>` e `activeFriendsOnRoad: StateFlow<Map<String, LiveLocation>>`.
  - Adicionar métodos `toggleLocationSharing(friendUid: String)` e evento de navegação `focusFriendOnMap(lat, lng)`.
- Em `FriendsScreen.kt`:
  - No item de cada amigo:
    - Indicador visual "Na estrada" (chip/badge destacado) quando o amigo estiver transmitindo ativamente, com botão "Ver no mapa".
    - Switch ou botão de alternância discreto: "Compartilhar minha localização com este amigo" (ativado/desativado).

- [ ] **Step 4: Executar testes para verificar que passam**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; ./gradlew.bat :app:testDebugUnitTest --tests app.hunter.app.social.SocialViewModelLocationTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/app/hunter/app/social/SocialViewModel.kt app/src/main/kotlin/app/hunter/app/social/SocialUiMapper.kt app/src/main/kotlin/app/hunter/app/ui/FriendsScreen.kt app/src/test/kotlin/app/hunter/app/social/SocialViewModelLocationTest.kt
git commit -m "feat(app): controles de permissao e status na estrada na aba Amigos"
```

---

### Task 6: Visualização no Mapa (`HunterMap` e `MapScreen`)

**Files:**
- Modify: `app/src/main/kotlin/app/hunter/app/ui/HunterMap.kt`
- Modify: `app/src/main/kotlin/app/hunter/app/ui/MapScreen.kt`
- Modify: `app/src/main/kotlin/app/hunter/app/ui/HunterViewModel.kt`
- Create: `app/src/test/kotlin/app/hunter/app/ui/FriendMarkerFormattingTest.kt`

**Interfaces:**
- Consumes: amigos ativos e coordenadas de `SocialViewModel` / `HunterViewModel`.
- Produces: marcadores de moto no MapLibre sobrepostos no mapa com nome e indicador de tempo.

- [ ] **Step 1: Escrever teste de formatação de marcador e tempo decorrido**

Criar `app/src/test/kotlin/app/hunter/app/ui/FriendMarkerFormattingTest.kt`:
- Formatação de tempo decorrido: "agora" (< 30s), "há 1 min", "há 3 min".
- Ignora marcadores com tempo > 5 min.

- [ ] **Step 2: Executar teste para verificar que falha**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; ./gradlew.bat :app:testDebugUnitTest --tests app.hunter.app.ui.FriendMarkerFormattingTest`
Expected: FAIL.

- [ ] **Step 3: Implementar marcadores no `HunterMap` e navegação no `MapScreen`**

- Em `HunterMap.kt`:
  - Adicionar suporte a marcadores de amigos (`FriendMarker(uid, name, lat, lng, bearing, text)`).
  - Desenhar ícone de moto com callout contendo o nome do amigo e status.
  - Atualizar posições suavemente conforme novas coordenadas chegam.
- Em `MapScreen.kt`:
  - Conectar ao fluxo de amigos autorizados ativos somente enquanto a tela do mapa estiver em primeiro plano (Lifecycle-aware).
  - Suportar intent/evento de focar em coordenadas específicas quando acionado via "Ver no mapa" da aba Amigos.

- [ ] **Step 4: Executar testes para verificar que passam**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; ./gradlew.bat :app:testDebugUnitTest --tests app.hunter.app.ui.FriendMarkerFormattingTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/app/hunter/app/ui/HunterMap.kt app/src/main/kotlin/app/hunter/app/ui/MapScreen.kt app/src/main/kotlin/app/hunter/app/ui/HunterViewModel.kt app/src/test/kotlin/app/hunter/app/ui/FriendMarkerFormattingTest.kt
git commit -m "feat(app): marcadores de amigos na estrada no mapa principal"
```

---

### Task 7: Roteiro de Testes Manuais, Documentação e Validação Completa

**Files:**
- Create: `docs/TESTES-MANUAIS-LOCALIZACAO.md`
- Modify: `docs/ARQUITETURA.md`
- Modify: `docs/FIREBASE.md`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: todo o sistema implementado.
- Produces: documentação do Firebase, roteiro de validação com 2 aparelhos e release pronta.

- [ ] **Step 1: Escrever o roteiro de testes manuais com dois aparelhos**

Criar `docs/TESTES-MANUAIS-LOCALIZACAO.md` contendo o passo a passo completo:
1. Preparação: Aparelho A (Usuário Ana) e Aparelho B (Usuário Beto), ambos amigos aceitos.
2. Permissão seletiva: Ana autoriza Beto na aba Amigos; Beto não autoriza Ana.
3. Moto desconectada: Ana sem Pod conectado -> Beto não vê marcador nem status de Ana.
4. Conexão da moto: Ana conecta o Tripper Pod (ou simula PodPhase.CONNECTED) -> Beto vê badge "Na estrada" na aba Amigos e marcador de moto com nome de Ana no mapa.
5. Desconexão da moto: Ana desliga o Pod -> marcador no mapa de Beto desaparece imediatamente.
6. Revogação: Ana revoga permissão de Beto -> Beto não vê mais Ana mesmo com moto conectada.
7. Desfazer amizade / Bloqueio: remove permissão e impede acesso.
8. Exclusão de conta: Ana apaga a conta -> `localizacoes` e `permissoesLocalizacao` são limpos do Firestore.

- [ ] **Step 2: Atualizar `docs/ARQUITETURA.md`, `docs/FIREBASE.md` e `CHANGELOG.md`**

- Em `docs/ARQUITETURA.md`: documentar o fluxo de `LiveLocationPublisher`, coleções `permissoesLocalizacao` e `localizacoes`, e o ciclo de vida da transmissão.
- Em `docs/FIREBASE.md`: documentar as novas coleções e o comando de deploy das regras (`firebase deploy --only firestore:rules`).
- Em `CHANGELOG.md`: documentar a entrega da versão 0.5.2 (Localização compartilhada com amigos).

- [ ] **Step 3: Executar a suíte completa de compilação e testes**

Run:
1. `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; ./gradlew.bat :sync-core:test :app:testDebugUnitTest :app:assembleRelease --console=plain -q`
2. `cd firebase && npx firebase-tools emulators:exec --only firestore --project demo-rumo "npm test"`
Expected: Todos os testes passam e o APK de release compila com sucesso.

- [ ] **Step 4: Commit**

```bash
git add docs/TESTES-MANUAIS-LOCALIZACAO.md docs/ARQUITETURA.md docs/FIREBASE.md CHANGELOG.md
git commit -m "docs: documentacao e roteiro de testes manuais da localizacao compartilhada"
```
