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
