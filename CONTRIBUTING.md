# Como contribuir

Contribuições são bem-vindas: abra uma issue para conversar ou envie um pull request direto.

## Ambiente

- JDK 17 ou superior (o JBR do Android Studio serve) e Android SDK 36.
- O Gradle 8.14 **não roda no JDK 25**; se necessário, defina `JAVA_HOME` para um JDK 17–21.

```bash
./gradlew test                 # testes unitários (JVM)
./gradlew :app:assembleRelease # APK de release
```

## Organização

A lógica fica em módulos JVM puros (`core-model`, `pod-protocol`, `navigation`), testáveis sem aparelho.
Só o que depende do Android fica nos módulos `pod-ble`, `trips`, `maintenance` e `app`. Veja
[docs/ARQUITETURA.md](docs/ARQUITETURA.md).

## Regras

- Código novo na lógica vem com teste. Para rede, use respostas reais gravadas em `src/test/resources`.
- Mantenha as mensagens e textos da interface em português do Brasil.
- Mudanças no protocolo do Pod devem citar a fonte (captura real ou `ref/wazetripper/docs/PROTOCOL.md`).
- Rode `./gradlew test` antes de abrir o PR; o CI roda o mesmo.
