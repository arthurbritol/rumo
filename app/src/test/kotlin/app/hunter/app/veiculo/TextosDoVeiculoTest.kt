package app.hunter.app.veiculo

import app.hunter.model.TipoVeiculo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TextosDoVeiculoTest {
    @Test fun `sem veiculo vale a moto`() {
        assertSame(TextosDoVeiculo.MOTO, TextosDoVeiculo.de(null))
        assertSame(TextosDoVeiculo.CARRO, TextosDoVeiculo.de(TipoVeiculo.CARRO))
    }

    @Test fun `termos da moto`() {
        val t = TextosDoVeiculo.MOTO
        assertEquals("moto", t.veiculo)
        assertEquals("motociclista", t.pessoa)
        assertEquals("motociclistas", t.pessoas)
        assertEquals("sua moto", t.seu)
        assertEquals("da moto", t.daDo)
        assertEquals("Minha moto", t.minha)
        assertEquals("Moto", t.rotuloDoTipo)
        assertEquals("Motociclista", t.nomePadrao)
    }

    @Test fun `termos do carro`() {
        val t = TextosDoVeiculo.CARRO
        assertEquals("carro", t.veiculo)
        assertEquals("motorista", t.pessoa)
        assertEquals("motoristas", t.pessoas)
        assertEquals("seu carro", t.seu)
        assertEquals("do carro", t.daDo)
        assertEquals("Meu carro", t.minha)
        assertEquals("Carro", t.rotuloDoTipo)
        assertEquals("Motorista", t.nomePadrao)
    }

    @Test fun `frases concordam com o tipo`() {
        assertEquals("Calculando rota para moto…", TextosDoVeiculo.MOTO.calculandoRota)
        assertEquals("Calculando rota para carro…", TextosDoVeiculo.CARRO.calculandoRota)
        assertEquals("Manutenção da moto", TextosDoVeiculo.MOTO.tituloDoLembrete(1))
        assertEquals("Manutenção do carro (3 itens)", TextosDoVeiculo.CARRO.tituloDoLembrete(3))
        assertEquals("O hodômetro e as revisões da moto são ajustados na Garagem.", TextosDoVeiculo.MOTO.hodometroNaGaragem)
        assertEquals("O hodômetro e as revisões do carro são ajustados na Garagem.", TextosDoVeiculo.CARRO.hodometroNaGaragem)
        assertEquals("O km do carro se ajusta na Garagem.", TextosDoVeiculo.CARRO.kmNaGaragem)
        assertEquals("Mecânica de moto", TextosDoVeiculo.MOTO.atalhoMecanica)
        assertEquals("Oficina mecânica", TextosDoVeiculo.CARRO.atalhoMecanica)
    }

    @Test fun `nome padrao por tipo e sem veiculo`() {
        assertEquals("Motociclista", TextosDoVeiculo.nomePadrao(TipoVeiculo.MOTO))
        assertEquals("Motorista", TextosDoVeiculo.nomePadrao(TipoVeiculo.CARRO))
        assertEquals("Motociclista", TextosDoVeiculo.nomePadrao(null))
    }

    @Test fun `Pod e role so para moto ou sem veiculo`() {
        assertTrue(podPermitido(null))
        assertTrue(podPermitido(TipoVeiculo.MOTO))
        assertFalse(podPermitido(TipoVeiculo.CARRO))
        assertTrue(rolePermitido(null))
        assertTrue(rolePermitido(TipoVeiculo.MOTO))
        assertEquals(ROLE_PARA_CARRO, rolePermitido(TipoVeiculo.CARRO))
        assertTrue(ROLE_PARA_CARRO)
    }
}
