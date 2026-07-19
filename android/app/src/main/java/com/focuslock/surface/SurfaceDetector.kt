package com.focuslock.surface

/** Per-surface policy setting, as configured in the "Blocked Surfaces" editor. */
enum class SurfaceMode {
    OFF, BLOCK, ONE_AT_A_TIME;

    companion object {
        fun fromWire(value: String?): SurfaceMode = when (value) {
            "block" -> BLOCK
            "one_at_a_time" -> ONE_AT_A_TIME
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
        fun byPackage(pkg: String): BlockableSurface? = entries.firstOrNull { pkg in it.packageNames }
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

    // Best-effort ad markers for the reel counter. Neither platform exposes a real
    // "this is an ad" flag over the accessibility API — this is a text-label
    // heuristic only. Deliberately conservative (no bare "Ad", which would
    // substring-match ordinary words like "Adam"): it under-detects ads rather
    // than risking any behavioral change to real content. Counting is the only
    // thing this gates — it never affects whether a reel is shown or blocked.
    val adKeywords: Map<BlockableSurface, Set<String>> = mapOf(
        BlockableSurface.INSTAGRAM_REELS to setOf("Sponsored"),
        BlockableSurface.YOUTUBE_SHORTS to setOf("Sponsored")
    )
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
        if (mode == SurfaceMode.OFF) {
            state = State.Idle
            return SurfaceDecision.ALLOW
        }
        if (stillInCooldown(nowMs)) return SurfaceDecision.ALLOW
        if (state is State.InViewer) return SurfaceDecision.ALLOW

        return when (mode) {
            SurfaceMode.BLOCK -> pressBack(nowMs)
            SurfaceMode.ONE_AT_A_TIME -> {
                state = State.InViewer(surface, entryIndex = null, accumulatedDeltaY = 0)
                SurfaceDecision.GRANT_ENTRY
            }
            SurfaceMode.OFF -> SurfaceDecision.ALLOW
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
        if (mode == SurfaceMode.OFF) return SurfaceDecision.ALLOW
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

    fun reset() {
        state = State.Idle
        lastBackAtMs = null
    }
}
