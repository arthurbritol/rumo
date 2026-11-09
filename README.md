# Rumo

App Android (Kotlin + Jetpack Compose + Room) para a Royal Enfield Hunter 350. Mapa com busca de destino, rotas
pensadas para moto, navegação curva a curva com voz, gravação de viagens, controle de abastecimento e
manutenção, mapas offline e envio da navegação ao Tripper Pod por Bluetooth. Tudo fica no celular (sem
backend). Não modifica o Waze.

## O que o app faz

**Mapa**
- Mapa em tela cheia (MapLibre + OpenFreeMap, sem chave de API), claro ou escuro conforme o sistema.
- Busca de destino (Photon/OpenStreetMap) com resultados instantâneos de favoritos e recentes, distância
  até cada resultado e atalhos (posto, restaurante, mecânica…). Toque longo no mapa escolhe um ponto.
- Rotas para moto (Valhalla): *mais rápida*, *estradas e curvas*, *sem pedágio* e *sem terra*, com alternativas.
- Navegação curva a curva com voz em pt-BR, recálculo ao sair da rota e gravação automática da viagem.
  Durante a navegação a barra de abas some para liberar o mapa.
- Também grava viagem livre, sem destino.
- Funciona com posição por GPS, Wi-Fi ou antenas; avisa quando está sem internet e refaz a rota sozinho
  quando a conexão volta.

**Viagens**
- Histórico com mapa do trajeto colorido por velocidade, altimetria e estatísticas (distância, tempo em
  movimento, paradas, velocidade média e máxima). Renomear e excluir.

**Garagem**
- Hodômetro, combustível (km/l, custo por km, autonomia, gráfico) e manutenção por km e/ou dias, com barras
  de progresso.
- Lembretes por notificação (a cada 12 h): avisa ao chegar perto do vencimento e ao vencer.
- Mapas offline: baixe uma região ao redor de você ou o corredor de uma rota (estilos claro e escuro).

**Planejamento de combustível**
- Na pré-visualização da rota, o app estima o combustível restante (último tanque cheio + consumo) e, se a
  rota não cabe na autonomia (com 15% de folga), sugere onde abastecer, lista postos do OpenStreetMap
  (Overpass) perto desse ponto e recalcula a rota passando pelo posto escolhido.

**Pod**
- Conexão Bluetooth com o Tripper Pod, PIN no primeiro uso, reconexão automática e bússola.
- Durante a navegação, o Pod mostra a manobra, a distância e a chegada/tempo/distância restantes
  (tradução Valhalla → ícones do Pod em `navigation/PodManeuvers.kt`).
- O protocolo foi validado apenas no Pod da Meteor 350; na Hunter 350 a compatibilidade é esperada, mas não
  verificada. Faça um teste com a moto parada antes de depender dele.

Serviços externos gratuitos, para uso pessoal: tiles do OpenFreeMap, rotas do servidor público do Valhalla
(valhalla1.openstreetmap.de) e busca do Photon (Komoot). Para uso intenso, hospede o próprio Valhalla.

## Instalação

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

O APK de release é assinado com a chave de debug (instala direto, sem Play Store). Requer Android 8.0+ e
celular arm64.

## Arquitetura

| Módulo | Tipo | Conteúdo |
|---|---|---|
| `core-model` | Kotlin/JVM | `TrackPoint`, `TripStatsAccumulator` (filtros de GPS, distância, tempo em movimento, paradas, altimetria), combustível, manutenção, lembretes, planejador de autonomia |
| `pod-protocol` | Kotlin/JVM | Pacotes do Tripper Pod (CRC-16, handshake/PIN/hora, bússola, navegação, ícones) e `PodSession`. Sem Bluetooth |
| `pod-ble` | Android lib | `BlePodLink` (GATT cliente + servidor local), `PodService` (foreground, reconexão automática, bússola) |
| `navigation` | Kotlin/JVM | `ValhallaClient`, `PhotonGeocoder`, `FuelStationFinder`, `NavEngine`/`NavSession`, avisos de voz, `PodManeuvers` |
| `trips` | Android lib | Room, `TripRecorder` (testável na JVM), `TrackingService` (foreground, tipo `location`), GPS via `LocationManager` |
| `maintenance` | Android lib | Entidades e DAO de abastecimento, manutenção e hodômetro |
| `app` | Aplicativo | `AppDatabase` (Room, KSP), UI Compose, mapa, mapas offline, lembretes (WorkManager) |

Um único banco Room, definido no `app`, reúne as entidades dos módulos; os serviços acham o `TripStore`
pela `Application`.

## Build e testes

Requer JDK 17+ (usa-se o JBR do Android Studio) e o Android SDK 36 (`local.properties` não é versionado).

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew test :app:assembleRelease
```

O Gradle 8.14 não roda no JDK 25: se o `java` do PATH for o 25, defina `JAVA_HOME` como acima. Os testes
(JVM) cobrem o protocolo do Pod, o motor de navegação (com respostas reais das APIs), as estatísticas de
viagem, combustível, manutenção e lembretes.

## Referência do protocolo

`ref/wazetripper` (clone somente leitura, não versionado): `docs/PROTOCOL.md` indica o nível de confiança de
cada item. Atribuição em [NOTICE.md](NOTICE.md).
