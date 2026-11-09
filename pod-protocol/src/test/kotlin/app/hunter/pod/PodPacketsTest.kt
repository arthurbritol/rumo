package app.hunter.pod

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Vetores de teste portados de ref/wazetripper/src/test/.../PacketsCheck.java (WazeTripper, Apache-2.0).
 *
 * Origem das esperas, como no original:
 *  - REAL: pacote capturado de um Pod real (Meteor 350) que o desenhou;
 *  - EXT:  valor de referência externo (CRC padrão, pacotes documentados de outros apps);
 *  - LOCK: trava de regressão, layout ainda não visto no hardware.
 * Nada aqui foi validado na Hunter 350.
 */
class PodPacketsTest {

    private fun hex(b: ByteArray) = PodPacket.toHex(b)

    private fun assertWellFormed(p: ByteArray) {
        assertEquals(20, p.size)
        assertTrue(PodPacket.isValid(p), "CRC inválido: ${hex(p)}")
    }

    // ---------- CRC ----------

    @Test
    fun `EXT crc16 de 123456789 e o valor padrao CCITT-FALSE 0x29B1`() {
        assertEquals(0x29B1, PodPacket.crc16("123456789".toByteArray()))
    }

    @Test
    fun `crc16 confere com referencia bit a bit independente`() {
        fun ref(b: ByteArray): Int {
            var crc = 0xFFFF
            for (x in b) {
                crc = crc xor ((x.toInt() and 0xFF) shl 8)
                repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF }
            }
            return crc
        }
        for (seed in 0 until 256) {
            val b = ByteArray(18) { i -> (seed * 31 + i * 7).toByte() }
            assertEquals(ref(b), PodPacket.crc16(b), "seed=$seed")
        }
    }

    @Test
    fun `EXT STOP_NAV de outro app (CRC F7 82)`() {
        val p = PodPacket.withCrc(PodPacket.fromHex("10 11 1C 00 00 01 00 FF 00 00 00 00 00 00 00 00 00 00"))
        assertEquals("10 11 1C 00 00 01 00 FF 00 00 00 00 00 00 00 00 00 00 F7 82", hex(p))
    }

    @Test
    fun `withCrc rejeita payload que nao tem 18 bytes`() {
        assertFailsWith<IllegalArgumentException> { PodPacket.withCrc(ByteArray(17)) }
        assertFailsWith<IllegalArgumentException> { PodPacket.withCrc(ByteArray(19)) }
    }

    @Test
    fun `isValid detecta CRC errado e tamanho errado`() {
        val ok = PodPackets.ping()
        assertTrue(PodPacket.isValid(ok))
        val bad = ok.copyOf().also { it[19] = (it[19] + 1).toByte() }
        assertFalse(PodPacket.isValid(bad))
        assertFalse(PodPacket.isValid(ok.copyOf(19)))
    }

    // ---------- Pacotes fixos ----------

    @Test
    fun `REAL PING_FW`() {
        assertEquals("03 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 45 D9", hex(PodPackets.ping()))
    }

    @Test
    fun `REAL HANDSHAKE_RESUME`() {
        assertEquals("21 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 40 45", hex(PodPackets.handshake(newDevice = false)))
    }

    @Test
    fun `REAL HANDSHAKE_SHOW_PIN`() {
        assertEquals("21 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 50 A7", hex(PodPackets.handshake(newDevice = true)))
    }

    // ---------- PIN ----------

    @Test
    fun `PIN 1234 vira comando 0x20 mais ASCII`() {
        val p = PodPackets.pin("1234")
        assertWellFormed(p)
        assertEquals("20 31 32 33 34 00 00", hex(p.copyOfRange(0, 7)))
    }

    @Test
    fun `PIN maior que 6 caracteres e truncado`() {
        assertEquals("20 31 32 33 34 35 36 00", hex(PodPackets.pin("12345678").copyOfRange(0, 8)))
    }

    // ---------- SET_TIME ----------

    @Test
    fun `REAL SET_TIME 13h55 em 12h (PM sem bit extra)`() {
        assertEquals(
            "50 01 37 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 51 A8",
            hex(PodPackets.setTime(13, 55, is12h = true)),
        )
    }

    @Test
    fun `REAL SET_TIME 13h54 em 12h`() {
        assertEquals(
            "50 01 36 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 F9 8C",
            hex(PodPackets.setTime(13, 54, is12h = true)),
        )
    }

    @Test
    fun `SET_TIME 24h usa hora crua`() {
        assertEquals(13, PodPackets.setTime(13, 55, is12h = false)[1].toInt())
    }

    @Test
    fun `SET_TIME 12h AM soma 0x40, meia-noite e 12 AM, meio-dia e 12 PM`() {
        assertEquals(0x49, PodPackets.setTime(9, 5, true)[1].toInt() and 0xFF)
        assertEquals(0x4C, PodPackets.setTime(0, 0, true)[1].toInt() and 0xFF)
        assertEquals(0x0C, PodPackets.setTime(12, 0, true)[1].toInt() and 0xFF)
        assertWellFormed(PodPackets.setTime(13, 55, true))
    }

    // ---------- Bússola ----------

    @Test
    fun `REAL bussola SE noite`() = assertEquals(
        "10 11 41 00 00 00 01 FF 00 00 00 FF FF FF 70 00 00 00 CE D1",
        hex(PodPackets.compass(CompassDirection.SE, night = true)),
    )

    @Test
    fun `REAL bussola SW noite`() = assertEquals(
        "10 11 41 00 00 00 01 FF 00 00 00 FF FF FF 80 00 00 00 51 9C",
        hex(PodPackets.compass(CompassDirection.SW, night = true)),
    )

    @Test
    fun `REAL bussola NE noite`() = assertEquals(
        "10 11 41 00 00 00 01 FF 00 00 00 FF FF FF 50 00 00 00 F9 9F",
        hex(PodPackets.compass(CompassDirection.NE, night = true)),
    )

    @Test
    fun `REAL bussola N noite`() = assertEquals(
        "10 11 41 00 00 00 01 FF 00 00 00 FF FF FF 10 00 00 00 97 03",
        hex(PodPackets.compass(CompassDirection.N, night = true)),
    )

    @Test
    fun `LOCK bussola N dia`() = assertEquals(
        "10 11 41 00 00 00 00 FF 00 00 00 FF FF FF 10 00 00 00 94 76",
        hex(PodPackets.compass(CompassDirection.N, night = false)),
    )

    @Test
    fun `rumos reais e bordas dos setores`() {
        val cases = listOf(
            141f to CompassDirection.SE, 235f to CompassDirection.SW, 51f to CompassDirection.NE, 10f to CompassDirection.N,
            22.4f to CompassDirection.N, 22.6f to CompassDirection.NE, 359.9f to CompassDirection.N,
            90f to CompassDirection.E, 180f to CompassDirection.S, 270f to CompassDirection.W, 315f to CompassDirection.NW,
            0f to CompassDirection.N, 360f to CompassDirection.N, -10f to CompassDirection.N, -90f to CompassDirection.W,
        )
        for ((bearing, expected) in cases) {
            assertEquals(expected, CompassDirection.fromBearing(bearing), "rumo $bearing")
        }
    }

    @Test
    fun `codigos de direcao da bussola seguem a tabela nao linear do Pod`() {
        assertEquals(
            listOf(0x10, 0x50, 0x20, 0x70, 0x40, 0x80, 0x30, 0x60),
            CompassDirection.entries.map { it.code },
        )
    }

    // ---------- Distância ----------

    @Test
    fun `encodeDistance em metros ate 999 e km x10 acima`() {
        fun enc(m: Int) = PodPackets.encodeDistance(m).toString()
        assertEquals("00 00 01", enc(0))
        assertEquals("00 44 01", enc(68))
        assertEquals("01 04 01", enc(260))
        assertEquals("03 E7 01", enc(999))
        assertEquals("00 0A 02", enc(1000))
        assertEquals("00 1D 02", enc(2942))
        assertEquals("00 E5 02", enc(22900))
        assertEquals("FF FF 02", enc(99_999_999))
    }

    // ---------- Navegação (todos REAL, de log de um trajeto) ----------

    private fun nav(man: Int, dist: Int, next: Int, nextDist: Int, bottom: BottomInfo, b6: Int) =
        hex(PodPackets.navigationRaw(man, dist, next, nextDist, bottom, b6))

    private val total2542 = BottomInfo.TotalDistance(2542)

    @Test
    fun `REAL nav virar a esquerda 167 m total 2,5 km noite`() = assertEquals(
        "10 11 14 00 A7 01 01 FF FF FF 41 00 19 02 00 00 00 00 19 1A",
        nav(PodIcon.TURN_LEFT, 167, 0xFF, -1, total2542, 0x01),
    )

    @Test
    fun `REAL nav sem distancia ainda`() = assertEquals(
        "10 11 14 00 00 01 01 FF FF FF 41 00 19 02 00 00 00 00 A3 AD",
        nav(PodIcon.TURN_LEFT, 0, 0xFF, -1, total2542, 0x01),
    )

    @Test
    fun `REAL nav rodape em tempo restante 8 min`() = assertEquals(
        "10 11 14 00 A6 01 01 FF FF FF 41 00 08 00 00 00 00 00 79 DC",
        nav(PodIcon.TURN_LEFT, 166, 0xFF, -1, BottomInfo.TimeRemaining(8), 0x01),
    )

    @Test
    fun `REAL nav rodape em distancia total 2942 m`() = assertEquals(
        "10 11 14 00 A6 01 01 FF FF FF 41 00 1D 02 00 00 00 00 64 DA",
        nav(PodIcon.TURN_LEFT, 166, 0xFF, -1, BottomInfo.TotalDistance(2942), 0x01),
    )

    @Test
    fun `REAL nav chegando ao destino a 68 m`() = assertEquals(
        "10 11 00 00 44 01 21 FF FF FF 41 00 44 01 00 00 00 00 68 CA",
        nav(PodIcon.DESTINATION, 68, 0xFF, -1, BottomInfo.TotalDistance(68), 0x21),
    )

    @Test
    fun `REAL nav hora de chegada 24h 17h28`() = assertEquals(
        "10 11 00 00 44 01 21 FF FF FF 41 11 1C 00 00 00 00 00 30 57",
        nav(PodIcon.DESTINATION, 68, 0xFF, -1, BottomInfo.ArrivalTime(17, 28, is12h = false), 0x21),
    )

    @Test
    fun `REAL nav hora de chegada 12h 5h28 PM vira 0x85`() = assertEquals(
        "10 11 00 00 44 01 21 FF FF FF 41 85 1C 00 00 00 00 00 6D 13",
        nav(PodIcon.DESTINATION, 68, 0xFF, -1, BottomInfo.ArrivalTime(17, 28, is12h = true), 0x21),
    )

    @Test
    fun `REAL nav radar modo compativel (seta 0x3C, distancia do radar no rodape)`() = assertEquals(
        "10 11 14 00 AE 01 01 3C FF FF 41 01 04 01 00 00 00 00 3E E1",
        nav(PodIcon.TURN_LEFT, 174, PodIcon.DEPART, -1, BottomInfo.TotalDistance(260), 0x01),
    )

    @Test
    fun `REAL nav radar layout antigo (seta 0x45, rodape 250 m)`() = assertEquals(
        "10 11 14 00 A7 01 01 45 FF FF 41 00 FA 01 00 00 00 00 C6 C0",
        nav(PodIcon.TURN_LEFT, 167, 0x45, -1, BottomInfo.TotalDistance(250), 0x01),
    )

    @Test
    fun `LOCK radar experimental usa os bytes 8-9 e limita em 0xFFFE`() {
        fun b(nextDist: Int) = PodPackets.navigationRaw(PodIcon.TURN_LEFT, 174, PodIcon.DEPART, nextDist, total2542, 0x01)
        b(260).let { assertEquals(0x01, it[8].toInt() and 0xFF); assertEquals(0x04, it[9].toInt() and 0xFF) }
        b(-1).let { assertEquals(0xFF, it[8].toInt() and 0xFF); assertEquals(0xFF, it[9].toInt() and 0xFF) }
        b(70_000).let { assertEquals(0xFF, it[8].toInt() and 0xFF); assertEquals(0xFE, it[9].toInt() and 0xFF) }
        assertWellFormed(b(260))
    }

    @Test
    fun `navigation de alto nivel calcula a intensidade do byte 6`() {
        // 68 m à noite → 0x21; 167 m à noite → 0x01 (mesmos casos reais acima)
        val a = PodPackets.navigation(PodIcon.DESTINATION, 68, bottom = BottomInfo.TotalDistance(68), night = true)
        assertEquals(nav(PodIcon.DESTINATION, 68, 0xFF, -1, BottomInfo.TotalDistance(68), 0x21), hex(a))
        val b = PodPackets.navigation(PodIcon.TURN_LEFT, 167, bottom = total2542, night = true)
        assertEquals("10 11 14 00 A7 01 01 FF FF FF 41 00 19 02 00 00 00 00 19 1A", hex(b))
    }

    // ---------- Telas especiais ----------

    @Test
    fun `EXT tela ociosa 0x3C (CRC 10 50)`() {
        val p = PodPacket.withCrc(PodPacket.fromHex("10 11 3C 00 00 04 40 15 00 00 41 00 04 03 00 00 00 00"))
        assertEquals("10 11 3C 00 00 04 40 15 00 00 41 00 04 03 00 00 00 00 10 50", hex(p))
    }

    @Test
    fun `LOCK rota iniciada`() = assertEquals(
        "10 11 45 00 00 01 00 FF FF FF 41 FF FF FF 00 00 00 00 1A 5A",
        hex(PodPackets.routeStarted()),
    )

    @Test
    fun `LOCK recalculando dia e noite`() {
        assertEquals("10 11 1C 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 0B EA", hex(PodPackets.recalculating(false)))
        assertEquals(1, PodPackets.recalculating(true)[6].toInt())
    }

    @Test
    fun `LOCK icone de ligacao`() = assertEquals(
        "40 05 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 ED 5E",
        hex(PodPackets.callIcon()),
    )

    @Test
    fun `todo pacote gerado tem 20 bytes e CRC valido`() {
        listOf(
            PodPackets.ping(), PodPackets.handshake(true), PodPackets.handshake(false), PodPackets.pin("0000"),
            PodPackets.setTime(23, 59, false), PodPackets.compass(123f, true), PodPackets.routeStarted(),
            PodPackets.recalculating(false), PodPackets.callIcon(),
            PodPackets.navigation(PodIcon.roundaboutRightHand(3), 400, PodIcon.TURN_RIGHT, BottomInfo.TimeRemaining(75)),
        ).forEach(::assertWellFormed)
    }

    // ---------- Intensidade por distância (byte 6) ----------

    @Test
    fun `intensidade por faixa de distancia`() {
        val faixas = listOf(
            0 to 0x50, 10 to 0x50, 11 to 0x40, 20 to 0x40, 21 to 0x30, 45 to 0x30, 46 to 0x20,
            70 to 0x20, 71 to 0x10, 95 to 0x10, 96 to 0x00, 500 to 0x00, Int.MAX_VALUE to 0x00,
        )
        for ((m, v) in faixas) assertEquals(v, PodPackets.distanceIntensity(m, night = false), "$m m")
        assertEquals(0x21, PodPackets.distanceIntensity(68, night = true))
        assertEquals(0x01, PodPackets.distanceIntensity(166, night = true))
    }

    // ---------- Rodapé ----------

    private val at1700 = LocalDateTime.of(2026, 9, 20, 17, 0)

    private fun bottom(b: BottomInfo) = b.bytes().joinToString(" ") { "%02X".format(it) }

    @Test
    fun `rodape distancia total`() {
        assertEquals("00 1D 02", bottom(BottomInfo.TotalDistance(2942)))
        assertEquals("FF FF FF", bottom(BottomInfo.TotalDistance(-1)))
    }

    @Test
    fun `rodape tempo restante`() {
        assertEquals("00 08 00", bottom(BottomInfo.TimeRemaining(8)))
        assertEquals("02 05 00", bottom(BottomInfo.TimeRemaining(125)))
        assertEquals("FF FF FF", bottom(BottomInfo.TimeRemaining(0)))
    }

    @Test
    fun `rodape hora de chegada 24h e 12h`() {
        assertEquals("11 1C 00", bottom(BottomInfo.ArrivalTime.from(at1700, 28, is12h = false)))
        assertEquals("85 1C 00", bottom(BottomInfo.ArrivalTime.from(at1700, 28, is12h = true)))
        assertEquals("49 0F 00", bottom(BottomInfo.ArrivalTime.from(LocalDateTime.of(2026, 9, 20, 9, 5), 10, is12h = true)))
        val late = LocalDateTime.of(2026, 9, 20, 23, 50)
        assertEquals("00 0A 00", bottom(BottomInfo.ArrivalTime.from(late, 20, is12h = false)))
        assertEquals("40 0A 00", bottom(BottomInfo.ArrivalTime.from(late, 20, is12h = true)))
        assertEquals("FF FF FF", bottom(BottomInfo.ArrivalTime.from(at1700, 0, is12h = false)))
    }

    // ---------- Ícones ----------

    @Test
    fun `rotatoria mao direita usa 0x32 mais saida menos 1 limitada a 1-9`() {
        assertEquals(0x32, PodIcon.roundaboutRightHand(1))
        assertEquals(0x33, PodIcon.roundaboutRightHand(2))
        assertEquals(0x3A, PodIcon.roundaboutRightHand(9))
        assertEquals(0x3A, PodIcon.roundaboutRightHand(12))
        assertEquals(0x32, PodIcon.roundaboutRightHand(0))
        assertEquals(0x32, PodIcon.roundaboutRightHand(-1))
    }

}
