package app.glyph

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore.Audio.Media as M
import androidx.documentfile.provider.DocumentFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File

object Library {
    fun scan(c: Context, folderUri: Uri? = null): List<Track> {
        val out = mutableListOf<Track>()
        val folderPath = folderUri?.let { getPathFromUri(it) }

        val selection = if (folderPath != null) {
            "${M.IS_MUSIC}!=0 AND ${M.DATA} LIKE ?"
        } else {
            "${M.IS_MUSIC}!=0"
        }
        val selectionArgs = if (folderPath != null) arrayOf("$folderPath%") else null

        c.contentResolver.query(
            M.EXTERNAL_CONTENT_URI,
            arrayOf(M._ID, M.DATA, M.TITLE, M.ARTIST, M.ALBUM, M.DURATION),
            selection,
            selectionArgs,
            "${M.TITLE} COLLATE NOCASE"
        )?.use { q ->
            while (q.moveToNext()) {
                out += Track(
                    q.getLong(0),
                    q.getString(1) ?: "",
                    q.getString(2) ?: "",
                    q.getString(3) ?: "",
                    q.getString(4) ?: "",
                    (q.getLong(5) / 1000).toInt()
                )
            }
        }

        // If MediaStore didn't return any tracks for this specific folder,
        // fallback to reading audio files directly via SAF DocumentTree
        if (out.isEmpty() && folderUri != null) {
            val root = DocumentFile.fromTreeUri(c, folderUri)
            if (root != null) scanDocDir(root, out)
        }

        return out
    }

    private fun scanDocDir(dir: DocumentFile, out: MutableList<Track>) {
        val audioExts = setOf("mp3", "flac", "m4a", "ogg", "opus", "wav")
        for (f in dir.listFiles()) {
            if (f.isDirectory) {
                scanDocDir(f, out)
            } else if (f.isFile) {
                val name = f.name ?: continue
                val ext = name.substringAfterLast(".", "").lowercase()
                if (ext in audioExts) {
                    val title = name.substringBeforeLast(".")
                    out += Track(
                        id = f.uri.hashCode().toLong(),
                        path = f.uri.toString(),
                        title = title,
                        artist = "Unknown",
                        album = "",
                        durSec = 0
                    )
                }
            }
        }
    }

    fun isrc(path: String): String? = runCatching {
        if (path.startsWith("content://")) return null
        AudioFileIO.read(File(path)).tag?.getFirst(FieldKey.ISRC)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Checks if audio file already has embedded lyrics in its metadata. */
    fun checkExisting(c: Context, t: Track): St? = runCatching {
        val lyr = getLyrics(c, t) ?: return null
        if (lyr.isBlank()) return null
        val isSoulSleep = lyr.contains("[by:Soul Sleep")
        val src = if (isSoulSleep) "Soul Sleep" else "embedded"
        if (Regex("<\\d{1,2}:\\d{2}").containsMatchIn(lyr)) {
            St.Done(Grain.WORD, src)
        } else if (Regex("\\[\\d{1,2}:\\d{2}").containsMatchIn(lyr)) {
            St.Done(Grain.LINE, src)
        } else if (isSoulSleep) {
            St.Done(Grain.LINE, src)
        } else {
            null
        }
    }.getOrNull()

    private fun getLyrics(c: Context, t: Track): String? = runCatching {
        if (!t.path.startsWith("content://")) {
            val file = File(t.path)
            if (!file.exists() || !file.canRead()) return null
            AudioFileIO.read(file).tag?.getFirst(FieldKey.LYRICS)
        } else {
            val uri = Uri.parse(t.path)
            val ext = t.title.substringAfterLast(".", "mp3")
            val temp = File(c.cacheDir, "read_check_${System.currentTimeMillis()}.$ext")
            try {
                c.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                } ?: return null
                AudioFileIO.read(temp).tag?.getFirst(FieldKey.LYRICS)
            } finally {
                if (temp.exists()) temp.delete()
            }
        }
    }.getOrNull()

    /** Embeds strictly into the audio metadata tag (FieldKey.LYRICS). No .lrc file is created. */
    fun write(c: Context, t: Track, f: Found, folderUri: Uri?) {
        val textToSave = if (f.text.contains("[by:Soul Sleep")) {
            f.text
        } else {
            "[by:Soul Sleep (${f.source})]\n${f.text}"
        }

        if (!t.path.startsWith("content://")) {
            val file = File(t.path)

            // 1. Direct write if the file is directly writable
            if (file.exists() && file.canWrite()) {
                try {
                    val af = AudioFileIO.read(file)
                    af.tagOrCreateAndSetDefault.apply { setField(FieldKey.LYRICS, textToSave) }
                    af.commit()
                    return
                } catch (_: Exception) {
                    // Fallback to SAF below
                }
            }

            // 2. SAF folder write
            val treeUri = folderUri ?: error("Specific folder permission required to write metadata")
            val docFile = findDocumentFile(c, treeUri, file)
                ?: error("Could not locate ${file.name} in selected folder")

            writeViaSafStream(c, docFile.uri, file.name, textToSave)
        } else {
            val trackUri = Uri.parse(t.path)
            writeViaSafStream(c, trackUri, "${t.title}.audio", textToSave)
        }
    }

    private fun writeViaSafStream(c: Context, uri: Uri, fileName: String, lyricsText: String) {
        val ext = fileName.substringAfterLast(".", "mp3")
        val tempFile = File(c.cacheDir, "meta_${System.currentTimeMillis()}.$ext")
        try {
            c.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Failed to read audio file stream")

            val af = AudioFileIO.read(tempFile)
            af.tagOrCreateAndSetDefault.apply { setField(FieldKey.LYRICS, lyricsText) }
            af.commit()

            c.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                tempFile.inputStream().use { input -> input.copyTo(output) }
            } ?: error("Failed to write metadata stream")
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    fun getFolderName(c: Context, uri: Uri): String {
        return DocumentFile.fromTreeUri(c, uri)?.name
            ?: getPathFromUri(uri)?.substringAfterLast("/")
            ?: "Selected Folder"
    }

    fun getPathFromUri(uri: Uri): String? = runCatching {
        val docId = DocumentsContract.getTreeDocumentId(uri) ?: return null
        if (docId.startsWith("primary:")) {
            val rel = docId.removePrefix("primary:")
            val base = Environment.getExternalStorageDirectory().absolutePath
            if (rel.isEmpty()) base else "$base/$rel"
        } else {
            null
        }
    }.getOrNull()

    fun findDocumentFile(c: Context, treeUri: Uri, file: File): DocumentFile? {
        val tree = DocumentFile.fromTreeUri(c, treeUri) ?: return null

        val direct = tree.findFile(file.name)
        if (direct != null) return direct

        val folderPath = getPathFromUri(treeUri)
        if (folderPath != null && file.absolutePath.startsWith(folderPath)) {
            val rel = file.absolutePath.removePrefix(folderPath).trim('/', '\\')
            val parts = rel.split('/', '\\').filter { it.isNotEmpty() }
            var current: DocumentFile = tree
            for (part in parts) {
                current = current.findFile(part) ?: break
            }
            if (current.isFile && current.name == file.name) return current
        }

        return searchRecursive(tree, file.name)
    }

    private fun searchRecursive(dir: DocumentFile, targetName: String): DocumentFile? {
        for (f in dir.listFiles()) {
            if (f.isFile && f.name == targetName) return f
            if (f.isDirectory) {
                val found = searchRecursive(f, targetName)
                if (found != null) return found
            }
        }
        return null
    }
}
