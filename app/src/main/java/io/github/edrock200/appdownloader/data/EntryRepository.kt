package io.github.edrock200.appdownloader.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Stores the saved entries as a JSON file in the app's private storage.
 *
 * Writes are atomic (temporary file, sync, rename) and the previous version is kept as
 * `entries.json.bak`. A damaged file is never silently replaced: unreadable entries are skipped,
 * an unreadable file falls back to the backup copy, and the bad file is kept aside. A failed save
 * (e.g. storage full) keeps the list in memory and is retried on the next change.
 */
class EntryRepository(private val file: File) {

    constructor(context: Context) : this(File(context.filesDir, "entries.json"))

    private val backup = File(file.parentFile, file.name + ".bak")
    private val mutex = Mutex()
    private val serializer = ListSerializer(AppEntry.serializer())

    private val _problem = MutableStateFlow<String?>(null)
    /** A one-off message about loading or saving problems, for the UI to show (then [clearProblem]). */
    val problem: StateFlow<String?> = _problem.asStateFlow()
    fun clearProblem() {
        _problem.value = null
    }

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<AppEntry>> = _entries.asStateFlow()

    private var unsaved = false

    fun get(id: String): AppEntry? = _entries.value.firstOrNull { it.id == id }

    suspend fun add(entry: AppEntry) = mutate { it + entry }

    suspend fun update(id: String, transform: (AppEntry) -> AppEntry) =
        mutate { list -> list.map { if (it.id == id) transform(it) else it } }

    suspend fun delete(id: String) = mutate { list -> list.filterNot { it.id == id } }

    /** Replaces the whole list (used when restoring a backup). */
    suspend fun replaceAll(entries: List<AppEntry>) = mutate { entries }

    private suspend fun mutate(block: (List<AppEntry>) -> List<AppEntry>) = mutex.withLock {
        val current = _entries.value
        val updated = block(current)
        if (updated == current && !unsaved) return@withLock // Nothing changed: skip the write.
        _entries.value = updated
        // Finish the write even if the caller is cancelled, so memory and disk stay in step.
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                save(updated)
                if (unsaved) {
                    unsaved = false
                    if (_problem.value == SAVE_FAILED) _problem.value = null
                }
            } catch (e: IOException) {
                unsaved = true
                _problem.value = SAVE_FAILED
            }
        }
    }

    private fun load(): List<AppEntry> {
        if (!file.exists()) return if (backup.exists()) loadBackupOrEmpty("Your app list was restored from its backup copy.") else emptyList()
        val text = runCatching { file.readText() }.getOrNull()
        val parsed = text?.let { decode(it) }
        return when {
            parsed == null -> {
                keepAside(file)
                loadBackupOrEmpty("Your saved app list was damaged. It was set aside and its backup copy was used.")
            }
            parsed.skipped > 0 -> {
                _problem.value = "${parsed.skipped} saved app${if (parsed.skipped == 1) "" else "s"} couldn't be read and " +
                    "${if (parsed.skipped == 1) "was" else "were"} left out. The original file was kept."
                file.copyTo(File(file.parentFile, "entries.partial-${System.currentTimeMillis()}.json"), overwrite = true)
                parsed.entries
            }
            else -> parsed.entries
        }
    }

    private fun loadBackupOrEmpty(message: String): List<AppEntry> {
        val fromBackup = runCatching { backup.readText() }.getOrNull()?.let { decode(it) }
        return if (fromBackup != null) {
            _problem.value = message
            fromBackup.entries
        } else {
            if (file.exists() || backup.exists()) {
                _problem.value = "Your saved app list couldn't be read. The damaged file was kept so nothing is lost."
            }
            emptyList()
        }
    }

    private fun keepAside(bad: File) {
        runCatching { bad.copyTo(File(bad.parentFile, "entries.corrupt-${System.currentTimeMillis()}.json"), overwrite = true) }
    }

    private class Decoded(val entries: List<AppEntry>, val skipped: Int)

    /** Decodes entry by entry, so one unreadable entry doesn't lose the rest. Null if the file isn't a list at all. */
    private fun decode(text: String): Decoded? {
        val array = runCatching { JSON.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return null
        var skipped = 0
        val entries = array.mapNotNull { element ->
            runCatching { JSON.decodeFromJsonElement(AppEntry.serializer(), element) }.getOrNull().also { if (it == null) skipped++ }
        }
        return Decoded(entries, skipped)
    }

    private fun save(list: List<AppEntry>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(JSON.encodeToString(serializer, list).toByteArray())
            out.fd.sync()
        }
        if (file.exists()) {
            backup.delete()
            if (!file.renameTo(backup)) file.copyTo(backup, overwrite = true)
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Could not replace ${file.name}")
        }
    }

    companion object {
        const val SAVE_FAILED = "Couldn't save your app list (storage may be full). Free up some space; changes are kept until then."

        val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            coerceInputValues = true
        }
    }
}
