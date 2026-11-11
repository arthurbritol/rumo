// Servidor intermediário de trânsito do Rumo (Cloudflare Worker).
//
// O app pede  GET /flow/{z}/{x}/{y}.png?t=<token do app>  e este servidor pede a imagem de fluxo de trânsito à TomTom
// com a chave guardada aqui (a chave NUNCA vai dentro do APK). Cada imagem fica guardada por 2 minutos no cache da
// Cloudflare, então todas as pessoas da mesma região compartilham as mesmas requisições à TomTom.
//
// Proteções: token do app, só imagens que cobrem o Brasil, e só zoom 8 a 16 (limita quem tentar gastar a cota).

export const TTL_SECONDS = 120;
export const MIN_ZOOM = 8;
export const MAX_ZOOM = 16;
// Retângulo que cobre o Brasil (com folga nas fronteiras).
export const BRAZIL = { south: -34.0, north: 6.0, west: -74.5, east: -33.5 };

const TILE_PATH = /^\/flow\/(\d{1,2})\/(\d{1,6})\/(\d{1,6})\.png$/;

/** Limites geográficos de uma imagem x/y/z (esquema Web Mercator). */
export function tileBounds(z, x, y) {
  const n = 2 ** z;
  const lon = (tx) => (tx / n) * 360 - 180;
  const lat = (ty) => (Math.atan(Math.sinh(Math.PI * (1 - (2 * ty) / n))) * 180) / Math.PI;
  return { west: lon(x), east: lon(x + 1), north: lat(y), south: lat(y + 1) };
}

/** A imagem cruza o retângulo do Brasil? */
export function intersectsBrazil(z, x, y) {
  const b = tileBounds(z, x, y);
  return b.east > BRAZIL.west && b.west < BRAZIL.east && b.north > BRAZIL.south && b.south < BRAZIL.north;
}

function text(status, body, extra = {}) {
  return new Response(body, { status, headers: { 'content-type': 'text/plain; charset=utf-8', ...extra } });
}

export default {
  async fetch(request, env) {
    if (request.method !== 'GET' && request.method !== 'HEAD') return text(405, 'method not allowed');
    const url = new URL(request.url);

    const m = TILE_PATH.exec(url.pathname);
    if (!m) return text(404, 'not found');

    // Token do app: sem ele, qualquer pessoa poderia gastar a cota da TomTom.
    if (env.APP_TOKEN && url.searchParams.get('t') !== env.APP_TOKEN) return text(403, 'forbidden');

    const z = Number(m[1]);
    const x = Number(m[2]);
    const y = Number(m[3]);
    const n = 2 ** z;
    if (z < MIN_ZOOM || z > MAX_ZOOM || x >= n || y >= n) return text(404, 'tile out of range');
    if (!intersectsBrazil(z, x, y)) return text(404, 'tile outside coverage');
    if (!env.TOMTOM_KEY) return text(500, 'server not configured');

    // relative0 = velocidade relativa ao fluxo livre; 512 px = metade das requisições de 256 px.
    const upstream =
      `https://api.tomtom.com/traffic/map/4/tile/flow/relative0/${z}/${x}/${y}.png` +
      `?key=${encodeURIComponent(env.TOMTOM_KEY)}&tileSize=512`;

    const res = await fetch(upstream, {
      // Cache de borda da Cloudflare: 2 min para sucesso; erros não ficam guardados (nem a chave vira cache de erro).
      cf: { cacheEverything: true, cacheTtlByStatus: { '200-299': TTL_SECONDS, '300-599': 0 } },
    });

    if (!res.ok) {
      return text(502, 'upstream error', { 'cache-control': 'no-store' });
    }
    return new Response(res.body, {
      status: 200,
      headers: {
        'content-type': 'image/png',
        'cache-control': `public, max-age=${TTL_SECONDS}`,
        'access-control-allow-origin': '*',
      },
    });
  },
};
