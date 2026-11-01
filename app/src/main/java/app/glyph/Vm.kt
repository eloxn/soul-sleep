package app.glyph

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface St {
    object Idle : St; object Work : St; object Miss : St; object Fail : St
    data class Done(val g: Grain, val src: String) : St
}

class Vm(app: Application) : AndroidViewModel(app) {
    val tracks = MutableStateFlow<List<Track>>(emptyList())
    val status = MutableStateFlow<Map<Long, St>>(emptyMap())
    val rest = MutableStateFlow(0)      // seconds left in the cooldown
    val served = MutableStateFlow(0)    // requests completed this session
    val folderUri = MutableStateFlow<Uri?>(null)
    val folderName = MutableStateFlow<String?>(null)

    private val prefs = app.getSharedPreferences("soul_sleep_prefs", Context.MODE_PRIVATE)
    private val gate = Mutex()          // FIFO: strictly one request at a time

    var onPickFolder: (() -> Unit)? = null

    init {
        val saved = prefs.getString("folder_tree_uri", null)
        if (saved != null) {
            val uri = Uri.parse(saved)
            val hasPerm = app.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isWritePermission
            }
            if (hasPerm) {
                folderUri.value = uri
                folderName.value = Library.getFolderName(app, uri)
            }
        }
    }

    fun requestPickFolder() {
        onPickFolder?.invoke()
    }

    fun setFolder(uri: Uri) {
        folderUri.value = uri
        folderName.value = Library.getFolderName(getApplication(), uri)
        prefs.edit().putString("folder_tree_uri", uri.toString()).apply()
        load()
    }

    fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            tracks.value = Library.scan(getApplication(), folderUri.value)
        }
    }

    private fun put(id: Long, s: St) = status.update { it + (id to s) }

    fun add(t: Track) {
        if (status.value[t.id] is St.Work) return
        if (folderUri.value == null) {
            requestPickFolder()
            return
        }
        put(t.id, St.Work)
        viewModelScope.launch {
            gate.withLock {
                val r = withContext(Dispatchers.IO) {
                    runCatching {
                        Sources.find(t)?.also { Library.write(getApplication(), t, it, folderUri.value) }
                    }
                }
                put(t.id, r.fold({ f -> if (f == null) St.Miss else St.Done(f.grain, f.source) }, { St.Fail }))
                served.update { it + 1 }
                if (served.value % 50 == 0) {           // 1 minute rest after every 50 requests
                    for (s in 60 downTo 1) { rest.value = s; delay(1000) }
                    rest.value = 0
                }
            }
        }
    }

    fun addAll() {
        if (folderUri.value == null) {
            requestPickFolder()
            return
        }
        tracks.value.filter { status.value[it.id] !is St.Done }.forEach { add(it) }
    }
}
