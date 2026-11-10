# Mudanças

Formato baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/).

## [0.6.0]

### Adicionado
- **Design System Novare**: paleta semântica completa com variantes Midnight Navy (modo escuro profundo em camadas com base azul-marinho `#0B111D`, superfícies `#141C2B`/`#1E2D44` e acentos ciano e âmbar) e Clean Slate (modo claro contrastante e arejado com bordas tonais sutis).
- **Tipografia e Geometria refinadas**: cantos arredondados contínuos com `SquircleIcon`, `HCard` e elevações sutis via micro-bordas tonais.
- **Biblioteca de Ícones Nativos Font Awesome 6**: coleção vetorial em `app/src/main/res/drawable/fa_*.xml` com mais de 40 glifos de alta legibilidade para navegação, telemetria, controles e ações (Solid, Regular e Brand).
- **Componentes Base Unificados (`app.hunter.app.ui.Components`)**:
  - `HCard`: container com cantos arredondados de 22dp, preenchimento de superfície e micro-borda tonal suave.
  - `SquircleIcon`: container quadrado arredondado para ícones Font Awesome ou Material com fundo colorido.
  - `HeroMetricCard`: cartão hero para grandes instrumentos (hodômetro, resumo do rolê, autonomia) com alinhamento refinado de ações.
  - `RumoSearchField`: campo de busca estilizado de 48dp com cantos de 14dp, área de toque acessível e ícone Font Awesome integrado.
  - `RumoPrimaryButton`: botão de ação primária com altura de 54dp, cantos de 16dp e gradiente Novare (`#FF7A1A` -> `#EA580C`).
  - `RumoSecondaryButton`: botão de ação secundária com outline, cantos de 16dp e suporte a estado desabilitado.
  - `StatusPill`: pílula compacta de status/tendência com fundo colorido e tipografia destacada.
  - `RumoBadge`: badge compacto com cantos arredondados e cor configurável.

### Alterado
- **Navegação e Interface Global**: substituição de Material Icons padrão por glifos vetoriais Font Awesome 6 (`fa_*.xml`) em todas as telas (`MapScreen`, `TripsScreen`, `GarageScreen`, `FriendsScreen`, `RoleScreens`, `ProfileScreen`, `OnboardingScreen`, `OfflineMapsScreen`).
- **Aba Mapa e HUD**: controles flutuantes com `SquircleIcon` (camadas, busca, centralização, bússola), HUD de navegação e badges de telemetria refinados.
- **Garagem e Manutenção**: cartões de status e métricas de moto reestilizados com `HCard`, badges de alerta tonalizados e formulários modernizados.
- **Histórico e Detalhes de Viagem**: cards de viagem modernizados em `TripsScreen` com `LazyColumn` ponderado, layout compacto e estatísticas organizadas com `HeroMetricCard`.
- **Social e Amigos**: cards de amigos e rolês em grupo com avatares em squircle, chips de status ("Na estrada"), sino de notificações interativo com rolagem contextual e botões de ação consistentes.
- **Onboarding e Telas de Apoio**: apresentação inicial com ilustrações harmonizadas, tipografia equilibrada e transições elegantes.

## [0.5.2]

### Adicionado
- **Localização compartilhada com amigos**: acompanhe amigos em tempo real durante passeios e viagens com a moto conectada via Bluetooth (Tripper Pod).
- **Controle seletivo amigo por amigo**: ative ou desative o compartilhamento individualmente para cada amigo através de um interruptor dedicado na aba Amigos.
- **Transmissão atrelada à moto**: a localização só é transmitida para a nuvem quando a moto estiver conectada (`PodPhase.CONNECTED`) e houver ao menos um amigo autorizado. Ao desligar a moto ou desconectar o Pod, a localização é apagada do Firestore imediatamente.
- **Status "Na estrada" na aba Amigos**: amigos autorizados que estiverem pilotando mostram o badge destacado "Na estrada" e o botão "Ver no mapa".
- **Marcadores de moto no mapa principal**: motos de amigos autorizados são desenhadas no `HunterMap` com ícone orientado pelo rumo (`bearing`), nome do amigo e tempo decorrido desde a última leitura (ex: "agora", "há 1 min").
- **Centralização suave de câmera**: tocar em "Ver no mapa" na aba Amigos alterna para a aba Mapa e centraliza a visualização na coordenada exata da moto do parceiro.
- **Regras de segurança no Firestore**: novas coleções `permissoesLocalizacao` e `localizacoes` protegidas por regras estritas — somente amigos mútuos com permissão explícita ativa conseguem ler coordenadas.
- **Limpeza completa ao apagar a conta**: ao excluir a conta, todos os registros de localização e permissões de localização (enviadas e recebidas) são limpos da nuvem.

### Alterado
- Regras de segurança do Firestore atualizadas com as novas coleções de localização: é necessário republicá-las com `firebase deploy --only firestore:rules`. Veja [docs/FIREBASE.md](docs/FIREBASE.md).

## [0.5.0]

### Adicionado
- Aba **Amigos** (precisa de conta): busca por nome (a partir de 3 letras, até 20 resultados), convites (enviar, aceitar, recusar, cancelar), desfazer amizade e bloquear. Cada amigo mostra a moto e os km rodados na semana (de segunda a domingo). Limite de 50 amigos.
- "Aparecer na busca": pergunta única na primeira vez que você abre a aba e interruptor no Perfil. Desligado, ninguém te acha pelo nome (amigos continuam te vendo).
- **Rolê em grupo**: crie um rolê com até 9 amigos; cada um informa o próprio orçamento e o app calcula a média, o alcance sugerido (km) pelo custo por km de cada um e avisa quem não tem dados de consumo. Quem aceitou pode sair; o criador remove participantes ou encerra. Rolês expiram em 7 dias (política de TTL no Firestore).
- Marcador na aba Amigos com convites e rolês pendentes. Sem internet a aba mostra um aviso e o botão "Tentar de novo".
- Apagar a conta também apaga o que é social (perfil público, atividade, convites, amizades, bloqueios e participação em rolês), com retomada se for interrompido.
- Pacote `app.hunter.social` no `sync-core` (motor, semana, planejador de rolê, chave de busca) e adaptador `FirestoreSocialStore` no app. Novas regras do Firestore, com testes de emulador por caminho.

### Alterado
- Sem conta (ou sem `google-services.json`) a aba Amigos só mostra o convite para entrar; nada social roda.
- Regras do Firestore ampliadas: é preciso republicá-las (`firebase deploy --only firestore:rules`) e ativar o TTL de `roles.expiraEm`. Veja [docs/FIREBASE.md](docs/FIREBASE.md).

## [0.4.0]

### Adicionado
- Aviso de atualização mais esperto: o app procura versão nova toda vez que volta para a frente (no máximo a cada 15 minutos) e o Perfil ganhou "Buscar atualização", com a versão instalada e o resultado da busca.
- Conta opcional (Firebase): entrar com Google ou e-mail e senha (com confirmação de e-mail e "esqueci a senha"). O app continua funcionando sem conta.
- Sincronização com a conta: perfil, moto, hodômetro, abastecimentos, manutenção, locais favoritos, resumo das viagens e a preferência de voz. Funciona sem internet (edita offline e sincroniza ao reconectar); a edição mais recente vence e nada é duplicado.
- Quem usava sem conta e cria uma conta mantém os dados (mesclados, sem duplicar). Reinstalar e entrar recupera tudo, exceto o trajeto completo das viagens, que fica só no aparelho (a viagem volta no histórico, sem mapa).
- Apresentação na primeira abertura (4 telas, com "Pular"), login, permissões uma a uma (sem localização em segundo plano) e cadastro da moto, completo ou curto. "Rever apresentação" fica no perfil.
- Perfil (ícone no topo da Garagem): sair da conta (bloqueado enquanto houver algo sem enviar), apagar meus dados e minha conta (digitar APAGAR, com retomada se for interrompido) e avisos para visitante e e-mail não confirmado.
- Módulo `sync-core` (JVM puro) com o motor de sincronização, a mesclagem e o fluxo de apresentação, mais regras do Firestore com testes de emulador.

### Alterado
- Banco do app na versão 3 (migração automática, sem perder dados): id global, data de alteração e marca de pendente nos registros.
- Quem já usava o app não vê a apresentação nem login forçado: entra direto no mapa e recebe um aviso discreto para criar conta.
- Ao voltar a internet o app sincroniza sozinho.
- Sem `google-services.json` no build o login fica desabilitado e o app funciona como antes.

## [0.3.0]

### Adicionado
- Atualização pelo próprio app: avisa quando há release nova no GitHub, mostra as alterações e baixa/instala o APK.
- Nome **Rumo**, ícone adaptativo (com versão monocromática) e identidade visual.
- Busca de destino com favoritos e recentes instantâneos, distância até cada resultado e atalhos.
- Aviso de falta de internet e novo cálculo automático da rota quando a conexão volta.
- Posição por Wi-Fi/antenas e última posição conhecida no mapa (funciona em ambiente fechado).
- Botão do Android volta ao mapa a partir das outras abas; barra de abas some durante a navegação.

### Alterado
- Mapa fica sempre montado (trocar de aba não recarrega tiles); estilo claro mais leve.
- Rede: tempos limite separados, 3 tentativas com pausa crescente, conexão reaproveitada e cache da busca.
- APK de release no lugar do de debug (bem mais fluido).

## [0.2.0]

### Adicionado
- Mapa MapLibre com rotas para moto (Valhalla), navegação curva a curva com voz e recálculo.
- Planejador de paradas por autonomia, mapas offline e lembretes de manutenção.
- Histórico de viagens com traçado colorido por velocidade, garagem (combustível, manutenção, hodômetro).

## [0.1.0]

### Adicionado
- Protocolo do Tripper Pod (`pod-protocol`), conexão BLE, gravação de viagens em segundo plano.
