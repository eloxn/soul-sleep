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

enum class Filter { ALL, WORD, LINE, NONE, RETRY, ADD }

class Vm(app: Application) : AndroidViewModel(app) {
    val tracks = MutableStateFlow<List<Track>>(emptyList())
    val status = MutableStateFlow<Map<Long, St>>(emptyMap())
    val rest = MutableStateFlow(0)      // seconds left in the cooldown
    val served = MutableStateFlow(0)    // requests completed this session
    val folderUri = MutableStateFlow<Uri?>(null)
    val folderName = MutableStateFlow<String?>(null)
    val filter = MutableStateFlow(Filter.ALL)
    val isScanning = MutableStateFlow(false)

    val counts: StateFlow<Map<Filter, Int>> = combine(tracks, status) { tr, st ->
        var word = 0; var line = 0; var none = 0; var retry = 0; var add = 0
        for (t in tr) {
            when (val s = st[t.id] ?: St.Idle) {
                is St.Done -> if (s.g == Grain.WORD) word++ else line++
                St.Miss -> none++
                St.Fail -> retry++
                else -> add++
            }
        }
        mapOf(
            Filter.ALL to tr.size,
            Filter.WORD to word,
            Filter.LINE to line,
            Filter.NONE to none,
            Filter.RETRY to retry,
            Filter.ADD to add
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val filteredTracks: StateFlow<List<Track>> = combine(tracks, status, filter) { tr, st, f ->
        tr.filter { matchesFilter(it, f, st) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    fun setFilter(f: Filter) {
        filter.value = f
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

    fun rescan() {
        if (isScanning.value) return
        viewModelScope.launch(Dispatchers.IO) {
            isScanning.value = true
            try {
                val list = Library.scan(getApplication(), folderUri.value)
                tracks.value = list
                // Check existing embedded lyrics in metadata
                val existing = mutableMapOf<Long, St>()
                for (t in list) {
                    val s = Library.checkExisting(getApplication(), t)
                    if (s != null) existing[t.id] = s
                }
                status.value = existing
            } finally {
                isScanning.value = false
            }
        }
    }

    fun load() {
        rescan()
    }

    private fun put(id: Long, s: St) = status.update { it + (id to s) }

    fun add(t: Track, force: Boolean = false) {
        if (status.value[t.id] is St.Work) return
        if (!force && status.value[t.id] is St.Done) return
        if (folderUri.value == null) {
            requestPickFolder()
            return
        }
        put(t.id, St.Work)
        viewModelScope.launch {
            gate.withLock {
                // If not forced, check if the audio file already has lyrics on storage
                if (!force) {
                    val already = withContext(Dispatchers.IO) {
                        Library.checkExisting(getApplication(), t)
                    }
                    if (already != null) {
                        put(t.id, already)
                        return@withLock
                    }
                }

                val r = withContext(Dispatchers.IO) {
                    runCatching {
                        Sources.find(t)?.also { Library.write(getApplication(), t, it, folderUri.value) }
                    }
                }
                r.onFailure {
                    android.util.Log.e("SoulSleep", "Failed to fetch/write for ${t.title} (${t.artist}): ${it.message}", it)
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

    /** Specifically searches for word-by-word lyrics in the API to upgrade a track from line to word sync. */
    fun changeToWords(t: Track) {
        if (status.value[t.id] is St.Work) return
        if (folderUri.value == null) {
            requestPickFolder()
            return
        }
        val prev = status.value[t.id] ?: St.Idle
        put(t.id, St.Work)
        viewModelScope.launch {
            gate.withLock {
                val r = withContext(Dispatchers.IO) {
                    runCatching {
                        Sources.findWord(t)?.also { found ->
                            Library.write(getApplication(), t, found, folderUri.value)
                        }
                    }
                }
                r.onFailure {
                    android.util.Log.e("SoulSleep", "Failed to search word lyrics for ${t.title}: ${it.message}", it)
                }
                val found = r.getOrNull()
                if (found != null) {
                    put(t.id, St.Done(Grain.WORD, found.source))
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(getApplication(), "Found word lyrics for ${t.title}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // Restore previous line-sync status so existing lyrics are never lost
                    put(t.id, prev)
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(getApplication(), "No word-by-word lyrics found for ${t.title}. Line lyrics kept.", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                served.update { it + 1 }
                if (served.value % 50 == 0) {
                    for (s in 60 downTo 1) { rest.value = s; delay(1000) }
                    rest.value = 0
                }
            }
        }
    }

    /** Batch search for word-by-word lyrics for all songs currently having LINE sync. */
    fun upgradeAllLineToWords() {
        if (folderUri.value == null) {
            requestPickFolder()
            return
        }
        val lineTracks = tracks.value.filter {
            val s = status.value[it.id]
            s is St.Done && s.g == Grain.LINE
        }
        if (lineTracks.isEmpty()) {
            android.widget.Toast.makeText(getApplication(), "No LINE tracks to upgrade", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        android.widget.Toast.makeText(getApplication(), "Searching word lyrics for ${lineTracks.size} track(s)...", android.widget.Toast.LENGTH_SHORT).show()

        viewModelScope.launch {
            var upgraded = 0
            for (t in lineTracks) {
                if (status.value[t.id] is St.Work) continue
                val prev = status.value[t.id] ?: St.Idle
                put(t.id, St.Work)
                gate.withLock {
                    val r = withContext(Dispatchers.IO) {
                        runCatching {
                            Sources.findWord(t)?.also { found ->
                                Library.write(getApplication(), t, found, folderUri.value)
                            }
                        }
                    }
                    val found = r.getOrNull()
                    if (found != null) {
                        put(t.id, St.Done(Grain.WORD, found.source))
                        upgraded++
                    } else {
                        put(t.id, prev)
                    }
                    served.update { it + 1 }
                    if (served.value % 50 == 0) {
                        for (s in 60 downTo 1) { rest.value = s; delay(1000) }
                        rest.value = 0
                    }
                }
            }
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    getApplication(),
                    if (upgraded > 0) "Upgraded $upgraded track(s) to word lyrics!" else "No word lyrics found for LINE tracks",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun addAll() {
        if (folderUri.value == null) {
            requestPickFolder()
            return
        }
        val currentFilter = filter.value
        if (currentFilter == Filter.LINE) {
            upgradeAllLineToWords()
            return
        }
        val targets = tracks.value.filter { matchesFilter(it, currentFilter, status.value) }
        val toProcess = if (currentFilter == Filter.ALL || currentFilter == Filter.ADD) {
            targets.filter { status.value[it.id] !is St.Done }
        } else {
            targets.filter { status.value[it.id] !is St.Work }
        }
        toProcess.forEach { add(it, force = (currentFilter == Filter.RETRY || currentFilter == Filter.NONE)) }
    }

    companion object {
        fun matchesFilter(t: Track, f: Filter, st: Map<Long, St>): Boolean {
            val s = st[t.id] ?: St.Idle
            return when (f) {
                Filter.ALL -> true
                Filter.WORD -> s is St.Done && s.g == Grain.WORD
                Filter.LINE -> s is St.Done && s.g == Grain.LINE
                Filter.NONE -> s is St.Miss
                Filter.RETRY -> s is St.Fail
                Filter.ADD -> s is St.Idle || s is St.Work
            }
        }
    }
}
