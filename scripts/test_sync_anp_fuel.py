#!/usr/bin/env python3
"""Testes do ETL de preços da ANP (sync_anp_fuel.py): base histórica + semanas recentes."""

import gzip
import json
import os
import tempfile
import unittest

import sync_anp_fuel as s

HEADER = (
    "Regiao - Sigla;Estado - Sigla;Municipio;Revenda;CNPJ da Revenda;Nome da Rua;Numero Rua;Complemento;"
    "Bairro;Cep;Produto;Data da Coleta;Valor de Venda;Valor de Compra;Unidade de Medida;Bandeira\n"
)


def csv_row(prod, data, preco, cnpj=" 08.738.994/0001-50", mun="SERRA", uf="ES", rua="RUA DAS FLORES", num="100"):
    return f"SE;{uf};{mun};AUTO POSTO TESTE LTDA;{cnpj};{rua};{num};;CENTRO;29160-000;{prod};{data};{preco};;R$ / litro;VIBRA\n"


def rec(prod, data, preco, cnpj="08738994000150", nome="POSTO X", mun="Serra", uf="ES"):
    return {"cnpj": cnpj, "nome": nome, "bandeira": "VIBRA", "endereco": "RUA DAS FLORES, 100", "bairro": "CENTRO",
            "cep": "29160000", "mun": mun, "uf": uf, "prod": prod, "preco": preco, "data": data}


class TestSyncAnpFuel(unittest.TestCase):
    def test_map_product(self):
        self.assertEqual(s.map_product("GASOLINA"), "gc")
        self.assertEqual(s.map_product("GASOLINA COMUM"), "gc")
        self.assertEqual(s.map_product("GASOLINA ADITIVADA"), "ga")
        self.assertEqual(s.map_product("ETANOL"), "et")
        self.assertIsNone(s.map_product("DIESEL S10"))
        self.assertIsNone(s.map_product("GNV"))

    def test_parse_history_csv(self):
        raw = ("﻿" + HEADER + csv_row("GASOLINA", "01/12/2025", "6,14") + csv_row("DIESEL S10", "01/12/2025", "5,90")
               + csv_row("ETANOL", "01/12/2025", "0")).encode("utf-8")
        rows = s.parse_history_csv(raw)
        self.assertEqual(len(rows), 1)  # diesel e preço zero ficam de fora
        self.assertEqual(rows[0]["prod"], "gc")
        self.assertEqual(rows[0]["preco"], 6.14)
        self.assertEqual(rows[0]["endereco"], "RUA DAS FLORES, 100")
        self.assertEqual(rows[0]["uf"], "ES")

    def test_station_key_uses_cnpj_digits(self):
        self.assertEqual(s.station_key(" 08.738.994/0001-50", "n", "c", "e"), "08738994000150")
        self.assertEqual(s.station_key("198451000185", "n", "c", "e"), "00198451000185")  # xlsx perde o zero à esquerda
        self.assertEqual(len(s.station_key("", "n", "c", "e")), 12)

    def test_date_key(self):
        self.assertEqual(s.date_key("05/11/2026"), "20261105")
        self.assertEqual(s.date_key("x"), "")

    def test_url_sort_keys(self):
        base = "https://x/dsan/"
        self.assertEqual(s.history_url_sort_key(base + "2025/precos-gasolina-etanol-07.csv"), (2025, 7))
        self.assertEqual(s.history_url_sort_key(base + "2026/07-dados-abertos-precos-gasolina-etanol.csv"), (2026, 7))
        self.assertEqual(s.history_url_sort_key(base + "2026/04-dados-abertos-precos-gasolina-etanol"), (2026, 4))
        a = s.weekly_url_sort_key("https://x/revendas_lpc_2026-10-04_2026-10-10.xlsx")
        b = s.weekly_url_sort_key("https://x/revendas_lpc_2026_10_11-2026_10_17.xlsx")
        self.assertLess(a, b)

    def test_week_overrides_history_per_product(self):
        records = [
            rec("gc", "10/03/2026", 6.00), rec("et", "10/03/2026", 4.50),  # base histórica
            rec("gc", "03/11/2026", 6.40),  # semana recente: só gasolina
        ]
        payload = s.build_payload_from_records(records)
        self.assertEqual(len(payload["postos"]), 1)
        st = payload["postos"][0]
        self.assertEqual(st["id"], "anp_08738994000150")
        self.assertEqual(st["gc"], 6.40)   # a semana vence
        self.assertEqual(st["et"], 4.50)   # etanol segue o da base
        self.assertEqual(st["data"], "03/11/2026")

    def test_older_record_does_not_override_newer(self):
        payload = s.build_payload_from_records([rec("gc", "03/11/2026", 6.40), rec("gc", "10/03/2026", 5.00)])
        self.assertEqual(payload["postos"][0]["gc"], 6.40)

    def test_averages_ignore_prices_older_than_window(self):
        records = [
            rec("gc", "03/11/2026", 6.00, cnpj="11111111000111"),
            rec("gc", "01/11/2026", 6.20, cnpj="22222222000122"),
            rec("gc", "10/03/2026", 9.00, cnpj="33333333000133"),  # velho demais para a média
        ]
        payload = s.build_payload_from_records(records)
        self.assertEqual(payload["cidades"]["es_serra"]["gcAvg"], 6.10)
        self.assertEqual(payload["estados"]["ES"]["gcAvg"], 6.10)
        self.assertEqual(len(payload["postos"]), 3)  # mas o posto antigo continua na base, com a data dele

    def test_all_27_states_present_and_gzip_roundtrip(self):
        with tempfile.TemporaryDirectory() as d:
            src = os.path.join(d, "m.csv")
            with open(src, "w", encoding="utf-8") as f:
                f.write(HEADER + csv_row("GASOLINA", "05/11/2026", "6,29") + csv_row("ETANOL", "05/11/2026", "4,59"))
            out = os.path.join(d, "o.json.gz")
            s.process_anp_data(src, out)
            data = json.loads(gzip.open(out).read())
            self.assertEqual(len(data["estados"]), 27)
            self.assertEqual(data["postos"][0]["gc"], 6.29)
            self.assertEqual(data["postos"][0]["et"], 4.59)


if __name__ == "__main__":
    unittest.main()
