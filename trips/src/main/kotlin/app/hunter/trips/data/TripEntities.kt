package app.hunter.trips.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.hunter.model.TrackPoint
import app.hunter.model.TripStats
import app.hunter.model.TripSummary

@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtMs: Long,
    /** null enquanto grava. */
    val endedAtMs: Long? = null,
    val distanceM: Double = 0.0,
    val elapsedMs: Long = 0,
    val movingTimeMs: Long = 0,
    val stoppedTimeMs: Long = 0,
    val stopCount: Int = 0,
    val maxSpeedMps: Double = 0.0,
    val elevationGainM: Double = 0.0,
    val elevationLossM: Double = 0.0,
) {
    fun stats() = TripStats(
        distanceM, elapsedMs, movingTimeMs, stoppedTimeMs, stopCount, maxSpeedMps, elevationGainM, elevationLossM,
    )

    fun toSummary() = TripSummary(id, startedAtMs, endedAtMs, stats())

    fun withStats(s: TripStats) = copy(
        distanceM = s.distanceM, elapsedMs = s.elapsedMs, movingTimeMs = s.movingTimeMs,
        stoppedTimeMs = s.stoppedTimeMs, stopCount = s.stopCount, maxSpeedMps = s.maxSpeedMps,
        elevationGainM = s.elevationGainM, elevationLossM = s.elevationLossM,
    )
}

@Entity(
    tableName = "track_points",
    primaryKeys = ["tripId", "idx"],
    foreignKeys = [ForeignKey(TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId")],
)
data class TrackPointEntity(
    val tripId: Long,
    /** Ordem do ponto dentro do trajeto. */
    @ColumnInfo(name = "idx") val index: Int,
    val timeMs: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double?,
    val speedMps: Float?,
    val accuracyM: Float?,
    val bearingDeg: Float?,
) {
    fun toPoint() = TrackPoint(timeMs, latitude, longitude, altitudeM, speedMps, accuracyM, bearingDeg)

    companion object {
        fun from(tripId: Long, index: Int, p: TrackPoint) = TrackPointEntity(
            tripId, index, p.timeMs, p.latitude, p.longitude, p.altitudeM, p.speedMps, p.accuracyM, p.bearingDeg,
        )
    }
}
