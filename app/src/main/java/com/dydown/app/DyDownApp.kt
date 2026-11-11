package com.dydown.app

import android.app.Application
import com.dydown.app.data.Prefs
import com.dydown.app.download.Notifier
import com.dydown.app.ytdlp.YtDlp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DyDownApp : Application() {

    lateinit var prefs: Prefs
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        Notifier.createChannels(this)

        // python 运行时解压比较慢，放后台线程，别卡启动
        appScope.launch {
            runCatching {
                YtDlp.init(this@DyDownApp)
                prefs.ytDlpVersion = YtDlp.version(this@DyDownApp)
            }
        }
    }

    companion object {
        lateinit var instance: DyDownApp
            private set
    }
}
