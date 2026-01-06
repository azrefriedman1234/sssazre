package com.pasiflonet.mobile.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.pasiflonet.mobile.R

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // זמני: מסך ריק כדי לא לשבור. אפשר להרחיב אחרי שהכל ירוק.
        setContentView(R.layout.activity_main)
        title = "הגדרות (V3)"
    }
}
