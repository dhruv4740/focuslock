package com.focuslock.data

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
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
        val userUsedMinutes: Double
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

    private val db: FirebaseFirestore
        get() = FirebaseFirestore.getInstance()

    // ── Policy ────────────────────────────────────────────────────────────────

    fun fetchPolicy(): JSONObject {
        val snap = Tasks.await(
            db.collection("users").document(userId)
                .collection("policy").document("global").get()
        )
        val data = snap.data ?: return JSONObject()
        return JSONObject().apply {
            put("targetApps", JSONArray(data["targetApps"] as? List<String> ?: emptyList<String>()))
            put("targetDomains", JSONArray(data["targetDomains"] as? List<String> ?: emptyList<String>()))
        }
    }

    // ── Evaluation (three reads — uses offline cache when network is absent) ──

    fun evaluate(date: String): Decision {
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
        val now = java.time.Instant.now().toString()
        val focusStillRunning = focusActive && focusEndsAt.isNotEmpty() && focusEndsAt > now

        @Suppress("UNCHECKED_CAST")
        val blockWindows = policy["blockWindows"] as? List<Map<String, Any>> ?: emptyList()
        val inBlockWindow = blockWindows.any { w ->
            val startStr = w["start"] as? String ?: "00:00"
            val endStr   = w["end"]   as? String ?: "23:59"
            @Suppress("UNCHECKED_CAST")
            val days = (w["days"] as? List<Number>)?.map { it.toInt() } ?: (0..6).toList()
            val cal = java.util.Calendar.getInstance()
            val dayOfWeek = cal.get(java.util.Calendar.DAY_OF_WEEK) - 1 // 0=Sun
            val currentMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
            val (sh, sm) = startStr.split(":").map { it.toInt() }
            val (eh, em) = endStr.split(":").map { it.toInt() }
            dayOfWeek in days && currentMin in (sh * 60 + sm) until (eh * 60 + em)
        }

        val dailyLimitMinutes = (policy["dailyLimitMinutes"] as? Number)?.toInt() ?: 0
        val extensionMinutes = (policy["extensionMinutes"] as? Number)?.toInt() ?: 15
        val hardBlockAfterLimit = policy["hardBlockAfterLimit"] as? Boolean ?: false

        // Keep precision as Double throughout; avoid Int truncation
        val usedMinutes = usage["usedMinutes"] as? Double ?: 0.0
        val extensionsUsedToday = (usage["extensionsUsedToday"] as? Number)?.toInt() ?: 0
        val effectiveLimit = dailyLimitMinutes + extensionsUsedToday * extensionMinutes

        if (focusStillRunning || inBlockWindow) {
            return Decision(
                allowNow = false,
                action = "hard-block",
                reason = if (focusStillRunning) "focus-session-active" else "scheduled-block-window",
                effectiveLimit = 0,
                usedMinutes = usedMinutes,
                userUsedMinutes = usedMinutes
            )
        }

        val allowNow = effectiveLimit == 0 || usedMinutes < effectiveLimit

        if (!allowNow && hardBlockAfterLimit) {
            return Decision(
                allowNow = false,
                action = "hard-block",
                reason = "daily-limit-reached",
                effectiveLimit = effectiveLimit,
                usedMinutes = usedMinutes,
                userUsedMinutes = usedMinutes
            )
        }

        return Decision(
            allowNow = allowNow,
            action = "none",
            reason = "within-limit",
            effectiveLimit = effectiveLimit,
            usedMinutes = usedMinutes,
            userUsedMinutes = usedMinutes
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

        return JSONObject().apply {
            put("usedMinutes", usedMinutes)
            put("opensToday", opensToday)
        }
    }

    // ── Policy read/write ─────────────────────────────────────────────────────

    data class PolicyData(
        val dailyLimitMinutes: Int,
        val extensionMinutes: Int,
        val dailyExtensionLimit: Int,
        val hardBlockAfterLimit: Boolean,
        val targetApps: List<String>,
        val targetDomains: List<String>,
        val blockWindows: List<Map<String, Any>>
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
            }
        )
    }

    fun updatePolicy(policy: PolicyData) {
        val data = mapOf(
            "dailyLimitMinutes" to policy.dailyLimitMinutes,
            "extensionMinutes" to policy.extensionMinutes,
            "dailyExtensionLimit" to policy.dailyExtensionLimit,
            "hardBlockAfterLimit" to policy.hardBlockAfterLimit,
            "targetApps" to policy.targetApps,
            "targetDomains" to policy.targetDomains,
            "blockWindows" to policy.blockWindows.map { w ->
                mapOf(
                    "start" to (w["start"] as? String ?: "00:00"),
                    "end" to (w["end"] as? String ?: "23:59"),
                    "days" to ((w["days"] as? List<*>)?.map { (it as Number).toInt() } ?: (0..6).toList())
                )
            }
        )
        Tasks.await(
            db.collection("users").document(userId)
                .collection("policy").document("global").set(data)
        )
    }

    // ── Weekly usage ──────────────────────────────────────────────────────────

    data class DayUsage(val date: String, val label: String, val usedMinutes: Double)

    fun getUsageWeek(): List<DayUsage> {
        val today = java.time.LocalDate.now()
        val dayNames = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        return (6 downTo 0).map { daysAgo ->
            val date = today.minusDays(daysAgo.toLong())
            val dateStr = date.toString()
            val snap = Tasks.await(
                db.collection("users").document(userId)
                    .collection("usage_days").document(dateStr).get()
            )
            val usedMinutes = snap.data?.get("usedMinutes") as? Double ?: 0.0
            // DayOfWeek value: 1=Mon..7=Sun → index 0=Sun..6=Sat via % 7
            DayUsage(dateStr, dayNames[date.dayOfWeek.value % 7], usedMinutes)
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
