package app.hunter.app.veiculo

import app.hunter.app.ui.onboarding.BikeForm
import app.hunter.app.ui.onboarding.BikeFormMapping
import app.hunter.app.ui.onboarding.FormField
import app.hunter.model.TipoVeiculo
import app.hunter.model.Veiculo
import app.hunter.sync.BikeCatalog
import app.hunter.sync.CarCatalog
import app.hunter.sync.UserProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VeiculoFormTest {
    private fun campos(f: VeiculoForm) = f.validar().map { it.field }.toSet()

    private fun preenchido(tipo: TipoVeiculo = TipoVeiculo.CARRO) = VeiculoForm(tipo).apply { selecionar("Chevrolet", "Onix") }

    @Test fun `padrao de tanque por tipo`() {
        assertEquals("13", VeiculoForm(TipoVeiculo.MOTO).tanque)
        assertEquals("45", VeiculoForm(TipoVeiculo.CARRO).tanque)
    }

    @Test fun `marca e modelo sao obrigatorios, apelido nao`() {
        val f = VeiculoForm(TipoVeiculo.CARRO)
        assertEquals(setOf(FormField.BRAND, FormField.MODEL), campos(f))
        f.selecionar("Chevrolet", "Onix")
        assertTrue(f.validar().isEmpty())
    }

    @Test fun `textos acima de 60 caracteres sao recusados, espacos nas pontas nao contam`() {
        val f = preenchido()
        f.apelido = "a".repeat(61)
        f.selecionar("b".repeat(61), "m".repeat(61))
        assertEquals(setOf(FormField.NICKNAME, FormField.BRAND, FormField.MODEL), campos(f))
        f.apelido = " " + "a".repeat(60) + " "
        f.selecionar("b".repeat(60), "m".repeat(60))
        assertTrue(f.validar().isEmpty())
    }

    @Test fun `ano vazio ou de 1900 a 2100`() {
        val f = preenchido()
        for (ok in listOf("", "1900", "2100", "2024")) { f.ano = ok; assertTrue(f.validar().isEmpty(), ok) }
        for (ruim in listOf("1899", "2101", "0")) { f.ano = ruim; assertEquals(setOf(FormField.YEAR), campos(f), ruim) }
    }

    @Test fun `tanque maior que zero e ate 200, virgula ou ponto`() {
        val f = preenchido()
        for (ok in listOf("0,5", "45", "200", "50.5")) { f.editarTanque(ok); assertTrue(f.validar().isEmpty(), ok) }
        for (ruim in listOf("", "0", "200,1", "abc", "-5")) { f.editarTanque(ruim); assertEquals(setOf(FormField.TANK), campos(f), ruim) }
    }

    @Test fun `trocar o tipo limpa marca e modelo e ajusta o tanque so se nao foi editado`() {
        val f = VeiculoForm(TipoVeiculo.MOTO)
        f.selecionar("Royal Enfield", "Meteor 350")
        assertEquals("15", f.tanque)
        f.escolherTipo(TipoVeiculo.CARRO)
        assertEquals("", f.marca)
        assertEquals("", f.modelo)
        assertEquals("45", f.tanque)
        f.editarTanque("52")
        f.escolherTipo(TipoVeiculo.MOTO)
        assertEquals("52", f.tanque)
    }

    @Test fun `mapeia o formulario para Veiculo`() {
        val f = VeiculoForm(TipoVeiculo.CARRO)
        f.apelido = "  Mercedinha "
        f.selecionar("Fiat", " Strada ")
        f.ano = "2022"
        f.editarTanque("55,5")
        val v = f.paraVeiculo("id1", 123L)
        assertEquals(Veiculo("id1", TipoVeiculo.CARRO, "Mercedinha", "Fiat", "Strada", 2022, 55.5, null, 123L, null), v)
        f.ano = ""
        assertNull(f.paraVeiculo("id1", 123L).ano)
    }

    @Test fun `editar preserva consumo padrao e carrega os campos`() {
        val base = Veiculo("x", TipoVeiculo.MOTO, "Hunter", "Royal Enfield", "Hunter 350", 2023, 13.0, 30.0, 5L)
        val f = VeiculoForm.de(base)
        assertEquals("Hunter", f.apelido)
        assertEquals("2023", f.ano)
        assertEquals("13", f.tanque)
        f.editarTanque("14")
        val v = f.paraVeiculo(base.id, base.criadoEm, base)
        assertEquals(30.0, v.consumoPadraoKmL)
        assertEquals(14.0, v.tanqueLitros)
        assertEquals(5L, v.criadoEm)
    }

    @Test fun `veiculo migrado sem marca nem modelo pode ser editado sem exigir os dois`() {
        val legado = Veiculo("l", TipoVeiculo.MOTO, "Minha moto", "", "", null, 13.0, null, 1L)
        assertFalse(VeiculoForm.de(legado).exigirMarcaModelo)
        assertTrue(VeiculoForm.de(legado).validar().isEmpty())
        assertTrue(VeiculoForm.de(legado.copy(marca = "Honda")).exigirMarcaModelo)
    }

    @Test fun `onboarding mapeia o formulario completo e o curto`() {
        val f = BikeForm(short = false)
        f.escolherTipo(TipoVeiculo.CARRO)
        f.apelido = " Meu Onix "
        f.selectBike("Chevrolet", "Onix")
        f.year = "2020"
        val v = Veiculo("a", TipoVeiculo.MOTO, "Minha moto", "", "", null, 13.0, null, 1L)
        val aplicado = BikeFormMapping.aplicar(v, f)
        assertEquals(TipoVeiculo.CARRO, aplicado.tipo)
        assertEquals("Chevrolet", aplicado.marca)
        assertEquals("Onix", aplicado.modelo)
        assertEquals(2020, aplicado.ano)
        assertEquals("Meu Onix", aplicado.apelido)
        f.apelido = ""
        assertEquals("Minha moto", BikeFormMapping.aplicar(v, f).apelido) // sem apelido digitado, mantém o atual
        assertEquals("", BikeFormMapping.apelido(f))
        val curto = BikeForm(short = true)
        assertEquals("Minha moto", BikeFormMapping.apelido(curto))
        curto.escolherTipo(TipoVeiculo.CARRO)
        assertEquals("Meu carro", BikeFormMapping.apelido(curto))
    }
}

class CatalogoDoTipoTest {
    @Test fun `devolve o catalogo do tipo`() {
        assertEquals(BikeCatalog.brands, catalogoDoTipo(TipoVeiculo.MOTO))
        assertEquals(CarCatalog.brands, catalogoDoTipo(TipoVeiculo.CARRO))
        assertTrue(catalogoDoTipo(TipoVeiculo.CARRO).isNotEmpty())
    }

    @Test fun `modelos pelo tipo e marca`() {
        assertTrue("Onix" in modelosDoTipo(TipoVeiculo.CARRO, "Chevrolet"))
        assertTrue(modelosDoTipo(TipoVeiculo.MOTO, "Chevrolet").isEmpty())
        assertTrue("Hunter 350" in modelosDoTipo(TipoVeiculo.MOTO, "Royal Enfield"))
        assertTrue(modelosDoTipo(TipoVeiculo.CARRO, "Outra").isEmpty())
    }

    @Test fun `marcas de cada lista tem nomes unicos`() {
        for (t in TipoVeiculo.entries) {
            val nomes = catalogoDoTipo(t).map { it.name }
            assertEquals(nomes.size, nomes.toSet().size)
        }
    }
}

class TemVeiculoTest {
    private val v = Veiculo("a", TipoVeiculo.CARRO, "", "", "", null, 45.0, null, 1L)

    @Test fun `qualquer veiculo ativo conta, moto ou carro`() {
        assertTrue(temVeiculo(v, null))
        assertTrue(temVeiculo(v.copy(tipo = TipoVeiculo.MOTO), UserProfile("A", null, null, null)))
    }

    @Test fun `sem veiculo so o perfil antigo com marca e modelo conta`() {
        assertFalse(temVeiculo(null, null))
        assertFalse(temVeiculo(null, UserProfile("A", null, null, null)))
        assertFalse(temVeiculo(null, UserProfile("A", "Honda", null, null)))
        assertTrue(temVeiculo(null, UserProfile("A", "Honda", "CG 160", null)))
    }

    @Test fun `nome de exibicao e detalhe`() {
        assertEquals("Carro", v.nomeExibicao())
        val c = v.copy(apelido = "Meu", marca = "Fiat", modelo = "Uno", ano = 2010)
        assertEquals("Meu", c.nomeExibicao())
        assertEquals("Fiat Uno · 2010", c.detalhe())
        val d = v.copy(marca = "Fiat", modelo = "Uno")
        assertEquals("Fiat Uno", d.nomeExibicao())
        assertEquals("", d.detalhe())
    }
}
