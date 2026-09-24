package space.deytt.connect

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Approximate city-level location resolved from the current public IP; the IP itself is never retained. */
internal data class IpNetworkLocation(
    val latitude: Double,
    val longitude: Double,
    val city: String,
    val region: String,
    val countryCode: String,
    val savedAtMillis: Long,
) {
    val placeLabel: String
        get() = listOf(city, region, countryCode).firstOrNull(String::isNotBlank) ?: "Неизвестный регион"
}

internal class IpNetworkLocationStore(context: Context) {
    private val preferences = context.getSharedPreferences("network_location_cache", Context.MODE_PRIVATE)

    fun read(): IpNetworkLocation? {
        if (!preferences.contains(KEY_LATITUDE) || !preferences.contains(KEY_LONGITUDE)) return null
        val latitude = preferences.getString(KEY_LATITUDE, null)?.toDoubleOrNull() ?: return null
        val longitude = preferences.getString(KEY_LONGITUDE, null)?.toDoubleOrNull() ?: return null
        if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -85.0..85.0 || longitude !in -180.0..180.0) return null
        return IpNetworkLocation(
            latitude = latitude,
            longitude = longitude,
            city = preferences.getString(KEY_CITY, "").orEmpty(),
            region = preferences.getString(KEY_REGION, "").orEmpty(),
            countryCode = preferences.getString(KEY_COUNTRY, "").orEmpty(),
            savedAtMillis = preferences.getLong(KEY_SAVED_AT, 0L),
        )
    }

    fun save(location: IpNetworkLocation) {
        preferences.edit()
            .putString(KEY_LATITUDE, location.latitude.toString())
            .putString(KEY_LONGITUDE, location.longitude.toString())
            .putString(KEY_CITY, location.city)
            .putString(KEY_REGION, location.region)
            .putString(KEY_COUNTRY, location.countryCode)
            .putLong(KEY_SAVED_AT, location.savedAtMillis)
            .apply()
    }

    fun clear() = preferences.edit().clear().apply()

    fun isStale(location: IpNetworkLocation, nowMillis: Long = System.currentTimeMillis()): Boolean =
        location.savedAtMillis <= 0L || nowMillis - location.savedAtMillis >= CACHE_MAX_AGE_MILLIS

    private companion object {
        const val KEY_LATITUDE = "latitude"
        const val KEY_LONGITUDE = "longitude"
        const val KEY_CITY = "city"
        const val KEY_REGION = "region"
        const val KEY_COUNTRY = "country"
        const val KEY_SAVED_AT = "saved_at"
        const val CACHE_MAX_AGE_MILLIS = 6 * 60 * 60 * 1000L
    }
}

internal object IpNetworkLocationClient {
    private const val ENDPOINT = "https://ipinfo.io/json"
    private const val CONNECT_TIMEOUT_MILLIS = 3_500
    private const val READ_TIMEOUT_MILLIS = 3_500
    private const val MAX_RESPONSE_CHARS = 8_192

    fun fetch(): IpNetworkLocation {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-store")
            instanceFollowRedirects = false
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("IP location request failed with HTTP ${connection.responseCode}")
            }
            val payload = connection.inputStream.bufferedReader().use { reader ->
                val result = StringBuilder()
                while (result.length < MAX_RESPONSE_CHARS) {
                    val line = reader.readLine() ?: break
                    if (result.length + line.length + 1 > MAX_RESPONSE_CHARS) {
                        throw IOException("IP location response exceeds the size limit")
                    }
                    result.append(line).append('\n')
                }
                result.toString()
            }
            val json = JSONObject(payload)
            val coordinates = json.optString("loc").split(',', limit = 2)
            if (coordinates.size != 2) throw IOException("IP location response has no coordinates")
            val latitude = coordinates[0].trim().toDoubleOrNull()
            val longitude = coordinates[1].trim().toDoubleOrNull()
            if (latitude == null || longitude == null || !latitude.isFinite() || !longitude.isFinite() ||
                latitude !in -85.0..85.0 || longitude !in -180.0..180.0
            ) {
                throw IOException("IP location response has invalid coordinates")
            }
            return IpNetworkLocation(
                latitude = latitude,
                longitude = longitude,
                city = json.optString("city").takeIf { it != "null" }.orEmpty(),
                region = json.optString("region").takeIf { it != "null" }.orEmpty(),
                countryCode = json.optString("country").takeIf { it != "null" }.orEmpty(),
                savedAtMillis = System.currentTimeMillis(),
            )
        } finally {
            connection.disconnect()
        }
    }
}
