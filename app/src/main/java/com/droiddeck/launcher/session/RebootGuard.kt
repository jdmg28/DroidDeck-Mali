package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import java.io.File

/**
 * A session that ends without its own teardown and leaves a game behind is a device restart, not a
 * closed session: Android kills an app cleanly, and this one writes `.complete` when it ends a
 * session itself. Three of those in a row on this stack (2026-10-14, -19 and -20) each happened
 * with a game on screen, and the phone was unusable after the second.
 *
 * This remembers them and steps the next session down a ladder, so a phone that keeps restarting
 * lands on a configuration that can be tested instead of repeating the one that took it down. The
 * user's own switches are never rewritten: the ladder is applied on top of them, one line in the
 * session log says so, and the app shows a row to clear it (Modo seguro → Volver a normal).
 *
 * The rungs are ordered by what the sessions' logs point at, and each one only adds:
 *
 *  1. the client's browser goes back to the CPU ([SessionPrefs.steamUiOnGpu] is left as the user
 *     set it) **and** games present through shared memory instead of dma-bufs: the menu drew
 *     glitched and then restarted the phone on 2026-10-19, the store's browser was rendering while
 *     a game was torn down on 2026-10-18, and the restart on 2026-10-20 came while the game put
 *     its first frames on screen - both halves are the same GPU, one presentation path each;
 *  2. the driver's in-ring semaphore waits come off (PANVK_KBASE_GPU_SEMAPHORE_WAITS=0): the
 *     fallback that does not turn a slow submit into a job timeout, and a job timeout on kbase is
 *     a soft reset - which is what a restart looks like from the outside;
 *  3. the session runs at 1280x720 at most, whatever the resolution setting says: fewer pixels and
 *     a smaller swapchain is the last lever, and the phone is restarting anyway.
 *
 * Anything that closes cleanly with a game in it means the current rung works: the trail is
 * cleared and the next session is normal again.
 */
object RebootGuard {
    private const val TAG = "RebootGuard"

    const val LEVEL_OFF = 0

    /** The browser back on the CPU, and games through shared memory (no dma-buf). */
    const val LEVEL_SAFE_PRESENT = 1

    /** ...and no in-ring semaphore waits on the driver. */
    const val LEVEL_NO_SEMA = 2

    /** ...and the session at 1280x720 at most. */
    const val LEVEL_SMALL = 3

    private const val KEY = "rebootTrail"
    private const val WINDOW_MS = 48L * 60 * 60 * 1000
    private const val KEEP = 8

    /** Which rung a trail of [count] restarts (inside the window) puts the next session on. */
    internal fun levelFor(count: Int): Int = when {
        count <= 0 -> LEVEL_OFF
        count == 1 -> LEVEL_SAFE_PRESENT
        count == 2 -> LEVEL_NO_SEMA
        else -> LEVEL_SMALL
    }

    /** Restart times (epoch ms) still inside the window, oldest first. */
    fun trail(context: Context): List<Long> {
        val now = System.currentTimeMillis()
        return stored(context).filter { it in (now - WINDOW_MS)..now }
    }

    fun level(context: Context): Int = levelFor(trail(context).size)

    /**
     * Notes that the session in [dir] ended without teardown. Only counts when a game was running
     * in it (`game-started.txt`, written by droiddeck-session): a store-only session that
     * dies is a different problem, and stepping down the ladder for it would hide it.
     */
    fun record(context: Context, dir: File?, gameRan: Boolean) {
        if (!gameRan) return
        val kept = (stored(context) + System.currentTimeMillis()).takeLast(KEEP)
        SessionPrefs.saveRebootTrail(context, kept)
        Log.w(TAG, "a session with a game ended without teardown; ${kept.size} restart(s) remembered, " +
            "next session steps down to level ${levelFor(kept.count { it >= System.currentTimeMillis() - WINDOW_MS })}")
    }

    /** A session that closed with a game in it: the current rung works. */
    fun clear(context: Context) {
        if (stored(context).isEmpty()) return
        SessionPrefs.saveRebootTrail(context, emptyList())
        Log.i(TAG, "a session with a game closed cleanly; the restart trail is cleared")
    }

    private fun stored(context: Context): List<Long> =
        SessionPrefs.rebootTrail(context).sorted()
}
