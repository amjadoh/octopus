package app.octopus.local.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import app.octopus.local.domain.ProviderId
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("octopus_settings")
data class UserSettings(val consent: Boolean = false, val allowed: Set<ProviderId> = ProviderId.entries.toSet())
class SettingsStore(private val context: Context) {
    private val consentKey = intPreferencesKey("read_only_consent_version")
    private fun key(provider: ProviderId) = booleanPreferencesKey("allowed_${provider.name}")
    val flow = context.settingsDataStore.data.map { values ->
        UserSettings(values[consentKey] == 1, ProviderId.entries.filter { values[key(it)] != false }.toSet())
    }
    suspend fun acceptConsent() { context.settingsDataStore.edit { it[consentKey] = 1 } }
    suspend fun setAllowed(provider: ProviderId, allowed: Boolean) { context.settingsDataStore.edit { it[key(provider)] = allowed } }
    suspend fun clear() { context.settingsDataStore.edit { it.clear() } }
}
