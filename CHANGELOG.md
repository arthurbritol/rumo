# Mudanças

Formato baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/).

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
