# Hunter Companion

App Android (Kotlin + Jetpack Compose + Room) para a Royal Enfield Hunter 350: grava pedais, controla
abastecimento e manutenção e, quando a moto chegar (30/11/2026), envia navegação com rotas próprias ao
Tripper Pod por Bluetooth. Tudo local no celular (sem backend). Não modifica o Waze.

## Módulos

| Módulo | Tipo | Conteúdo |
|---|---|---|
| `core-model` | Kotlin/JVM | `TrackPoint`, `TripStats`, `TripStatsAccumulator` (filtros de GPS, distância, tempo em movimento, paradas, altimetria com histerese, gap) |
| `pod-protocol` | Kotlin/JVM | Pacotes do Tripper Pod (CRC-16, handshake/PIN/hora, bússola, navegação, ícones) e `PodSession`. **Sem Bluetooth** |
| `pod-ble` | Android lib | `BlePodLink` (GATT cliente + servidor local), `PodService` (foreground, reconexão automática, bússola) |
| `trips` | Android lib | Entidades/DAO Room, `TripRecorder` (testável na JVM), `TrackingService` (foreground, tipo `location`), GPS via `LocationManager` |
| `maintenance` | Android lib | Esqueleto: abastecimento, manutenção, hodômetro (próxima etapa) |
| `app` | Aplicativo | `AppDatabase` (Room, KSP), `HunterApp`, UI Compose mínima (gravar/parar + histórico) |

Decisões de arquitetura: um único banco Room definido no `app`, com as entidades dos módulos de feature;
o serviço acha o `TripStore` pela `Application` (`TripsGraph`).

## Build e testes

Requer JDK 17+ (usa-se o JBR do Android Studio) e o Android SDK 36 (`local.properties` não é versionado).

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew test :app:assembleDebug
```

O Gradle 8.14 **não roda no JDK 25**; se o `java` do PATH for o 25, defina `JAVA_HOME` como acima.
APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Referência do protocolo

`ref/wazetripper` (clone somente leitura; não versionado): `docs/PROTOCOL.md` tem o nível de confiança de
cada item (confirmado / provável / não verificado). Atribuição em [NOTICE.md](NOTICE.md).

## App (5 abas)

Pedal (velocidade ao vivo, gravar/parar, alertas de manutenção) · Histórico (traçado colorido por velocidade,
altimetria, estatísticas) · Combustível (km/l tanque cheio a cheio, custo/km, autonomia) · Manutenção
(hodômetro + lembretes por km/dias) · Pod (conexão BLE, PIN, bússola).

Instalar no celular: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

## Estado

- [x] `pod-protocol`: pacotes + `PodSession` (handshake, keepalive, escritas serializadas) — 55 testes
- [x] Gravação em segundo plano (foreground service), retomada após processo morto
- [x] Combustível, manutenção, hodômetro, histórico com traçado — lógica testada na JVM
- [x] `pod-ble`: transporte BLE (cliente + servidor GATT) e `PodService` com bússola pelo GPS
- [ ] **Nada disso foi executado em aparelho** (só build + 85 testes na JVM); UI nunca foi vista rodando
- [ ] BLE do Pod: só teste de bancada com a moto (30/11/2026) confirma
- [ ] Mapa com tiles (hoje o traçado é desenhado sem fundo, offline), lembretes por notificação
- [ ] Rotas próprias (Valhalla/GraphHopper), tradução das manobras para os ícones do Pod, modo grupo
