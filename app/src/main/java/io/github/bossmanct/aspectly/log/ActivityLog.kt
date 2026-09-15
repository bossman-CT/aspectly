package io.github.bossmanct.aspectly.log

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every privileged action Aspectly takes, recorded on-device and nowhere else.
 *
 * This is the transparency mechanism. Open source proves what the app *can* do, but
 * only to people who read Kotlin; the log proves what it *actually did*, to anyone who
 * can scroll a list. It is the difference between asking users to trust a claim and
 * letting them check it.
 *
 * Commands and connection state only — never app content, never anything the user did
 * not ask Aspectly to touch. Nothing here is ever transmitted.
 */
object ActivityLog {

    private const val FILE_NAME = "activity.log"
    private const val MAX_BYTES = 512 * 1024

    private val mutex = Mutex()
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    suspend fun record(context: Context, entry: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = file(context)
            if (file.length() > MAX_BYTES) trim(file)
            file.appendText("${stamp.format(Date())}  $entry\n")
        }
    }

    suspend fun read(context: Context): List<String> = withContext(Dispatchers.IO) {
        val file = file(context)
        if (file.exists()) file.readLines().asReversed() else emptyList()
    }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock { file(context).writeText("") }
    }

    fun exportFile(context: Context): File = file(context)

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun trim(file: File) {
        val kept = file.readLines().takeLast(1_000)
        file.writeText(kept.joinToString("\n", postfix = "\n"))
    }
}
