package com.pasiflonet.mobile

import android.app.Application
import androidx.work.Configuration
import com.pasiflonet.mobile.td.TdLibManager
import org.drinkless.tdlib.TdApi

class PasiflonetApp : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        TdLibManager.init(this)

        // קריטי: ליצור client כבר כאן כדי לקבל authState מיד בכניסה השנייה
        TdLibManager.ensureClient()

        // “ניעור” מצב הרשאה כדי שיזרום authState מהר
        try {
            TdLibManager.send(TdApi.GetAuthorizationState()) { }
        } catch (_: Throwable) { }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
