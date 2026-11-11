#!/usr/bin/env python3
"""
Médias de preços por MUNICÍPIO da ANP: planilha mensal ("mensal-municipios-desde-jan2026.xlsx") como base e planilha
semanal ("semanal-municipios-2026.xlsx") por cima; vale o dado mais recente de cada município e combustível.

As planilhas trazem preço médio/mínimo/máximo de revenda e nº de postos pesquisados, mas NÃO trazem coordenadas: elas
vêm do IBGE (município -> latitude/longitude). Os dados entram no payload como `cidades`, e a média de cada estado
passa a ser a média dos municípios ponderada pelo nº de postos.
"""

from __future__ import annotations

import csv
import io
import re
import unicodedata
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional, Tuple

NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36 Rumo-ETL/3.0"

MUNICIPAL_PAGES = [
    "https://www.gov.br/anp/pt-br/assuntos/precos-e-defesa-da-concorrencia/precos/precos-revenda-e-de-distribuicao-combustiveis/serie-historica-do-levantamento-de-precos",
    "https://www.gov.br/anp/pt-br/assuntos/precos-e-defesa-da-concorrencia/precos/precos-revenda-e-de-distribuicao-combustiveis",
]
IBGE_MUNICIPIOS = "https://raw.githubusercontent.com/kelvins/municipios-brasileiros/main/csv/municipios.csv"
IBGE_ESTADOS = "https://raw.githubusercontent.com/kelvins/municipios-brasileiros/main/csv/estados.csv"

PRODUCTS = {"GASOLINA COMUM": "gc", "GASOLINA ADITIVADA": "ga", "ETANOL HIDRATADO": "et", "OLEO DIESEL S10": "ds"}
EXCEL_EPOCH = datetime(1899, 12, 30)


def unaccent(text: str) -> str:
    nfkd = unicodedata.normalize("NFKD", text or "")
    return "".join(c for c in nfkd if not unicodedata.combining(c)).upper().strip()


def city_key(uf: str, municipio: str) -> str:
    clean = re.sub(r"[^a-z0-9]+", "_", unaccent(municipio).lower()).strip("_")
    return f"{uf.lower()}_{clean}"


def _get(url: str, timeout: int = 120) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read()


def _links(pattern: str) -> List[str]:
    found: List[str] = []
    for page in MUNICIPAL_PAGES:
        try:
            html = _get(page, timeout=30).decode("utf-8", errors="ignore")
        except Exception:
            continue
        found += re.findall(r'href=["\']([^"\']*' + pattern + r')["\']', html, re.IGNORECASE)
    return sorted(set(u if u.startswith("http") else f"https://www.gov.br{u}" for u in found))


def find_municipal_urls() -> List[str]:
    """[mensal (base), semanal (atualização)] mais recentes, os que existirem."""
    urls: List[str] = []
    monthly = _links(r"mensal-municipios-desde-[a-z]+\d{4}\.xlsx")
    weekly = _links(r"semanal-municipios-\d{4}\.xlsx")
    if monthly:
        urls.append(monthly[-1])
    if weekly:
        urls.append(sorted(weekly, key=lambda u: re.findall(r"\d{4}", u)[-1])[-1])
    return urls


def _col(ref: str) -> int:
    n = 0
    for ch in re.match(r"[A-Z]+", ref).group(0):
        n = n * 26 + ord(ch) - 64
    return n - 1


def xlsx_rows(xlsx_bytes: bytes) -> List[List[str]]:
    """Linhas da primeira planilha, com cada célula na coluna certa (células vazias viram '')."""
    with zipfile.ZipFile(io.BytesIO(xlsx_bytes)) as z:
        shared: List[str] = []
        if "xl/sharedStrings.xml" in z.namelist():
            for si in ET.fromstring(z.read("xl/sharedStrings.xml")).findall(NS + "si"):
                shared.append("".join(t.text or "" for t in si.iter(NS + "t")))
        sheet = sorted(n for n in z.namelist() if n.startswith("xl/worksheets/sheet"))[0]
        data = ET.fromstring(z.read(sheet)).find(NS + "sheetData")
        rows: List[List[str]] = []
        for row in data.findall(NS + "row"):
            vals: Dict[int, str] = {}
            for c in row.findall(NS + "c"):
                v = c.find(NS + "v")
                val = v.text if v is not None and v.text is not None else ""
                if c.get("t") == "s" and val.isdigit():
                    val = shared[int(val)] if int(val) < len(shared) else ""
                vals[_col(c.get("r", "A1"))] = val
            rows.append([vals.get(i, "") for i in range(max(vals) + 1)] if vals else [])
        return rows


def _num(s: str) -> Optional[float]:
    try:
        v = float(str(s).replace(",", "."))
    except ValueError:
        return None
    return v if 0 < v < 100 else None


def _end_of_month_serial(month_start: int) -> int:
    d = EXCEL_EPOCH + timedelta(days=month_start)
    nxt = datetime(d.year + (d.month == 12), d.month % 12 + 1, 1)
    return (nxt - EXCEL_EPOCH).days - 1


def ref_label(serial: int, weekly: bool) -> str:
    d = EXCEL_EPOCH + timedelta(days=serial)
    return d.strftime("%d/%m/%Y") if weekly else d.strftime("%m/%Y")


def parse_municipal(rows: List[List[str]], uf_by_name: Dict[str, str]) -> Dict[str, Dict[str, Any]]:
    """
    {city_key: {"uf", "mun", "gc": (avg, min, max, n, fim_serial, semanal), ...}} da planilha mensal ou semanal, com a
    coleta mais recente de cada município/produto. Mensal: coluna MES; semanal: DATA FINAL.
    """
    header_i = next((i for i, r in enumerate(rows[:40]) if "PRODUTO" in [unaccent(x) for x in r]), None)
    if header_i is None:
        return {}
    cols = {unaccent(h): i for i, h in enumerate(rows[header_i])}
    weekly = "DATA FINAL" in cols

    def idx(*names: str) -> Optional[int]:
        return next((cols[n] for n in names if n in cols), None)

    i_date = idx("DATA FINAL", "MES")
    i_prod, i_uf, i_mun = idx("PRODUTO"), idx("ESTADO"), idx("MUNICIPIO")
    i_n = idx("NUMERO DE POSTOS PESQUISADOS")
    i_avg, i_min, i_max = idx("PRECO MEDIO REVENDA"), idx("PRECO MINIMO REVENDA"), idx("PRECO MAXIMO REVENDA")
    if None in (i_date, i_prod, i_uf, i_mun, i_avg):
        return {}

    out: Dict[str, Dict[str, Any]] = {}
    for r in rows[header_i + 1:]:
        if len(r) <= i_avg:
            continue
        prod = PRODUCTS.get(unaccent(r[i_prod]))
        raw_date = r[i_date].split(".")[0]
        avg = _num(r[i_avg])
        if prod is None or avg is None or not raw_date.isdigit():
            continue
        name = unaccent(r[i_uf])
        uf = uf_by_name.get(name, name if len(name) == 2 else "")
        mun = r[i_mun].strip()
        if not uf or not mun:
            continue
        end = int(raw_date) if weekly else _end_of_month_serial(int(raw_date))
        key = city_key(uf, mun)
        entry = out.setdefault(key, {"uf": uf, "mun": mun.title()})
        if prod in entry and entry[prod][4] > end:
            continue

        def at(i: Optional[int]) -> float:
            return round((_num(r[i]) if i is not None and len(r) > i else None) or avg, 2)

        n = int(float(r[i_n])) if i_n is not None and len(r) > i_n and r[i_n].replace(".", "", 1).isdigit() else 0
        entry[prod] = (round(avg, 2), at(i_min), at(i_max), n, end, weekly)
    return out


def merge_municipal(base: Dict[str, Dict[str, Any]], update: Dict[str, Dict[str, Any]]) -> Dict[str, Dict[str, Any]]:
    """Por município e combustível, vale o dado de coleta mais recente (o semanal vence o mensal mais antigo)."""
    out = {k: dict(v) for k, v in base.items()}
    for key, upd in update.items():
        cur = out.setdefault(key, {"uf": upd["uf"], "mun": upd["mun"]})
        for p in ("gc", "ga", "et", "ds"):
            if p in upd and (p not in cur or upd[p][4] >= cur[p][4]):
                cur[p] = upd[p]
    return out


def load_ibge_coords() -> Dict[str, Tuple[float, float]]:
    """{city_key: (lat, lon)} dos 5.570 municípios do IBGE."""
    ufs: Dict[str, str] = {}
    for row in csv.DictReader(io.StringIO(_get(IBGE_ESTADOS, 60).decode("utf-8-sig"))):
        ufs[row["codigo_uf"]] = row["uf"]
    coords: Dict[str, Tuple[float, float]] = {}
    for row in csv.DictReader(io.StringIO(_get(IBGE_MUNICIPIOS, 60).decode("utf-8-sig"))):
        uf = ufs.get(row["codigo_uf"])
        if uf:
            coords[city_key(uf, row["nome"])] = (float(row["latitude"]), float(row["longitude"]))
    return coords


def apply_municipal(
    payload: Dict[str, Any],
    muni: Dict[str, Dict[str, Any]],
    coords: Dict[str, Tuple[float, float]],
) -> None:
    """Coloca os municípios em `cidades`, corrige a posição dos postos e recalcula a média de cada estado."""
    cidades = payload["cidades"]
    for key, m in muni.items():
        old = cidades.get(key, {})
        lat, lon = coords.get(key) or (old.get("lat"), old.get("lon"))
        stats = [m[p] for p in ("gc", "ga", "et", "ds") if p in m]
        if lat is None or not stats:
            continue
        newest = max(stats, key=lambda s: s[4])
        c: Dict[str, Any] = {"uf": m["uf"], "mun": m["mun"], "lat": lat, "lon": lon, "ref": ref_label(newest[4], newest[5])}
        for p in ("gc", "ga", "et", "ds"):
            s = m.get(p)
            c[f"{p}Avg"], c[f"{p}Min"], c[f"{p}Max"] = (s[0], s[1], s[2]) if s else (None, None, None)
        cidades[key] = c

    for st in payload["postos"]:
        if st["cityKey"] in coords and st["cityKey"] in cidades:
            st["lat"], st["lon"] = cidades[st["cityKey"]]["lat"], cidades[st["cityKey"]]["lon"]

    acc: Dict[Tuple[str, str], List[float]] = {}
    newest_by_uf: Dict[str, Tuple[int, bool]] = {}
    for m in muni.values():
        for p in ("gc", "et", "ds"):
            s = m.get(p)
            if s:
                w = max(s[3], 1)
                a = acc.setdefault((m["uf"], p), [0.0, 0.0])
                a[0] += s[0] * w
                a[1] += w
                if m["uf"] not in newest_by_uf or s[4] > newest_by_uf[m["uf"]][0]:
                    newest_by_uf[m["uf"]] = (s[4], s[5])
    for (uf, p), (total, weight) in acc.items():
        estado = payload["estados"].get(uf)
        if estado is not None and weight:
            estado[f"{p}Avg"] = round(total / weight, 2)
            estado["ref"] = ref_label(*newest_by_uf[uf])
