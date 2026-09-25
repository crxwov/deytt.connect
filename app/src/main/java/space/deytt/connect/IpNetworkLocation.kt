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
) {
    val placeLabel: String
        get() = listOf(city, region, countryCode).firstOrNull(String::isNotBlank) ?: "Неизвестный регион"
}

internal class IpNetworkLocationStore(context: Context) {
    private val preferences = context.getSharedPreferences("network_location_cache", Context.MODE_PRIVATE)

    /** Removes coordinates written by older versions; current location stays in memory only. */
    fun clear() = preferences.edit().clear().apply()
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
            )
        } finally {
            connection.disconnect()
        }
    }
}
