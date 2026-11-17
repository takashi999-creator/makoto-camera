package app.makoto.camera

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class LocationTracker(context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)

    var last: Location? by mutableStateOf<Location?>(null)
        private set

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { last = it }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        try {
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L).build()
            client.requestLocationUpdates(req, callback, Looper.getMainLooper())
        } catch (e: Exception) {
            // 位置情報の許可がない場合は、位置なしで撮影する
        }
    }

    fun stop() {
        client.removeLocationUpdates(callback)
    }
}
