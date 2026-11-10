#!/usr/bin/env python3
"""
Testes unitários para o script de ingestão e normalização de combustíveis da ANP (sync_anp_fuel.py).
"""

import gzip
import json
import os
import subprocess
import sys
import tempfile
import unittest

# Importa o módulo sob teste
try:
    import sync_anp_fuel
except ImportError:
    # Quando o script ainda não foi criado, falha esperada pelo TDD
    sync_anp_fuel = None


MOCK_ANP_CSV_CONTENT = """Regiao - Sigla;Estado - Sigla;Municipio;Revenda;CNPJ da Revenda;Endereco da Revenda;Bairro;Cep;Produto;Data da Coleta;Valor de Venda;Bandeira
SE;SP;Campinas;Auto Posto Shell Portal;12.345.678/0001-90;Rua Barao de Jaguara, 100;Centro;13015-001;GASOLINA COMUM;08/11/2026;5,79;Shell
SE;SP;Campinas;Auto Posto Shell Portal;12.345.678/0001-90;Rua Barao de Jaguara, 100;Centro;13015-001;GASOLINA ADITIVADA;08/11/2026;5,99;Shell
SE;SP;Campinas;Auto Posto Shell Portal;12.345.678/0001-90;Rua Barao de Jaguara, 100;Centro;13015-001;ETANOL;08/11/2026;3,95;Shell
SE;SP;Campinas;Auto Posto Shell Portal;12.345.678/0001-90;Rua Barao de Jaguara, 100;Centro;13015-001;DIESEL S10;08/11/2026;5,69;Shell
SE;SP;Campinas;Posto Ipiranga Cambui;98.765.432/0001-11;Av. Julio de Mesquita, 500;Cambui;13025-061;GASOLINA;07/11/2026;5,99;Ipiranga
SE;SP;Campinas;Posto Ipiranga Cambui;98.765.432/0001-11;Av. Julio de Mesquita, 500;Cambui;13025-061;ETANOL HIDRATADO;07/11/2026;4,05;Ipiranga
NE;PE;Recife;Posto BR Derby;11.222.333/0001-44;Praca do Derby, 10;Derby;52010-000;GASOLINA COMUM;09/11/2026;5,85;Petrobras / Vibra
NE;PE;Recife;Posto BR Derby;11.222.333/0001-44;Praca do Derby, 10;Derby;52010-000;ETANOL;09/11/2026;4,10;Petrobras / Vibra
"""


class TestSyncAnpFuel(unittest.TestCase):

    def setUp(self):
        if sync_anp_fuel is None:
            self.fail("sync_anp_fuel module not found (TDD red phase)")

    def test_unaccent_and_normalize_city_key(self):
        self.assertEqual(sync_anp_fuel.normalize_city_key("SP", "Campinas"), "sp_campinas")
        self.assertEqual(sync_anp_fuel.normalize_city_key("SP", "São Paulo"), "sp_sao_paulo")
        self.assertEqual(sync_anp_fuel.normalize_city_key("PE", "Recife"), "pe_recife")
        self.assertEqual(sync_anp_fuel.normalize_city_key("RJ", "São João de Meriti"), "rj_sao_joao_de_meriti")

    def test_product_mapping(self):
        self.assertEqual(sync_anp_fuel.map_product("GASOLINA COMUM"), "gc")
        self.assertEqual(sync_anp_fuel.map_product("GASOLINA"), "gc")
        self.assertEqual(sync_anp_fuel.map_product("GASOLINA ADITIVADA"), "ga")
        self.assertEqual(sync_anp_fuel.map_product("ETANOL"), "et")
        self.assertEqual(sync_anp_fuel.map_product("ETANOL HIDRATADO"), "et")
        self.assertEqual(sync_anp_fuel.map_product("DIESEL S10"), "ds")
        self.assertEqual(sync_anp_fuel.map_product("OLEO DIESEL S10"), "ds")
        self.assertIsNone(sync_anp_fuel.map_product("GNV"))

    def test_parse_and_aggregate_data(self):
        rows = sync_anp_fuel.parse_csv_content(MOCK_ANP_CSV_CONTENT)
        self.assertEqual(len(rows), 8)

        payload = sync_anp_fuel.build_payload(rows)

        # Checa envelope raiz
        self.assertEqual(payload["v"], "1.0")
        self.assertTrue("geradoEm" in payload)
        self.assertEqual(len(payload["cidades"]), 2)
        self.assertEqual(len(payload["estados"]), 2)
        self.assertEqual(len(payload["postos"]), 3)

        # Checa agregações da cidade de Campinas
        campinas = payload["cidades"]["sp_campinas"]
        self.assertEqual(campinas["uf"], "SP")
        self.assertEqual(campinas["mun"], "Campinas")
        self.assertEqual(campinas["gcAvg"], 5.89)  # (5.79 + 5.99) / 2
        self.assertEqual(campinas["gcMin"], 5.79)
        self.assertEqual(campinas["gcMax"], 5.99)
        self.assertEqual(campinas["gaAvg"], 5.99)
        self.assertEqual(campinas["etAvg"], 4.00)  # (3.95 + 4.05) / 2
        self.assertEqual(campinas["etMin"], 3.95)
        self.assertEqual(campinas["etMax"], 4.05)
        self.assertEqual(campinas["dsAvg"], 5.69)
        self.assertEqual(campinas["ref"], "2026-11-08")

        # Checa agregações da cidade de Recife
        recife = payload["cidades"]["pe_recife"]
        self.assertEqual(recife["uf"], "PE")
        self.assertEqual(recife["mun"], "Recife")
        self.assertEqual(recife["gcAvg"], 5.85)
        self.assertEqual(recife["etAvg"], 4.10)
        self.assertEqual(recife["ref"], "2026-11-09")

        # Checa agregações por estado
        uf_sp = payload["estados"]["SP"]
        self.assertEqual(uf_sp["uf"], "SP")
        self.assertEqual(uf_sp["gcAvg"], 5.89)
        self.assertEqual(uf_sp["etAvg"], 4.00)
        self.assertEqual(uf_sp["dsAvg"], 5.69)

        uf_pe = payload["estados"]["PE"]
        self.assertEqual(uf_pe["uf"], "PE")
        self.assertEqual(uf_pe["gcAvg"], 5.85)
        self.assertEqual(uf_pe["etAvg"], 4.10)

        # Checa postos
        postos_by_cnpj = {p["id"]: p for p in payload["postos"]}
        p_shell = postos_by_cnpj.get("posto_12345678000190")
        self.assertIsNotNone(p_shell)
        self.assertEqual(p_shell["nome"], "Auto Posto Shell Portal")
        self.assertEqual(p_shell["bandeira"], "Shell")
        self.assertEqual(p_shell["cityKey"], "sp_campinas")
        self.assertEqual(p_shell["uf"], "SP")
        self.assertEqual(p_shell["gc"], 5.79)
        self.assertEqual(p_shell["ga"], 5.99)
        self.assertEqual(p_shell["et"], 3.95)
        self.assertEqual(p_shell["ds"], 5.69)
        self.assertEqual(p_shell["data"], "2026-11-08")

    def test_gzip_compression_roundtrip(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            csv_path = os.path.join(tmpdir, "mock_anp.csv")
            with open(csv_path, "w", encoding="utf-8") as f:
                f.write(MOCK_ANP_CSV_CONTENT)

            output_gz = os.path.join(tmpdir, "anp_output.json.gz")
            sync_anp_fuel.process_anp_data(input_source=csv_path, output_path=output_gz)

            self.assertTrue(os.path.exists(output_gz))

            with gzip.open(output_gz, "rt", encoding="utf-8") as gz_in:
                data = json.load(gz_in)

            self.assertEqual(data["v"], "1.0")
            self.assertEqual(len(data["cidades"]), 2)
            self.assertEqual(len(data["estados"]), 2)
            self.assertEqual(len(data["postos"]), 3)

    def test_cli_execution(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            csv_path = os.path.join(tmpdir, "mock_anp.csv")
            with open(csv_path, "w", encoding="utf-8") as f:
                f.write(MOCK_ANP_CSV_CONTENT)

            output_gz = os.path.join(tmpdir, "cli_output.json.gz")
            script_path = os.path.abspath(sync_anp_fuel.__file__)

            result = subprocess.run(
                [sys.executable, script_path, "--input", csv_path, "--output", output_gz],
                capture_output=True,
                text=True
            )
            self.assertEqual(result.returncode, 0, f"CLI stderr: {result.stderr}")
            self.assertTrue(os.path.exists(output_gz))

            with gzip.open(output_gz, "rt", encoding="utf-8") as gz_in:
                data = json.load(gz_in)
            self.assertEqual(len(data["cidades"]), 2)

    def test_seed_generation(self):
        seed = sync_anp_fuel.generate_seed_payload()
        self.assertEqual(seed["v"], "1.0")
        self.assertEqual(len(seed["estados"]), 27)
        self.assertEqual(len(seed["cidades"]), 27)
        self.assertTrue(len(seed["postos"]) >= 50)
        for k, c in seed["cidades"].items():
            self.assertIn("uf", c)
            self.assertIn("mun", c)
            self.assertIn("lat", c)
            self.assertIn("lon", c)
            self.assertIn("ref", c)
        for k, s in seed["estados"].items():
            self.assertIn("uf", s)
            self.assertIn("lat", s)
            self.assertIn("lon", s)
            self.assertIn("ref", s)


if __name__ == "__main__":
    unittest.main()
