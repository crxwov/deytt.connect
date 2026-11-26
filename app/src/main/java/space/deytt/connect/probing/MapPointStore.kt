package space.deytt.connect

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal class MapPointStore(context: Context) {
    private val preferences = context.getSharedPreferences("custom_map_point", Context.MODE_PRIVATE)

    fun read(): IpNetworkLocation? = preferences.getString("point", null)?.let { saved ->
        runCatching {
            val point = JSONObject(saved)
            IpNetworkLocation(
                point.getDouble("lat"), point.getDouble("lon"),
                point.getString("city"), "", point.getString("country"), custom = true,
            )
        }.getOrNull()
    }

    fun save(point: IpNetworkLocation?) {
        val value = point?.let {
            JSONObject().put("lat", it.latitude).put("lon", it.longitude)
                .put("city", it.city).put("country", it.countryCode).toString()
        }
        preferences.edit().putString("point", value).apply()
    }
}

internal object CitySearch {
    fun find(query: String, language: String): List<IpNetworkLocation> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchLanguage = if (language == "en") "en" else "default"
        val endpoint = URL("https://photon.komoot.io/api/?q=$encoded&layer=city&limit=6&lang=$searchLanguage")
        val connection = endpoint.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val features = JSONObject(response).getJSONArray("features")
            return (0 until features.length()).mapNotNull { index ->
                val feature = features.getJSONObject(index)
                val data = feature.getJSONObject("properties")
                val coordinates = feature.getJSONObject("geometry").getJSONArray("coordinates")
                val city = data.optString("name").filterNot(Char::isISOControl).trim().take(80)
                val country = data.optString("countrycode")
                val lat = coordinates.optDouble(1)
                val lon = coordinates.optDouble(0)
                if (city.isBlank() || !country.matches(Regex("[A-Za-z]{2}")) ||
                    lat !in -90.0..90.0 || lon !in -180.0..180.0
                ) return@mapNotNull null
                IpNetworkLocation(lat, lon, city, data.optString("state"), country, custom = true)
            }.distinctBy { Triple(it.city, it.region, it.countryCode) }
        } finally {
            connection.disconnect()
        }
    }
}
