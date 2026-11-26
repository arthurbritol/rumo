package app.hunter.app.veiculo

import app.hunter.app.ui.ProfileEditForm
import app.hunter.model.TipoVeiculo
import app.hunter.model.Veiculo
import app.hunter.sync.UserProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PerfilVeiculoTest {
    private fun veiculo(tipo: TipoVeiculo, marca: String = "", modelo: String = "", ano: Int? = null) = Veiculo(
        id = "v", tipo = tipo, apelido = "", marca = marca, modelo = modelo, ano = ano,
        tanqueLitros = 13.0, consumoPadraoKmL = null, criadoEm = 1, removidoEm = null,
    )

    private val perfil = UserProfile("Ana", "Honda", "CG 160", 2020)

    @Test
    fun `com carro ativo o cartao mostra marca e modelo do carro`() {
        val d = PerfilVeiculo.dadosExibidos(veiculo(TipoVeiculo.CARRO, "Fiat", "Argo", 2022), perfil)
        assertEquals(PerfilVeiculo.Dados("Fiat", "Argo", "2022"), d)
    }

    @Test
    fun `sem veiculo ativo o cartao mostra o perfil`() {
        assertEquals(PerfilVeiculo.Dados("Honda", "CG 160", "2020"), PerfilVeiculo.dadosExibidos(null, perfil))
        assertEquals(PerfilVeiculo.Dados(null, null, null), PerfilVeiculo.dadosExibidos(null, null))
    }

    @Test
    fun `o formulario parte dos dados do veiculo ativo e do nome do perfil`() {
        val f = PerfilVeiculo.formDe(perfil, "conta", veiculo(TipoVeiculo.CARRO, "Fiat", "Argo", 2022))
        assertEquals("Ana", f.name)
        assertEquals("Fiat", f.brand)
        assertEquals("2022", f.year)
        assertEquals("Honda", PerfilVeiculo.formDe(perfil, "conta", null).brand)
    }

    @Test
    fun `editar com moto ativa grava no veiculo e no perfil`() {
        val moto = veiculo(TipoVeiculo.MOTO, "Honda", "CG 160", 2020)
        val f = ProfileEditForm("Ana", "Yamaha", "Fazer 250", "2021")
        val r = PerfilVeiculo.aplicar(f, moto, perfil, voice = true)
        assertEquals("Yamaha", r.veiculo!!.marca)
        assertEquals("Fazer 250", r.veiculo!!.modelo)
        assertEquals(2021, r.veiculo!!.ano)
        assertEquals(UserProfile("Ana", "Yamaha", "Fazer 250", 2021, voice = true), r.perfil)
    }

    @Test
    fun `editar com carro ativo nao mexe em marca e modelo do perfil`() {
        val carro = veiculo(TipoVeiculo.CARRO, "Fiat", "Argo", 2022)
        val f = ProfileEditForm("Ana Maria", "Fiat", "Cronos", "2023")
        val r = PerfilVeiculo.aplicar(f, carro, perfil, voice = false)
        assertEquals("Cronos", r.veiculo!!.modelo)
        assertEquals(UserProfile("Ana Maria", "Honda", "CG 160", 2020, voice = false), r.perfil)
    }

    @Test
    fun `campo vazio nunca apaga o que ja existe`() {
        val moto = veiculo(TipoVeiculo.MOTO, "Honda", "CG 160", 2020)
        val r = PerfilVeiculo.aplicar(ProfileEditForm("Ana", "", " ", ""), moto, perfil, voice = true)
        assertEquals("Honda", r.veiculo!!.marca)
        assertEquals("CG 160", r.veiculo!!.modelo)
        assertEquals(2020, r.veiculo!!.ano)
        assertEquals(perfil, r.perfil)
    }

    @Test
    fun `ano do veiculo antes de 1950 nao vai para o perfil mas vai para o veiculo`() {
        val moto = veiculo(TipoVeiculo.MOTO, "Harley", "Knucklehead", 1940)
        val r = PerfilVeiculo.aplicar(ProfileEditForm("Ana", "Harley", "Knucklehead", "1940"), moto, perfil, voice = true)
        assertEquals(1940, r.veiculo!!.ano)
        assertEquals(2020, r.perfil.year)
        assertNull(PerfilVeiculo.aplicar(ProfileEditForm("Ana", "H", "K", "1940"), null, null, true).perfil.year)
    }

    @Test
    fun `sem veiculo ativo so o perfil e gravado`() {
        val r = PerfilVeiculo.aplicar(ProfileEditForm("Ana", "Yamaha", "", "2021"), null, perfil, voice = true)
        assertNull(r.veiculo)
        assertEquals("Yamaha", r.perfil.brand)
        assertEquals("CG 160", r.perfil.model)
    }
}
