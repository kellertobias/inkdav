package de.tobisk.inkdav.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "inkdav_settings")

data class InkDavSettings(
    val calendarPastDays: Int = 180,
    val calendarFutureMonths: Int = 36,
    val landscapeWeekStartHour: Int = 8,
    val landscapeWeekEndHour: Int = 24,
    val wifiOnlyFiles: Boolean = true,
    val boldText: Boolean = true,
    val pageNavigation: Boolean = true,
    val hiddenCalendarIds: Set<String> = emptySet(),
    val localFilesRootUri: String? = null
)

class UserPreferences(private val context: Context) {
    private object Keys {
        val pastDays = intPreferencesKey("calendar_past_days")
        val futureMonths = intPreferencesKey("calendar_future_months")
        val landscapeWeekStartHour = intPreferencesKey("landscape_week_start_hour")
        val landscapeWeekEndHour = intPreferencesKey("landscape_week_end_hour")
        val wifiOnlyFiles = booleanPreferencesKey("wifi_only_files")
        val boldText = booleanPreferencesKey("bold_text")
        val pageNavigation = booleanPreferencesKey("page_navigation")
        val hiddenCalendarIds = stringSetPreferencesKey("hidden_calendar_ids")
        val localFilesRootUri = stringPreferencesKey("local_files_root_uri")
    }

    val settings: Flow<InkDavSettings> = context.dataStore.data.map { value ->
        InkDavSettings(
            calendarPastDays = value[Keys.pastDays] ?: 180,
            calendarFutureMonths = value[Keys.futureMonths] ?: 36,
            landscapeWeekStartHour = value[Keys.landscapeWeekStartHour] ?: 8,
            landscapeWeekEndHour = value[Keys.landscapeWeekEndHour] ?: 24,
            wifiOnlyFiles = value[Keys.wifiOnlyFiles] ?: true,
            boldText = value[Keys.boldText] ?: true,
            pageNavigation = value[Keys.pageNavigation] ?: true,
            hiddenCalendarIds = value[Keys.hiddenCalendarIds] ?: emptySet(),
            localFilesRootUri = value[Keys.localFilesRootUri]
        )
    }

    suspend fun setCalendarWindow(pastDays: Int, futureMonths: Int) = context.dataStore.edit {
        it[Keys.pastDays] = pastDays.coerceIn(0, 3650)
        it[Keys.futureMonths] = futureMonths.coerceIn(1, 120)
    }

    suspend fun setLandscapeWeekHours(startHour: Int, endHour: Int) = context.dataStore.edit {
        val start = startHour.coerceIn(0, 23)
        it[Keys.landscapeWeekStartHour] = start
        it[Keys.landscapeWeekEndHour] = endHour.coerceIn(start + 1, 24)
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

    suspend fun setLocalFilesRoot(uri: String?) = context.dataStore.edit {
        if (uri == null) it.remove(Keys.localFilesRootUri) else it[Keys.localFilesRootUri] = uri
    }
}
