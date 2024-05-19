package it.dii.unipi.masss.noisemapper

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.BufferedReader
import java.io.InputStreamReader

interface FileDownloadCallback {
    fun onFileDownloaded(filePath: String)
    fun onFileDownloadError(errorMessage: String)
}

class ConfigData(val mapping : Map<String, String>, val layout : Map<String, List<Any>>)

class BLEConfig(private val context: Context, offline: Boolean = false, serverUrl : String) {
    private val successfulConfig: Boolean
    lateinit var beaconRoomMap : ConfigData
    val url = serverUrl
    var lock = Object()

    // immediately try to contact the server to get the config file
    init {
        Log.i("NoiseMapper", "Url for BLE config is $url")
        if (!offline) {
                GetConfigFileFromServer(url)
                synchronized(lock) {
                    lock.wait()
                }
        }
        successfulConfig = readJSONfile()
    }

    // read the JSON file from the internal storage
    fun readFromFile(context: Context, fileName: String): String {
        val stringBuilder = StringBuilder()
        try {
            val fileInputStream = context.openFileInput(fileName)
            val inputStreamReader = InputStreamReader(fileInputStream)
            val bufferedReader = BufferedReader(inputStreamReader)
            var text: String? = bufferedReader.readLine()
            while (text != null) {
                stringBuilder.append(text).append("\n")
                text = bufferedReader.readLine()
            }
            fileInputStream.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return stringBuilder.toString()
    }

    fun readJSONfile(): Boolean {
        try {
            val gson = Gson()
            val jsonString = readFromFile(context, context.getString(R.string.config_file_name))
            beaconRoomMap = gson.fromJson(jsonString, ConfigData::class.java)
        }
        catch (e: Exception) { // if the file is not found or the JSON is not correctly formatted
            Log.i("NoiseMapper", "Error reading JSON config file")
            e.printStackTrace()
            return false
        }
        return true
    }

    // use the noiseMapIO class to download the config file from the server
    fun GetConfigFileFromServer(url: String) {
        val callback = object : FileDownloadCallback {
            override fun onFileDownloaded(filePath: String) {
                Log.d("NoiseMapper","Config file correctly downloaded")
            }

            override fun onFileDownloadError(errorMessage: String) {
                Log.e("NoiseMapper", "Error on downloading BLE config $errorMessage")
            }
        }
        val noise_map_io = NoiseMapIO(context, url);
        noise_map_io.retrieveFileFromServer(callback , fileToSave="config.json",lock)
    }



    fun gotConfig(): Boolean {
        return successfulConfig // can be true either if the config was correctly downloaded or if a local copy is available
    }
}