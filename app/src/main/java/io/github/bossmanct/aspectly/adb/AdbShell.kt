package io.github.bossmanct.aspectly.adb

import android.content.Context
import io.github.bossmanct.aspectly.log.ActivityLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs commands from [AspectlyCommands] and nothing else.
 *
 * Every command opens its own `shell:` stream, which runs it once and closes. That is
 * deliberate rather than holding an interactive shell open: a one-shot stream cannot
 * be left in a half-consumed state, and there is no persistent shell for a bug to
 * accidentally write into.
 */
object AdbShell {

    suspend fun run(context: Context, command: String): Result<String> =
        withContext(Dispatchers.IO) {
            ActivityLog.record(context, "RUN  $command")
            runCatching {
                val manager = AspectlyAdbManager.getInstance(context)
                manager.openStream("shell:$command").use { stream ->
                    // A command that prints nothing (am force-stop, most wm calls) can
                    // have its stream closed by adbd before the read begins, which
                    // throws "Stream closed". That is normal completion, not failure —
                    // a genuine problem surfaces when openStream itself fails.
                    runCatching {
                        stream.openInputStream().bufferedReader().readText()
                    }.getOrDefault("")
                }
            }.onSuccess { output ->
                val summary = output.trim().lines().firstOrNull().orEmpty()
                ActivityLog.record(context, "OK   ${summary.ifEmpty { "(no output)" }}")
            }.onFailure { error ->
                ActivityLog.record(context, "FAIL ${error.message ?: error::class.simpleName}")
            }
        }

    suspend fun runAll(context: Context, commands: List<String>): Result<Unit> {
        commands.forEach { command ->
            run(context, command).onFailure { return Result.failure(it) }
        }
        return Result.success(Unit)
    }
}
