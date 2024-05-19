package it.dii.unipi.masss.noisemapper
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
// class that detects the power events related to the battery and the power save mode, it registers a receiver to listen to the events
class PowerSaveModeDetector(private val noiseActivity: NoiseActivity) {
    var isPowerSaveMode: Boolean
    init {
        val powerManager = noiseActivity.getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryManager = noiseActivity.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryStatus = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        isPowerSaveMode = powerManager.isPowerSaveMode || batteryStatus <= 20 // if the battery is low or the power save mode is on
    }

    private val powerSaveModeReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            var newIsPowerSaveMode = isPowerSaveMode
            when (intent.action) {
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    if (powerManager.isPowerSaveMode) {
                        // Device entered power save mode
                        Log.i("PowerSaveModeDetector", "Power save mode ON")
                        newIsPowerSaveMode = true
                    } else {
                        Log.i("PowerSaveModeDetector", "Power save mode OFF")
                        // Device exited power save mode
                        newIsPowerSaveMode = false
                    }
                }
                Intent.ACTION_BATTERY_LOW -> {
                    newIsPowerSaveMode = true
                    Log.i("PowerSaveModeDetector", "Battery low")
                    // Battery is low
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    Log.i("PowerSaveModeDetector", "Power connected")
                    newIsPowerSaveMode = false
                    // Power is connected
                }
            }
            if (newIsPowerSaveMode != isPowerSaveMode) { // this prevent the callback to be called if the mode is the same
                noiseActivity.onBatteryStatusUpdate() // callback that force to update the timer interval
                isPowerSaveMode = newIsPowerSaveMode
            }
        }
    }


        fun register() {
            val intentFilter = IntentFilter().apply {
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                addAction(Intent.ACTION_BATTERY_LOW)
                addAction(Intent.ACTION_POWER_CONNECTED)
            }
            noiseActivity.registerReceiver(powerSaveModeReceiver, intentFilter)
        }

        fun unregister() {
            noiseActivity.unregisterReceiver(powerSaveModeReceiver)
        }

}
