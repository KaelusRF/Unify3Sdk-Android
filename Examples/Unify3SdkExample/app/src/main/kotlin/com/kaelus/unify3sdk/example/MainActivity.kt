package com.kaelus.unify3sdk.example

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.openReturnLossExampleButton).setOnClickListener {
            startActivity(Intent(this, ReturnLossTestActivity::class.java))
        }

        findViewById<Button>(R.id.openCalibrationExampleButton).setOnClickListener {
            startActivity(Intent(this, OslCalibrationActivity::class.java))
        }
    }
}