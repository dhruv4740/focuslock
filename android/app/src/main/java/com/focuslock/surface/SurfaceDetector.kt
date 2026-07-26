package com.focuslock.surface

/**
 * Per-surface policy setting, as configured in the "Reels & Shorts" editor.
 *
 * Note this only controls *enforcement*. Reel impressions are counted in every
 * mode including [OFF] — counting and blocking are independent pipelines (see
 * [ReelImpressionCounter]). Before that split, the daily count was a side effect
 * of the [ONE_AT_A_TIME] entry grant, so it froze in [BLOCK] and never ran in
 * [OFF], and the number meant "viewer re-entries" rather than "reels watched".
 */
enum class SurfaceMode {
    OFF, QUOTA, BLOCK, ONE_AT_A_TIME;

    /** Wire value written to Firestore `blockedSurfaces`. */
    val wire: String
        get() = when (this) {
            OFF -> "off"
            QUOTA -> "quota"
            BLOCK -> "block"
            ONE_AT_A_TIME -> "one_at_a_time"
        }

    companion object {
        // Unknown values degrade to OFF so a doc written by a newer client never
        // crashes or over-blocks an older one.
        fun fromWire(value: String?): SurfaceMode = when (value) {
            "block" -> BLOCK
            "one_at_a_time" -> ONE_AT_A_TIME
            "quota" -> QUOTA
            else -> OFF
        }
    }
}

/**
 * A doomscroll surface we can detect inside a specific app. [packageNames] includes
 * known modded builds (Morphe/ReVanced/RVX rename the applicationId but ship the
 * same UI); [resourcePrefix] is the stock resource-table package, which mods
 * usually leave untouched — view-ID probes try it alongside the runtime package.
 */
enum class BlockableSurface(
    val wireKey: String,
    val packageNames: Set<String>,
    val resourcePrefix: String
) {
    INSTAGRAM_REELS(
        "instagram_reels",
        setOf("com.instagram.android"),
        "com.instagram.android"
    ),
    YOUTUBE_SHORTS(
        "youtube_shorts",
        setOf(
            "com.google.android.youtube",
            "app.morphe.android.youtube",
            "app.revanced.android.youtube",
            "app.rvx.android.youtube"
        ),
        "com.google.android.youtube"
    );

    companion object {
        // Substring tokens for the heuristic fallback. Modded clients (Morphe,
        // ReVanced, RVX, and anything a user renames in ReVanced Manager) keep the
        // vendor name in their applicationId but the exact string is unknowable, so
        // an exact-match list can never be complete — and a miss is silent.
        private val heuristicTokens: Map<String, BlockableSurface> = mapOf(
            "youtube" to YOUTUBE_SHORTS,
            "instagram" to INSTAGRAM_REELS
        )

        // Sibling apps that share the vendor token but have no reel/short viewer.
        // Excluding them only saves wasted probe work — a false match would still
        // be harmless, because the view-ID and fullscreen-bounds gates have to pass
        // before anything is counted or blocked.
        private val excludedTokens = listOf("music", "kids", "creator", "studio", ".tv", "vr")

        /**
         * Resolves a runtime package to the surface it hosts.
         *
         * Three layers, cheapest first: the known-package set, a vendor-token
         * heuristic that survives arbitrary mod renames, then [extra] — packages the
         * user declared by hand in Settings for anything the heuristic misses.
         */
        fun byPackage(
            pkg: String,
            extra: Map<String, BlockableSurface> = emptyMap()
        ): BlockableSurface? {
            entries.firstOrNull { pkg in it.packageNames }?.let { return it }
            extra[pkg]?.let { return it }
            val lower = pkg.lowercase()
            if (excludedTokens.any { it in lower }) return null
            return heuristicTokens.entries.firstOrNull { it.key in lower }?.value
        }
    }
}

/**
 * View-ID markers used to detect each surface. These are internal Instagram/YouTube
 * resource IDs, not a public API — they silently stop matching whenever those apps
 * ship a redesign. Re-verify with `adb shell uiautomator dump` after app updates and
 * update the sets below; a stale ID means the surface stops being detected, never a
 * crash or a wrong-app block (fail-open by construction).
 */
object SurfaceMarkers {
    // Bare resource-entry names of the vertical pager that hosts the reel/short
    // stream (no package prefix — modded builds may report either the stock or
    // their own package in viewIdResourceName). TYPE_VIEW_SCROLLED events whose
    // source entry-name matches are pager advances; scrolls from any other ID
    // (comments sheet, feed, chip row) are a different view and are ignored.
    val pagerIdNames: Map<BlockableSurface, Set<String>> = mapOf(
        BlockableSurface.INSTAGRAM_REELS to setOf(
            "clips_viewer_view_pager"
        ),
        BlockableSurface.YOUTUBE_SHORTS to setOf(
            "reel_recycler"
        )
    )

    // Entry names whose presence anywhere in the window means the viewer surface
    // is on screen (superset of pagerIdNames).
    val presenceIdNames: Map<BlockableSurface, Set<String>> = mapOf(
        BlockableSurface.INSTAGRAM_REELS to setOf(
            "clips_viewer_view_pager",
            "clips_video_container"
        ),
        BlockableSurface.YOUTUBE_SHORTS to setOf(
            "reel_recycler",
            "reel_watch_fragment_root",
            "reel_player_page_container"
        )
    )

    /** "com.pkg:id/some_view" → "some_view"; null when the ID has no entry part. */
    fun entryName(viewIdResourceName: String?): String? =
        viewIdResourceName?.substringAfter(":id/", missingDelimiterValue = "")?.ifEmpty { null }

    /**
     * Fully-qualified ID candidates to probe via findAccessibilityNodeInfosByViewId:
     * the stock resource prefix plus the runtime package (modded builds may remap
     * the resource table to their renamed package).
     */
    fun presenceIdCandidates(surface: BlockableSurface, runtimePackage: String): List<String> {
        val names = presenceIdNames[surface] ?: return emptyList()
        val prefixes = linkedSetOf(surface.resourcePrefix, runtimePackage)
        return names.flatMap { name -> prefixes.map { prefix -> "$prefix:id/$name" } }
    }

    // There was an ad-exclusion heuristic here that searched the whole window tree for
    // "Sponsored" and skipped the count on a hit. It was removed, not disabled: the
    // search is a case-insensitive *substring* match over every node, so a sponsored
    // label in a suggested-accounts row or a visible comment silently suppressed the
    // count for a genuine organic reel. Its failure mode was under-counting, which is
    // the exact bug the counter rewrite exists to fix, and now that counts gate quota
    // enforcement a false match also hands out free reels. If ad exclusion is wanted
    // again, scope it to the settled pager page and require an exact text match.
}

// A feed-embedded reel/short (Instagram home feed, YouTube home feed) can expose the
// same view IDs as the real fullscreen viewer. The viewer is edge-to-edge; a feed post
// is at most ~4:5 and moves with scrolling — so gate presence matches on the node
// filling most of the window, not just matching by ID.
internal fun isViewerSizedBounds(
    nodeW: Int,
    nodeH: Int,
    windowW: Int,
    windowH: Int,
    minHeightFraction: Double = 0.75,
    minWidthFraction: Double = 0.85
): Boolean =
    windowW > 0 && windowH > 0 &&
        nodeH >= windowH * minHeightFraction &&
        nodeW >= windowW * minWidthFraction

/**
 * Counts distinct reel/short impressions, independently of [SurfaceMode] and of the
 * enforcement state machine. Runs in every mode, including [SurfaceMode.OFF].
 *
 * This exists because counting used to be a side effect of [SurfaceSessionTracker]'s
 * one-at-a-time entry grant, which made the daily total wrong in three ways: it never
 * incremented in BLOCK or OFF, it counted one reel per viewer session rather than per
 * reel, and — the dominant bug — the tracker's post-Back cooldown swallowed the grant
 * whenever the user re-entered the viewer within ~1.5s. Fast scrolling is exactly that
 * pattern, so heavy use undercounted the most.
 *
 * The fix is to not depend on enforcement timing at all. A session is closed by two
 * explicit signals we own — [onSurfaceLeft] (left the app, or we just issued a Back
 * press) — plus a generous [sessionGapMs] backstop for incidental probe misses. That
 * makes a re-entry after a Back press count reliably no matter how fast it happens,
 * while a comments sheet briefly stealing the active window still does not recount.
 *
 * Pure: no Android dependencies, all timing passed in via `nowMs`.
 */
class ReelImpressionCounter(
    private val advanceDeltaPx: Int = 900,
    private val sessionGapMs: Long = 3000L,
    private val maxAdvancePerEvent: Int = 10,
    private val minAdvanceIntervalMs: Long = 250L
) {
    private class SurfaceState {
        var sessionOpen = false
        var lastIndex: Int? = null
        /** Latched once the app is known to emit usable indices, so the delta
         *  fallback can never double-count the same advance. */
        var sawIndexEvents = false
        var accumulatedDeltaY = 0
        var lastSeenAtMs = 0L
        var lastAdvanceAtMs = 0L
    }

    private val states = mutableMapOf<BlockableSurface, SurfaceState>()

    private fun stateOf(surface: BlockableSurface) = states.getOrPut(surface) { SurfaceState() }

    /** True when the gap since the last event is long enough to call the session over. */
    private fun SurfaceState.stale(nowMs: Long) = nowMs - lastSeenAtMs > sessionGapMs

    private fun SurfaceState.openSession(nowMs: Long, index: Int?) {
        sessionOpen = true
        lastIndex = index
        sawIndexEvents = index != null
        accumulatedDeltaY = 0
        lastSeenAtMs = nowMs
        lastAdvanceAtMs = nowMs
    }

    /**
     * The viewer is on screen. Returns 1 when this opens a new session (the reel the
     * user landed on), 0 while an existing session is still running.
     */
    fun onViewerVisible(surface: BlockableSurface, nowMs: Long): Int {
        val st = stateOf(surface)
        if (st.sessionOpen && !st.stale(nowMs)) {
            st.lastSeenAtMs = nowMs
            return 0
        }
        st.openSession(nowMs, index = null)
        return 1
    }

    /**
     * A swipe on the reel pager. Returns how many new reels it consumed — normally 1,
     * more for a fling that skips several items.
     */
    fun onPagerScrolled(
        surface: BlockableSurface,
        fromIndex: Int,
        toIndex: Int,
        scrollDeltaY: Int,
        nowMs: Long
    ): Int {
        val st = stateOf(surface)
        val settled = settledIndex(fromIndex, toIndex)

        if (!st.sessionOpen || st.stale(nowMs)) {
            // Entry event was missed (service reconnected mid-viewer, or the presence
            // probe never fired). Adopt this scroll as the session start.
            st.openSession(nowMs, index = settled)
            return 1
        }
        st.lastSeenAtMs = nowMs

        if (settled != null) {
            st.sawIndexEvents = true
            val previous = st.lastIndex
            st.lastIndex = settled
            // First settled event of the session just establishes the baseline — the
            // reel at that index was already counted when the session opened.
            if (previous == null) return 0
            if (settled == previous) return 0
            // Layout thrash can bounce the settled index A→B→A within a few ms. A real
            // swipe never lands two reels that fast, so treat it as one view.
            if (nowMs - st.lastAdvanceAtMs < minAdvanceIntervalMs) return 0
            st.lastAdvanceAtMs = nowMs
            // Clamped: a RecyclerView rebind can snap the index back to 0, which would
            // otherwise register as a huge burst of phantom reels.
            return kotlin.math.abs(settled - previous).coerceAtMost(maxAdvancePerEvent)
        }

        // Unsettled event. If this app has ever reported a usable settled index, the
        // index path owns counting for the whole session — running the delta fallback
        // alongside it would count every advance twice.
        if (st.sawIndexEvents) return 0

        // No index signal at all — fall back to scroll distance. A full swipe to the
        // next reel covers roughly one screen height; carry the remainder so a run of
        // partial scroll events still adds up to exactly one reel.
        st.accumulatedDeltaY += kotlin.math.abs(scrollDeltaY)
        val advances = st.accumulatedDeltaY / advanceDeltaPx
        st.accumulatedDeltaY -= advances * advanceDeltaPx
        if (advances > 0) st.lastAdvanceAtMs = nowMs
        return advances.coerceAtMost(maxAdvancePerEvent)
    }

    /**
     * The pager is showing exactly one reel only when the first and last visible
     * positions agree. RecyclerView reports `fromIndex = findFirstVisibleItemPosition()`
     * and `toIndex = findLastVisibleItemPosition()`, so mid-swipe — with two reels
     * partially on screen — they differ. Counting on every `toIndex` change (what the
     * old advance logic did) therefore fires repeatedly during a single swipe.
     */
    private fun settledIndex(fromIndex: Int, toIndex: Int): Int? =
        if (fromIndex >= 0 && fromIndex == toIndex) fromIndex else null

    /**
     * The user left this surface for real — switched app, or we just issued a Back
     * press to kick them out. The next sighting is a genuinely new reel and counts,
     * with no dependence on how quickly they come back.
     */
    fun onSurfaceLeft(surface: BlockableSurface) {
        states[surface]?.sessionOpen = false
    }

    fun reset() = states.clear()
}

enum class SurfaceDecision { ALLOW, PRESS_BACK, GRANT_ENTRY }

/**
 * Pure state machine deciding whether a detected reel/short surface should be left
 * alone or kicked with a Back press. No Android dependencies — all timing is passed
 * in via [nowMs] so this is fully unit-testable.
 */
class SurfaceSessionTracker(
    private val backDebounceMs: Long = 1500L,
    private val postBackCooldownMs: Long = 1500L,
    private val advanceDeltaPx: Int = 900
) {
    private sealed class State {
        object Idle : State()
        data class InViewer(
            val surface: BlockableSurface,
            var entryIndex: Int?,
            var accumulatedDeltaY: Int
        ) : State()
        // viewerWentAway flips once the viewer actually leaves the screen during
        // cooldown; a later sighting is then a genuine re-entry (user navigated
        // back in), not the Back-press animation still settling — and must be
        // handled fresh instead of granting free untracked viewing time.
        data class Cooldown(val untilMs: Long, var viewerWentAway: Boolean = false) : State()
    }

    private var state: State = State.Idle
    private var lastBackAtMs: Long? = null

    private fun pressBack(nowMs: Long): SurfaceDecision {
        val last = lastBackAtMs
        if (last != null && nowMs - last < backDebounceMs) {
            return SurfaceDecision.ALLOW
        }
        lastBackAtMs = nowMs
        state = State.Cooldown(nowMs + postBackCooldownMs)
        return SurfaceDecision.PRESS_BACK
    }

    /**
     * True while a just-issued Back press is still settling; callers should not re-arm.
     * Only called from viewer-visible/scroll paths, so if the viewer already went away
     * during this cooldown, its reappearance means the user re-entered — cooldown ends.
     */
    private fun stillInCooldown(nowMs: Long): Boolean {
        val cooldown = state as? State.Cooldown ?: return false
        if (nowMs < cooldown.untilMs && !cooldown.viewerWentAway) return true
        state = State.Idle
        return false
    }

    fun onViewerVisible(surface: BlockableSurface, mode: SurfaceMode, nowMs: Long): SurfaceDecision {
        // QUOTA enforces nothing on its own — SurfaceBlocker.effectiveMode upgrades it
        // to BLOCK once the daily reel limit is reached, so the tracker only ever sees
        // QUOTA while the user is still under quota.
        if (mode == SurfaceMode.OFF || mode == SurfaceMode.QUOTA) {
            state = State.Idle
            return SurfaceDecision.ALLOW
        }
        if (stillInCooldown(nowMs)) return SurfaceDecision.ALLOW
        // Only a session for *this* surface suppresses re-arming. Without the surface
        // check, an Instagram session left open (app-switched via Recents, which is
        // filtered as a transient system package) meant YouTube Shorts was never
        // blocked — onPagerScrolled already compared surfaces, this path did not.
        (state as? State.InViewer)?.let { if (it.surface == surface) return SurfaceDecision.ALLOW }

        return when (mode) {
            SurfaceMode.BLOCK -> pressBack(nowMs)
            SurfaceMode.ONE_AT_A_TIME -> {
                state = State.InViewer(surface, entryIndex = null, accumulatedDeltaY = 0)
                SurfaceDecision.GRANT_ENTRY
            }
            SurfaceMode.OFF, SurfaceMode.QUOTA -> SurfaceDecision.ALLOW
        }
    }

    fun onPagerScrolled(
        surface: BlockableSurface,
        mode: SurfaceMode,
        fromIndex: Int,
        toIndex: Int,
        scrollDeltaY: Int,
        nowMs: Long
    ): SurfaceDecision {
        // QUOTA must be inert here for the same reason it is in onViewerVisible: while
        // the user is under quota it enforces nothing, and SurfaceBlocker.effectiveMode
        // upgrades it to BLOCK once the limit is hit. Without this the scroll would fall
        // through to the missed-entry branch below, adopt a one-at-a-time session, and
        // Back the user out on their next swipe — making QUOTA behave like ONE_AT_A_TIME.
        if (mode == SurfaceMode.OFF || mode == SurfaceMode.QUOTA) return SurfaceDecision.ALLOW
        if (stillInCooldown(nowMs)) return SurfaceDecision.ALLOW

        val current = state as? State.InViewer
        if (current == null || current.surface != surface) {
            if (mode == SurfaceMode.BLOCK) return pressBack(nowMs)
            // Missed the entry event (e.g. service reconnected mid-viewer) — adopt
            // this scroll as the start of a one-at-a-time session. Returned as
            // GRANT_ENTRY so the caller counts it: silently-adopted sessions were
            // previously invisible to the daily reel counter.
            state = State.InViewer(
                surface = surface,
                entryIndex = if (toIndex >= 0) toIndex else if (fromIndex >= 0) fromIndex else null,
                accumulatedDeltaY = 0
            )
            return SurfaceDecision.GRANT_ENTRY
        }

        return evaluateAdvance(current, toIndex, scrollDeltaY, nowMs)
    }

    private fun evaluateAdvance(
        viewer: State.InViewer,
        toIndex: Int,
        scrollDeltaY: Int,
        nowMs: Long
    ): SurfaceDecision {
        if (toIndex >= 0) {
            if (viewer.entryIndex == null) {
                viewer.entryIndex = toIndex
                return SurfaceDecision.ALLOW
            }
            return if (toIndex != viewer.entryIndex) {
                pressBack(nowMs)
            } else {
                SurfaceDecision.ALLOW
            }
        }

        // No usable index from this event — fall back to accumulated scroll
        // distance; a full swipe to the next reel covers roughly one screen height.
        viewer.accumulatedDeltaY += kotlin.math.abs(scrollDeltaY)
        return if (viewer.accumulatedDeltaY >= advanceDeltaPx) {
            pressBack(nowMs)
        } else {
            SurfaceDecision.ALLOW
        }
    }

    fun onViewerGone(nowMs: Long) {
        val cooldown = state as? State.Cooldown
        if (cooldown != null && nowMs < cooldown.untilMs) {
            // The Back press landed. Remember it so a quick re-entry inside the
            // remaining cooldown is treated as a fresh session, not free time.
            cooldown.viewerWentAway = true
            return
        }
        state = State.Idle
    }

    /**
     * The surface this tracker currently holds a viewer session for, or null when
     * idle or cooling down. Exposed so a policy refresh can reset the tracker only
     * when the mode of the surface actually being tracked changed — resetting
     * unconditionally on every 15-minute refresh wiped live session state and made
     * the next probe issue a spurious re-entry.
     */
    val activeSurface: BlockableSurface?
        get() = (state as? State.InViewer)?.surface

    fun reset() {
        state = State.Idle
        lastBackAtMs = null
    }
}
