package io.github.bossmanct.aspectly.adb

import android.content.Context
import io.github.bossmanct.aspectly.log.ActivityLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Runs commands from [AspectlyCommands] and nothing else.
 *
 * Every command opens its own `shell:` stream, which runs it once and closes. That is
 * deliberate rather than holding an interactive shell open: a one-shot stream cannot be
 * left in a half-consumed state, and there is no persistent shell for a bug to write
 * into by accident.
 */
object AdbShell {

    private const val COMMAND_TIMEOUT_MS = 15_000L

    suspend fun run(context: Context, command: String): Result<String> =
        withContext(Dispatchers.IO) {
            ActivityLog.record(context, "RUN  $command")
            runCatching {
                // Nothing in libadb times out. When the connection dies mid-command —
                // which happens when Auto Blocker disables debugging while a restore is
                // running — the read blocks forever and holds the session lock with it,
                // which presented as the UI freezing on "Connecting and applying".
                withTimeout(COMMAND_TIMEOUT_MS) {
                    AspectlyAdbManager.getInstance(context)
                        .openStream("shell:$command")
                        .use { stream ->
                            // A command that prints nothing (am force-stop, most wm
                            // calls) can have its stream closed by adbd before the read
                            // begins, which throws "Stream closed". That is normal
                            // completion; a real problem surfaces at openStream.
                            runCatching {
                                stream.openInputStream().bufferedReader().readText()
                            }.getOrDefault("")
                        }
                }
            }.onSuccess { output ->
                val summary = output.trim().lines().firstOrNull().orEmpty()
                ActivityLog.record(context, "OK   ${summary.ifEmpty { "(no output)" }}")
            }.onFailure { error ->
                // withTimeout cancels this coroutine, and ActivityLog.record suspends —
                // so without NonCancellable the failure never reaches the log and the
                // command just appears to vanish.
                withContext(NonCancellable) {
                    ActivityLog.record(
                        context,
                        "FAIL ${error::class.simpleName}: ${error.message ?: "no message"}",
                    )
                }
            }
        }

    suspend fun runAll(context: Context, commands: List<String>): Result<Unit> {
        commands.forEach { command ->
            run(context, command).onFailure { return Result.failure(it) }
        }
        return Result.success(Unit)
    }
}
