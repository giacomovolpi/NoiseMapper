package it.dii.unipi.masss.noisemapper

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import com.google.gson.Gson
import com.kontakt.sdk.android.ble.manager.ProximityManager
import com.kontakt.sdk.android.ble.manager.ProximityManagerFactory
import com.kontakt.sdk.android.ble.manager.listeners.simple.SimpleIBeaconListener
import com.kontakt.sdk.android.common.KontaktSDK
import com.kontakt.sdk.android.common.profile.IBeaconDevice
import com.kontakt.sdk.android.common.profile.IBeaconRegion
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

class BLEScanner(val activity: NoiseActivity, private val isPowerSaveMode: Boolean) {
    private val proximityManager : ProximityManager
    private var lastUpdate: Long = 0
    var json_array_request: ArrayList<Map<String, Any>> = ArrayList()

    // obtain flush window from numbers.xml
    private var FAST_FLUSH_WINDOW = activity.resources.getInteger(R.integer.FAST_FLUSH_SAMPLES_WINDOW)
    private var SLOW_FLUSH_WINDOW = activity.resources.getInteger(R.integer.SLOW_FLUSH_SAMPLES_WINDOW)
    private var retries = 1

    init {
        KontaktSDK.initialize(activity);
        proximityManager = ProximityManagerFactory.create(activity)
        setupProximityManager(activity)
    }

    // flush window size depends on the power save mode
    fun getFlushWindow() : Int
    {
        return if (isPowerSaveMode) SLOW_FLUSH_WINDOW else FAST_FLUSH_WINDOW
    }

    // define the update callback for bluetooth related events
    fun setupProximityManager(activity: NoiseActivity) {
        proximityManager.setIBeaconListener(object : SimpleIBeaconListener() {
            override fun onIBeaconsUpdated(
                beacons: MutableList<IBeaconDevice>,
                region: IBeaconRegion
            ) {
                val mutableBeacons =
                    beacons.filter { it.uniqueId in activity.bleConfig.beaconRoomMap.mapping.keys }
                        .toMutableList()
                if (mutableBeacons.isEmpty()) {
                    return
                }
                mutableBeacons.sortByDescending { it.rssi } // sort by rssi
                val strongestBeacon = mutableBeacons[0]
                val nearest_room =
                    activity.bleConfig.beaconRoomMap.mapping.get(strongestBeacon.uniqueId) ?: "Unknown"
                activity.findViewById<TextView>(R.id.current_room).text = "Current room: $nearest_room"
                // print the updated iBeacon devices
                println("iBeacon: Updated iBeacon devices: $beacons")
                activity.findViewById<ListView>(R.id.beacon_list).adapter = BeaconAdapter(mutableBeacons)
                val average_noise =
                    activity.map_noise_level.filter { it.key > lastUpdate && it.value != Double.NEGATIVE_INFINITY }.values.toList()
                        .average()
                lastUpdate = System.currentTimeMillis()
                pushUpdate(nearest_room, average_noise)
            }
        })
    }

    private fun flushRequest() {
        //val json_array = generate_json_from_arraylist()
        val json = Gson()
        val json_array = json.toJson(json_array_request)
        //send the array of json, then clear it
        send_json_array(json_array)
    }

    // locally save samples in a queue, then send them to the server in batches to reduce network overhead
    private fun pushUpdate(nearest_room: String?, average_noise: Double) {
        //push json in the queue
        val m = mapOf("room" to nearest_room, "noise" to average_noise) as Map<String, Any>
        json_array_request.add(m)
        //if the array gets gets to a certain size, all the json ar sent to server server
        if (json_array_request.size == getFlushWindow() * retries) { // this depends on how many retries we did already
            flushRequest()
        }
    }

    private fun send_json_array(json: String) {
        // push the updated iBeacon devices to endpoint /beacons
        val measurement_url = activity.url + "/measurements"
        println("Pushing $json to $measurement_url")
        val client = OkHttpClient()
        val body = json.toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder()
            .url(measurement_url)
            .post(body)
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.d("NoiseMapper", "Failed to push update")
            }
            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    Log.d("NoiseMapper", "Successful push")
                    // clear the json array
                    json_array_request.clear()
                    retries = 1 // 1 instead of 0 because we need to multiply it by the flush window size
                } else {
                    Log.d("NoiseMapper", "Failed push")
                    retries++ // if the push fails, we retry at the next flush_window
                }
            }
        })
    }


    // adapter for the list of beacons, used to render the beacon list if we're in debug mode
    class BeaconAdapter(private val beacons: MutableList<IBeaconDevice>) : BaseAdapter() {
        override fun getCount(): Int { return beacons.size }
        override fun getItem(position: Int): Any { return beacons[position] }
        override fun getItemId(position: Int): Long { return position.toLong() }
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val beacon = beacons[position]
            val view = convertView ?: LayoutInflater.from(parent?.context)
                .inflate(R.layout.beacon_item, parent, false)
            view.findViewById<TextView>(R.id.beacon_id).text = beacon.uniqueId
            view.findViewById<TextView>(R.id.beacon_distance).text =
                String.format("%.2f", beacon.distance) + " m" // stop at centimeter level precision (don't need more)
            return view
        }
    }

    fun stopScanning() {
        proximityManager.stopScanning()
        flushRequest() // this is to send the last batch of data also if the window is not full
    }

    fun startScanning() {
        proximityManager.connect {
            proximityManager.startScanning()
        }
    }

}
