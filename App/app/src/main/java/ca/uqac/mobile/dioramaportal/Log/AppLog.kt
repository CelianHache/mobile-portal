package ca.uqac.mobile.dioramaportal.Log

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Journal de l'app :
 * - [all] : tous les messages depuis le lancement (console, onglet Session) ;
 * - [history] : le contenu du fichier avant ce lancement (console, onglet Historique) ;
 * - [call] : seulement ceux de l'appel en cours (page vidéo), vidé à chaque nouvel appel ;
 * - fichier `app.log`, dans le dossier de l'app accessible en USB :
 *   Android/data/ca.uqac.mobile.dioramaportal/files/app.log
 */
object AppLog {

    data class Entry(val time: Long, val message: String) {
        /** Affichage court (onglet Session). */
        val line: String get() = "${format("HH:mm:ss", time)}  $message"
        /** Format du fichier, avec la date (onglet Historique). */
        val fileLine: String get() = "${format("yyyy-MM-dd HH:mm:ss.SSS", time)}  $message"
    }

    /** Lignes du fichier écrites avant ce lancement de l'app (anciennes sessions). */
    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<String>> = _history.asStateFlow()

    private val _all = MutableStateFlow<List<Entry>>(emptyList())
    val all: StateFlow<List<Entry>> = _all.asStateFlow()

    private val _call = MutableStateFlow<List<Entry>>(emptyList())
    val call: StateFlow<List<Entry>> = _call.asStateFlow()

    @Volatile private var file: File? = null
    private val fileWriter = Executors.newSingleThreadExecutor()

    /** Chemin du fichier journal, affiché dans la console. */
    val filePath: String? get() = file?.absolutePath

    fun init(context: Context) {
        if (file != null) return
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val f = File(dir, FILE_NAME)
        // Rotation simple : au-delà de 1 Mo, l'ancien journal devient app.log.1.
        val previous = File(dir, "$FILE_NAME.1")
        if (f.length() > MAX_FILE_BYTES) f.renameTo(previous)
        file = f
        // Lu sur le thread du fichier, donc avant toute écriture de cette session.
        fileWriter.execute {
            _history.value = listOf(previous, f)
                .filter { it.exists() }
                .flatMap { runCatching { it.readLines() }.getOrDefault(emptyList()) }
                .filter { it.isNotBlank() }
                .takeLast(MAX_HISTORY)
        }
        log("--- Démarrage de l'app (${Build.MODEL}, Android ${Build.VERSION.RELEASE}) ---")
    }

    fun log(message: String) {
        val entry = Entry(System.currentTimeMillis(), message)
        _all.update { (it + entry).takeLast(MAX_ALL) }
        _call.update { (it + entry).takeLast(MAX_CALL) }
        file?.let { f ->
            fileWriter.execute {
                runCatching { f.appendText(entry.fileLine + "\n") }
            }
        }
    }

    /** Un nouvel appel commence : son journal repart de zéro. */
    fun newCall() {
        _call.value = emptyList()
    }

    private fun format(pattern: String, time: Long) =
        SimpleDateFormat(pattern, Locale.FRANCE).format(Date(time))

    private const val FILE_NAME = "app.log"
    private const val MAX_FILE_BYTES = 1_000_000L
    private const val MAX_ALL = 2000
    private const val MAX_CALL = 500
    private const val MAX_HISTORY = 20_000
}