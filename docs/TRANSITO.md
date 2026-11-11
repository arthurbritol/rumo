# Camada de trânsito

O Rumo mostra o trânsito (linhas verde, amarela e vermelha por fluxo) com imagens da **TomTom**. Para a chave da TomTom
não ficar dentro do APK, o app pede as imagens a um **servidor intermediário** (Cloudflare Worker, em
`workers/traffic-proxy`), e ele pede à TomTom.

```
app  ──(token do app)──▶  Worker (Cloudflare)  ──(chave TomTom)──▶  TomTom
                              │ guarda cada imagem por 2 min: quem está na mesma região divide as requisições
```

**Proteções do servidor:** exige o token do app; só atende imagens que cobrem o Brasil; só zoom 8 a 16; erro da TomTom
não vaza a chave. Cota gratuita da TomTom: 50.000 requisições de imagem por dia (confira em
<https://developer.tomtom.com/pricing>; acima disso a TomTom cobra cerca de US$ 0,08 a cada 1.000, ou bloqueia).

No app o botão do semáforo liga e desliga a camada (começa desligada e o app lembra a escolha). Sem o servidor
configurado, o botão não aparece.

## Configuração (uma vez só)

1. **TomTom**: crie uma conta gratuita em <https://developer.tomtom.com>, crie uma chave de API (o produto "Traffic API"
   precisa estar habilitado) e copie a chave.
2. **Cloudflare**: crie uma conta gratuita em <https://dash.cloudflare.com>.
   - Em *Workers & Pages* escolha o seu subdomínio `workers.dev` (o primeiro acesso pede).
   - Copie o **Account ID** (aparece na página inicial de Workers & Pages, na lateral direita).
   - Em *My Profile → API Tokens → Create Token*, use o modelo **"Edit Cloudflare Workers"** e copie o token.
3. **GitHub** (repositório `arthurbritol/rumo` → Settings → Secrets and variables → Actions → *New repository secret*):

   | Segredo | Valor |
   |---|---|
   | `TOMTOM_KEY` | a chave da TomTom |
   | `CLOUDFLARE_API_TOKEN` | o token da Cloudflare |
   | `CLOUDFLARE_ACCOUNT_ID` | o Account ID da Cloudflare |
   | `TRAFFIC_APP_TOKEN` | uma senha qualquer, longa e aleatória (ex.: 32 letras e números); o app e o servidor usam a mesma |

4. Em *Actions → Servidor de trânsito → Run workflow*. Ele testa e publica. No fim, o endereço aparece no log, no formato
   `https://rumo-traffic.<seu-subdominio>.workers.dev`.
5. Em *Settings → Secrets and variables → Actions → Variables → New repository variable*:
   `TRAFFIC_TILE_URL` = esse endereço (sem barra no final).
6. Gere uma release nova (qualquer mudança no app na `main` faz isso). A partir dela o botão de trânsito aparece.

## Acompanhar o uso

No painel da Cloudflare (Workers & Pages → rumo-traffic → Metrics) aparece o total de requisições; no painel da TomTom,
o consumo da chave. Se passar perto de 50.000 por dia, dá para reduzir o gasto (renovação mais espaçada, zoom mínimo
maior) ou contratar o plano pago.

## Atenções

- A TomTom pede o crédito "© TomTom" visível: ele aparece na informação de fontes do mapa (botão "i"). Confirme as regras de
  uso comercial do plano gratuito com a TomTom antes de publicar na Play Store.
- O token do app fica no APK e pode ser extraído; ele só dificulta uso indevido. Se houver abuso, troque o segredo
  `TRAFFIC_APP_TOKEN` e gere uma release nova.
- A chave da TomTom nunca deve ir para o código nem para o APK.
