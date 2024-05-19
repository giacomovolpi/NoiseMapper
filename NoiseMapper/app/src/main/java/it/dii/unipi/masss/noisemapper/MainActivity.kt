package it.dii.unipi.masss.noisemapper

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity(){
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val editText = findViewById<EditText>(R.id.editText)
        findViewById<View>(R.id.button_noise_map).setOnClickListener {
            // get the editText text field and check if it is empty
            var text_server : String = editText.text.toString()
            // regex to check if the text is a valid IP address
            val ipPattern = Regex(
                "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}" +
                        "(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
            )
            if (text_server.isEmpty() || !ipPattern.matches(text_server)) {
                Log.i("MainActivity", "Empty text field")
                // make a toast to notify the user
                Toast.makeText(this, "Please insert a valid IP address", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            else{
                val http = "http://"
                text_server = http.plus(text_server).plus(":5002")
                Log.i("MainActivity", "Text field not empty $text_server")
                Log.i("MainActivity", "Starting NoiseActivity")
                // start the NoiseActivity and addextra the text val
                val intent = Intent(this, NoiseActivity::class.java)
                intent.putExtra("serverURL", text_server.toString())
                startActivity(intent)
            }
        }
    }
}
