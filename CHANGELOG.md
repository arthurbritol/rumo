# Mudanças

Formato baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/).

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
