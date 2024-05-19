package it.dii.unipi.masss.noisemapper

import android.content.Context
import android.media.MediaRecorder
import android.widget.TextView
import it.dii.unipi.masss.noisemapper.NoiseActivity.RecorderTask
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Timer

// class that is responsible for the microphone recording
class NoiseMicrophone(
    context: Context,
    cacheDir: File,
    private val sound: TextView,
    private val recorderTask: RecorderTask,
    private val isPowerSaveMode: Boolean,
){
    private val AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_PERFORMANCE
    private val OUTPUT_FORMAT_AUDIO = MediaRecorder.OutputFormat.MPEG_4
    private val AUDIO_ENCODER = MediaRecorder.AudioEncoder.AAC
    private val AUDIO_ENCODING_BIT_RATE = context.resources.getInteger(R.integer.AUDIO_ENCODING_BIT_RATE)
    private val AUDIO_SAMPLING_RATE = context.resources.getInteger(R.integer.AUDIO_SAMPLING_RATE)
    private val FAST_REFRESH_RATE = context.resources.getInteger(R.integer.FAST_AUDIO_REFRESH_RATE)
    private val SLOW_REFRESH_RATE = context.resources.getInteger(R.integer.SLOW_AUDIO_REFRESH_RATE)
    private val mRecorder: MediaRecorder
    private var timer: Timer? = null
    private var hasRecorderStarted : Boolean = false
    init {
        mRecorder = MediaRecorder()
        mRecorder.setAudioSource(AUDIO_SOURCE)
        mRecorder.setOutputFormat(OUTPUT_FORMAT_AUDIO)
        mRecorder.setAudioEncoder(AUDIO_ENCODER)
        mRecorder.setAudioEncodingBitRate(AUDIO_ENCODING_BIT_RATE);
        mRecorder.setAudioSamplingRate(AUDIO_SAMPLING_RATE);
        mRecorder.setOutputFile(FileOutputStream(File(cacheDir, "audio.mp3"), false).fd)

    }
    fun startListening() { // this method prepare the media recorder and start the timer for noise sampling
        try {
            mRecorder.prepare()
            mRecorder.start()
            hasRecorderStarted = true
        } catch (e: IllegalStateException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        if (timer == null) {
            // If timer is not initialized, create and start it
            timer = Timer()
            recorderTask.setup(mRecorder, sound)
            timer?.schedule(recorderTask, 0, (if (isPowerSaveMode) SLOW_REFRESH_RATE else FAST_REFRESH_RATE).toLong())
        }
    }

    fun stopListening() { // this method stops the timer and the media recorder
        timer?.cancel()
        timer = null
        try {
            if (hasRecorderStarted) {
                mRecorder.stop()
                mRecorder.release()
                hasRecorderStarted = false
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

}
