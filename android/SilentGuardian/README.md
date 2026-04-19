# 🛡️ SilentGuardian — Week 2: Signal Expansion

## Project Overview: Layer 1 Evolution
Week 2 focuses on extending the collection engine to gather **behavioral context**. By moving beyond simple screen-lock tracking, we are preparing the groundwork for Week 3’s Anomaly Detection. We now answer three critical questions:
* **Where are you?** (Location context)
* **Who are you talking to?** (Social/Call activity)
* **Is your device healthy?** (Battery/Power state)

---

## 📂 Updated Project Structure
```text
app/src/main/
├── AndroidManifest.xml        # Added: Location, Call Log, and Notification permissions
└── java/com/silentguardian/
    ├── DatabaseHelper.kt      # Updated: Generic schema to handle multi-type signals
    ├── UsageCollectorWorker.kt# Updated: Aggregates Location, Battery, and Calls
    ├── NotificationMonitor.kt # NEW: Service to monitor messaging activity (WhatsApp)
    ├── BootReceiver.kt        # Reschedules Worker and Notification Service on startup
    └── MainActivity.kt        # Updated: Permission Dashboard + Multi-type event feed
```

---

## ✨ What’s New in Week 2

### 1. Unified Event Feed
The UI now aggregates diverse signals into a single chronological timeline.
* 📞 **Call Event:** Logs call types (incoming/outgoing/missed). *Note: Numbers are hashed for privacy.*
* ⚡ **Power State:** Logs battery percentage and charging status.
* 📍 **Location Sync:** Periodic lat/long snapshots to establish "Safe Zones."
* 💬 **WhatsApp Activity:** Increments a counter via Notification Access (reads metadata only).
* 🔓 **Screen Activity:** Legacy tracking for unlock/lock events.

### 2. Multi-Stage Permission Manager
To handle sensitive data access, a new **Permission Dashboard** handles:
* **Location:** "Allow all the time" required for background breadcrumbs.
* **Call Logs:** Used to detect unusual social patterns (e.g., late-night calls).
* **Notification Access:** Required to bridge the gap for apps like WhatsApp that don't share usage stats easily.

### 3. Smart Triggering
`UsageCollectorWorker` now runs every 15–30 minutes, but also forces an immediate save if:
* The battery drops below **15%**.
* A phone call ends.

---

## 📊 Database Schema
The SQLite table has been redesigned to be **polymorphic**, allowing flexible storage for different signal types.

```sql
CREATE TABLE events (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp     DATETIME DEFAULT CURRENT_TIMESTAMP,
    event_type    TEXT NOT NULL,   -- "CALL", "BATTERY", "LOCATION", "WHATSAPP", "UNLOCK"
    primary_val   TEXT,            -- e.g., "Incoming", "85%", "51.5074,-0.1278"
    secondary_val TEXT,            -- e.g., "Duration: 5m", "Charging", "Accuracy: 10m"
    day_of_week   TEXT NOT NULL    -- "MONDAY"
);
```

---

## 💻 Implementation Highlights (Kotlin)

### Call Log Collection
Added to `UsageCollectorWorker`:
```kotlin
val cursor = context.contentResolver.query(CallLog.Calls.CONTENT_URI, null, null, null, CallLog.Calls.DATE + " DESC")
// Capture last call type and timestamp...
db.insertEvent("CALL", callType, "Duration: $duration")
```

### Battery State Observer
```kotlin
val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
val status = if(isPlugged) "Charging" else "Discharging"
db.insertEvent("BATTERY", "$level%", status)
```

---

## 🧪 How to Test
1.  **Sync:** Pull the `arnav/week-2` branch and sync Gradle.
2.  **Permissions Marathon:** Open the app and address the banners:
    * **Notification Access:** Enable for SilentGuardian in System Settings.
    * **Location:** Set to "Allow all the time."
3.  **Generate Data:**
    * Place a short call or send/receive a WhatsApp message.
    * Plug or unplug your charger.
4.  **Verify:** Tap **Refresh** in the app. You should see a mix of icons (📞, ⚡, 📍) in the feed.

---

## 🚀 Week 3: Build and Train Agent 1 — Routine Modeller
* **Feature Extraction:** Build extractor in Python (raw SQLite log $\rightarrow$ daily feature vector).
* **Validation:** Test on your own 5-day phone data and visualize the vectors.