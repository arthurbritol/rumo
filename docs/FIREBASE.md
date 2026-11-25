# Configuração do Firebase (conta e sincronização)

O app compila e roda sem Firebase: sem `app/google-services.json` o plugin do Google Services não é aplicado e `AccountConfig.isAvailable` fica falso (o app segue sem conta). Para ligar login e sincronização, siga os passos abaixo (plano Spark, gratuito: Authentication + Firestore).

## Passo a passo

1. No console do Firebase (https://console.firebase.google.com), crie um projeto (Analytics é opcional e pode ficar desligado).
2. Registre um app Android com o nome de pacote `app.hunter.companion`.
3. Cadastre as impressões digitais SHA-1 e SHA-256 da chave `rumo` (Configurações do projeto > Seus apps > Adicionar impressão digital). Para obtê-las:

   ```
   keytool -list -v -keystore C:\Users\arthur.lucio\.rumo\rumo-release.keystore -alias rumo
   ```

   Se também quiser testar com o build de debug local, cadastre as impressões da chave de debug (`~/.android/debug.keystore`, alias `androiddebugkey`, senha `android`).
4. Em Authentication > Método de login, ative **Google** e **E-mail/senha**.
5. Em Firestore Database, crie o banco em **modo de produção**.
6. Baixe o `google-services.json` e coloque em `app/google-services.json` (o arquivo não deve ser inventado nem copiado de outro projeto). **Faça commit do arquivo no repositório** (o dono do projeto): ele não é segredo (só identifica o projeto; quem protege os dados são as regras do Firestore e a autenticação), e sem ele as releases geradas pelo GitHub Actions saem **sem login**. Para limitar abuso, restrinja a chave de API no Google Cloud Console (APIs e serviços > Credenciais > a chave "Android key": restrição de app Android com o pacote `app.hunter.companion` e as SHA-1 da chave `rumo`, e restrição de APIs às usadas pelo Firebase Auth e Firestore).
7. Publique as regras do Firestore (`firebase/firestore.rules`) com a Firebase CLI: copie `firebase/.firebaserc.example` para `firebase/.firebaserc`, ajuste o ID do projeto e rode `firebase deploy --only firestore:rules` dentro de `firebase/`. O marcador de exclusão de conta pendente fica no documento `usuarios/{uid}/config/exclusao` (campo booleano `pending`), e não no perfil. Ele é permanente: depois de apagar a conta, só ele sobra (sem nenhum dado pessoal), para que outro aparelho ainda logado na conta nunca reenvie os dados.

Para rodar os testes das regras localmente: dentro de `firebase/`, `npm ci` e `npx firebase-tools emulators:exec --only firestore --project demo-rumo "npm test"` (precisa de JDK 21+).

8. Faça o teste final descrito em [Como testar](#como-testar).

## Como testar

Depois do passo 8, com o `google-services.json` no lugar:

1. Gere o APK (`./gradlew.bat :app:assembleRelease`) e instale no celular (`adb install -r app/build/outputs/apk/release/app-release.apk`). Use a mesma chave `rumo` cujas impressões digitais foram cadastradas; com outra chave o login Google falha.
2. Abra o app, escolha **Continuar com Google** (ou crie uma conta com e-mail) e confira no console, em Authentication > Usuários, que o usuário apareceu.
3. Cadastre a moto, registre um abastecimento e aguarde alguns segundos. No console, em Firestore Database, abra `usuarios/{uid}`: devem existir o perfil e as subcoleções (`abastecimentos`, `config/hodometro`).
4. Em Garagem > ícone de perfil, apague a conta e confira que o perfil `usuarios/{uid}`, as subcoleções de dados e o usuário sumiram do console (fica só `usuarios/{uid}/config/exclusao` com `pending: true`, de propósito).
5. Se o login Google falhar com "Nenhuma conta disponível" ou erro de desenvolvedor, falta a impressão digital SHA-1/SHA-256 da chave que assinou o APK (passo 3) ou o `google-services.json` é anterior a ela: baixe-o de novo.
6. Se a gravação no Firestore for recusada ("permission denied" no `adb logcat`, etiqueta `Rumo/sync`), as regras não foram publicadas (passo 7).

O roteiro completo, para fazer no aparelho com calma, está em [TESTES-MANUAIS-CONTA.md](TESTES-MANUAIS-CONTA.md).

Aviso: a conta de e-mail com o mesmo endereço do Google é vinculada (entrar com Google e com e-mail/senha usando o mesmo endereço resulta na mesma conta).

## Amigos e rolê (versão 0.5)

Depois de atualizar o app para a 0.5, faça **uma vez** no projeto Firebase (sem isso a aba Amigos recebe "permission denied" e os rolês nunca expiram):

1. **Publicar as regras novas** (`firebase/firestore.rules` agora cobre `busca`, `perfisPublicos`, `atividade`, `convites`, `amizades`, `bloqueios` e `roles`):

   ```
   cd firebase
   firebase deploy --only firestore:rules
   ```

2. **Índices:** nenhum índice composto é necessário. As consultas usam só filtros de igualdade (`convites` por `de`/`para`, `amizades` por `a`/`b`, `bloqueios` por `quem`, `roles` por `participantes` com array-contains) ou um intervalo num único campo (`busca`, por prefixo de `displayNameLower`), que o Firestore indexa sozinho. `firebase/firestore.indexes.json` continua vazio. Se algum dia o Logcat (`Rumo/social`) mostrar `FAILED_PRECONDITION ... requires an index`, abra o link da mensagem e crie o índice sugerido, ou acrescente-o a `firestore.indexes.json` e rode `firebase deploy --only firestore:indexes`.

3. **Ativar a política de TTL** do campo `expiraEm` da coleção `roles` (apaga os rolês vencidos, 7 dias depois de criados ou renovados):
   1. No console do Firebase, abra **Firestore Database**.
   2. Abra a aba **Time to live** (em versões novas do console pode aparecer como "Políticas de TTL"; também fica no Google Cloud Console, em Firestore > Time-to-live).
   3. **Criar política**: ID da coleção `roles`, campo de carimbo de data/hora `expiraEm`.
   4. Salve. A política leva alguns minutos para ficar ativa, e a exclusão pode atrasar até cerca de 24 horas depois do vencimento: por isso o app também ignora e fecha rolês vencidos por conta própria.
   5. O TTL só apaga o documento do rolê; o Firestore não apaga a subcoleção `roles/{id}/membros` em cascata. O app remove os membros ao encerrar um rolê, e o que sobrar fica inacessível (as regras exigem o rolê) e sem dados pessoais além do nome e do orçamento.

4. Para testar com calma, siga [TESTES-MANUAIS-AMIGOS.md](TESTES-MANUAIS-AMIGOS.md).

Testes das regras (inclui o social): dentro de `firebase/`, `npx firebase-tools emulators:exec --only firestore --project demo-rumo "npm test"`.

## Versões escolhidas

Verificadas com AGP 8.13.2, Kotlin 2.2.20, Gradle 8.14.3 (`./gradlew.bat test :app:assembleRelease` sem `google-services.json`).

| Item | Versão |
| --- | --- |
| Plugin `com.google.gms.google-services` | 4.4.4 |
| `firebase-bom` | 34.19.0 (última 34.x; a 35.x não foi usada para ficar na linha pedida) |
| `androidx.credentials` (credentials e credentials-play-services-auth) | 1.6.0 |
| `googleid` | 1.1.1 |

Com o `google-services.json` presente o plugin é aplicado; **um build COM o arquivo nunca foi verificado** (todos os builds e testes até aqui foram sem ele). Depois de adicioná-lo, o primeiro item a rodar é o "0. Build com o google-services.json" de [TESTES-MANUAIS-CONTA.md](TESTES-MANUAIS-CONTA.md).

O Firestore usa cache só em memória (os documentos da conta não ficam gravados no aparelho depois de sair). Escritas feitas offline não sobrevivem a matar o app; não há perda, porque o banco local (Room) é a fonte da verdade e reenvia o que estiver pendente na próxima sincronização.

## Passos para o social (projeto 2)

Amigos e rolê usam as coleções `busca`, `perfisPublicos`, `atividade`, `convites`, `amizades`, `bloqueios` e `roles` (com a subcoleção `membros`), todas protegidas por `firebase/firestore.rules`.

1. **Publique as regras**: dentro de `firebase/`, `firebase deploy --only firestore:rules`. Sem isso, toda operação social é recusada ("permission denied").
2. **Índices**: a busca por prefixo (`busca` ordenada por `displayNameLower` com `startAt`/`endAt`) usa só um campo e **não precisa de índice composto**. As demais consultas (igualdade em `de`, `para`, `a`, `b` e `array-contains` em `participantes`) também dispensam índice.
3. **TTL dos rolês** (apaga rolês vencidos sozinho): no console do Firebase, Firestore Database > Time-to-live (TTL) > Criar política, grupo de coleções `roles`, campo de carimbo de data/hora `expiraEm`. O app grava `expiraEm` como carimbo de data/hora (as regras aceitam número ou carimbo), que é o tipo que o TTL exige; a leitura converte de volta para milissegundos. Mesmo antes da política, o app trata o rolê vencido como expirado. Os documentos da subcoleção `membros` **não** são apagados pelo TTL (o TTL só remove o documento do rolê); eles ficam ilegíveis, pois as regras exigem o rolê existente para ler os membros.
4. **Limitação conhecida (membros de rolê vencido)**: o TTL apaga só o documento `roles/{id}`; os documentos de `roles/{id}/membros/{uid}` ficam no servidor, ilegíveis pelas regras e fora do alcance de `deleteAllSocial`. O caminho de limpeza é `FirestoreSocialStore.closeRole`: o cliente do criador encerra os rolês vencidos que enxerga (a ligação disso vem na Task 7).
5. **Bloqueios e consultas**: cada documento de `bloqueios` leva o campo `quem` (uid de quem bloqueia), e a lista dos bloqueios usa `where quem == uid`. Toda consulta de lista precisa de uma restrição que as regras consigam provar (`para`/`de` em convites, `a`/`b` em amizades, `quem` em bloqueios, `participantes array-contains` em roles, `limit` <= 20 em busca); uma consulta sem isso é recusada em tempo de execução.

## Localização compartilhada com amigos (versão 0.5.2 / Projeto 3)

Após atualizar o app para a versão 0.5.2, aplique as novas regras de segurança no projeto Firebase real (sem isso as permissões e a localização recebem "permission denied"):

1. **Publicar as regras novas** (`firebase/firestore.rules` agora cobre também `permissoesLocalizacao` e `localizacoes`):

   ```bash
   cd firebase
   firebase deploy --only firestore:rules
   ```

2. **Novas coleções e regras de segurança**:
   - **`permissoesLocalizacao/{de_para}`**:
     - Concessão de permissão unidirecional para visualização de posição. O ID do documento é estritamente `de + '_' + para`.
     - `create`: exige login, `request.auth.uid == de`, `de != para`, amizade mútua ativa (`amigos(de, para)`) e campos válidos (`de`, `para`, opcional `criadoEm`).
     - `read, delete`: permitido para ambas as pontas (`request.auth.uid == de` ou `request.auth.uid == para`), permitindo ao concedente revogar ou ao amigo destinatário remover.
   - **`localizacoes/{uid}`**:
     - Ponto em tempo real da moto do motociclista `uid`.
     - `create, update`: apenas o próprio dono (`request.auth.uid == uid`), desde que a conta não esteja em exclusão (`semExclusao(uid)`), com campos válidos (`latitude` entre -90 e 90, `longitude` entre -180 e 180, `bearing` opcional 0..360, `speedKmh` opcional >= 0 e `updatedAtMs` epoch ms válido).
     - `delete`: apenas o próprio dono (`request.auth.uid == uid`).
     - `read`: apenas o dono OU usuário logado que seja amigo mútuo (`amigos(uid, request.auth.uid)`) **e** possua concessão ativa em `permissoesLocalizacao/$(uid + '_' + request.auth.uid)`.

3. **Índices**: nenhum índice composto é necessário. As consultas em `permissoesLocalizacao` utilizam filtros simples de igualdade em campo único (`whereEqualTo("de", uid)` e `whereEqualTo("para", uid)`). A leitura de coordenadas é feita diretamente por referência de documento (`localizacoes/{uid}`). O arquivo `firebase/firestore.indexes.json` continua vazio.

4. **Testes automatizados das regras**: dentro de `firebase/`, execute os testes no emulador local:
   ```bash
   npx firebase-tools emulators:exec --only firestore --project demo-rumo "npm test"
   ```

5. **Testes manuais**: para o roteiro completo de validação com dois aparelhos, consulte [TESTES-MANUAIS-LOCALIZACAO.md](TESTES-MANUAIS-LOCALIZACAO.md).

### Quando a localização é publicada (atualizado)

`localizacoes/{uid}` agora é gravado com o **Pod conectado ou com o app aberto em primeiro plano** (GPS do celular), desde que haja permissão concedida a algum amigo. Em segundo plano sem o Pod a posição é apagada. Nenhuma regra mudou por isso.

### "Visto por último": `presenca/{uid}` (REQUER publicar as regras)

Nova coleção `presenca/{uid}` com `{ lastSeenMs }` (epoch ms do aparelho de quem grava):
- `create, update`: só o dono, sem exclusão de conta pendente, apenas o campo `lastSeenMs` (número entre 0 e 1e14).
- `delete`: só o dono.
- `read`: o dono, ou amigo (`amigos(uid, request.auth.uid)`) que tenha `permissoesLocalizacao/{uid}_{leitor}` (mesma condição de `localizacoes`).

**Deploy necessário** antes de usar o recurso (sem ele o app funciona normalmente, mas o "Visto há ..." não aparece e o log mostra `PERMISSION_DENIED` em `Rumo/social`):

```bash
cd firebase
firebase deploy --only firestore:rules
```

Testes das regras: `firebase/test/presence.rules.test.js` (rodam com os demais pelo comando do item 4). Nenhum índice novo.

## Vários veículos (carro e moto): `veiculos`, `hodometros` e `veiculoId` (REQUER publicar as regras ANTES do app)

A conta passa a ter vários veículos. Novidades no Firestore (`firebase/firestore.rules`):

- `usuarios/{uid}/veiculos/{id}`: um documento por veículo (`tipo` só `MOTO`/`CARRO`, `apelido`/`marca`/`modelo` até 60 caracteres, `ano` inteiro de 1900 a 2100, `tanqueLitros` de 0 a 200, `consumoPadraoKmL` de 0 (exclusivo) a 100, `criadoEm`, `removidoEm`, `updatedAtMs`, `deleted`). Só o dono lê e escreve. Remover um veículo é lógico (`removidoEm`); o documento não é apagado.
- `usuarios/{uid}/hodometros/{veiculoId}`: hodômetro e tanque por veículo (`offsetKm`, `tankLiters`, `updatedAtMs`, `veiculoId` igual ao id do caminho). O documento antigo `config/hodometro` continua aceito (com `veiculoId` opcional) e é lido como o hodômetro do veículo migrado.
- `veiculoAtivoId` (opcional, texto até 64) no perfil `usuarios/{uid}`, para o outro aparelho da mesma conta abrir no mesmo veículo.
- `veiculoId` (opcional, texto até 64) nos documentos de abastecimentos, manutenção e viagens. O limite de 20 chaves por documento não mudou (o maior, a viagem, tem 14).
- Documentos antigos (sem `veiculoId`) pertencem ao veículo migrado: o app os atribui ao baixar, sem perder nada. O veículo migrado ("Minha moto") tem id fixo `00000000-0000-4000-8000-000000000001`, igual em todos os aparelhos da conta, para dois aparelhos que migraram sozinhos não gerarem duas motos.

**Passo manual, nesta ordem:**

1. Publique as regras ANTES de distribuir o app: `cd firebase && firebase deploy --only firestore:rules`.
2. Só então publique/instale o app com os vários veículos.

**E se o app sair antes das regras?** O app continua funcionando: os dados locais ficam intactos, e a sincronização falha com erro tratado (o aviso de sincronização do app; `PERMISSION_DENIED` em `Rumo/sync` no `adb logcat`) até as regras serem publicadas. Nada se perde; os dados sobem na primeira sincronização depois do deploy. As regras novas são compatíveis para trás: apps antigos continuam sincronizando (ignoram os campos novos; os documentos que eles reescrevem voltam sem `veiculoId` e são reatribuídos ao veículo migrado).

Testes das regras: `firebase/test/veiculos.rules.test.js` (rodam com os demais: `npx firebase-tools emulators:exec --only firestore --project demo-rumo "npm test"` dentro de `firebase/`). Nenhum índice novo.
