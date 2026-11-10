package app.hunter.app.data.anp

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "anp_stations",
    indices = [
        Index(value = ["lat", "lon"]),
        Index(value = ["cityKey"]),
    ],
)
data class AnpStationEntity(
    @PrimaryKey val id: String,
    val nome: String,
    val bandeira: String,
    val cityKey: String,
    val uf: String,
    val endereco: String,
    val bairro: String,
    val cep: String,
    val lat: Double,
    val lon: Double,
    val precoGasolina: Double? = null,
    val precoGasolinaAdit: Double? = null,
    val precoEtanol: Double? = null,
    val precoDiesel: Double? = null,
    val dataColeta: String = "",
)

@Entity(
    tableName = "anp_city_stats",
    indices = [
        Index(value = ["lat", "lon"]),
    ],
)
data class AnpCityStatsEntity(
    @PrimaryKey val cityKey: String,
    val uf: String,
    val municipio: String,
    val lat: Double,
    val lon: Double,
    val gasolinaAvg: Double? = null,
    val gasolinaMin: Double? = null,
    val gasolinaMax: Double? = null,
    val gasolinaAditAvg: Double? = null,
    val gasolinaAditMin: Double? = null,
    val gasolinaAditMax: Double? = null,
    val etanolAvg: Double? = null,
    val etanolMin: Double? = null,
    val etanolMax: Double? = null,
    val dieselAvg: Double? = null,
    val dieselMin: Double? = null,
    val dieselMax: Double? = null,
    val dataReferencia: String = "",
    val updatedAtMs: Long = System.currentTimeMillis(),
)

@Entity(tableName = "anp_state_stats")
data class AnpStateStatsEntity(
    @PrimaryKey val uf: String,
    val lat: Double,
    val lon: Double,
    val gasolinaAvg: Double? = null,
    val etanolAvg: Double? = null,
    val dieselAvg: Double? = null,
    val dataReferencia: String = "",
    val updatedAtMs: Long = System.currentTimeMillis(),
)
