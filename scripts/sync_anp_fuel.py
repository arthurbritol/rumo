#!/usr/bin/env python3
"""
sync_anp_fuel.py - ETL para normalização de preços de combustíveis da ANP.

Baixa o levantamento oficial mais recente de preços de combustíveis da ANP:
https://www.gov.br/anp/pt-br/assuntos/precos-e-defesa-da-concorrencia/precos/levantamento-de-precos-de-combustiveis-ultimas-semanas-pesquisadas

Filtra estritamente os combustíveis automotivos solicitados:
- Gasolina Comum (gc)
- Gasolina Aditivada (ga)
- Etanol Hidratado (et)
Ignorando GLP, GNV e Diesel.

Normaliza municípios e UFs de todo o Brasil, calcula médias, mínimos e máximos
municipais e estaduais, mapeia postos revendedores e exporta payload JSON compactado (.json.gz).
"""

from __future__ import annotations

import argparse
import csv
from datetime import datetime, timezone, timedelta
import gzip
import hashlib
import io
import json
import os
import re
import sys
import unicodedata
import urllib.request
import zipfile
import xml.etree.ElementTree as ET
from collections import defaultdict
from typing import Any, Dict, List, Optional, Tuple


# URL oficial da página da ANP com as últimas semanas pesquisadas
OFFICIAL_ANP_SURVEY_PAGE = (
    "https://www.gov.br/anp/pt-br/assuntos/precos-e-defesa-da-concorrencia/precos/"
    "levantamento-de-precos-de-combustiveis-ultimas-semanas-pesquisadas"
)

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

# Centróides das principais capitais e cidades polo
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
    "sp_sao_jose_dos_campos": (-23.1896, -45.8841),
    "sp_sorocaba": (-23.5015, -47.4526),
    "sp_piracicaba": (-22.7338, -47.6476),
    "sp_bauru": (-22.3145, -49.0587),
    "mg_uberlandia": (-18.9186, -48.2772),
    "mg_juiz_de_fora": (-21.7642, -43.3503),
    "rj_niteroi": (-22.8832, -43.1034),
    "pr_londrina": (-23.3045, -51.1696),
    "pr_maringa": (-23.4210, -51.9331),
    "sc_joinville": (-26.3044, -48.8456),
    "rs_caxias_do_sul": (-29.1678, -51.1794),
    "ba_feira_de_santana": (-12.2576, -38.9667),
    "pe_caruaru": (-8.2833, -35.9761),
    "pe_petrolina": (-9.3891, -40.5028),
    "pb_campina_grande": (-7.2307, -35.8817),
    "se_aracaju": (-10.91110, -37.07170),
    "to_palmas": (-10.18440, -48.33360),
}

PLACEHOLDER_NAME = "Posto de Combustível"

ESTADOS_NOME_TO_UF: Dict[str, str] = {
    "ACRE": "AC", "ALAGOAS": "AL", "AMAPA": "AP", "AMAZONAS": "AM",
    "BAHIA": "BA", "CEARA": "CE", "DISTRITO FEDERAL": "DF", "ESPIRITO SANTO": "ES",
    "GOIAS": "GO", "MARANHAO": "MA", "MATO GROSSO": "MT", "MATO GROSSO DO SUL": "MS",
    "MINAS GERAIS": "MG", "PARA": "PA", "PARAIBA": "PB", "PARANA": "PR",
    "PERNAMBUCO": "PE", "PIAUI": "PI", "RIO DE JANEIRO": "RJ",
    "RIO GRANDE DO NORTE": "RN", "RIO GRANDE DO SUL": "RS", "RONDONIA": "RO",
    "RORAIMA": "RR", "SANTA CATARINA": "SC", "SAO PAULO": "SP",
    "SERGIPE": "SE", "TOCANTINS": "TO"
}


def unaccent(text: str) -> str:
    """Remove acentuações e caracteres diacríticos."""
    nfkd = unicodedata.normalize("NFKD", text)
    return "".join(c for c in nfkd if not unicodedata.combining(c)).upper().strip()


def normalize_city_key(uf: str, municipio: str) -> str:
    """Gera chave estável para o município (ex: 'sp_campinas')."""
    clean_mun = unaccent(municipio).lower()
    clean_mun = re.sub(r"[^a-z0-9]+", "_", clean_mun).strip("_")
    return f"{uf.lower()}_{clean_mun}"


def get_coordinates(city_key: str, uf: str) -> Tuple[float, float]:
    """Retorna coordenadas da cidade cadastrada ou centróide da UF."""
    if city_key in CITY_COORDINATES:
        return CITY_COORDINATES[city_key]
    return STATE_CENTROIDS.get(uf.upper(), (-14.2350, -51.9253))


UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36 Rumo-ETL/3.0"

# Série histórica (dados abertos): um CSV por mês, com todos os postos pesquisados naquele mês.
OFFICIAL_ANP_HISTORY_PAGE = (
    "https://www.gov.br/anp/pt-br/centrais-de-conteudo/dados-abertos/serie-historica-de-precos-de-combustiveis"
)

# Prices older than this (relative to the newest collection date) don't enter city/state averages.
AVERAGE_WINDOW_DAYS = 45


def _http_get(url: str, timeout: int = 60) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read()


def _page_links(page_url: str, pattern: str) -> List[str]:
    """Links (absolutos) da página que casam com `pattern`, sem repetição."""
    html = _http_get(page_url, timeout=30).decode("utf-8", errors="ignore")
    found = re.findall(r'href=["\']([^"\']*' + pattern + r')["\']', html, re.IGNORECASE)
    out: List[str] = []
    for link in found:
        if not link.startswith("http"):
            link = f"https://www.gov.br{link}"
        if link not in out:
            out.append(link)
    return out


def history_url_sort_key(url: str) -> Tuple[int, int]:
    """(ano, mês) do CSV mensal. A ANP mudou o padrão do nome: '2025/precos-gasolina-etanol-07.csv' e '2026/07-dados-abertos-...csv'."""
    y = re.search(r"/dsan/(\d{4})/", url)
    if not y:
        return (0, 0)
    name = url.rsplit("/", 1)[-1]
    m = re.match(r"(\d{2})-", name) or re.search(r"-(\d{2})\.csv$", name)
    return (int(y.group(1)), int(m.group(1))) if m else (0, 0)


def weekly_url_sort_key(url: str) -> str:
    """Data inicial da semana (aaaammdd) no nome do arquivo; os nomes da ANP misturam '-' e '_'."""
    m = re.search(r"(\d{4})[-_](\d{2})[-_](\d{2})", url.rsplit("/", 1)[-1])
    return "".join(m.groups()) if m else ""


def list_history_urls(months: int = 12) -> List[str]:
    links = _page_links(OFFICIAL_ANP_HISTORY_PAGE, r"dsan/\d{4}/[^\"']*etanol[^\"']*")
    links = [u for u in links if history_url_sort_key(u) != (0, 0)]
    return sorted(links, key=history_url_sort_key)[-months:]


def list_weekly_urls(weeks: int = 4) -> List[str]:
    links = _page_links(OFFICIAL_ANP_SURVEY_PAGE, r"revendas_lpc_[^\"']+\.xlsx")
    links = [u for u in links if weekly_url_sort_key(u)]
    return sorted(links, key=weekly_url_sort_key)[-weeks:]


def fetch_history_records(months: int = 12) -> List[Dict[str, Any]]:
    """Base: série histórica dos últimos `months` meses (todos os postos que a ANP pesquisou no período)."""
    records: List[Dict[str, Any]] = []
    for url in list_history_urls(months):
        try:
            print(f"Baixando série histórica: {url}")
            records.extend(parse_history_csv(_http_get(url, timeout=120)))
        except Exception as e:  # um mês fora do ar não derruba a base inteira
            print(f"Aviso: não foi possível ler {url}: {e}")
    return records


def fetch_weekly_records(weeks: int = 4) -> List[Dict[str, Any]]:
    """Atualização: as planilhas semanais mais recentes (preço de revenda por posto)."""
    records: List[Dict[str, Any]] = []
    for url in list_weekly_urls(weeks):
        try:
            print(f"Baixando semana: {url}")
            content = _http_get(url, timeout=120)
            if len(content) > 50_000:
                records.extend(parse_xlsx_records(content))
        except Exception as e:
            print(f"Aviso: não foi possível ler {url}: {e}")
    return records


def fetch_latest_anp_weekly_survey() -> Optional[bytes]:
    """Compatibilidade: baixa só a planilha da semana mais recente."""
    try:
        urls = list_weekly_urls(1)
        return _http_get(urls[-1], timeout=120) if urls else None
    except Exception as e:
        print(f"Aviso ao consultar página semanal da ANP: {e}")
        return None


def map_product(raw: str) -> Optional[str]:
    """GASOLINA / GASOLINA COMUM -> gc, GASOLINA ADITIVADA -> ga, ETANOL [HIDRATADO] -> et, outros -> None."""
    p = unaccent(raw)
    if "GASOLINA" in p and "ADITIVADA" in p:
        return "ga"
    if "GASOLINA" in p:
        return "gc"
    if "ETANOL" in p:
        return "et"
    return None


def parse_history_csv(raw: bytes) -> List[Dict[str, Any]]:
    """CSV mensal da série histórica (';', UTF-8 com BOM) -> registros no mesmo formato do XLSX semanal."""
    text = raw.decode("utf-8-sig", errors="replace")
    reader = csv.DictReader(io.StringIO(text, newline=""), delimiter=";")
    records: List[Dict[str, Any]] = []
    for row in reader:
        prod = map_product(row.get("Produto", ""))
        if prod is None:
            continue
        try:
            price = float((row.get("Valor de Venda") or "").replace(",", ".").strip())
        except ValueError:
            continue
        if price <= 0.0 or price > 30.0:
            continue
        uf = (row.get("Estado - Sigla") or "").strip().upper()
        mun = (row.get("Municipio") or "").strip().title()
        if not uf or not mun:
            continue
        rua = (row.get("Nome da Rua") or "").strip()
        numero = (row.get("Numero Rua") or "").strip()
        endereco = rua
        if numero and numero.upper() not in ("SN", "S/N", "-"):
            endereco = f"{rua}, {numero}".strip(", ")
        records.append({
            "cnpj": (row.get("CNPJ da Revenda") or "").strip(),
            "nome": (row.get("Revenda") or "").strip() or PLACEHOLDER_NAME,
            "bandeira": (row.get("Bandeira") or "").strip() or "Branca",
            "endereco": endereco,
            "bairro": (row.get("Bairro") or "").strip(),
            "cep": (row.get("Cep") or "").strip(),
            "mun": mun,
            "uf": uf,
            "prod": prod,
            "preco": price,
            "data": (row.get("Data da Coleta") or "").strip(),
        })
    return records


def date_key(data: str) -> str:
    """'dd/mm/aaaa' -> 'aaaammdd' (ordenável); data inválida -> ''."""
    m = re.fullmatch(r"(\d{2})/(\d{2})/(\d{4})", (data or "").strip())
    return f"{m.group(3)}{m.group(2)}{m.group(1)}" if m else ""


def station_key(cnpj: str, nome: str, city_key: str, endereco: str) -> str:
    """CNPJ (14 dígitos) é o id estável do posto; sem CNPJ, um hash do nome/cidade/endereço."""
    digits = re.sub(r"\D", "", cnpj or "")
    if digits:
        return digits.zfill(14)
    return hashlib.md5(f"{nome}_{city_key}_{endereco}".encode()).hexdigest()[:12]


def parse_xlsx_records(xlsx_bytes: bytes) -> List[Dict[str, Any]]:
    """
    Extrai registros do arquivo XLSX de revendas da ANP de forma 100% nativa (zipfile + xml.etree).
    Filtra estritamente: GASOLINA COMUM, GASOLINA ADITIVADA e ETANOL.
    """
    records: List[Dict[str, Any]] = []
    with zipfile.ZipFile(io.BytesIO(xlsx_bytes)) as z:
        # 1. Carrega tabela de strings compartilhadas (sharedStrings.xml)
        shared_strings: List[str] = []
        if "xl/sharedStrings.xml" in z.namelist():
            tree = ET.fromstring(z.read("xl/sharedStrings.xml"))
            for si in tree.findall("{http://schemas.openxmlformats.org/spreadsheetml/2006/main}si"):
                t = si.find("{http://schemas.openxmlformats.org/spreadsheetml/2006/main}t")
                if t is not None:
                    shared_strings.append(t.text or "")
                else:
                    txt = "".join(
                        r.findtext("{http://schemas.openxmlformats.org/spreadsheetml/2006/main}t") or ""
                        for r in si.findall("{http://schemas.openxmlformats.org/spreadsheetml/2006/main}r")
                    )
                    shared_strings.append(txt)

        # 2. Localiza a planilha principal
        sheet_file = "xl/worksheets/sheet1.xml"
        if sheet_file not in z.namelist():
            candidates = [n for n in z.namelist() if n.startswith("xl/worksheets/sheet")]
            if candidates:
                sheet_file = candidates[0]
            else:
                return []

        sheet_tree = ET.fromstring(z.read(sheet_file))
        sheet_data = sheet_tree.find("{http://schemas.openxmlformats.org/spreadsheetml/2006/main}sheetData")
        if sheet_data is None:
            return []

        rows = sheet_data.findall("{http://schemas.openxmlformats.org/spreadsheetml/2006/main}row")

        def get_row_values(row_elem) -> List[str]:
            vals: List[str] = []
            for c in row_elem.findall("{http://schemas.openxmlformats.org/spreadsheetml/2006/main}c"):
                t = c.get("t")
                v = c.find("{http://schemas.openxmlformats.org/spreadsheetml/2006/main}v")
                val = v.text if v is not None else ""
                if t == "s" and val.isdigit():
                    idx = int(val)
                    val = shared_strings[idx] if idx < len(shared_strings) else val
                vals.append(val)
            return vals

        # 3. Localiza linha de cabeçalho
        header_idx = -1
        headers: List[str] = []
        for i, r in enumerate(rows[:25]):
            row_vals = [unaccent(str(x)) for x in get_row_values(r)]
            if "CNPJ" in row_vals and "PRODUTO" in row_vals:
                header_idx = i
                headers = row_vals
                break

        if header_idx == -1:
            return []

        col_map = {h: idx for idx, h in enumerate(headers)}
        prod_col = col_map.get("PRODUTO")
        price_col = col_map.get("PRECO DE REVENDA") or col_map.get("VALOR DE VENDA")
        mun_col = col_map.get("MUNICIPIO")
        est_col = col_map.get("ESTADO")
        band_col = col_map.get("BANDEIRA")
        fant_col = col_map.get("FANTASIA")
        cnpj_col = col_map.get("CNPJ")
        end_col = col_map.get("ENDERECO")
        num_col = col_map.get("NUMERO")
        bairro_col = col_map.get("BAIRRO")
        cep_col = col_map.get("CEP")
        data_col = col_map.get("DATA DA COLETA")

        if prod_col is None or price_col is None or mun_col is None or est_col is None:
            return []

        for r in rows[header_idx + 1:]:
            vals = get_row_values(r)
            if len(vals) <= max(prod_col, price_col):
                continue

            raw_prod = unaccent(str(vals[prod_col]))
            # Filtro estrito: somente Gasolina Comum, Gasolina Aditivada, Etanol
            prod_code = None
            if "GASOLINA" in raw_prod and "ADITIVADA" in raw_prod:
                prod_code = "ga"
            elif "GASOLINA" in raw_prod:
                prod_code = "gc"
            elif "ETANOL" in raw_prod:
                prod_code = "et"
            else:
                continue

            raw_price = str(vals[price_col]).replace(",", ".").strip()
            try:
                price = float(raw_price)
                if price <= 0.0 or price > 30.0:
                    continue
            except (ValueError, TypeError):
                continue

            raw_est = unaccent(str(vals[est_col]))
            uf = ESTADOS_NOME_TO_UF.get(raw_est, raw_est if len(raw_est) == 2 else "BR")
            mun = str(vals[mun_col]).strip().title()

            def cell(col: Optional[int]) -> str:
                return str(vals[col]).strip() if col is not None and col < len(vals) else ""

            nome = cell(fant_col)
            if not nome or nome == "-":
                nome = PLACEHOLDER_NAME

            endereco = cell(end_col)
            numero = cell(num_col)
            if numero and numero.upper() not in ("SN", "S/N", "-"):
                endereco = f"{endereco}, {numero}".strip(", ")

            bairro = cell(bairro_col)
            cep = cell(cep_col)
            bandeira = cell(band_col) or "Branca"
            cnpj = cell(cnpj_col)
            data_coleta = cell(data_col)
            if data_coleta:
                try:
                    val_float = float(data_coleta)
                    if 30000 <= val_float <= 60000:
                        data_coleta = (datetime(1899, 12, 30) + timedelta(days=int(val_float))).strftime("%d/%m/%Y")
                except (ValueError, OverflowError):
                    pass

            records.append({
                "cnpj": cnpj,
                "nome": nome,
                "bandeira": bandeira,
                "endereco": endereco,
                "bairro": bairro,
                "cep": cep,
                "mun": mun,
                "uf": uf,
                "prod": prod_code,
                "preco": price,
                "data": data_coleta,
            })

    return records


def build_payload_from_records(records: List[Dict[str, Any]], max_stations: Optional[int] = None) -> Dict[str, Any]:
    """
    Agrega os registros (série histórica + semanas recentes) em estados, cidades e postos.

    - Cada posto guarda, por produto, o preço da coleta MAIS RECENTE (a semana sobrescreve a série histórica).
    - Médias/mín/máx de cidade e estado usam só preços coletados nos últimos AVERAGE_WINDOW_DAYS dias da base,
      para um preço de oito meses atrás não puxar a média de hoje.
    """
    # Mais antigos primeiro: campos de endereço/nome do registro mais novo vencem.
    ordered = sorted(records, key=lambda r: date_key(r.get("data", "")))

    stations_map: Dict[str, Dict[str, Any]] = {}
    best: Dict[str, Dict[str, Tuple[str, float]]] = defaultdict(dict)  # id -> prod -> (dk, preco)
    city_info: Dict[str, Tuple[str, str]] = {}

    for rec in ordered:
        uf = rec["uf"]
        city_key = normalize_city_key(uf, rec["mun"])
        city_info[city_key] = (rec["mun"], uf)
        prod, preco, dk = rec["prod"], rec["preco"], date_key(rec.get("data", ""))
        sid = station_key(rec.get("cnpj", ""), rec["nome"], city_key, rec["endereco"])

        st = stations_map.get(sid)
        if st is None:
            lat, lon = get_coordinates(city_key, uf)
            st = {
                "id": f"anp_{sid}", "nome": rec["nome"], "bandeira": rec["bandeira"], "cityKey": city_key,
                "uf": uf, "endereco": rec["endereco"], "bairro": rec["bairro"], "cep": rec["cep"],
                "lat": lat, "lon": lon, "gc": None, "ga": None, "et": None, "ds": None, "data": "",
            }
            stations_map[sid] = st
        else:
            for field in ("bandeira", "endereco", "bairro", "cep"):
                if rec[field]:
                    st[field] = rec[field]
            if rec["nome"] != PLACEHOLDER_NAME:
                st["nome"] = rec["nome"]

        prev = best[sid].get(prod)
        if prev is None or dk >= prev[0]:
            best[sid][prod] = (dk, preco)
            st[prod] = preco
            if dk and dk >= date_key(st["data"]):
                st["data"] = rec["data"]

    all_dks = [dk for per in best.values() for dk, _ in per.values() if dk]
    cutoff = ""
    if all_dks:
        newest = datetime.strptime(max(all_dks), "%Y%m%d")
        cutoff = (newest - timedelta(days=AVERAGE_WINDOW_DAYS)).strftime("%Y%m%d")

    city_prices: Dict[str, Dict[str, List[float]]] = defaultdict(lambda: {"gc": [], "ga": [], "et": []})
    state_prices: Dict[str, Dict[str, List[float]]] = defaultdict(lambda: {"gc": [], "ga": [], "et": []})
    city_dates: Dict[str, List[str]] = defaultdict(list)
    state_dates: Dict[str, List[str]] = defaultdict(list)
    for sid, per in best.items():
        st = stations_map[sid]
        for prod, (dk, preco) in per.items():
            if dk < cutoff:
                continue
            city_prices[st["cityKey"]][prod].append(preco)
            state_prices[st["uf"]][prod].append(preco)
            if dk:
                city_dates[st["cityKey"]].append(dk)
                state_dates[st["uf"]].append(dk)

    def ref_of(dks: List[str]) -> str:
        return datetime.strptime(max(dks), "%Y%m%d").strftime("%d/%m/%Y") if dks else ""

    final_cidades: Dict[str, Dict[str, Any]] = {}
    for c_key, c_prods in city_prices.items():
        mun, uf = city_info[c_key]
        lat, lon = get_coordinates(c_key, uf)
        c_dict: Dict[str, Any] = {"uf": uf, "mun": mun, "lat": lat, "lon": lon, "ref": ref_of(city_dates[c_key])}
        for p in ("gc", "ga", "et"):
            vals = c_prods[p]
            c_dict[f"{p}Avg"] = round(sum(vals) / len(vals), 2) if vals else None
            c_dict[f"{p}Min"] = round(min(vals), 2) if vals else None
            c_dict[f"{p}Max"] = round(max(vals), 2) if vals else None
        c_dict["dsAvg"] = c_dict["dsMin"] = c_dict["dsMax"] = None
        final_cidades[c_key] = c_dict

    final_estados: Dict[str, Dict[str, Any]] = {}
    for uf, s_prods in state_prices.items():
        lat, lon = STATE_CENTROIDS.get(uf, (-14.2350, -51.9253))
        s_dict: Dict[str, Any] = {"uf": uf, "lat": lat, "lon": lon, "ref": ref_of(state_dates[uf])}
        for p in ("gc", "et"):
            vals = s_prods[p]
            s_dict[f"{p}Avg"] = round(sum(vals) / len(vals), 2) if vals else None
        s_dict["dsAvg"] = None
        final_estados[uf] = s_dict

    # Garante as 27 UFs (sem dado: valores de contingência).
    for uf, (lat, lon) in STATE_CENTROIDS.items():
        if uf not in final_estados:
            final_estados[uf] = {
                "uf": uf, "lat": lat, "lon": lon,
                "ref": datetime.now(timezone.utc).strftime("%d/%m/%Y"),
                "gcAvg": 6.30, "etAvg": 4.20, "dsAvg": None,
            }

    final_postos = list(stations_map.values())
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
    """Seed de contingência contendo todas as capitais caso não haja conexão."""
    ref_date = datetime.now(timezone.utc).strftime("%d/%m/%Y")
    estados: Dict[str, Dict[str, Any]] = {}
    cidades: Dict[str, Dict[str, Any]] = {}
    postos: List[Dict[str, Any]] = []

    for uf, (lat, lon) in STATE_CENTROIDS.items():
        estados[uf] = {
            "uf": uf,
            "lat": lat,
            "lon": lon,
            "ref": ref_date,
            "gcAvg": 6.38,
            "etAvg": 4.15,
            "dsAvg": None,
        }

    for c_key, (lat, lon) in CITY_COORDINATES.items():
        uf = c_key[:2].upper()
        mun = c_key[3:].replace("_", " ").title()
        cidades[c_key] = {
            "uf": uf,
            "mun": mun,
            "lat": lat,
            "lon": lon,
            "gcAvg": 6.35,
            "gcMin": 6.09,
            "gcMax": 6.69,
            "gaAvg": 6.65,
            "gaMin": 6.39,
            "gaMax": 6.99,
            "etAvg": 4.12,
            "etMin": 3.89,
            "etMax": 4.39,
            "dsAvg": None,
            "dsMin": None,
            "dsMax": None,
            "ref": ref_date,
        }
        postos.append({
            "id": f"anp_seed_{c_key}",
            "nome": f"Posto Shell Centro {mun}",
            "bandeira": "Shell",
            "cityKey": c_key,
            "uf": uf,
            "endereco": f"Av. Principal, 100",
            "bairro": "Centro",
            "cep": "00000-000",
            "lat": lat,
            "lon": lon,
            "gc": 6.29,
            "ga": 6.59,
            "et": 4.09,
            "ds": None,
            "data": ref_date,
        })

    return {
        "v": "1.0",
        "geradoEm": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "cidades": cidades,
        "estados": estados,
        "postos": postos,
    }


def _parse_bytes(raw: bytes) -> List[Dict[str, Any]]:
    return parse_xlsx_records(raw) if raw[:2] == b"PK" else parse_history_csv(raw)


def process_anp_data(
    input_source: Optional[str],
    output_path: str,
    max_stations: Optional[int] = None,
    use_seed_fallback: bool = False,
    auto_anp: bool = True,
    history_months: int = 12,
    weeks: int = 4,
) -> None:
    """
    Base = série histórica (últimos `history_months` meses); atualização = `weeks` semanas mais recentes por cima.
    `input_source` (arquivo local .csv/.xlsx ou URL) substitui o download automático.
    """
    records: List[Dict[str, Any]] = []

    if not use_seed_fallback:
        if input_source:
            if input_source.startswith(("http://", "https://")):
                records = _parse_bytes(_http_get(input_source, timeout=120))
            else:
                with open(input_source, "rb") as f:
                    records = _parse_bytes(f.read())
        elif auto_anp:
            base = fetch_history_records(history_months)
            weekly = fetch_weekly_records(weeks)
            print(f"Base histórica: {len(base)} registros | semanas recentes: {len(weekly)} registros")
            records = base + weekly

    if records:
        print(f"Total de registros válidos de combustíveis (GC, GA, ET): {len(records)}")
        payload = build_payload_from_records(records, max_stations=max_stations)
    else:
        print("Usando payload de seed de contingência.")
        payload = generate_seed_payload()
        if max_stations and max_stations > 0:
            payload["postos"] = payload["postos"][:max_stations]

    parent_dir = os.path.dirname(os.path.abspath(output_path))
    if parent_dir:
        os.makedirs(parent_dir, exist_ok=True)

    json_bytes = json.dumps(payload, ensure_ascii=False, indent=None).encode("utf-8")
    with gzip.open(output_path, "wb") as gz_out:
        gz_out.write(json_bytes)

    print(
        f"Sucesso: {output_path} gerado "
        f"({len(payload['cidades'])} cidades, {len(payload['estados'])} estados, {len(payload['postos'])} postos, "
        f"{len(json_bytes)} bytes brutos -> {os.path.getsize(output_path)} bytes comprimidos)"
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="ETL de preços de combustíveis da ANP (base histórica + semanas recentes)")
    parser.add_argument("--input", type=str, default=None,
                        help="Arquivo .csv/.xlsx local ou URL. Se omitido, baixa a série histórica e as semanas recentes da ANP.")
    parser.add_argument("--output", type=str, required=True, help="Arquivo final compactado (.json.gz)")
    parser.add_argument("--max-stations", type=int, default=None, help="Limite de postos no payload")
    parser.add_argument("--history-months", type=int, default=12, help="Meses da série histórica usados como base (padrão 12)")
    parser.add_argument("--weeks", type=int, default=4, help="Semanas recentes aplicadas por cima da base (padrão 4)")
    parser.add_argument("--auto-anp", action="store_true", default=True, help="Baixa os dados da ANP (padrão)")
    parser.add_argument("--generate-seed", action="store_true", help="Força o seed de contingência")
    args = parser.parse_args()

    process_anp_data(
        input_source=args.input,
        output_path=args.output,
        max_stations=args.max_stations,
        use_seed_fallback=args.generate_seed,
        auto_anp=args.auto_anp and not args.generate_seed,
        history_months=args.history_months,
        weeks=args.weeks,
    )


if __name__ == "__main__":
    main()
