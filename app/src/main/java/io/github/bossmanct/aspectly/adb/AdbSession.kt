package io.github.bossmanct.aspectly.adb

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serialises every operation that talks to adbd.
 *
 * There is exactly one connection manager, and discovery resets it before each probe
 * (a failed connect leaves a dead socket that poisons later attempts). That reset is
 * correct in isolation and destructive under concurrency: when the background restore
 * and a user-initiated connect ran together, each tore down the connection the other
 * was mid-command on. From the log:
 *
 * ```
 * 16:04:24  RUN  am compat enable OVERRIDE_MIN_ASPECT_RATIO ...   <- never completes
 * 16:04:25  SCAN probing 43779                                    <- second restore begins
 * ```
 *
 * The app looked stuck because two operations kept cancelling each other.
 *
 * Take this only at entry points — [kotlinx.coroutines.sync.Mutex] is not reentrant, so
 * locking again inside a call that already holds it would deadlock.
 */
object AdbSession {

    private val mutex = Mutex()

    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }

    val isBusy: Boolean get() = mutex.isLocked
}
