# Rumo para carro e moto — desenho e plano

Data: 2026-11-25. Estado: **proposta, nada implementado**. Inventário de apoio (feito por leitura do código): resumo na seção "Pontos do código".

## Decisões do dono

- Um app só, "Rumo", com nome e ícone neutros. Textos e ícones do mapa mudam conforme o veículo.
- Cada pessoa pode ter **vários veículos** (moto e/ou carro) e um **veículo ativo** que troca na hora.
- O **Pod** (Bluetooth) é só para moto: some para quem usa carro.
- **Rolê por perto separado por tipo de veículo** (moto com moto, carro com carro). Amigos e "visto por último" valem entre tipos.
- Tudo mais se adapta ao veículo ativo: rota, combustível e autonomia, manutenção, avisos, textos.

## Objetivos, não objetivos e cuidados

- Objetivo: quem tem só carro usa o app inteiro sem ver nada de moto; quem tem moto não perde nada do que existe hoje.
- Não objetivo: caminhão, ônibus, bicicleta, outros perfis; variante visual por veículo; app separado.
- Cuidado 1: dados e contas existentes (hoje a "moto" é só o perfil) migram sem perda: viram o primeiro veículo, tipo moto.
- Cuidado 2: nada quebra para quem ainda está com a versão antiga do app (regras do Firestore e Worker publicados antes do app, e compatíveis para trás).
- Cuidado 3 (privacidade): separar o rolê por tipo divide as células e reduz quantas pessoas há em cada uma; em região pouco povoada isso deixa a posição aproximada mais identificável. Mitigação: mantenho o arredondamento atual, só mostro "alguém por perto" como hoje e não mostro tipo exato quando há poucos; revisar no teste de campo.

## Modelo

- **Veículo** (novo): `id` (UUID), `tipo` (`MOTO` | `CARRO`), `apelido`, `marca`, `modelo`, `ano`, `tanqueLitros`, `consumoPadraoKmL` opcional, `criadoEm`, `removidoEm`. O "veículo ativo" é uma preferência local e também vai no perfil do usuário (para o outro aparelho da mesma conta).
- Room passa da versão 4 para 5: tabela `veiculos`; `FuelupEntity`, `MaintenanceItemEntity` e o odômetro/tanque ganham `veiculoId` (migração cria o veículo "Minha moto" a partir do perfil atual e liga tudo a ele). `TripEntity` ganha `veiculoId` opcional (viagens antigas ficam do veículo migrado).
- Sync (`sync-core`): nova coleção `usuarios/{uid}/veiculos`; documentos de abastecimento, manutenção e viagem ganham `veiculoId`; `UserProfile` ganha `veiculoAtivoId`. Regras do Firestore atualizadas (`hasOnly` e limite de chaves) e com teste, e **publicadas antes do app**.
- Catálogo (`BikeCatalog.kt`) ganha irmão de carros (marcas e modelos mais comuns no Brasil) e cadastro livre quando o modelo não está na lista. Padrões de tanque: moto ~13 L (como hoje), carro 45 L; consumo padrão do planejador por tipo.
- Manutenção: itens padrão por tipo (carro: troca de óleo, filtros, pneus/alinhamento, pastilhas, correia dentada, bateria, revisão; moto como hoje).

## Comportamento por veículo

- **Rota** (`ValhallaClient`): moto usa `motorcycle` como hoje; carro usa `auto` com `use_highways`, `use_tolls`, `use_ferry`. Perfis na tela: moto (Mais rápida, Estradas e curvas, Sem pedágio, Sem estrada de terra); carro (Mais rápida, Sem pedágio, Evitar balsa; sem "curvas" e sem "terra"). Antes de codar, confirmar que o servidor Valhalla público aceita `auto` (provável).
- **Pod**: aba, permissões Bluetooth e textos de onboarding só aparecem quando existe ao menos um veículo do tipo moto e o ativo é moto; para carro, nada de Bluetooth é pedido.
- **Avisos** (`Perigo`/Pardal): radares, lombadas, semáforo com câmera, travessias e pontos de atenção valem para os dois. Confirmar na implementação se algum tipo é só de moto (nada encontrado até agora).
- **Textos e ícones**: os textos estão fixos no Kotlin (o `strings.xml` só tem o nome do app). Plano: criar um apoio `TextosDoVeiculo` (por tipo: "moto"/"carro", "motociclista"/"motorista", "Navegue de moto"→"Navegue" neutro etc.) e trocar as ocorrências, em vez de migrar tudo para `strings.xml` agora. O ícone do mapa (o "marcador do motociclista" vetorial) ganha o equivalente de carro.
- **Garagem**: lista de veículos com adicionar, escolher o ativo, editar e remover; abastecimentos, manutenção, autonomia e estatísticas sempre do veículo ativo.

## Rolê por perto separado por tipo

- O Durable Object da célula passa a ter nome `<tipo>|<célula>` (`m|-21:-41`, `c|-21:-41`). O cliente manda `v=m|c` em `/ws?cell=...&v=...`; sem `v` (cliente antigo) vale `m`, então as células de moto de hoje continuam as mesmas. Valida-se `v` no Worker (`CELL_RE`/`cellNoBrasil`, cabeçalho interno da célula, encontros, testes e `loadtest`).
- Contas (bloqueios, amigos, denúncias, suspensão automática) continuam globais por pessoa, não por tipo.
- Quem tem os dois veículos entra no rolê do tipo do veículo ativo; trocar de veículo desliga o rolê atual e pergunta se quer procurar no outro tipo.
- Textos, marcadores e cartões do rolê ficam por tipo ("motociclista por perto" / "motorista por perto").
- Amigos: o `LiveLocationPublisher` e o perfil público ganham o tipo do veículo ativo para o amigo aparecer com o ícone certo no mapa; regras sociais do Firestore atualizadas e publicadas antes do app.

## Plano de tarefas

1. **Modelo de veículo e migração Room 4→5**: entidade, DAO, migração com teste (dados antigos viram o veículo "moto"), veículo ativo em preferências.
2. **Sync e regras**: `usuarios/{uid}/veiculos`, `veiculoId` nos documentos, `veiculoAtivoId` no perfil, regras do Firestore e testes; documentação do passo manual de publicar as regras.
3. **Garagem multi-veículo e cadastro**: telas (lista, adicionar, trocar ativo), catálogo de carros, onboarding sem pressupor moto.
4. **Roteamento por veículo**: perfis e custo do Valhalla por tipo, perfis na tela só os do tipo.
5. **Combustível e manutenção por veículo**: padrões por tipo, `veiculoId` nas consultas, autonomia e planejador do veículo ativo.
6. **Textos e ícones neutros**: `TextosDoVeiculo`, troca de todas as ocorrências (maiores: Pod, Perfil, Mapa, Rolê, Garagem, Amigos, Onboarding), ícone de carro no mapa, Pod condicionado ao tipo.
7. **Rolê por tipo, Worker**: nome do DO com tipo, `v` com padrão `m`, validações, testes, loadtest, deploy do Worker **antes** do app.
8. **Rolê por tipo e amigos, app**: cliente com `v`, troca de veículo, textos/marcadores, tipo do veículo nos amigos, `fix` de compatibilidade com cliente antigo.
9. **Revisão final e roteiros**: testes manuais (conta antiga migrando, dois veículos, rolê carro×moto separados, Pod some no carro), documentação, CHANGELOG.

Ordem: 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9. O 7 pode andar em paralelo com 3 a 6 (outro módulo). Publicar em duas versões do app: **A** (tarefas 1 a 6, sem rolê de carro; carro já navega, abastece e faz manutenção) e **B** (7 e 8). Isso reduz o risco e deixa o dono testar cedo.

## Pontos do código (inventário)

- Moto hoje é só `UserProfile(displayName, brand, model, year, voice)` (`sync-core/.../Model.kt`, `ProfilePrefs.kt`, Firestore `usuarios/{uid}`). Não há entidade de veículo; tanque (`OdometerEntity`, 13 L) é uma linha única; Room na versão 4 com migrações testadas.
- `firestore.rules`: `perfilValido()` e o documento de hodômetro usam `hasOnly`, então campos novos são recusados até publicar as regras; `docSyncValido` aceita no máximo 20 chaves.
- `ValhallaClient.buildRequest` fixa `motorcycle`; `RouteProfile` tem rótulos de moto.
- Rolê: célula de 1° (`cellId` em `NearbyCells.kt` e `geo.js`), Durable Object pelo nome da célula (`index.js`), protocolo sem campo de veículo.
- Pod: módulos `pod-ble` e `pod-protocol`, `PodScreen.kt`, `ProfileScreen.kt`, `PodManeuvers.kt`, `PermissionPlan.kt`; `TrackingService` e `TripRecorder` são neutros.
- Textos fixos no Kotlin: ~33 menções em `PodScreen`, ~20 em `ProfileScreen`, ~10 em `HunterMap`, ~8 em `NearbyScreens`, ~7 em cada de `MapScreen`, `GarageScreen`, `FriendsScreen`, `LiveLocationPublisher`; `IntroPages.kt` ("Navegue de moto").
- Identidade: `applicationId = app.hunter.companion` e nome "Rumo" não citam moto; o ícone do app precisa de decisão (neutro).

## Riscos

- Migração do banco e do sync com dados reais de moto: testes de migração e ensaio com uma conta antiga.
- Ordem de publicação: regras do Firestore e Worker primeiro; o app aceita servidor sem os campos novos durante a transição.
- Rolê de carro com poucas pessoas no começo (células separadas).
- Servidor de rotas público: confirmar `auto`; termos de uso do serviço continuam valendo para o projeto da Play Store.
