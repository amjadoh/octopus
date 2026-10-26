package app.octopus.local.catalog

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.*
import java.util.Locale
import kotlin.coroutines.resume

data class CurrentAreaHint(val city: String, val district: String?)

/** One foreground lookup on explicit tap. Coordinates are neither retained nor logged. */
class CurrentAreaLocator(private val context: Context) {
    private val manager get() = context.getSystemService(LocationManager::class.java)
    fun permitted() = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun locate(): CurrentAreaHint {
        check(permitted()) { "اسمح بالوصول إلى الموقع أثناء استخدام التطبيق، أو اختر المدينة والحي من الاقتراحات." }
        check(manager.isLocationEnabled) { "الموقع مغلق؛ فعّله من إعدادات الجوال ثم أعد تحديد الموقع." }
        check(Geocoder.isPresent()) { "خدمة أسماء المناطق غير متاحة على هذا الجهاز؛ اختر المنطقة من الاقتراحات." }
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val providers = listOfNotNull(if (fine) LocationManager.GPS_PROVIDER else null, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        check(providers.isNotEmpty()) { "لا يتوفر مصدر لتحديد الموقع؛ اختر المنطقة من الاقتراحات." }
        val location = withTimeoutOrNull(25_000) {
            coroutineScope {
                val result = CompletableDeferred<Location>()
                val jobs = providers.map { provider -> launch {
                    val found = runCatching { current(provider) }.getOrNull()
                    if (found != null && usable(found)) result.complete(found)
                } }
                try { result.await() } finally { jobs.forEach { it.cancel() } }
            }
        } ?: error("لم يتحدد موقع حديث بدقة كافية؛ حاول قرب نافذة أو اختر المنطقة من الاقتراحات.")
        val geocoder = Geocoder(context, Locale.forLanguageTag("ar-SA"))
        val addresses = withTimeoutOrNull(15_000) {
            if (Build.VERSION.SDK_INT >= 33) suspendCancellableCoroutine { continuation ->
                geocoder.getFromLocation(location.latitude, location.longitude, 5, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<android.location.Address>) {
                        if (continuation.isActive) continuation.resume(addresses.toList())
                    }
                    override fun onError(errorMessage: String?) {
                        if (continuation.isActive) continuation.resume(emptyList())
                    }
                })
            } else withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(location.latitude, location.longitude, 5).orEmpty()
            }
        }.orEmpty().filter { it.countryCode.equals("SA", true) }
        val address = addresses.firstOrNull { !it.locality.isNullOrBlank() }
            ?: error("لم أتعرف على مدينة سعودية من الموقع؛ اخترها من الاقتراحات.")
        return CurrentAreaHint(address.locality, address.subLocality?.takeIf { it.isNotBlank() })
    }

    private fun usable(location: Location): Boolean {
        val age = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        return age in 0..120_000_000_000 && location.hasAccuracy() && location.accuracy in 0f..500f
    }

    @SuppressLint("MissingPermission")
    private suspend fun current(provider: String): Location? = suspendCancellableCoroutine { continuation ->
        if (Build.VERSION.SDK_INT >= 30) {
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                if (continuation.isActive) continuation.resume(location)
            }
        } else {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    manager.removeUpdates(this)
                    if (continuation.isActive) continuation.resume(location)
                }
                @Deprecated("Legacy Android callback")
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) {
                    manager.removeUpdates(this)
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            continuation.invokeOnCancellation { manager.removeUpdates(listener) }
            @Suppress("DEPRECATION")
            manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        }
    }
}
