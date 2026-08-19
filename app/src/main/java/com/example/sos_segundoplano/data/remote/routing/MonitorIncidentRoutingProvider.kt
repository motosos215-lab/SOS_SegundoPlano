package com.example.sos_segundoplano.data.remote.routing

import com.example.sos_segundoplano.BuildConfig
import com.example.sos_segundoplano.domain.routing.MonitorIncidentRoute
import com.example.sos_segundoplano.domain.routing.MonitorIncidentRouteResult
import com.example.sos_segundoplano.domain.routing.MonitorIncidentRoutingRepository
import com.example.sos_segundoplano.ui.maps.MotoMapPoint
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Routing is deliberately isolated from the MotoSOS backend contract. The public OSRM endpoint is
 * suitable for prototype/demo use and can later be replaced with a MotoSOS/self-hosted routing
 * base URL without changing the Monitor UI or MapLibre rendering code.
 */
object MonitorIncidentRoutingProvider {
    val repository: MonitorIncidentRoutingRepository by lazy {
        val moshi = Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
        val api = Retrofit.Builder()
            .baseUrl(BuildConfig.MONITOR_ROUTING_BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(OsrmRouteApi::class.java)
        DefaultMonitorIncidentRoutingRepository(api)
    }
}

private interface OsrmRouteApi {
    @GET("route/v1/driving/{coordinates}")
    suspend fun route(
        @Path(value = "coordinates", encoded = true) coordinates: String,
        @Query("alternatives") alternatives: Boolean = false,
        @Query("steps") steps: Boolean = false,
        @Query("geometries") geometries: String = "geojson",
        @Query("overview") overview: String = "full"
    ): Response<OsrmRouteResponseDto>
}

@JsonClass(generateAdapter = false)
private data class OsrmRouteResponseDto(
    val code: String? = null,
    val routes: List<OsrmRouteDto>? = null
)

@JsonClass(generateAdapter = false)
private data class OsrmRouteDto(
    val distance: Double? = null,
    val duration: Double? = null,
    val geometry: OsrmGeometryDto? = null
)

@JsonClass(generateAdapter = false)
private data class OsrmGeometryDto(
    val type: String? = null,
    val coordinates: List<List<Double>>? = null
)

private class DefaultMonitorIncidentRoutingRepository(
    private val api: OsrmRouteApi
) : MonitorIncidentRoutingRepository {
    override suspend fun route(origin: MotoMapPoint, destination: MotoMapPoint): MonitorIncidentRouteResult {
        if (!origin.isValidRoutingPoint() || !destination.isValidRoutingPoint()) {
            return MonitorIncidentRouteResult.Failure("No hay coordenadas válidas para calcular la ruta.")
        }
        return runCatching {
            val encodedCoordinates = listOf(origin, destination).joinToString(";") { point ->
                String.format(Locale.US, "%.7f,%.7f", point.longitude, point.latitude)
            }
            val response = api.route(encodedCoordinates)
            if (!response.isSuccessful) {
                return@runCatching MonitorIncidentRouteResult.Failure("No pudimos calcular la ruta por calles en este momento.")
            }
            val body = response.body()
            if (body?.code != "Ok") {
                return@runCatching MonitorIncidentRouteResult.Failure("No se encontró una ruta disponible hasta el incidente.")
            }
            val first = body.routes.orEmpty().firstOrNull()
                ?: return@runCatching MonitorIncidentRouteResult.Failure("No se encontró una ruta disponible hasta el incidente.")
            val points = first.geometry?.coordinates.orEmpty().mapNotNull { coordinate: List<Double> ->
                val longitude = coordinate.getOrNull(0)
                val latitude = coordinate.getOrNull(1)
                if (
                    latitude != null && longitude != null &&
                    latitude.isFinite() && longitude.isFinite() &&
                    latitude in -90.0..90.0 && longitude in -180.0..180.0
                ) {
                    MotoMapPoint(latitude = latitude, longitude = longitude)
                } else null
            }
            val distance = first.distance
            val duration = first.duration
            if (points.size < 2 || distance == null || duration == null || !distance.isFinite() || !duration.isFinite()) {
                return@runCatching MonitorIncidentRouteResult.Failure("La ruta recibida no contiene información suficiente.")
            }
            MonitorIncidentRouteResult.Success(
                MonitorIncidentRoute(
                    points = points,
                    distanceMeters = distance.coerceAtLeast(0.0),
                    durationSeconds = duration.coerceAtLeast(0.0)
                )
            )
        }.getOrElse {
            MonitorIncidentRouteResult.Failure("No pudimos conectar con el servicio de rutas. Puedes reintentarlo.")
        }
    }

    private fun MotoMapPoint.isValidRoutingPoint(): Boolean =
        latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
            !(latitude == 0.0 && longitude == 0.0)
}
