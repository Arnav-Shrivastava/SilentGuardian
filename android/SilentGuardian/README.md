---

## 🧠 Week 3: Feature Extraction — SQLite → Daily Feature Vectors

### What Changed
Week 3 moves from raw signal collection to **behavioral summarization**. The Python 
feature extractor reads the SQLite database and converts raw events into one row per 
day — ready for the HMM routine modeller.

---

## 📂 Updated Project Structure
```text
SilentGuardian/
├── android/                        # Arnav — Android app (unchanged this week)
└── ml/
    ├── feature_extractor.py        # NEW: SQLite → daily feature vectors
    ├── routine_modeller.py         # NEW (Kunal): HMM trained on synthetic data
    ├── models/
    │   ├── hmm_baseline.pkl        # NEW (Kunal): trained HMM model
    │   └── threshold.json          # NEW (Kunal): anomaly score threshold
    ├── notebooks/
    │   ├── feature_visualization.ipynb    # NEW: visualize real phone data
    │   └── hmm_states_visualization.ipynb # NEW (Kunal): HMM states + transitions
    └── data/
        ├── synthetic_dataset.csv   # Kunal's Week 2 synthetic data
        └── real_features.csv       # OUTPUT of feature_extractor.py (gitignored)
```

---

## ✨ What's New in Week 3

### 1. Feature Extractor (`ml/feature_extractor.py`)
Reads `silentguardian.db` and produces a clean DataFrame with one row per day.

**5 features extracted per day:**

| Feature | How It's Computed |
|---|---|
| `first_unlock_hour` | Hour of first UNLOCK event where primary_val = 'unlock' |
| `call_count` | Total CALL events that day |
| `social_score` | (call_count × 2) + (whatsapp_count × 1) |
| `location_changes` | Number of times LOCATION flips home → away or away → home |
| `app_diversity` | Unique unlock sessions / total unlock events |

**No personal data in any feature** — no names, no numbers, no message content, 
no GPS coordinates. Purely behavioural counts and timestamps.

### 2. Routine Modeller (`ml/routine_modeller.py`) — Kunal
Trains a GaussianHMM with 3 hidden states on the synthetic dataset:
- **State 0:** Active day — early unlock, multiple calls, location changes
- **State 1:** Moderate day — average routine
- **State 2:** Inactive day — late unlock, few calls, no location changes

Anomaly threshold: `mean_train_score - 2 × std_train_score`  
Days scoring below this are flagged as anomalies.

---

## 📊 Feature Output Schema
```sql
-- real_features.csv (gitignored — real user data)
date, first_unlock_hour, call_count, app_diversity, 
social_score, location_changes, day_of_week
```

---

## 🔒 Privacy Note
The raw `.db` file is **never committed to GitHub** (covered by `.gitignore`).  
To export your database: tap **Export DB** in the app → share via WhatsApp → 
save to `ml/data/silentguardian.db` locally.

```bash
# Pull DB from phone (alternative method if on USB debug)
adb -s <device_id> pull /sdcard/Download/silentguardian.db ./ml/data/silentguardian.db
```

---

## 🧪 How to Test

1. Export DB from the app after 3–5 days of data collection
2. Save to `ml/data/silentguardian.db`
3. Run the feature extractor:
```bash
cd ml
python feature_extractor.py
```
4. Check output — `data/real_features.csv` should have one row per day with no NaN 
   values except possibly `first_unlock_hour` on days with no unlocks

---

## 🚀 Week 4: Build Agent 2 — Deviation Detector
- **Anomaly scorer:** Mahalanobis distance from baseline profile → score 0–1
- **Checkpoints:** 8 AM unlock, 9 AM call, 12 PM activity, 6 PM location change
- **False alarm filters:** Weekend, travel, DND context reducers
- **Escalation timer:** Score must stay > 0.7 for 30 consecutive minutes