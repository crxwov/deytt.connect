package space.deytt.connect

import android.content.Context
import androidx.core.content.edit
import java.util.concurrent.CopyOnWriteArrayList

class FavoriteRoutesStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getFavorites(): Set<String> {
        return prefs.getStringSet(KEY_FAVORITES, emptySet())?.toSet() ?: emptySet()
    }

    fun isFavorite(routeId: String): Boolean {
        return getFavorites().contains(routeId)
    }

    fun setFavorite(routeId: String, favorite: Boolean) {
        val current = getFavorites().toMutableSet()
        val changed = if (favorite) current.add(routeId) else current.remove(routeId)
        if (changed) {
            prefs.edit { putStringSet(KEY_FAVORITES, current) }
            notifyListeners()
        }
    }

    fun toggleFavorite(routeId: String): Boolean {
        val current = getFavorites().toMutableSet()
        val newState = if (current.contains(routeId)) {
            current.remove(routeId)
            false
        } else {
            current.add(routeId)
            true
        }
        prefs.edit { putStringSet(KEY_FAVORITES, current) }
        notifyListeners()
        return newState
    }

    companion object {
        private const val PREFS_NAME = "favorite_routes_store"
        private const val KEY_FAVORITES = "favorite_route_ids"

        private val listeners = CopyOnWriteArrayList<() -> Unit>()

        fun addListener(listener: () -> Unit) {
            listeners.addIfAbsent(listener)
        }

        fun removeListener(listener: () -> Unit) {
            listeners.remove(listener)
        }

        private fun notifyListeners() {
            listeners.forEach { runCatching { it.invoke() } }
        }
    }
}
