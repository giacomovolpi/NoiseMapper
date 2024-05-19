package it.dii.unipi.masss.noisemapper

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import android.widget.Button
import android.widget.CheckBox
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.datepicker.MaterialDatePicker

import androidx.core.util.Pair
import java.util.Calendar
import java.util.Timer
import java.util.TimerTask
import kotlin.concurrent.fixedRateTimer
import kotlin.math.abs
import kotlin.math.log10


class NoiseActivity : AppCompatActivity() {
    private lateinit var debugCheckBox: CheckBox
    private lateinit var beaconList: ListView
    var FAST_UPDATE_MAP_PERIOD : Int = 0
    var SLOW_UPDATE_MAP_PERIOD : Int = 0
    lateinit var url: String

                     val map_noise_level: MutableMap<Long, Double> = mutableMapOf()
    private lateinit var noise_microphone: NoiseMicrophone
    private lateinit var ble_scanner: BLEScanner
    private lateinit var pickDateButton: Button
    private          val RECORD_AUDIO_BLUETOOTH_SCAN_PERMISSION_REQUEST_CODE = 101
    private lateinit var switchCompat: SwitchCompat
    private lateinit var webView: WebView
    private lateinit var graph: Graph
    private lateinit var updateMapTimer: Timer
            lateinit var bleConfig: BLEConfig
    private lateinit var noise_map_io: NoiseMapIO
    private lateinit var notGrantedPermissions: Array<String>
    private          val requiredPermissions = arrayOf(Manifest.permission.RECORD_AUDIO) +
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            else arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) // There is no need to ask about ACCESS_FINE_LOCATION anymore for BT scanning purposes for VERSION_CODES.S and higher if you add android:usesPermissionFlags="neverForLocation" under BLUETOOTH_SCAN in your manifest file.
    private var bluetoothAdapter: BluetoothAdapter? = null
    private lateinit var powerGovernor : PowerSaveModeDetector
    private lateinit var proximitySensorHandler: ProximitySensorHandler
    var startDate: Long = 0
    var endDate: Long = 0
    private lateinit var graphFileName : String


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FAST_UPDATE_MAP_PERIOD = resources.getInteger(R.integer.FAST_UPDATE_MAP_PERIOD)
        SLOW_UPDATE_MAP_PERIOD = resources.getInteger(R.integer.SLOW_UPDATE_MAP_PERIOD)
        graphFileName = resources.getString(R.string.graph_file_name)
        setContentView(R.layout.noise_activity)

        // get the URL from the intent
        url = intent.getStringExtra("serverURL").toString()
        Log.i("NoiseActivity", "URL is $url")

        bleConfig = BLEConfig(this, serverUrl = url)
        if (!bleConfig.gotConfig()){
            Toast.makeText(
                this,
                "Please connect to Internet for the first app lunch",
                Toast.LENGTH_LONG
            ).show()
            intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            return // if we're not able to load a valid configuration file, abort
        }

        noise_map_io = NoiseMapIO(this, url)
        createGraphUI()

        bluetoothAdapter = android.bluetooth.BluetoothManager::class.java.cast(
            getSystemService(android.content.Context.BLUETOOTH_SERVICE)
        )?.adapter

        proximitySensorHandler = ProximitySensorHandler(this)

        powerGovernor = PowerSaveModeDetector(this)
        powerGovernor.register()

        startDate = System.currentTimeMillis() - this.resources.getInteger(R.integer.MILLISECONDS_IN_A_WEEK)
        endDate = System.currentTimeMillis()
        pickDateButton = findViewById(R.id.pick_date_button)
        pickDateButton.setOnClickListener {
            val dateRangePicker =
                MaterialDatePicker.Builder.dateRangePicker()
                    .setTitleText("Select dates")
                    .setSelection(Pair(startDate, endDate))
                    .build()
            dateRangePicker.addOnPositiveButtonClickListener { selection ->
                startDate = selection.first
                // add 1 day to the end date to include the whole day
                endDate = selection.second.plus(this.resources.getInteger(R.integer.MILLISECONDS_IN_A_DAY))
                Log.i("MainActivity", "Date range selected: $startDate - $endDate")
                updateMap(startDate, endDate)
            }
            if (inSensingState()) { // the same button is used to
                updateMap()         // refresh the map
            } else {                // and to pick a date
                dateRangePicker.show(supportFragmentManager, "dateRangePicker")
            }
        }

        // register for the sensing switch change event
        switchCompat.setOnCheckedChangeListener { _, isChecked ->
            Log.i("NoiseMapper", "Switch event")
            if (isChecked) {
                Log.i("NoiseMapper", "Switch is ON: entering in sensing state")
                enterSensingState()
            } else {
                Log.i("NoiseMapper", "Switch is OFF: exiting sensing state")
                exitSensingState()
            }
        }

        // debug checkbox logic to show/hide the beacon list
        beaconList = findViewById(R.id.beacon_list)
        debugCheckBox = findViewById(R.id.debugCheckBox)
        debugCheckBox.setOnCheckedChangeListener { _, isChecked ->
            beaconList.visibility = if (isChecked) ListView.VISIBLE else ListView.GONE
        }
        Log.i("NoiseActivity", "Noise activity started")
    }

    // by default the map shows the last week of data
    fun createGraphUI() {
        graph = Graph(filesDir.absolutePath, bleConfig)
        switchCompat = findViewById(R.id.sensing_on_off)
        // create the graph
        val timeAWeekBefore = Calendar.getInstance()
        timeAWeekBefore.add(Calendar.HOUR_OF_DAY, -1 * 24 * 7)
        updateMap(startTS = timeAWeekBefore.timeInMillis)

        webView = findViewById(R.id.map_web_view)
        webView.settings.javaScriptEnabled = true;  // required for the plot to work
        webView.settings.allowFileAccess = true;    // required to access the html plot file
        webView.settings.builtInZoomControls = true;// zoom using touch gestures
    }

    private fun inSensingState(): Boolean {
        return findViewById<SwitchCompat>(R.id.sensing_on_off).isChecked
    }

    fun enterSensingState() {
        Log.i("NoiseMapper", "Entering sensing state")
        if (!permissionCheck()) {
            requestPermissions() // callback on all permissions granted will eventually call senseWithPermissions
        }else {
            senseWithPermissions()
        }
    }

    // must be called only once all permissions are granted
    private fun senseWithPermissions() {
        if (enableBT()) {
            initializeSensors()
            startSensing()
            scheduleUpdate()
            pickDateButton.text = getString(R.string.refresh_map)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableBT() : Boolean{
        // check that bluetooth is enabled, if not, ask the user to enable it
        if (!(bluetoothAdapter?.isEnabled)!!) {
            Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_SHORT).show()
            switchCompat.isChecked = false
            return false
        }
        return true
    }

    // enable all sensors
    private fun startSensing() {
        noise_microphone.startListening()
        ble_scanner.startScanning()
        proximitySensorHandler.startListening()
    }

    inner class RecorderTask() : TimerTask() {

        private lateinit var sound: TextView
        private lateinit var mRecorder: MediaRecorder

        // /!!\ This setup method must be called before the TimerTask is scheduled /!!\
        // We cannot use the default constructor because those variables are passed by NoiseMicrophone
        fun setup(mRecorder: MediaRecorder, sound: TextView) {
            this.mRecorder = mRecorder
            this.sound = sound
        }

        override fun run() {
            runOnUiThread {
                        val amplitude = mRecorder.maxAmplitude
                        Log.i("NoiseDetection", "Recorder max amplitude is $amplitude")
                        var amplitudeDb =
                            20 * log10(abs(if (amplitude == 0) 1 else amplitude).toDouble())
                        if (proximitySensorHandler.isNearObject) {
                            amplitudeDb += proximitySensorHandler.DB_ADJUSTMENT
                            Log.i("NoiseDetection", "Proximity sensor detected an object")
                        }
                        val currentTimestamp = System.currentTimeMillis()
                        map_noise_level[currentTimestamp] = amplitudeDb
                        Log.i(
                            "NoiseDetection",
                            "Level db is $amplitudeDb at time $currentTimestamp"
                        )
                        sound.text = String.format("%.1f dB", amplitudeDb)
                        when {
                            amplitudeDb > 80 -> { // High noise level
                                sound.setTextColor(
                                    ContextCompat.getColor(
                                        this@NoiseActivity,
                                        R.color.high_noise
                                    )
                                )
                            }

                            amplitudeDb > 60 -> { // Medium noise level
                                sound.setTextColor(
                                    ContextCompat.getColor(
                                        this@NoiseActivity,
                                        R.color.medium_noise
                                    )
                                )
                            }

                            else -> { // Low noise level
                                sound.setTextColor(
                                    ContextCompat.getColor(
                                        this@NoiseActivity,
                                        R.color.low_noise
                                    )
                                )
                            }
                        }
                    }
                }
            }

    private fun initializeSensors() {
        // check that bluetooth is enabled, if not, ask the user to enable it and return
        if (!(bluetoothAdapter?.isEnabled)!!) {
            Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_SHORT).show()
            switchCompat.isChecked = false
            return
        }
        val recorderTask = RecorderTask()
        // microphone sensor
        noise_microphone =
            NoiseMicrophone(this, cacheDir, findViewById(R.id.db_level), recorderTask, powerGovernor.isPowerSaveMode)
        // bluetooth scanner sensor
        ble_scanner = BLEScanner(this, powerGovernor.isPowerSaveMode)
    }


    // check that all required permissions are granted
    private fun permissionCheck(): Boolean {
        // check if the permissions are already granted
        notGrantedPermissions = requiredPermissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        return notGrantedPermissions.isEmpty()
    }

    private fun requestPermissions() {
        // request the permissions
        ActivityCompat.requestPermissions(
            this,
            notGrantedPermissions,
            RECORD_AUDIO_BLUETOOTH_SCAN_PERMISSION_REQUEST_CODE
        )
        return
    }

    // callback for the permissions request
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_BLUETOOTH_SCAN_PERMISSION_REQUEST_CODE) { // we only handle the permissions request for the sensors
            if (grantResults.filter { it != 0 }.isEmpty()) {
                enterSensingState() // all permissions are granted, use this thread to enter the sensing state
            } else {
                Toast.makeText(this, "Please allow all permissions", Toast.LENGTH_SHORT).show()
                switchCompat.isChecked = false
            }
        }
    }

    // schedule the update of the map depending on the power save mode
    private fun scheduleUpdate() {
        // start a timer
        updateMapTimer = fixedRateTimer(initialDelay = 2000, period = (
                if (powerGovernor.isPowerSaveMode)
                    SLOW_UPDATE_MAP_PERIOD
                else
                    FAST_UPDATE_MAP_PERIOD).toLong())
        {
            updateMap()
        }
    }

    // Try contacting the server to get the last hour of noise levels and update the map with the new data
    private fun updateMap(startTS: Long? = null, endTS: Long? = null) {
        Thread {
            val currentTime = Calendar.getInstance()

            // Get time an hour before
            val timeAnHourBefore = Calendar.getInstance()
            timeAnHourBefore.add(Calendar.HOUR_OF_DAY, -1)

            // Convert to Unix timestamp
            val currentUnixTimestamp = endTS ?: currentTime.timeInMillis
            val previousHourUnixTimestamp = startTS ?: timeAnHourBefore.timeInMillis
            graph.makeplot(
                noise_map_io.performGetRequest(
                    previousHourUnixTimestamp / 1000,
                    currentUnixTimestamp / 1000,
                    if (this::ble_scanner.isInitialized) ble_scanner.json_array_request else ArrayList()
                ),
                graphFileName
            )
            runOnUiThread {
                webView.loadUrl("file://" + filesDir.absolutePath + "/${graphFileName}") // webview must be accessed by the same thread that created it
            }
        }.start()
    }

    private fun exitSensingState() {
        if (!this::noise_microphone.isInitialized)  // we may exit while still initializing the sensors
            return                                  // in this case, we should not stop them
        pickDateButton.text = getString(R.string.pick_date)
        Log.i("NoiseMapper", "Exiting sensing state")
        stopSensing()
        stopUpdate()
    }

    // just recreate all the sensors and start sensing again
    // this is mainly due to reschedule all timers with the new period
    fun onBatteryStatusUpdate(){
        exitSensingState()
        enterSensingState()
    }


    // disable the sensors and reset the text value of the noise indicator to the default
    private fun stopSensing() {
        noise_microphone.stopListening()
        ble_scanner.stopScanning()
        proximitySensorHandler.stopListening()
        // reset the text value of the noise indicator
        val sound: TextView = findViewById(R.id.db_level)
        sound.text = getString(R.string.db_level_no_sensing)
        sound.setTextColor(findViewById<TextView>(R.id.current_room).currentTextColor)

    }

    // stop the timer that updates the map
    private fun stopUpdate() {
        updateMapTimer.cancel()
    }

    override fun onStop() {
        super.onStop()
        if (inSensingState()) { // we should stop sensing when the app is not in the foreground
            powerGovernor.unregister()
            switchCompat.isChecked = false
        }

    }
}

