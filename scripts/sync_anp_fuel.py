#!/usr/bin/env python3
"""
sync_anp_fuel.py - ETL para normalização de preços de combustíveis da ANP.

Baixa ou lê a série histórica da ANP, normaliza municípios e produtos,
calcula médias/mínimos/máximos municipais e estaduais, deduplica postos revendedores,
e exporta payload JSON comprimido com Gzip (.json.gz).
"""

from __future__ import annotations

import argparse
import csv
from datetime import datetime, timezone
import gzip
import hashlib
import io
import json
import os
import re
import sys
import unicodedata
import urllib.request
from typing import Any, Dict, List, Optional, Tuple


# Centróides das 27 UFs brasileiras (Latitude, Longitude)
STATE_CENTROIDS: Dict[str, Tuple[float, float]] = {
    "AC": (-9.97499, -67.82430),
    "AL": (-9.66599, -35.73500),
    "AP": (0.03493, -51.06940),
    "AM": (-3.11703, -60.02580),
    "BA": (-12.97140, -38.50140),
    "CE": (-3.71839, -38.54340),
    "DF": (-15.77972, -47.92972),
    "ES": (-20.31550, -40.31280),
    "GO": (-16.68690, -49.26480),
    "MA": (-2.53874, -44.28250),
    "MT": (-15.60100, -56.09740),
    "MS": (-20.44860, -54.62950),
    "MG": (-19.92080, -43.93780),
    "PA": (-1.45540, -48.48980),
    "PB": (-7.11532, -34.86100),
    "PR": (-25.42970, -49.27110),
    "PE": (-8.04760, -34.87700),
    "PI": (-5.09194, -42.80340),
    "RJ": (-22.90680, -43.17290),
    "RN": (-5.79448, -35.21100),
    "RS": (-30.03460, -51.21770),
    "RO": (-8.76116, -63.90040),
    "RR": (2.81972, -60.67333),
    "SC": (-27.59540, -48.54800),
    "SP": (-23.55050, -46.63330),
    "SE": (-10.91110, -37.07170),
    "TO": (-10.18440, -48.33360),
}

# Centróides de cidades capitais e polos principais
CITY_COORDINATES: Dict[str, Tuple[float, float]] = {
    "ac_rio_branco": (-9.97499, -67.82430),
    "al_maceio": (-9.66599, -35.73500),
    "ap_macapa": (0.03493, -51.06940),
    "am_manaus": (-3.11703, -60.02580),
    "ba_salvador": (-12.97140, -38.50140),
    "ce_fortaleza": (-3.71839, -38.54340),
    "df_brasilia": (-15.77972, -47.92972),
    "es_vitoria": (-20.31550, -40.31280),
    "go_goiania": (-16.68690, -49.26480),
    "ma_sao_luis": (-2.53874, -44.28250),
    "mt_cuiaba": (-15.60100, -56.09740),
    "ms_campo_grande": (-20.44860, -54.62950),
    "mg_belo_horizonte": (-19.92080, -43.93780),
    "pa_belem": (-1.45540, -48.48980),
    "pb_joao_pessoa": (-7.11532, -34.86100),
    "pr_curitiba": (-25.42970, -49.27110),
    "pe_recife": (-8.04760, -34.87700),
    "pi_teresina": (-5.09194, -42.80340),
    "rj_rio_de_janeiro": (-22.90680, -43.17290),
    "rn_natal": (-5.79448, -35.21100),
    "rs_porto_alegre": (-30.03460, -51.21770),
    "ro_porto_velho": (-8.76116, -63.90040),
    "rr_boa_vista": (2.81972, -60.67333),
    "sc_florianopolis": (-27.59540, -48.54800),
    "sp_sao_paulo": (-23.55050, -46.63330),
    "sp_campinas": (-22.90560, -47.06080),
    "sp_santos": (-23.96080, -46.33360),
    "sp_ribeirao_preto": (-21.17670, -47.81080),
    "se_aracaju": (-10.91110, -37.07170),
    "to_palmas": (-10.18440, -48.33360),
}


def unaccent(text: str) -> str:
    """Remove acentuações e caracteres diacríticos."""
    nfkd = unicodedata.normalize("NFKD", text)
    return "".join(c for c in nfkd if not unicodedata.combining(c))


def normalize_city_key(uf: str, municipio: str) -> str:
    """Gera a chave normalizada da cidade no formato: uf_municipio_sem_acento."""
    clean_mun = unaccent(municipio).strip().lower()
    clean_mun = re.sub(r"[^a-z0-9]+", "_", clean_mun).strip("_")
    return f"{uf.strip().lower()}_{clean_mun}"


def normalize_date(date_str: str) -> str:
    """Converte datas DD/MM/YYYY para formato ISO YYYY-MM-DD."""
    date_str = date_str.strip()
    if "/" in date_str:
        parts = date_str.split("/")
        if len(parts) == 3 and len(parts[2]) == 4:
            return f"{parts[2]}-{parts[1].zfill(2)}-{parts[0].zfill(2)}"
    return date_str


def map_product(produto: str) -> Optional[str]:
    """
    Mapeia os nomes de combustíveis da ANP para as chaves compactas:
    - GASOLINA COMUM / GASOLINA -> gc
    - GASOLINA ADITIVADA -> ga
    - ETANOL / ETANOL HIDRATADO -> et
    - DIESEL S10 / OLEO DIESEL S10 -> ds
    """
    p = unaccent(produto).upper().strip()
    if "GASOLINA ADITIVADA" in p:
        return "ga"
    if "GASOLINA" in p:
        return "gc"
    if "ETANOL" in p:
        return "et"
    if "DIESEL S10" in p or "S10" in p or "OLEO DIESEL S10" in p:
        return "ds"
    return None


def clean_header_name(header: str) -> str:
    """Normaliza nome de cabeçalho para comparação flexível."""
    h = unaccent(header).lower().strip()
    return re.sub(r"[^a-z0-9]", "", h)


def parse_csv_content(content: str) -> List[Dict[str, Any]]:
    """Lê string CSV com delimitador ; ou , e formata as linhas."""
    if not content.strip():
        return []

    # Detecta delimitador (; ou ,)
    first_line = content.strip().splitlines()[0]
    delimiter = ";" if ";" in first_line else ","

    reader = csv.reader(io.StringIO(content), delimiter=delimiter)
    try:
        raw_headers = next(reader)
    except StopIteration:
        return []

    col_map: Dict[str, int] = {}
    for idx, h in enumerate(raw_headers):
        clean_h = clean_header_name(h)
        if clean_h in ("regiaosigla", "regiao"):
            col_map["regiao"] = idx
        elif clean_h in ("estadosigla", "estado", "uf"):
            col_map["uf"] = idx
        elif clean_h in ("municipio", "cidade"):
            col_map["municipio"] = idx
        elif clean_h in ("revenda", "razaosocial", "nome"):
            col_map["revenda"] = idx
        elif clean_h in ("cnpjdarevenda", "cnpj"):
            col_map["cnpj"] = idx
        elif clean_h in ("enderecodarevenda", "endereco"):
            col_map["endereco"] = idx
        elif clean_h in ("bairro",):
            col_map["bairro"] = idx
        elif clean_h in ("cep",):
            col_map["cep"] = idx
        elif clean_h in ("produto",):
            col_map["produto"] = idx
        elif clean_h in ("datadacoleta", "data"):
            col_map["data"] = idx
        elif clean_h in ("valordevenda", "valor", "preco"):
            col_map["valor"] = idx
        elif clean_h in ("bandeira",):
            col_map["bandeira"] = idx
        elif clean_h in ("latitude", "lat"):
            col_map["lat"] = idx
        elif clean_h in ("longitude", "lon", "long"):
            col_map["lon"] = idx

    required = ("uf", "municipio", "produto", "valor")
    for r in required:
        if r not in col_map:
            raise ValueError(f"Coluna obrigatória '{r}' não identificada no cabeçalho: {raw_headers}")

    rows: List[Dict[str, Any]] = []
    for raw_row in reader:
        if not raw_row or len(raw_row) <= max(col_map.values()):
            continue

        uf = raw_row[col_map["uf"]].strip().upper()
        municipio = raw_row[col_map["municipio"]].strip()
        produto_raw = raw_row[col_map["produto"]].strip()
        prod_key = map_product(produto_raw)
        if not prod_key:
            continue

        valor_raw = raw_row[col_map["valor"]].strip().replace(",", ".")
        try:
            valor = float(valor_raw)
            if valor <= 0.0:
                continue
        except ValueError:
            continue

        data_coleta = (
            normalize_date(raw_row[col_map["data"]].strip())
            if "data" in col_map
            else datetime.now(timezone.utc).strftime("%Y-%m-%d")
        )

        cnpj = raw_row[col_map["cnpj"]].strip() if "cnpj" in col_map else ""
        revenda = raw_row[col_map["revenda"]].strip() if "revenda" in col_map else "Posto de Combustível"
        endereco = raw_row[col_map["endereco"]].strip() if "endereco" in col_map else ""
        bairro = raw_row[col_map["bairro"]].strip() if "bairro" in col_map else ""
        cep = raw_row[col_map["cep"]].strip() if "cep" in col_map else ""
        bandeira = raw_row[col_map["bandeira"]].strip() if "bandeira" in col_map else "Branca"

        lat_val = None
        lon_val = None
        if "lat" in col_map and raw_row[col_map["lat"]].strip():
            try:
                lat_val = float(raw_row[col_map["lat"]].strip().replace(",", "."))
            except ValueError:
                pass
        if "lon" in col_map and raw_row[col_map["lon"]].strip():
            try:
                lon_val = float(raw_row[col_map["lon"]].strip().replace(",", "."))
            except ValueError:
                pass

        rows.append({
            "uf": uf,
            "municipio": municipio,
            "revenda": revenda,
            "cnpj": cnpj,
            "endereco": endereco,
            "bairro": bairro,
            "cep": cep,
            "produto": prod_key,
            "data": data_coleta,
            "valor": valor,
            "bandeira": bandeira,
            "lat": lat_val,
            "lon": lon_val,
        })

    return rows


def get_coordinates(city_key: str, uf: str) -> Tuple[float, float]:
    """Obtém centróide da cidade com fallback para centróide da UF."""
    if city_key in CITY_COORDINATES:
        return CITY_COORDINATES[city_key]
    if uf in STATE_CENTROIDS:
        return STATE_CENTROIDS[uf]
    return (-14.2350, -51.9253)  # Centróide aproximado do Brasil


def build_payload(rows: List[Dict[str, Any]], max_stations: Optional[int] = None) -> Dict[str, Any]:
    """Agrega preços por cidade e estado e deduplica postos revendedores."""
    cidades_data: Dict[str, Dict[str, Any]] = {}
    estados_data: Dict[str, Dict[str, Any]] = {}
    postos_map: Dict[str, Dict[str, Any]] = {}

    for row in rows:
        uf = row["uf"]
        mun = row["municipio"]
        city_key = normalize_city_key(uf, mun)
        prod = row["produto"]
        val = row["valor"]
        data = row["data"]

        # Agregação municipal
        if city_key not in cidades_data:
            cidades_data[city_key] = {
                "uf": uf,
                "mun": mun,
                "gc": [],
                "ga": [],
                "et": [],
                "ds": [],
                "dates": [],
            }
        cidades_data[city_key][prod].append(val)
        cidades_data[city_key]["dates"].append(data)

        # Agregação estadual
        if uf not in estados_data:
            estados_data[uf] = {
                "uf": uf,
                "gc": [],
                "et": [],
                "ds": [],
                "dates": [],
            }
        if prod in ("gc", "et", "ds"):
            estados_data[uf][prod].append(val)
        estados_data[uf]["dates"].append(data)

        # Identificador do posto
        raw_cnpj = row["cnpj"]
        clean_cnpj = re.sub(r"[^0-9a-zA-Z]", "", raw_cnpj).lower()
        if clean_cnpj:
            station_id = clean_cnpj if clean_cnpj.startswith("posto_") else f"posto_{clean_cnpj}"
        else:
            seed_hash = hashlib.md5(f"{row['revenda']}_{city_key}_{row['endereco']}".encode()).hexdigest()[:12]
            station_id = f"posto_{seed_hash}"

        if station_id not in postos_map:
            lat, lon = get_coordinates(city_key, uf)
            if row["lat"] is not None and row["lon"] is not None:
                lat, lon = row["lat"], row["lon"]

            postos_map[station_id] = {
                "id": station_id,
                "nome": row["revenda"],
                "bandeira": row["bandeira"],
                "cityKey": city_key,
                "uf": uf,
                "endereco": row["endereco"],
                "bairro": row["bairro"],
                "cep": row["cep"],
                "lat": lat,
                "lon": lon,
                "gc": None,
                "ga": None,
                "et": None,
                "ds": None,
                "data": data,
                "_latest_dates": {},
            }

        st = postos_map[station_id]
        # Atualiza o preço se for uma data mais recente ou primeira coleta deste produto
        prev_date = st["_latest_dates"].get(prod)
        if prev_date is None or data >= prev_date:
            st[prod] = val
            st["_latest_dates"][prod] = data

        if data > st["data"]:
            st["data"] = data

    # Constrói cidades finais
    final_cidades: Dict[str, Dict[str, Any]] = {}
    for c_key, c_raw in cidades_data.items():
        lat, lon = get_coordinates(c_key, c_raw["uf"])
        c_dict: Dict[str, Any] = {
            "uf": c_raw["uf"],
            "mun": c_raw["mun"],
            "lat": lat,
            "lon": lon,
            "ref": max(c_raw["dates"]) if c_raw["dates"] else "",
        }
        for p in ("gc", "ga", "et", "ds"):
            vals = c_raw[p]
            if vals:
                c_dict[f"{p}Avg"] = round(sum(vals) / len(vals), 2)
                c_dict[f"{p}Min"] = round(min(vals), 2)
                c_dict[f"{p}Max"] = round(max(vals), 2)
            else:
                c_dict[f"{p}Avg"] = None
                c_dict[f"{p}Min"] = None
                c_dict[f"{p}Max"] = None
        final_cidades[c_key] = c_dict

    # Constrói estados finais
    final_estados: Dict[str, Dict[str, Any]] = {}
    for uf, s_raw in estados_data.items():
        lat, lon = STATE_CENTROIDS.get(uf, (-14.2350, -51.9253))
        s_dict: Dict[str, Any] = {
            "uf": uf,
            "lat": lat,
            "lon": lon,
            "ref": max(s_raw["dates"]) if s_raw["dates"] else "",
        }
        for p in ("gc", "et", "ds"):
            vals = s_raw[p]
            if vals:
                s_dict[f"{p}Avg"] = round(sum(vals) / len(vals), 2)
            else:
                s_dict[f"{p}Avg"] = None
        final_estados[uf] = s_dict

    # Constrói postos finais
    final_postos: List[Dict[str, Any]] = []
    for st in postos_map.values():
        clean_st = {k: v for k, v in st.items() if not k.startswith("_")}
        final_postos.append(clean_st)

    final_postos.sort(key=lambda p: (p["uf"], p["cityKey"], p["nome"]))

    if max_stations and max_stations > 0:
        final_postos = final_postos[:max_stations]

    return {
        "v": "1.0",
        "geradoEm": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "cidades": final_cidades,
        "estados": final_estados,
        "postos": final_postos,
    }


def generate_seed_payload() -> Dict[str, Any]:
    """
    Gera um payload robusto para o seed inicial do app,
    contendo todas as 27 capitais/estados brasileiros com médias representativas
    e postos de exemplo para pleno funcionamento 100% offline no primeiro boot.
    """
    # Médias representativas por UF (gc, et, ds)
    uf_samples: Dict[str, Tuple[str, float, float, float]] = {
        "AC": ("Rio Branco", 6.85, 4.89, 6.95),
        "AL": ("Maceió", 6.09, 4.45, 5.99),
        "AP": ("Macapá", 5.65, 4.99, 6.25),
        "AM": ("Manaus", 6.29, 4.69, 6.39),
        "BA": ("Salvador", 6.19, 4.39, 6.05),
        "CE": ("Fortaleza", 6.15, 4.59, 5.95),
        "DF": ("Brasília", 5.89, 3.99, 5.79),
        "ES": ("Vitória", 5.95, 4.19, 5.85),
        "GO": ("Goiânia", 5.79, 3.89, 5.75),
        "MA": ("São Luís", 5.69, 4.35, 5.89),
        "MT": ("Cuiabá", 5.85, 3.69, 5.95),
        "MS": ("Campo Grande", 5.75, 3.79, 5.85),
        "MG": ("Belo Horizonte", 5.89, 3.95, 5.79),
        "PA": ("Belém", 5.99, 4.59, 6.15),
        "PB": ("João Pessoa", 5.89, 4.19, 5.79),
        "PR": ("Curitiba", 5.99, 4.09, 5.89),
        "PE": ("Recife", 5.89, 4.15, 5.79),
        "PI": ("Teresina", 5.95, 4.29, 5.95),
        "RJ": ("Rio de Janeiro", 5.99, 4.25, 5.89),
        "RN": ("Natal", 6.09, 4.49, 5.99),
        "RS": ("Porto Alegre", 6.05, 4.35, 5.95),
        "RO": ("Porto Velho", 6.45, 4.75, 6.35),
        "RR": ("Boa Vista", 6.35, 4.95, 6.45),
        "SC": ("Florianópolis", 6.09, 4.29, 5.99),
        "SP": ("São Paulo", 5.79, 3.85, 5.69),
        "SE": ("Aracaju", 5.95, 4.39, 5.85),
        "TO": ("Palmas", 6.09, 4.25, 6.05),
    }

    ref_date = "2026-11-10"
    cidades: Dict[str, Dict[str, Any]] = {}
    estados: Dict[str, Dict[str, Any]] = {}
    postos: List[Dict[str, Any]] = []

    sample_brands = ["Petrobras / Vibra", "Ipiranga", "Shell", "Ale", "Branca"]

    for uf, (mun, gc, et, ds) in uf_samples.items():
        city_key = normalize_city_key(uf, mun)
        c_lat, c_lon = get_coordinates(city_key, uf)
        s_lat, s_lon = STATE_CENTROIDS.get(uf, (c_lat, c_lon))

        cidades[city_key] = {
            "uf": uf,
            "mun": mun,
            "lat": c_lat,
            "lon": c_lon,
            "gcAvg": gc,
            "gcMin": round(gc - 0.20, 2),
            "gcMax": round(gc + 0.30, 2),
            "gaAvg": round(gc + 0.25, 2),
            "gaMin": round(gc + 0.05, 2),
            "gaMax": round(gc + 0.50, 2),
            "etAvg": et,
            "etMin": round(et - 0.15, 2),
            "etMax": round(et + 0.20, 2),
            "dsAvg": ds,
            "dsMin": round(ds - 0.15, 2),
            "dsMax": round(ds + 0.25, 2),
            "ref": ref_date,
        }

        estados[uf] = {
            "uf": uf,
            "lat": s_lat,
            "lon": s_lon,
            "gcAvg": gc,
            "etAvg": et,
            "dsAvg": ds,
            "ref": ref_date,
        }

        # Cria 2 postos de referência para cada capital
        for i, brand in enumerate(sample_brands[:2], start=1):
            offset = 0.005 * i
            st_id = f"posto_seed_{uf.lower()}_{i:02d}"
            postos.append({
                "id": st_id,
                "nome": f"Auto Posto {brand.split()[0]} {mun}",
                "bandeira": brand,
                "cityKey": city_key,
                "uf": uf,
                "endereco": f"Av. Principal, {100 * i}",
                "bairro": "Centro",
                "cep": "00000-000",
                "lat": round(c_lat + offset, 5),
                "lon": round(c_lon + offset, 5),
                "gc": round(gc + (-0.05 if i == 1 else 0.08), 2),
                "ga": round(gc + 0.25, 2),
                "et": round(et + (-0.03 if i == 1 else 0.05), 2),
                "ds": round(ds, 2),
                "data": ref_date,
            })

    # Adiciona postos extras detalhados para capitais de alta densidade (SP, RJ, PE, MG)
    extras = [
        ("sp_sao_paulo", "SP", "Posto Ipiranga Av Paulista", "Ipiranga", "Av. Paulista, 1500", "Bela Vista", -23.5614, -46.6565, 5.75, 5.99, 3.79, 5.65),
        ("sp_sao_paulo", "SP", "Shell Marginal Pinheiros", "Shell", "Marginal Pinheiros, 4500", "Pinheiros", -23.5701, -46.6961, 5.69, 5.95, 3.75, 5.59),
        ("rj_rio_de_janeiro", "RJ", "Posto BR Copacabana", "Petrobras / Vibra", "Av. Atlântica, 2000", "Copacabana", -22.9698, -43.1812, 5.95, 6.19, 4.19, 5.85),
        ("pe_recife", "PE", "Posto Shell Derby", "Shell", "Praça do Derby, 50", "Derby", -8.0560, -34.9020, 5.85, 6.09, 4.09, 5.75),
        ("mg_belo_horizonte", "MG", "Posto Ale Savassi", "Ale", "Av. do Contorno, 6000", "Savassi", -19.9385, -43.9341, 5.85, 6.05, 3.89, 5.72),
    ]
    for idx, (c_key, uf, nome, brand, end, bairro, lat, lon, gc, ga, et, ds) in enumerate(extras, start=100):
        postos.append({
            "id": f"posto_seed_{idx}",
            "nome": nome,
            "bandeira": brand,
            "cityKey": c_key,
            "uf": uf,
            "endereco": end,
            "bairro": bairro,
            "cep": "00000-000",
            "lat": lat,
            "lon": lon,
            "gc": gc,
            "ga": ga,
            "et": et,
            "ds": ds,
            "data": ref_date,
        })

    return {
        "v": "1.0",
        "geradoEm": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "cidades": cidades,
        "estados": estados,
        "postos": postos,
    }


def read_source(input_source: str) -> str:
    """Lê CSV da ANP a partir de arquivo local ou URL remota HTTP/HTTPS."""
    if input_source.startswith("http://") or input_source.startswith("https://"):
        req = urllib.request.Request(
            input_source,
            headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Rumo-ETL/1.0"},
        )
        with urllib.request.urlopen(req, timeout=120) as resp:
            raw_bytes = resp.read()
    else:
        with open(input_source, "rb") as f:
            raw_bytes = f.read()

    # Descomprime se estiver gzippado
    if raw_bytes.startswith(b"\x1f\x8b"):
        raw_bytes = gzip.decompress(raw_bytes)

    # Tenta decodificar em utf-8 ou latin-1
    try:
        return raw_bytes.decode("utf-8")
    except UnicodeDecodeError:
        return raw_bytes.decode("latin-1")


def process_anp_data(
    input_source: Optional[str],
    output_path: str,
    max_stations: Optional[int] = None,
    use_seed_fallback: bool = False,
) -> None:
    """Pipeline de processamento de ponta a ponta."""
    if use_seed_fallback or not input_source:
        payload = generate_seed_payload()
        if max_stations and max_stations > 0:
            payload["postos"] = payload["postos"][:max_stations]
    else:
        content = read_source(input_source)
        rows = parse_csv_content(content)
        payload = build_payload(rows, max_stations=max_stations)

    # Garante diretório de destino
    parent_dir = os.path.dirname(os.path.abspath(output_path))
    if parent_dir:
        os.makedirs(parent_dir, exist_ok=True)

    # Serializa e comprime em gzip
    json_bytes = json.dumps(payload, ensure_ascii=False, indent=None).encode("utf-8")
    with gzip.open(output_path, "wb") as gz_out:
        gz_out.write(json_bytes)

    print(
        f"Sucesso: {output_path} gerado "
        f"({len(payload['cidades'])} cidades, {len(payload['estados'])} estados, {len(payload['postos'])} postos, "
        f"{len(json_bytes)} bytes brutos -> {os.path.getsize(output_path)} bytes comprimidos)"
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="ETL e normalizador de preços de combustíveis da ANP")
    parser.add_argument(
        "--input",
        type=str,
        default=None,
        help="Caminho do arquivo CSV ou URL remota da ANP. Se omitido, usa geração de seed.",
    )
    parser.add_argument(
        "--output",
        type=str,
        required=True,
        help="Caminho do arquivo final compactado (.json.gz)",
    )
    parser.add_argument(
        "--max-stations",
        type=int,
        default=None,
        help="Limite máximo de postos para persistir no payload",
    )
    parser.add_argument(
        "--generate-seed",
        action="store_true",
        help="Força a geração do seed inicial das 27 capitais/estados",
    )

    args = parser.parse_args()

    process_anp_data(
        input_source=args.input,
        output_path=args.output,
        max_stations=args.max_stations,
        use_seed_fallback=args.generate_seed or (args.input is None),
    )


if __name__ == "__main__":
    main()
