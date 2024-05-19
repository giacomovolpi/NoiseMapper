package it.dii.unipi.masss.noisemapper

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import okhttp3.internal.notify
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

//class that performs the config requests to the server and retrieves the array of tuples (room, noise) sending the time interval
class NoiseMapIO(private val context: Context, private val url : String = "") {
    private val CONNECTION_TIMEOUT = context.resources.getInteger(R.integer.TIMEOUT_CONNECTION) // must be less than 5 seconds, or else Android system will kill the app

    // perform the get request to download the config file from the server
    fun retrieveFileFromServer(
            callback: FileDownloadCallback,
            fileToSave: String,
            lock: Any
        ) {
            Thread {
                synchronized(lock) {
                    try {
                        val connection = URL(url+"/resources/"+context.getString(R.string.config_file_name)).openConnection() as HttpURLConnection
                        connection.connectTimeout = CONNECTION_TIMEOUT
                        connection.readTimeout = CONNECTION_TIMEOUT
                        connection.connect()
                        val inputStream = BufferedInputStream(connection.inputStream)
                        val file = File(context.filesDir, fileToSave)
                        println("files_downaload $file")
                        val outputStream = FileOutputStream(file)
                        inputStream.use { input ->
                            outputStream.use { output ->
                                input.copyTo(output)
                            }
                        }
                        callback.onFileDownloaded(file.absolutePath)
                        inputStream.close()
                        outputStream.close()

                    } catch (e: Exception) {
                        e.printStackTrace()
                        callback.onFileDownloadError("Error: ${e.message}")
                    }
                    lock.notify() // release the lock after downloading or after an error
                }
            }.start()
        }

        // get measurement list from server for samples inside the time interval [start_from, end_to]
        fun performGetRequest(start_from: Long, end_to: Long, auxilary: MutableList<Map<String, Any>>): Map<String, Double> {
            var roomNoise = mapOf<String, Double>();
            val lock = Object()
            Thread {
                synchronized(lock) {
                    try {
                        val url = URL("$url/measurements?start_from=$start_from&end_to=$end_to")
                        val connection = url.openConnection() as HttpURLConnection
                        connection.requestMethod = "GET"
                        connection.connectTimeout = CONNECTION_TIMEOUT
                        connection.readTimeout = CONNECTION_TIMEOUT
                        val responseCode = connection.responseCode

                        if (responseCode == HttpURLConnection.HTTP_OK) {
                            val gson = Gson()
                            val response = gson.fromJson(
                                connection.inputStream.bufferedReader().readText(),
                                Map::class.java
                            )["data"]
                            response as MutableList<Map<String, Any>>
                            response.addAll(auxilary)

                            try{
                                val alpha = 0.9

                                roomNoise = response.filter{ it["noise"] != null }          // discard all invalid samples
                                    .groupBy { it["room"] }                                 // graph plotting requires room name as key
                                    .mapValues { (_, samples) ->                            // for each room, average the noise levels
                                        samples.map { it["noise"]!! as Double }
                                        .reduce({ acc, d -> alpha * d + (1 - alpha) * acc })// exponential moving average via exponential smoothing
                                } as Map<String, Double> // we get a map of room -> average noise level

                            } catch (e: Exception){
                                Log.e("PollingRequest", "noise map averaging failed with exception: $e")
                            }
                            Log.i(
                                "PollingRequest",
                                "GET request successful with response: $roomNoise"
                            )
                        } else {
                            Log.e(
                                "PollingRequest",
                                "GET request failed with response code: $responseCode"
                            )
                        }
                        connection.disconnect()
                    } catch (e: Exception) {
                        Log.e("PollingRequest", "GET request failed with exception: $e")
                    }
                    lock.notify() // i release the lock after modifying roomNoise to avoid race condition
                }
            }.start()

            synchronized(lock) {
                lock.wait() // waits for the thread to finish
            }

            return roomNoise;
        }

}
