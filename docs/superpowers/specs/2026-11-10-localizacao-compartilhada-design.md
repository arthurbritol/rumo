# Localização compartilhada com amigos no Rumo — desenho

Projeto 3 de 7. O projeto 1 (conta, login, sincronização dos dados privados) e o projeto 2 (amigos, convites,
km da semana e rolê em grupo) já foram entregues (releases 0.4.x e 0.5.x). Este projeto adiciona a localização
compartilhada em tempo real com amigos autorizados enquanto a moto estiver conectada via Bluetooth (Tripper Pod).

Fora deste documento: catálogo de conexões por marca (projeto 4), pessoas por perto (projeto 5), preços de postos (projeto 6),
Play Store (projeto 7) e a rodada de estilização/ícones de telas (após o projeto 3).

---

## 1. Por que

Motociclistas amigos querem acompanhar onde os parceiros de estrada estão durante viagens e passeios para saber se o
amigo está bem, onde está no trajeto ou se está chegando ao ponto de encontro. A privacidade precisa ser absoluta:
controle seletivo amigo por amigo, e transmissão estritamente restrita ao período em que a moto estiver ligada e
conectada ao celular via Bluetooth. Moto desligada ou desconectada = nada compartilhado.

---

## 2. Objetivos

1. **Permissão seletiva amigo por amigo**:
   - Cada usuário escolhe explicitamente para quais amigos concede permissão de ver sua localização.
   - Sem permissão concedida, o amigo continua vendo apenas a marca/modelo da moto e os km da semana (como no projeto 2).
   - O usuário pode conceder e revogar a permissão a qualquer momento na aba Amigos.
2. **Transmissão vinculada à moto (Tripper Pod)**:
   - A localização só é transmitida para a nuvem se a moto estiver conectada via Bluetooth (`PodStatus.state.value.phase == PodPhase.CONNECTED`) **e** houver pelo menos um amigo autorizado.
   - Assim que o Pod desconecta (ignição desligada, afastamento ou desconexão manual), a transmissão para imediatamente e o registro ativo no Firestore é apagado pelo cliente.
3. **Corte imediato de acesso**:
   - Revogar permissão, desfazer amizade, bloquear o amigo ou apagar a conta encerra instantaneamente o compartilhamento e bloqueia o acesso via regras de segurança do Firestore.
4. **Visualização integrada (Opção 1 aprovada)**:
   - **No mapa principal (`HunterMap` / `MapScreen`)**: se houver amigos autorizados com a moto conectada na estrada, exibe marcador com ícone de moto, nome do amigo e indicador de atualização recente. O mapa só escuta amigos que nos autorizaram enquanto a tela do mapa estiver visível (Lifecycle resumed).
   - **Na aba Amigos (`FriendsScreen`)**: ao lado de cada amigo, exibe indicador de status ("Na estrada" com atalho "Ver no mapa" se ele estiver pilotando) e um botão/switch individual para "Compartilhar minha localização".
5. **Apagar a conta**:
   - O fluxo de exclusão de conta (`deleteAllUserData`) remove o documento de localização e todas as permissões de localização enviadas e recebidas.
6. **Segurança no Firestore (Plano Spark)**:
   - Sem Cloud Functions. Toda a autorização é garantida por regras em `firebase/firestore.rules`, testadas no emulador.
   - Baixo consumo de cotas: envio espaçado (a cada ~30 s ou 100 m de deslocamento em movimento) e listeners apenas de documentos específicos de amigos que nos autorizaram.

---

## 3. Fora do escopo

- Descobrir ou ver quem está por perto sem ser amigo aceito (isso é o Projeto 5).
- Histórico de trajeto de amigos (apenas o ponto atual/último ponto recente é compartilhado; o histórico de GPS continua estritamente local no aparelho de quem grava a viagem).
- Notificações push em segundo plano (sem servidor nem FCM pago).
- Compartilhamento de localização com o Pod desconectado (nem mesmo como opção manual, respeitando a premissa do produto).
- Compartilhamento em grupo de rolê sem permissão individual prévia de amizade.

---

## 4. Anti-objetivos (seria fracasso mesmo funcionando)

- Qualquer pessoa que não seja um amigo aceito **e** explicitamente autorizado conseguir ler a coordenada do usuário.
- A localização continuar visível ou legível depois que a moto foi desligada ou desconectada.
- Exigir a permissão perigosa de localização o tempo todo em segundo plano (`ACCESS_BACKGROUND_LOCATION`) — o app já possui serviços em primeiro plano com notificação (`PodService` e `TrackingService`) do tipo `LOCATION`.
- Estourar a cota gratuita do Firestore Spark fazendo leituras contínuas da coleção inteira ou enviando coordenadas a cada segundo.
- Deixar dados órfãos de localização após a exclusão da conta.

---

## 5. Restrições

- **Firebase Spark**: sem Cloud Functions nem servidores. Validação atômica e rígida nas regras do Firestore com testes no emulador cobrindo todos os caminhos.
- **Economia de leituras/escritas**:
  - Escrita: no máximo 1 escrita a cada 30 segundos (ou se deslocamento > 100 m). Em 2 horas de estrada = ~240 escritas (dentro da cota diária de 20.000 escritas).
  - Leitura: o cliente só faz *listen* nos UIDs dos amigos que lhe deram permissão, e somente enquanto a tela do mapa ou de amigos estiver ativa em primeiro plano.
- **Sem regressão no app**: não quebrar quem usa o app sem conta ou quem usa sem o Tripper Pod.
- **Tecnologias**: Kotlin 2.2.20, Jetpack Compose, Room, MapLibre, Firestore rules v2. Textos em pt-BR.

---

## 6. Abordagens consideradas

- **Abordagem A (Escolhida e aprovada pelo dono):**
  Publicador reativo (`LiveLocationPublisher`) independente no módulo `app`, observando `PodStatus` e `AndroidLocationSource`. Coleção de concessões `permissoesLocalizacao/{deUid_paraUid}` e coleção `localizacoes/{uid}`.
  *Por que venceu:* Desacoplado da gravação de viagens (compartilha pilotando com ou sem viagem sendo gravada no Room), seguro contra dados presos, controle granular simples.
- **Abordagem B (Descartada):**
  Compartilhamento acoplado dentro do `TrackingService` exigindo gravação de viagem ativa + array em `perfisPublicos`.
  *Por que foi descartada:* Forçaria o motociclista a lembrar de colocar "Gravar viagem" para poder compartilhar a localização, e misturaria regras de rede no serviço local de banco Room.

---

## 7. Decisões do dono (como ele decidiu)

- Escolha amigo por amigo de quem pode ver a localização da moto.
- Transmissão **apenas** com a moto conectada via Bluetooth (Tripper Pod). Desconectou = nada compartilhado.
- Amigos sem permissão continuam vendo só modelo da moto e km da semana.
- Revogação imediata a qualquer momento; desfazer amizade ou bloquear encerra o compartilhamento.
- Sem localização em segundo plano desnecessária; aproveitar os serviços em primeiro plano já existentes.
- Apagar a conta apaga a localização e permissões.
- Visualização no mapa principal (`HunterMap`) com marcador de moto e na lista de amigos com status e botão de foco.

---

## 8. Decisões do arquiteto (minhas; o dono pode trocar)

### 8.1 Modelo no Firestore

1. **`permissoesLocalizacao/{deUid_paraUid}`**:
   - Documento que formaliza que `deUid` permite que `paraUid` veja sua localização.
   - Campos:
     - `de`: string (uid de quem autoriza)
     - `para`: string (uid de quem pode ver)
     - `criadoEm`: number (timestamp ms)
   - Regras de segurança:
     - `allow create`: logado, `de == request.auth.uid`, `amigos(de, para)`, id coincide com `de + '_' + para`.
     - `allow read`: logado e `(request.auth.uid == de || request.auth.uid == para)`.
     - `allow delete`: logado e `(request.auth.uid == de || request.auth.uid == para)`. (Qualquer uma das pontas pode revogar/remover).

2. **`localizacoes/{uid}`**:
   - Ponto de localização em tempo real da moto do usuário `uid`.
   - Campos:
     - `latitude`: number (-90 a 90)
     - `longitude`: number (-180 a 180)
     - `bearing`: number (0 a 360, ou null)
     - `speedKmh`: number (>= 0, ou null)
     - `updatedAtMs`: number (epoch ms recente)
   - Regras de segurança:
     - `allow create, update`: logado, `dono(uid)`, `semExclusao(uid)` e campos válidos.
     - `allow delete`: logado e `dono(uid)`.
     - `allow read`: logado e (`dono(uid)` || (`amigos(uid, request.auth.uid)` && `exists(/databases/$(database)/documents/permissoesLocalizacao/$(uid + '_' + request.auth.uid))`)).

3. **Critério de Inatividade / Queda Abrupta**:
   - Além do `delete` explícito emitido pelo app ao desconectar o Pod, se o celular desligar de repente, o leitor no cliente considera offline qualquer documento com `updatedAtMs` mais antigo que **5 minutos** e não desenha a moto no mapa.

### 8.2 Componentes e Fluxo no Código

- **`sync-core` (JVM Puro - `app.hunter.social`)**:
  - Modelos: `LiveLocation(lat, lng, bearing, speedKmh, updatedAtMs)` e `LocationPermission(de, para, criadoEm)`.
  - Extensões na interface `SocialStore`:
    - `grantLocationPermission(friendUid: String)`
    - `revokeLocationPermission(friendUid: String)`
    - `observeGrantedPermissions(myUid: String): Flow<Set<String>>`
    - `observeReceivedPermissions(myUid: String): Flow<Set<String>>`
    - `publishLocation(loc: LiveLocation)`
    - `clearLocation()`
    - `observeFriendLocation(friendUid: String): Flow<LiveLocation?>`
  - Lógica em `SocialEngine` para gerenciar concessão e revogação.
- **`app` (`app.hunter.app.social`)**:
  - `FirestoreSocialStore`: implementa as chamadas no Firestore.
  - `LiveLocationPublisher`: componente acoplado ao `SocialGraph`. Observa:
    - Estado de autenticação do usuário
    - Concessões de permissão (`grantedPermissions`)
    - Estado do Pod (`PodStatus.state.map { it.phase == PodPhase.CONNECTED }`)
    - Fonte de GPS (`LocationSource`)
    - Quando conectado E houver amigos autorizados: emite atualização para o Firestore no intervalo mínimo de 30s ou deslocamento > 100m.
    - Quando o Pod desconectar: dispara `clearLocation()` imediatamente.
  - `HunterViewModel` / `HunterMap`:
    - Escuta `receivedPermissions` (amigos que nos autorizaram).
    - Para os amigos autorizados, escuta `observeFriendLocation` enquanto a tela de mapa estiver aberta e projeta os marcadores com ícone de moto e nome.
  - `FriendsScreen`:
    - Switch/botão de alternar "Compartilhar localização" para cada amigo.
    - Indicador de amigo "Na estrada" com botão para centralizar no mapa.
  - `deleteAllUserData`:
    - Adiciona a exclusão do próprio documento `localizacoes/{uid}` e das permissões de localização enviadas e recebidas.

---

## 9. Deixado para quem for construir

- Layout exato do marcador de moto no MapLibre (cor de destaque do tema, badge com nome do amigo).
- Detalhes de animação suave ou transição do marcador no mapa entre atualizações de 30s.
- Casos de borda de timeout de rede do Firestore ao apagar a localização na desconexão do Bluetooth.
- Configuração precisa dos testes de regras no emulador para todos os cenários permitidos e negados.

---

## 10. Como saber que está pronto

1. **Testes automatizados**:
   - Testes unitários JVM em `sync-core` para a lógica de permissões e de validade temporal da localização.
   - Testes de regras do Firestore no emulador (`firebase/test`):
     - Dono consegue escrever e apagar a própria localização.
     - Estranho não consegue ler.
     - Amigo sem permissão não consegue ler.
     - Amigo com permissão consegue ler.
     - Amigo perde leitura imediatamente se a permissão for apagada.
     - Amigo perde leitura imediatamente se a amizade for desfeita.
     - Usuário com exclusão de conta não consegue gravar localização.
     - Usuário consegue conceder e revogar permissão para amigo.
     - Usuário não consegue conceder permissão para quem não é amigo.
2. **Compilação e execução**:
   - `./gradlew.bat :sync-core:test :app:testDebugUnitTest :app:assembleRelease` passando sem erros.
3. **Roteiro de testes manuais com dois aparelhos**:
   - Documento `docs/TESTES-MANUAIS-LOCALIZACAO.md` descrevendo passo a passo para o dono testar com duas contas:
     - Conceder permissão ao Amigo B.
     - Conectar o Pod no Aparelho A -> Aparelho B vê a moto do Amigo A no mapa.
     - Desconectar o Pod no Aparelho A -> Aparelho B vê o marcador sumir do mapa.
     - Revogar permissão -> marcador some e Amigo B não vê mais nada.
     - Apagar conta -> apaga dados no Firestore.
