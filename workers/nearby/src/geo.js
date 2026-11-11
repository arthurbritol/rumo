// Geometria do "Rolê por perto": quadrados de 1° x 1°, distância e desvio de posição.

const M_POR_GRAU = 111320; // metros por grau de latitude
const RAIO_TERRA_M = 6371000;

// Id do quadrado de 1° x 1° que contém o ponto, ex.: Vitória -> "-21:-41".
export function cellId(lat, lon) {
  return `${Math.floor(lat)}:${Math.floor(lon)}`;
}

// Ids dos quadrados que o círculo (centro, raio em km) toca, sem repetir.
export function cellsInRadius(lat, lon, radiusKm) {
  const rM = radiusKm * 1000;
  const dLat = rM / M_POR_GRAU;
  const dLon = rM / (M_POR_GRAU * Math.cos((lat * Math.PI) / 180));
  const ids = [];
  for (let la = Math.floor(lat - dLat); la <= Math.floor(lat + dLat); la++) {
    for (let lo = Math.floor(lon - dLon); lo <= Math.floor(lon + dLon); lo++) {
      // Ponto do quadrado mais próximo do centro (aproximação plana em metros).
      const nLat = Math.min(Math.max(lat, la), la + 1);
      const nLon = Math.min(Math.max(lon, lo), lo + 1);
      const dy = (nLat - lat) * M_POR_GRAU;
      const dx = (nLon - lon) * M_POR_GRAU * Math.cos((lat * Math.PI) / 180);
      if (Math.sqrt(dx * dx + dy * dy) <= rM) ids.push(`${la}:${lo}`);
    }
  }
  return ids;
}

// Distância em metros entre dois pontos (haversine).
export function distanceM(a, b) {
  const rad = Math.PI / 180;
  const dLat = (b.lat - a.lat) * rad;
  const dLon = (b.lon - a.lon) * rad;
  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLon / 2) ** 2;
  return 2 * RAIO_TERRA_M * Math.asin(Math.min(1, Math.sqrt(h)));
}

// Desvio determinístico (por uid e por dia) de 200 a 400 m, em direção derivada do hash.
export async function fuzzOffset(uid, dayIso, secret) {
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey(
    'raw',
    enc.encode(secret),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign'],
  );
  const sig = new Uint8Array(await crypto.subtle.sign('HMAC', key, enc.encode(`${uid}|${dayIso}`)));
  const modulo = 200 + (sig[0] / 255) * 200;
  const angulo = (((sig[1] << 8) | sig[2]) / 65535) * 2 * Math.PI;
  return { eastM: modulo * Math.cos(angulo), northM: modulo * Math.sin(angulo) };
}

// Aplica um desvio em metros a uma coordenada.
export function applyOffset(lat, lon, off) {
  const dLat = off.northM / M_POR_GRAU;
  const dLon = off.eastM / (M_POR_GRAU * Math.cos((lat * Math.PI) / 180));
  return { lat: lat + dLat, lon: lon + dLon };
}
