package com.pasiflonet.mobile.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.pasiflonet.mobile.R
import com.pasiflonet.mobile.util.TempCleaner

class MainActivity : AppCompatActivity() {

    private lateinit var etTargetChannel: TextInputEditText
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val target = etTargetChannel.text?.toString()?.trim().orEmpty()
            val i = Intent(this, DetailsActivity::class.java)
                .putExtra(DetailsActivity.EXTRA_TARGET_CHANNEL, target)
                .putExtra(DetailsActivity.EXTRA_INPUT_URI, uri.toString())
            startActivity(i)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<MaterialToolbar>(R.id.toolbar).setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
                R.id.menu_exit -> { finishAffinity(); true }
                else -> false
            }
        }

        etTargetChannel = findViewById(R.id.etTargetChannel)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)

        findViewById<MaterialButton>(R.id.btnPickVideo).setOnClickListener {
            pickVideo.launch("video/*")
        }

        findViewById<MaterialButton>(R.id.btnClearTemp).setOnClickListener {
            val (count, bytes) = TempCleaner.clearPasiflonetTmp(this)
            tvLog.text = "נוקו $count פריטים (${bytes/1024}KB) מתוך cacheDir/pasiflonet_tmp"
        }

        tvStatus.text = "סטטוס: בחר וידאו כדי להתחיל"
    }
}
