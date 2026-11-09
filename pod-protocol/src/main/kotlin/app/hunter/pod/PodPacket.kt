package app.hunter.pod

/**
 * Enquadramento dos pacotes do Tripper Pod: 20 bytes = 18 de payload + CRC-16/CCITT-FALSE
 * (poly 0x1021, init 0xFFFF, MSB primeiro, sem XOR final) em big-endian.
 *
 * Formato documentado em ref/wazetripper/docs/PROTOCOL.md §2 (WazeTripper, Apache-2.0).
 * Ver NOTICE.md na raiz do projeto.
 */
object PodPacket {
    const val SIZE = 20
    const val PAYLOAD_SIZE = 18

    /** CRC-16/CCITT-FALSE dos primeiros [length] bytes. */
    fun crc16(data: ByteArray, length: Int = data.size): Int {
        require(length in 0..data.size) { "length fora do intervalo: $length" }
        var crc = 0xFFFF
        for (i in 0 until length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF
            }
        }
        return crc and 0xFFFF
    }

    /** Anexa o CRC (big-endian) a um payload de exatamente 18 bytes. */
    fun withCrc(payload: ByteArray): ByteArray {
        require(payload.size == PAYLOAD_SIZE) { "esperava $PAYLOAD_SIZE bytes, recebi ${payload.size}" }
        val crc = crc16(payload)
        return payload.copyOf(SIZE).also {
            it[18] = (crc shr 8).toByte()
            it[19] = crc.toByte()
        }
    }

    /** true se [packet] tem 20 bytes e o CRC confere. */
    fun isValid(packet: ByteArray): Boolean {
        if (packet.size != SIZE) return false
        val crc = crc16(packet, PAYLOAD_SIZE)
        return (packet[18].toInt() and 0xFF) == (crc shr 8) && (packet[19].toInt() and 0xFF) == (crc and 0xFF)
    }

    /** "10 11 1C" -> bytes. */
    fun fromHex(hex: String): ByteArray =
        hex.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    /** bytes -> "10 11 1C" (maiúsculas, separado por espaço). */
    fun toHex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
