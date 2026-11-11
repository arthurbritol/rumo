// Regra de "combina" do "Rolê por perto".

export const RITMOS = ["tranquilo", "moderado", "animado"];
export const DISTANCIAS = ["ate50", "50a150", "mais150"];
export const PARADAS = ["cafe", "mirante", "so_estrada", "sem_parada"];

// Mesmo ritmo E distância igual ou vizinha (ate50~50a150~mais150) E parada igual
// (ou sem_parada com so_estrada). Valor fora das listas nunca combina.
export function combina(a, b) {
  if (!a || !b) return false;
  const ia = DISTANCIAS.indexOf(a.distancia);
  const ib = DISTANCIAS.indexOf(b.distancia);
  if (!RITMOS.includes(a.ritmo) || !RITMOS.includes(b.ritmo)) return false;
  if (ia < 0 || ib < 0 || !PARADAS.includes(a.parada) || !PARADAS.includes(b.parada)) return false;
  if (a.ritmo !== b.ritmo) return false;
  if (Math.abs(ia - ib) > 1) return false;
  if (a.parada === b.parada) return true;
  const par = new Set([a.parada, b.parada]);
  return par.has("sem_parada") && par.has("so_estrada");
}
