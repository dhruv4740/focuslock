package com.focuslock.data

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import org.json.JSONArray
import org.json.JSONObject

object FocusPolicyClient {

    private const val TAG = "FocusPolicyClient"
    private const val PREFS_NAME = "FocusLockPrefs"
    private const val PREF_SYNC_ID = "sync_id"
    const val DEFAULT_USER_ID = "user_default"

    // Resolved from SharedPreferences in init(). Two people can each pick their
    // own sync code: the same code on a person's phone + desktop keeps those in
    // sync, while different people get fully isolated data trees in Firestore.
    @Volatile
    private var userId = DEFAULT_USER_ID

    // Normalize a sync code identically on Android and the Windows agent so the
    // same human input ("Dhruv 01") resolves to the same Firestore doc id.
    fun sanitizeSyncId(raw: String): String {
        var clean = raw.trim().lowercase()
        clean = clean.replace(Regex("[^a-z0-9_-]"), "-")
        clean = clean.replace(Regex("-+"), "-")
        clean = clean.trim('-')
        return if (clean.isEmpty()) DEFAULT_USER_ID else clean.take(64)
    }

    fun getSyncId(): String = userId

    fun setSyncId(context: Context, raw: String): String {
        val clean = sanitizeSyncId(raw)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREF_SYNC_ID, clean).apply()
        userId = clean
        Log.i(TAG, "Sync code set to '$clean'")
        return clean
    }

    data class Decision(
        val allowNow: Boolean,
        val action: String,
        val reason: String,
        val effectiveLimit: Int,
        val usedMinutes: Double,
        val userUsedMinutes: Double,
        val hardBlockAfterLimit: Boolean = true
    )

    data class ExtensionResult(
        val granted: Boolean,
        val reason: String,
        val grantedMinutes: Int
    )

    data class FocusSessionInfo(
        val active: Boolean,
        val endsAt: String
    )

    data class TodoItem(
        val id: String,
        val text: String,
        var completed: Boolean
    )

    fun init(context: Context) {
        if (FirebaseApp.getApps(context).isEmpty()) {
            val options = FirebaseOptions.Builder()
                .setApiKey("AIzaSyCwxWxVk7MMkpzkO39D7DTpbAyPrCmevvc")
                .setApplicationId("1:22012836707:web:3d78f0e2a75849e2c63156")
                .setProjectId("focusblock-e8376")
                .build()
            FirebaseApp.initializeApp(context, options)
            Log.i(TAG, "Firebase initialized")
        }

        // Resolve the active sync code (which Firestore user tree to read/write).
        userId = sanitizeSyncId(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_SYNC_ID, DEFAULT_USER_ID) ?: DEFAULT_USER_ID
        )

        // Enable offline persistence so Firestore caches data on-device.
        // This means evaluate() returns cached results even when offline,
        // preventing the "reset to allowed" offline bypass.
        try {
            val settings = FirebaseFirestoreSettings.Builder()
                .setPersistenceEnabled(true)
                .setCacheSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                .build()
            FirebaseFirestore.getInstance().firestoreSettings = settings
        } catch (e: Exception) {
            Log.w(TAG, "Could not configure Firestore persistence (already set?)", e)
        }
    }

    // Firestore rules require request.auth != null. We use anonymous auth: the
    // first Firestore access signs in (blocking on the calling background thread,
    // same as every other Tasks.await here); afterwards currentUser is non-null
    // so this is a cheap no-op. Failure is logged, not fatal — offline cache may
    // still serve the last read.
    private fun ensureSignedIn() {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) return
        try {
            Tasks.await(auth.signInAnonymously())
            Log.i(TAG, "Anonymous auth ready (uid=${auth.currentUser?.uid})")
        } catch (e: Exception) {
            Log.e(TAG, "Anonymous sign-in failed", e)
        }
    }

    // Firestore rules gate access to users/{userId} on membership in a
    // `members` allowlist (see firestore.rules). Join it once per (userId,
    // process) — FirebaseAuth persists the anonymous uid natively across app
    // restarts, so re-running this after the first successful join is a cheap
    // no-op on the read side, but we still memoize to avoid a transaction on
    // every single db access.
    @Volatile
    private var membershipEnsuredFor: String? = null

    private fun ensureMembership() {
        if (membershipEnsuredFor == userId) return
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        try {
            val userRef = FirebaseFirestore.getInstance().collection("users").document(userId)
            Tasks.await(FirebaseFirestore.getInstance().runTransaction { tx ->
                val snap = tx.get(userRef)
                if (!snap.exists()) {
                    tx.set(userRef, mapOf(
                        "members" to listOf(uid),
                        "createdAt" to java.time.Instant.now().toString()
                    ))
                } else {
                    @Suppress("UNCHECKED_CAST")
                    val members = (snap.get("members") as? List<String>) ?: emptyList()
                    if (uid !in members) {
                        tx.update(userRef, "members", members + uid)
                    }
                }
                null
            })
            membershipEnsuredFor = userId
        } catch (e: Exception) {
            Log.w(TAG, "ensureMembership failed", e)
        }
    }

    private val db: FirebaseFirestore
        get() {
            ensureSignedIn()
            ensureMembership()
            return FirebaseFirestore.getInstance()
        }

    // ── Policy ────────────────────────────────────────────────────────────────

    fun fetchPolicy(): JSONObject {
        val snap = Tasks.await(
            db.collection("users").document(userId)
                .collection("policy").document("global").get()
        )
        val data = snap.data ?: return JSONObject()
        @Suppress("UNCHECKED_CAST")
        val blockedSurfaces = data["blockedSurfaces"] as? Map<String, String> ?: emptyMap()
        @Suppress("UNCHECKED_CAST")
        val reelDailyLimit = data["reelDailyLimit"] as? Map<String, Number> ?: emptyMap()
        @Suppress("UNCHECKED_CAST")
        val appModes = data["appModes"] as? Map<String, String> ?: emptyMap()
        return JSONObject().apply {
            put("targetApps", JSONArray(data["targetApps"] as? List<String> ?: emptyList<String>()))
            put("targetDomains", JSONArray(data["targetDomains"] as? List<String> ?: emptyList<String>()))
            put("blockedSurfaces", JSONObject(blockedSurfaces))
            put("reelDailyLimit", JSONObject(reelDailyLimit.mapValues { it.value.toInt() }))
            put("appModes", JSONObject(appModes))
            put("sessionMaxMinutes", (data["sessionMaxMinutes"] as? Number)?.toInt() ?: 0)
            put("sessionBreakMinutes", (data["sessionBreakMinutes"] as? Number)?.toInt() ?: 0)
        }
    }

    // ── Evaluation (three reads — uses offline cache when network is absent) ──

    fun evaluate(date: String, packageName: String? = null): Decision {
        val policySnap = Tasks.await(
            db.collection("users").document(userId)
                .collection("policy").document("global").get()
        )
        val policy = policySnap.data ?: mapOf<String, Any>()

        val usageSnap = Tasks.await(
            db.collection("users").document(userId)
                .collection("usage_days").document(date).get()
        )
        val usage = usageSnap.data ?: mapOf<String, Any>()

        val focusSnap = Tasks.await(
            db.collection("users").document(userId)
                .collection("focus_session").document("current").get()
        )
        val focus = focusSnap.data ?: mapOf<String, Any>()

        val focusActive = focus["active"] as? Boolean ?: false
        val focusEndsAt = focus["endsAt"] as? String ?: ""
        val nowIso = java.time.Instant.now().toString()
        val focusStillRunning = focusActive && focusEndsAt.isNotEmpty() && focusEndsAt > nowIso

        @Suppress("UNCHECKED_CAST")
        val blockWindows = (policy["blockWindows"] as? List<Map<String, Any>> ?: emptyList()).map { w ->
            val startStr = w["start"] as? String ?: "00:00"
            val endStr = w["end"] as? String ?: "23:59"
            @Suppress("UNCHECKED_CAST")
            val days = (w["days"] as? List<Number>)?.map { it.toInt() } ?: (0..6).toList()
            val (sh, sm) = startStr.split(":").map { it.toInt() }
            val (eh, em) = endStr.split(":").map { it.toInt() }
            PolicyEngine.BlockWindow(sh * 60 + sm, eh * 60 + em, days.toSet())
        }

        @Suppress("UNCHECKED_CAST")
        val appModes = policy["appModes"] as? Map<String, String> ?: emptyMap()

        val cal = java.util.Calendar.getInstance()
        return PolicyEngine.decide(
            policy = PolicyEngine.PolicyInputs(
                dailyLimitMinutes = (policy["dailyLimitMinutes"] as? Number)?.toInt() ?: 0,
                extensionMinutes = (policy["extensionMinutes"] as? Number)?.toInt() ?: 15,
                hardBlockAfterLimit = policy["hardBlockAfterLimit"] as? Boolean ?: false,
                blockWindows = blockWindows,
                appModes = appModes
            ),
            usage = PolicyEngine.UsageInputs(
                // Keep precision as Double throughout; avoid Int truncation
                usedMinutes = usage["usedMinutes"] as? Double ?: 0.0,
                extensionsUsedToday = (usage["extensionsUsedToday"] as? Number)?.toInt() ?: 0
            ),
            now = PolicyEngine.Now(
                dayOfWeek = cal.get(java.util.Calendar.DAY_OF_WEEK) - 1, // 0=Sun
                minuteOfDay = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE),
                focusSessionActive = focusStillRunning
            ),
            packageName = packageName
        )
    }

    // ── Extension request ─────────────────────────────────────────────────────

    fun requestExtension(date: String): ExtensionResult {
        // Check focus session — no extensions during a focus block
        val focusSnap = Tasks.await(
            db.collection("users").document(userId)
                .collection("focus_session").document("current").get()
        )
        val focus = focusSnap.data ?: mapOf<String, Any>()
        val focusActive = focus["active"] as? Boolean ?: false
        val focusEndsAt = focus["endsAt"] as? String ?: ""
        val now = java.time.Instant.now().toString()
        if (focusActive && focusEndsAt.isNotEmpty() && focusEndsAt > now) {
            return ExtensionResult(false, "focus-session-active", 0)
        }

        // Read policy for extension config
        val policySnap = Tasks.await(
            db.collection("users").document(userId)
                .collection("policy").document("global").get()
        )
        val policy = policySnap.data ?: return ExtensionResult(false, "no-policy", 0)

        val dailyExtensionLimit = (policy["dailyExtensionLimit"] as? Number)?.toInt() ?: 1
        val extensionMinutes = (policy["extensionMinutes"] as? Number)?.toInt() ?: 15

        val usageRef = db.collection("users").document(userId)
            .collection("usage_days").document(date)

        // Atomically check and increment extensionsUsedToday
        return Tasks.await(db.runTransaction { tx ->
            val snap = tx.get(usageRef)
            val extensionsUsedToday = (snap.getLong("extensionsUsedToday") ?: 0L).toInt()

            if (extensionsUsedToday >= dailyExtensionLimit) {
                return@runTransaction ExtensionResult(
                    false,
                    "daily-extension-limit-reached",
                    0
                )
            }

            if (!snap.exists()) {
                tx.set(usageRef, mapOf("usedMinutes" to 0.0, "extensionsUsedToday" to 1, "opensToday" to 0))
            } else {
                tx.update(usageRef, "extensionsUsedToday", extensionsUsedToday + 1)
            }

            ExtensionResult(true, "granted", extensionMinutes)
        })
    }

    // ── Usage events ──────────────────────────────────────────────────────────

    fun postEvent(
        target: String,
        startedAt: String,
        endedAt: String,
        durationMinutes: Double
    ) {
        // Key the usage day by LOCAL date so it matches evaluate()/getStats()
        // (both use LocalDate.now()) and the Windows agent (local getTodayString()).
        // startedAt is a UTC ISO string, so startedAt.take(10) would land usage in
        // the wrong day for any session before local-midnight-plus-UTC-offset.
        val date = java.time.LocalDate.now().toString()
        val usageRef = db.collection("users").document(userId)
            .collection("usage_days").document(date)

        Tasks.await(db.runTransaction { tx ->
            val snap = tx.get(usageRef)
            if (!snap.exists()) {
                tx.set(
                    usageRef,
                    mapOf("usedMinutes" to durationMinutes, "extensionsUsedToday" to 0, "opensToday" to 1)
                )
            } else {
                val current = snap.getDouble("usedMinutes") ?: 0.0
                val opens = snap.getLong("opensToday") ?: 0L
                tx.update(
                    usageRef,
                    mapOf("usedMinutes" to current + durationMinutes, "opensToday" to opens + 1)
                )
            }
            null
        })

        // Additive per-app aggregate (dashboards read this in one shot instead of
        // scanning events). Merge write — the Windows agent round-trips unknown
        // fields untouched.
        Tasks.await(
            usageRef.set(
                mapOf("perApp" to mapOf(target to FieldValue.increment(durationMinutes))),
                SetOptions.merge()
            )
        )

        val eventData = mapOf(
            "type" to "usage",
            "target" to target,
            "durationMinutes" to durationMinutes,
            "startedAt" to startedAt,
            "timestamp" to endedAt
        )
        Tasks.await(
            db.collection("users").document(userId).collection("events").add(eventData)
        )

        Log.i(TAG, "Synced %.2f min for $target".format(durationMinutes))
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    fun getStats(date: String): JSONObject {
        val snap = Tasks.await(
            db.collection("users").document(userId)
                .collection("usage_days").document(date).get()
        )
        val data = snap.data ?: mapOf<String, Any>()
        val usedMinutes = data["usedMinutes"] as? Double ?: 0.0
        val opensToday = (data["opensToday"] as? Number)?.toInt() ?: 0
        @Suppress("UNCHECKED_CAST")
        val reelCounts = (data["reelCounts"] as? Map<String, Number>) ?: emptyMap()

        return JSONObject().apply {
            put("usedMinutes", usedMinutes)
            put("opensToday", opensToday)
            put("reelCounts", JSONObject(reelCounts.mapValues { it.value.toInt() }))
        }
    }

    // ── Reel counts ───────────────────────────────────────────────────────────

    fun getReelCounts(date: String): Map<String, Int> {
        val snap = Tasks.await(
            db.collection("users").document(userId)
                .collection("usage_days").document(date).get()
        )
        @Suppress("UNCHECKED_CAST")
        val raw = snap.data?.get("reelCounts") as? Map<String, Number> ?: emptyMap()
        return raw.mapValues { it.value.toInt() }
    }

    // One lightweight write per flush (see SurfaceBlocker) rather than a
    // transaction — nested-map merge creates usage_days/{date} and/or the
    // reelCounts field if missing, and atomically increments each wireKey via
    // FieldValue.increment without touching usedMinutes/opensToday.
    fun incrementReelCounts(date: String, deltas: Map<String, Int>) {
        if (deltas.isEmpty()) return
        val usageRef = db.collection("users").document(userId)
            .collection("usage_days").document(date)
        val nested = mapOf(
            "reelCounts" to deltas.mapValues { FieldValue.increment(it.value.toLong()) }
        )
        Tasks.await(usageRef.set(nested, SetOptions.merge()))
    }

    /** Absolute totals for the day. Idempotent — a retried or duplicated write is a no-op. */
    fun setReelCounts(date: String, totals: Map<String, Int>) {
        if (totals.isEmpty()) return
        Tasks.await(
            db.collection("users").document(userId)
                .collection("usage_days").document(date)
                .set(mapOf("reelCounts" to totals), SetOptions.merge())
        )
    }

    // ── Policy read/write ─────────────────────────────────────────────────────

    data class PolicyData(
        val dailyLimitMinutes: Int,
        val extensionMinutes: Int,
        val dailyExtensionLimit: Int,
        val hardBlockAfterLimit: Boolean,
        val targetApps: List<String>,
        val targetDomains: List<String>,
        val blockWindows: List<Map<String, Any>>,
        val blockedSurfaces: Map<String, String> = emptyMap(),
        val reelDailyLimit: Map<String, Int> = emptyMap(),
        // Additive fields (old docs parse fine with the defaults; the Windows
        // dashboard round-trips them untouched).
        val appModes: Map<String, String> = emptyMap(),
        val presets: Map<String, Map<String, Any>> = emptyMap(),
        val activePresetId: String = "",
        // Session chunking: after sessionMaxMinutes of cumulative managed-app
        // use, a sessionBreakMinutes hard break. 0 = disabled.
        val sessionMaxMinutes: Int = 0,
        val sessionBreakMinutes: Int = 0
    )

    fun getPolicy(): PolicyData {
        val snap = Tasks.await(
            db.collection("users").document(userId)
                .collection("policy").document("global").get()
        )
        val data = snap.data ?: mapOf<String, Any>()
        @Suppress("UNCHECKED_CAST")
        return PolicyData(
            dailyLimitMinutes = (data["dailyLimitMinutes"] as? Number)?.toInt() ?: 0,
            extensionMinutes = (data["extensionMinutes"] as? Number)?.toInt() ?: 15,
            dailyExtensionLimit = (data["dailyExtensionLimit"] as? Number)?.toInt() ?: 1,
            hardBlockAfterLimit = data["hardBlockAfterLimit"] as? Boolean ?: false,
            targetApps = (data["targetApps"] as? List<String>) ?: emptyList(),
            targetDomains = (data["targetDomains"] as? List<String>) ?: emptyList(),
            blockWindows = ((data["blockWindows"] as? List<Map<String, Any>>) ?: emptyList()).map { w ->
                mapOf(
                    "start" to (w["start"] as? String ?: "00:00"),
                    "end" to (w["end"] as? String ?: "23:59"),
                    "days" to ((w["days"] as? List<*>)?.map { (it as Number).toInt() } ?: (0..6).toList())
                )
            },
            blockedSurfaces = (data["blockedSurfaces"] as? Map<String, String>) ?: emptyMap(),
            reelDailyLimit = ((data["reelDailyLimit"] as? Map<String, Number>) ?: emptyMap())
                .mapValues { it.value.toInt() },
            appModes = (data["appModes"] as? Map<String, String>) ?: emptyMap(),
            presets = (data["presets"] as? Map<String, Map<String, Any>>) ?: emptyMap(),
            activePresetId = data["activePresetId"] as? String ?: "",
            sessionMaxMinutes = (data["sessionMaxMinutes"] as? Number)?.toInt() ?: 0,
            sessionBreakMinutes = (data["sessionBreakMinutes"] as? Number)?.toInt() ?: 0
        )
    }

    /**
     * Merge-writes only the given fields into policy/global. Each editor section
     * saves its own field set so the Reels card can never clobber Blocking-Rules
     * edits (or fields the Windows dashboard owns) and vice versa. List/map values
     * are replaced wholesale — that is the desired per-field semantics.
     */
    fun updatePolicyFields(fields: Map<String, Any>) {
        if (fields.isEmpty()) return
        Tasks.await(
            db.collection("users").document(userId)
                .collection("policy").document("global").set(fields, SetOptions.merge())
        )
    }

    // ── Weekly usage ──────────────────────────────────────────────────────────

    data class DayUsage(val date: String, val label: String, val usedMinutes: Double)

    fun getUsageWeek(): List<DayUsage> {
        val today = java.time.LocalDate.now()
        val dayNames = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val dates = (6 downTo 0).map { daysAgo -> today.minusDays(daysAgo.toLong()) }
        val dateStrs = dates.map { it.toString() }

        // Single whereIn query instead of 7 sequential document reads. whereIn
        // supports up to 10 values, so 7 dates is well within range. Missing
        // days simply don't come back in the snapshot — zero-fill them below.
        val snap = Tasks.await(
            db.collection("users").document(userId)
                .collection("usage_days")
                .whereIn(FieldPath.documentId(), dateStrs)
                .get()
        )
        val usedMinutesByDate = snap.documents.associate { doc ->
            // getDouble, not `as? Double`: Firestore hands back a Long for any value
            // written as an integer, and the cast would silently degrade that to 0.0.
            // The Windows agent writes these documents too, so the numeric type is
            // not fully under this client's control.
            doc.id to (doc.getDouble("usedMinutes") ?: 0.0)
        }

        return dates.map { date ->
            val dateStr = date.toString()
            // DayOfWeek value: 1=Mon..7=Sun → index 0=Sun..6=Sat via % 7
            DayUsage(dateStr, dayNames[date.dayOfWeek.value % 7], usedMinutesByDate[dateStr] ?: 0.0)
        }
    }

    // ── Focus session ─────────────────────────────────────────────────────────

    fun startFocusSession(durationMinutes: Int, reason: String = "deep-work"): FocusSessionInfo {
        val startsAt = java.time.Instant.now().toString()
        val endsAt = java.time.Instant.now().plusMillis(durationMinutes * 60_000L).toString()
        val data = mapOf("active" to true, "startsAt" to startsAt, "endsAt" to endsAt, "reason" to reason)
        Tasks.await(
            db.collection("users").document(userId)
                .collection("focus_session").document("current").set(data)
        )
        return FocusSessionInfo(true, endsAt)
    }

    fun stopFocusSession(): FocusSessionInfo {
        val data = mapOf("active" to false, "startsAt" to "", "endsAt" to "")
        Tasks.await(
            db.collection("users").document(userId)
                .collection("focus_session").document("current").set(data)
        )
        return FocusSessionInfo(false, "")
    }

    fun getFocusSession(): FocusSessionInfo {
        val snap = Tasks.await(
            db.collection("users").document(userId)
                .collection("focus_session").document("current").get()
        )
        val data = snap.data ?: mapOf<String, Any>()
        return FocusSessionInfo(
            active = data["active"] as? Boolean ?: false,
            endsAt = data["endsAt"] as? String ?: ""
        )
    }

    // ── Todos ─────────────────────────────────────────────────────────────────

    fun getTodos(): List<TodoItem> {
        val snap = Tasks.await(
            db.collection("users").document(userId).collection("todos").get()
        )
        return snap.documents.map { doc ->
            TodoItem(
                id = doc.id,
                text = doc.getString("text") ?: "",
                completed = doc.getBoolean("completed") ?: false
            )
        }
    }

    fun addTodo(text: String): TodoItem {
        val data = mapOf(
            "text" to text,
            "completed" to false,
            "createdAt" to java.time.Instant.now().toString()
        )
        val ref = Tasks.await(
            db.collection("users").document(userId).collection("todos").add(data)
        )
        return TodoItem(ref.id, text, false)
    }

    fun toggleTodo(todoId: String, completed: Boolean): Boolean {
        Tasks.await(
            db.collection("users").document(userId)
                .collection("todos").document(todoId).update("completed", completed)
        )
        return true
    }

    fun deleteTodo(todoId: String): Boolean {
        Tasks.await(
            db.collection("users").document(userId)
                .collection("todos").document(todoId).delete()
        )
        return true
    }
}
