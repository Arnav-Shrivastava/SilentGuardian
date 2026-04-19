package com.silentguardian

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.work.WorkInfo
import androidx.work.WorkManager

/**
 * MainActivity — the single screen of the SilentGuardian app.
 *
 * What it shows:
 *   1. Permission status banner (green = granted, red = needs action)
 *   2. WorkManager status (running / pending)
 *   3. A list of the last 10 SCREEN_UNLOCK events from SQLite
 *   4. A "Refresh" button to reload the list
 *   5. A "Run Now" button to trigger immediate data collection
 *
 * How it handles the PACKAGE_USAGE_STATS permission:
 *   This is a "special" permission — you can't request it with
 *   ActivityCompat.requestPermissions(). Instead you must send the user
 *   to Settings > Apps > Special app access > Usage access.
 *   We detect whether it's granted and show a banner with a deep-link button.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var db: DatabaseHelper

    // Views — we set these up programmatically so there's no XML dependency
    private lateinit var permissionBanner: LinearLayout
    private lateinit var permissionText: TextView
    private lateinit var grantPermissionBtn: Button
    private lateinit var statusText: TextView
    private lateinit var eventListLayout: LinearLayout
    private lateinit var refreshBtn: Button
    private lateinit var runNowBtn: Button
    private lateinit var emptyText: TextView

    // Permission request codes
    private val PERMISSION_REQUEST_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        db = DatabaseHelper(this)

        // Build the UI programmatically
        setContentView(buildLayout())

        // Step 1: check permission and update banner
        updatePermissionBanner()

        // Step 2: schedule the background worker (KEEP policy = safe to call repeatedly)
        UsageCollectorWorker.schedule(this)

        // Step 3: observe WorkManager status
        observeWorkerStatus()

        // Step 4: load and display the last 10 unlock events
        refreshEventList()
    }

    override fun onResume() {
        super.onResume()
        // Re-check permission every time user comes back (they may have just granted it)
        updatePermissionBanner()
        refreshEventList()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Permission handling
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * PACKAGE_USAGE_STATS is a "special" permission in the AppOps system.
     * We check it via AppOpsManager, not ContextCompat.checkSelfPermission().
     */
    private fun hasUsagePermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun hasRuntimePermissions(): Boolean {
        val callLog = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CALL_LOG)
        val location = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION)
        return callLog == android.content.pm.PackageManager.PERMISSION_GRANTED &&
               location == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val pkgName = packageName
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (!flat.isNullOrEmpty()) {
            val names = flat.split(":")
            for (name in names) {
                val cn = android.content.ComponentName.unflattenFromString(name)
                if (cn != null && cn.packageName == pkgName) return true
            }
        }
        return false
    }

    private fun updatePermissionBanner() {
        val usageOk = hasUsagePermission()
        val runtimeOk = hasRuntimePermissions()
        val notifyOk = isNotificationServiceEnabled()

        if (usageOk && runtimeOk && notifyOk) {
            permissionBanner.setBackgroundColor(0xFF1B5E20.toInt()) // dark green
            permissionText.text = "✅  All permissions granted — collection active"
            permissionText.setTextColor(0xFFA5D6A7.toInt())
            grantPermissionBtn.visibility = View.GONE
        } else {
            permissionBanner.setBackgroundColor(0xFF7F0000.toInt()) // dark red
            val sb = StringBuilder()
            if (!usageOk) sb.append("⚠️  Usage access NOT granted\n")
            if (!runtimeOk) sb.append("⚠️  Call Log/Location NOT granted\n")
            if (!notifyOk) sb.append("⚠️  Notification access NOT granted\n")
            sb.append("Tap below to fix")
            permissionText.text = sb.toString()
            permissionText.setTextColor(0xFFFFCDD2.toInt())
            grantPermissionBtn.visibility = View.VISIBLE
            
            grantPermissionBtn.text = when {
                !usageOk -> "Fix Usage Access"
                !runtimeOk -> "Grant Runtime Permissions"
                else -> "Enable Notification Access"
            }
            grantPermissionBtn.setOnClickListener {
                when {
                    !usageOk -> openUsageAccessSettings()
                    !runtimeOk -> requestRuntimePermissions()
                    else -> openNotificationAccessSettings()
                }
            }
        }
    }

    private fun openNotificationAccessSettings() {
        startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
    }

    private fun requestRuntimePermissions() {
        androidx.core.app.ActivityCompat.requestPermissions(
            this,
            arrayOf(
                android.Manifest.permission.READ_CALL_LOG,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updatePermissionBanner()
    }

    /**
     * Opens the system "Usage access" settings screen where the user can
     * toggle permission for SilentGuardian.
     */
    private fun openUsageAccessSettings() {
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WorkManager status
    // ─────────────────────────────────────────────────────────────────────────

    private fun observeWorkerStatus() {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(UsageCollectorWorker.WORK_NAME)
            .observe(this) { workInfos ->
                if (workInfos.isNullOrEmpty()) {
                    statusText.text = "Worker: not scheduled"
                    return@observe
                }
                val info = workInfos[0]
                val stateLabel = when (info.state) {
                    WorkInfo.State.ENQUEUED   -> "⏳ Waiting to run"
                    WorkInfo.State.RUNNING    -> "🔄 Running now"
                    WorkInfo.State.SUCCEEDED  -> "✅ Last run succeeded"
                    WorkInfo.State.FAILED     -> "❌ Last run failed — retrying"
                    WorkInfo.State.BLOCKED    -> "🚫 Blocked by constraints"
                    WorkInfo.State.CANCELLED  -> "⛔ Cancelled"
                    else                      -> info.state.name
                }
                statusText.text = "Worker: $stateLabel  |  Runs every 30 min"
            }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Event list display
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reads the last 10 SCREEN_UNLOCK events from SQLite and renders them
     * as a simple vertical list of cards.
     */
    private fun refreshEventList() {
        val events = db.getRecentEvents(
            eventType = null, // Fetch all types
            limit = 20
        )

        eventListLayout.removeAllViews()

        if (events.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            return
        }

        emptyText.visibility = View.GONE

        events.forEach { event ->
            val card = buildEventCard(event)
            eventListLayout.addView(card)
        }
    }

    private fun buildEventCard(event: EventRow): View {
        val emoji = when (event.eventType) {
            DatabaseHelper.EVENT_SCREEN_UNLOCK -> if (event.value == "unlock") "🔓" else "🔒"
            DatabaseHelper.EVENT_CALL -> "📞"
            DatabaseHelper.EVENT_CHARGE -> "⚡"
            DatabaseHelper.EVENT_LOCATION -> "📍"
            DatabaseHelper.EVENT_NOTIFICATION -> "💬"
            else -> "🔹"
        }

        val title = when (event.eventType) {
            DatabaseHelper.EVENT_SCREEN_UNLOCK -> if (event.value == "unlock") "Screen Unlocked" else "Screen Locked"
            DatabaseHelper.EVENT_CALL -> "Call Event"
            DatabaseHelper.EVENT_CHARGE -> "Power State"
            DatabaseHelper.EVENT_LOCATION -> "Location Sync"
            DatabaseHelper.EVENT_NOTIFICATION -> "WhatsApp Activity"
            else -> event.eventType
        }

        val cardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(0xFF1A1A2E.toInt())
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(6)) }
            layoutParams = params
        }

        val emojiView = TextView(this).apply {
            text = emoji
            textSize = 20f
            setPadding(0, 0, dp(12), 0)
        }

        val textLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val actionText = TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(0xFFE0E0E0.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val detailText = TextView(this).apply {
            text = event.value
            textSize = 12f
            setTextColor(0xFFB0BEC5.toInt())
        }

        val timeText = TextView(this).apply {
            text = "${event.timestamp} • ${event.dayOfWeek}"
            textSize = 11f
            setTextColor(0xFF7986CB.toInt()) // indigo accent
        }

        textLayout.addView(actionText)
        textLayout.addView(detailText)
        textLayout.addView(timeText)

        cardLayout.addView(emojiView)
        cardLayout.addView(textLayout)

        return cardLayout
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Build layout programmatically (no XML dependency)
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildLayout(): View {
        val scroll = android.widget.ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0D0D1A.toInt()) // very dark navy
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        scroll.addView(root)

        // ── App title ──────────────────────────────────────────────────────
        val titleText = TextView(this).apply {
            text = "🛡️  SilentGuardian"
            textSize = 22f
            setTextColor(0xFFE8EAF6.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(4)) }
        }
        val subtitleText = TextView(this).apply {
            text = "Elderly safety monitor — signal collection layer"
            textSize = 12f
            setTextColor(0xFF7986CB.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(20)) }
        }
        root.addView(titleText)
        root.addView(subtitleText)

        // ── Permission banner ──────────────────────────────────────────────
        permissionBanner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(16)) }
        }
        permissionText = TextView(this).apply {
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8)) }
        }
        grantPermissionBtn = Button(this).apply {
            text = "Open Usage Access Settings →"
            setOnClickListener { openUsageAccessSettings() }
        }
        permissionBanner.addView(permissionText)
        permissionBanner.addView(grantPermissionBtn)
        root.addView(permissionBanner)

        // ── Worker status ──────────────────────────────────────────────────
        val statusLabel = TextView(this).apply {
            text = "BACKGROUND WORKER"
            textSize = 10f
            setTextColor(0xFF9E9E9E.toInt())
            letterSpacing = 0.1f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(4)) }
        }
        statusText = TextView(this).apply {
            text = "Worker: checking..."
            textSize = 13f
            setTextColor(0xFFB0BEC5.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(16)) }
        }
        root.addView(statusLabel)
        root.addView(statusText)

        // ── Action buttons ─────────────────────────────────────────────────
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(20)) }
        }
        refreshBtn = Button(this).apply {
            text = "↻  Refresh"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(0, 0, dp(8), 0) }
            setOnClickListener { refreshEventList() }
        }
        runNowBtn = Button(this).apply {
            text = "▶  Run Now"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                UsageCollectorWorker.runNow(this@MainActivity)
                Toast.makeText(this@MainActivity, "Collecting signals...", Toast.LENGTH_SHORT).show()
                // Refresh list after a short delay so new data appears
                android.os.Handler(mainLooper).postDelayed({ refreshEventList() }, 2000)
            }
        }
        buttonRow.addView(refreshBtn)
        buttonRow.addView(runNowBtn)
        root.addView(buttonRow)

        // ── Events list ────────────────────────────────────────────────────
        val eventsLabel = TextView(this).apply {
            text = "LAST 20 BEHAVIORAL SIGNALS"
            textSize = 10f
            setTextColor(0xFF9E9E9E.toInt())
            letterSpacing = 0.1f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(10)) }
        }
        root.addView(eventsLabel)

        emptyText = TextView(this).apply {
            text = "No unlock events yet.\nTap 'Run Now' to collect your first data point."
            textSize = 13f
            setTextColor(0xFF616161.toInt())
            setPadding(0, dp(20), 0, 0)
            visibility = View.GONE
        }
        root.addView(emptyText)

        eventListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(eventListLayout)

        return scroll
    }

    // Helper: convert dp to pixels
    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
