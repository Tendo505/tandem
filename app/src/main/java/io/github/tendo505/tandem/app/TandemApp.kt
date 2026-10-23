package io.github.tendo505.tandem.app

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import io.github.tendo505.tandem.app.data.TandemDb
import io.github.tendo505.tandem.app.data.TandemRepository
import io.github.tendo505.tandem.app.data.randomId
import io.github.tendo505.tandem.app.sync.RetrofitSyncApi
import io.github.tendo505.tandem.app.sync.SyncApi
import io.github.tendo505.tandem.app.sync.SyncEngine
import io.github.tendo505.tandem.app.sync.SyncWorker
import io.github.tendo505.tandem.core.Hlc
import io.github.tendo505.tandem.core.HlcClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TandemApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("tandem", Context.MODE_PRIVATE)

    /** Identifies this phone inside timestamps. Random, so it says nothing about the person. */
    val nodeId: String = prefs.getString(KEY_NODE, null) ?: ("p" + randomId(10)).also { prefs.edit { putString(KEY_NODE, it) } }

    private val _serverUrl = MutableStateFlow(prefs.getString(KEY_SERVER, null) ?: BuildConfig.DEFAULT_SERVER_URL)
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    fun setServerUrl(url: String) {
        val clean = url.trim().trimEnd('/')
        prefs.edit { putString(KEY_SERVER, clean) }
        _serverUrl.value = clean
    }

    companion object {
        private const val KEY_NODE = "node_id"
        private const val KEY_SERVER = "server_url"
        val URL = Regex("^https?://[^\\s/?#]+(/\\S*)?$")
    }
}

class AppContainer(private val context: Context) {
    val settings = Settings(context)
    private val db = TandemDb.create(context)
    private val clock = HlcClock(settings.nodeId)
    val repo = TandemRepository(db.dao(), clock, onLocalChange = { SyncWorker.enqueue(context) })
    val sync = SyncEngine(db.dao(), clock, api = ::currentApi)

    private var api: Pair<String, SyncApi>? = null

    @Synchronized
    private fun currentApi(): SyncApi? {
        val url = settings.serverUrl.value.takeIf { Settings.URL.matches(it) } ?: return null
        api?.takeIf { it.first == url }?.let { return it.second }
        return RetrofitSyncApi(url).also { api = url to it }
    }

    fun start() {
        // If the phone's clock was set back since the last edit, keep issuing later timestamps anyway.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            db.dao().maxHlc()?.let { clock.observe(Hlc.parse(it)) }
        }
        SyncWorker.schedulePeriodic(context)
    }
}
