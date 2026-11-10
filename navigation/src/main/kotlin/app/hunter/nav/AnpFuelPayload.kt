package app.hunter.nav

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Representa um posto de combustível geolocalizado com os preços coletados pela ANP.
 *
 * Chaves curtas para compatibilidade com payload JSON compacto (< 1.5MB comprimido):
 * - `gc`: Gasolina Comum (R$/L)
 * - `ga`: Gasolina Aditivada (R$/L)
 * - `et`: Etanol Hidratado (R$/L)
 * - `ds`: Diesel S10 (R$/L)
 * - `data`: Data da coleta no posto
 */
@Serializable
data class AnpStationDto(
    val id: String,
    val nome: String,
    val bandeira: String,
    val cityKey: String,
    val uf: String,
    val endereco: String,
    val bairro: String,
    val cep: String,
    val lat: Double,
    val lon: Double,
    val gc: Double? = null,
    val ga: Double? = null,
    val et: Double? = null,
    val ds: Double? = null,
    val data: String = "",
)

/**
 * Estatísticas agregadas de preços por município compiladas a partir da série histórica da ANP.
 */
@Serializable
data class AnpCityDto(
    val uf: String,
    val mun: String,
    val lat: Double,
    val lon: Double,
    val gcAvg: Double? = null,
    val gcMin: Double? = null,
    val gcMax: Double? = null,
    val gaAvg: Double? = null,
    val gaMin: Double? = null,
    val gaMax: Double? = null,
    val etAvg: Double? = null,
    val etMin: Double? = null,
    val etMax: Double? = null,
    val dsAvg: Double? = null,
    val dsMin: Double? = null,
    val dsMax: Double? = null,
    val ref: String = "",
)

/**
 * Estatísticas agregadas de preços por Estado (UF) para fallback regional em 3º nível.
 */
@Serializable
data class AnpStateDto(
    val uf: String,
    val lat: Double,
    val lon: Double,
    val gcAvg: Double? = null,
    val etAvg: Double? = null,
    val dsAvg: Double? = null,
    val ref: String = "",
)

/**
 * Envelope raiz do payload JSON distribuído via GitHub Releases/Assets e embarcado no seed do app.
 */
@Serializable
data class AnpFuelPayload(
    val v: String = "1.0",
    val geradoEm: String = "",
    val cidades: Map<String, AnpCityDto> = emptyMap(),
    val estados: Map<String, AnpStateDto> = emptyMap(),
    val postos: List<AnpStationDto> = emptyList(),
) {
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = true
        }

        fun fromJson(text: String): AnpFuelPayload = json.decodeFromString(text)

        fun toJson(payload: AnpFuelPayload): String = json.encodeToString(payload)
    }
}
