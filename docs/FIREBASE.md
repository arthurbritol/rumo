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
6. Baixe o `google-services.json` e coloque em `app/google-services.json` (o arquivo não deve ser inventado nem copiado de outro projeto).
7. Publique as regras do Firestore (`firebase/firestore.rules`) com a Firebase CLI: copie `firebase/.firebaserc.example` para `firebase/.firebaserc`, ajuste o ID do projeto e rode `firebase deploy --only firestore:rules` dentro de `firebase/`. O marcador de exclusão de conta pendente fica no documento `usuarios/{uid}/config/exclusao` (campo booleano `pending`), e não no perfil.

Para rodar os testes das regras localmente: dentro de `firebase/`, `npm ci` e `npx firebase-tools emulators:exec --only firestore --project demo-rumo "npm test"` (precisa de JDK 21+).

8. Faça o teste final descrito em [Como testar](#como-testar).

## Como testar

Depois do passo 8, com o `google-services.json` no lugar:

1. Gere o APK (`./gradlew.bat :app:assembleRelease`) e instale no celular (`adb install -r app/build/outputs/apk/release/app-release.apk`). Use a mesma chave `rumo` cujas impressões digitais foram cadastradas; com outra chave o login Google falha.
2. Abra o app, escolha **Continuar com Google** (ou crie uma conta com e-mail) e confira no console, em Authentication > Usuários, que o usuário apareceu.
3. Cadastre a moto, registre um abastecimento e aguarde alguns segundos. No console, em Firestore Database, abra `usuarios/{uid}`: devem existir o perfil e as subcoleções (`abastecimentos`, `config/hodometro`).
4. Em Garagem > ícone de perfil, apague a conta e confira que `usuarios/{uid}` e o usuário sumiram do console.
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

Com o `google-services.json` presente o plugin é aplicado; essa combinação ainda precisa ser conferida quando o arquivo for fornecido.
