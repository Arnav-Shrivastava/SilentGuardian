# SilentGuardian — Week 1 Android App

**What this is:** Layer 1 (Signal Collection) of the SilentGuardian architecture.
A background Android service that silently logs phone unlock events to SQLite.

---

## Files

```
app/src/main/
├── AndroidManifest.xml          ← declares permissions + components
└── java/com/silentguardian/
    ├── DatabaseHelper.kt        ← SQLite: schema, insert, query, purge
    ├── UsageCollectorWorker.kt  ← WorkManager worker: runs every 30 mins, reads UsageStatsManager
    ├── BootReceiver.kt          ← Reschedules worker after device reboot
    └── MainActivity.kt          ← UI: permission banner + last 10 unlock events
```

---

## How to Run

1. Open Android Studio → File → Open → select the `SilentGuardian/` folder
2. Wait for Gradle sync to complete (~2 min first time)
3. Plug in your Android phone (USB debugging on) or start an emulator
4. Hit the green ▶ Run button
5. **Grant permission manually:**
   - App shows a red "Usage access NOT granted" banner
   - Tap "Open Usage Access Settings"
   - Find "SilentGuardian" and toggle it ON
   - Hit back — banner turns green

6. Tap **"Run Now"** to trigger immediate collection
7. Unlock your phone 2-3 times, tap **"Refresh"** — events appear in the list

---

## SQLite Schema

```sql
CREATE TABLE events (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp    TEXT NOT NULL,   -- "2026-04-19 07:42:00"
    event_type   TEXT NOT NULL,   -- "SCREEN_UNLOCK"
    value        TEXT,            -- "unlock" or "lock"
    day_of_week  TEXT NOT NULL    -- "MONDAY"
)
```

---

## How to Explain This to Judges

**"How does the background collection work?"**

> "We use Android's WorkManager to schedule a job that runs every 30 minutes.
> WorkManager is battery-efficient — it uses the operating system's job scheduler
> under the hood, so Android doesn't kill it. Each time it runs, it reads the
> UsageStatsManager API for any KEYGUARD_HIDDEN events — that's Android's name
> for 'phone unlocked'. We write those to a local SQLite database with a timestamp
> and day of week. Nothing leaves the phone."

**"Why not just use a broadcast receiver for screen unlock?"**

> "ACTION_USER_PRESENT broadcasts work but Android can kill broadcast receivers
> in background on newer Android versions. WorkManager is the robust solution —
> it's guaranteed to run, retries on failure, and survives reboots. For a safety
> monitoring app, reliability is non-negotiable."

**"What data are you actually storing?"**

> "Just: 'phone was unlocked at 7:42 AM on a Monday.' No app names, no message
> content, no location. The timestamp and day are everything we need to learn
> 'grandma normally unlocks at 7 AM' and detect 'today it's noon and no unlock.'"

---

## What's Next (Week 2)

Extend `UsageCollectorWorker` to also collect:
- Call log events (CallLog.Calls API)
- Battery charging/uncharging events
- App session diversity (UsageStatsManager queryUsageStats)
- Coarse location changes (cell tower level)

All added to the same SQLite `events` table with different `event_type` values.
