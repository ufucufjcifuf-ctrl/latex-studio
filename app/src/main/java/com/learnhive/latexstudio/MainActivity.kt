package com.learnhive.latexstudio

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            text = "Learn Hive Mobile LaTeX Studio\n\nEngine Architecture: ARM64-v8a\nStatus: Initialized Successfully!"
            textSize = 20f
            setPadding(50, 150, 50, 50)
        }
        setContentView(tv)
    }
}
