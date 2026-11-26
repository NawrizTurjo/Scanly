package dev.turjo.paperless.core.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/** Whether the phone is plugged in; background work can wait for it. */
interface PowerState {
    val charging: Flow<Boolean>

    companion object {
        /** For tests and callers that don't care: always charging. */
        val ALWAYS_CHARGING = object : PowerState {
            override val charging: Flow<Boolean> = flowOf(true)
        }
    }
}

@Singleton
class AndroidPowerState @Inject constructor(@ApplicationContext private val context: Context) : PowerState {
    override val charging: Flow<Boolean> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(intent.isPluggedIn())
            }
        }
        // ACTION_BATTERY_CHANGED is sticky: registering returns the current state at once.
        val current = ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        trySend(current?.isPluggedIn() ?: false)
        awaitClose { context.unregisterReceiver(receiver) }
    }.distinctUntilChanged()
}

private fun Intent.isPluggedIn() = getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
