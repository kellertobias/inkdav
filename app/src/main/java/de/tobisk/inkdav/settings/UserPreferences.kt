package de.tobisk.inkdav.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "inkdav_settings")

data class InkDavSettings(
    val calendarPastDays: Int = 180,
    val calendarFutureMonths: Int = 36,
    val wifiOnlyFiles: Boolean = true,
    val boldText: Boolean = true,
    val pageNavigation: Boolean = true,
    val hiddenCalendarIds: Set<String> = emptySet(),
)

class UserPreferences(private val context: Context) {
    private object Keys {
        val pastDays = intPreferencesKey("calendar_past_days")
        val futureMonths = intPreferencesKey("calendar_future_months")
        val wifiOnlyFiles = booleanPreferencesKey("wifi_only_files")
        val boldText = booleanPreferencesKey("bold_text")
        val pageNavigation = booleanPreferencesKey("page_navigation")
        val hiddenCalendarIds = stringSetPreferencesKey("hidden_calendar_ids")
    }

    val settings: Flow<InkDavSettings> = context.dataStore.data.map { value ->
        InkDavSettings(
            calendarPastDays = value[Keys.pastDays] ?: 180,
            calendarFutureMonths = value[Keys.futureMonths] ?: 36,
            wifiOnlyFiles = value[Keys.wifiOnlyFiles] ?: true,
            boldText = value[Keys.boldText] ?: true,
            pageNavigation = value[Keys.pageNavigation] ?: true,
            hiddenCalendarIds = value[Keys.hiddenCalendarIds] ?: emptySet(),
        )
    }

    suspend fun setCalendarWindow(pastDays: Int, futureMonths: Int) = context.dataStore.edit {
        it[Keys.pastDays] = pastDays.coerceIn(0, 3650)
        it[Keys.futureMonths] = futureMonths.coerceIn(1, 120)
    }

    suspend fun setEink(bold: Boolean, pages: Boolean) = context.dataStore.edit {
        it[Keys.boldText] = bold
        it[Keys.pageNavigation] = pages
    }

    suspend fun setCalendarVisible(collectionId: String, visible: Boolean) = context.dataStore.edit {
        val hidden = (it[Keys.hiddenCalendarIds] ?: emptySet()).toMutableSet()
        if (visible) hidden.remove(collectionId) else hidden.add(collectionId)
        it[Keys.hiddenCalendarIds] = hidden
    }
}
