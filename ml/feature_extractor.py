"""
feature_extractor.py
--------------------
Extracts per-day behavioural features from the SilentGuardian SQLite database
and writes them to ml/data/real_features.csv.

No ML libraries are used — pure pandas + sqlite3.
"""

import sqlite3
import math
import pandas as pd
from pathlib import Path

# ── Configuration ────────────────────────────────────────────────────────────
# Adjust DB_PATH if the database lives elsewhere on your machine.
DB_PATH = "data/silentguardian_export.db"

OUTPUT_DIR = Path(__file__).parent / "data"
OUTPUT_PATH = OUTPUT_DIR / "real_features.csv"
# ─────────────────────────────────────────────────────────────────────────────


# ── Helper functions ──────────────────────────────────────────────────────────

def _first_unlock_hour(unlock_events: pd.DataFrame) -> float:
    """
    Return the hour (0-23, float) of the first UNLOCK event whose
    primary_val is 'unlock'.  Returns NaN when no such event exists.
    """
    unlocks = unlock_events[unlock_events["primary_val"].str.lower() == "unlock"]
    if unlocks.empty:
        return float("nan")
    first_ts = pd.to_datetime(unlocks["timestamp"].iloc[0])
    return float(first_ts.hour)


def _app_diversity(unlock_events: pd.DataFrame) -> float:
    """
    Compute app_diversity as  unique_sessions / total_unlocks.

    A session = a consecutive unlock→lock pair.
    'unique_sessions' counts how many such pairs exist.
    'total_unlocks'   counts rows where primary_val == 'unlock'.

    Returns 0.0 when there are no UNLOCK events at all.
    """
    if unlock_events.empty:
        return 0.0

    total_unlocks = (
        unlock_events["primary_val"].str.lower() == "unlock"
    ).sum()

    if total_unlocks == 0:
        return 0.0

    # Count lock/unlock pairs by scanning events in timestamp order.
    sorted_events = unlock_events.sort_values("timestamp")
    session_count = 0
    in_session = False

    for val in sorted_events["primary_val"].str.lower():
        if val == "unlock" and not in_session:
            in_session = True
        elif val == "lock" and in_session:
            session_count += 1
            in_session = False

    # A session that never received a matching lock still counts.
    if in_session:
        session_count += 1

    return round(session_count / total_unlocks, 6)


def _location_changes(location_events: pd.DataFrame) -> int:
    """
    Count the number of times primary_val flips between 'home' and 'away'
    in LOCATION events ordered by timestamp.
    """
    if location_events.empty:
        return 0

    sorted_locs = (
        location_events
        .sort_values("timestamp")["primary_val"]
        .str.lower()
    )

    # Keep only recognised values so noise doesn't trigger spurious flips.
    recognised = sorted_locs[sorted_locs.isin(["home", "away"])].tolist()

    if len(recognised) < 2:
        return 0

    changes = sum(
        1 for a, b in zip(recognised, recognised[1:]) if a != b
    )
    return changes


# ── Core extraction ───────────────────────────────────────────────────────────

def load_events(db_path: str) -> pd.DataFrame:
    """Load every event row from the SQLite database."""
    db_path = Path(db_path).resolve()
    if not db_path.exists():
        raise FileNotFoundError(
            f"Database not found at: {db_path}\n"
            "Please update DB_PATH at the top of this script."
        )

    conn = sqlite3.connect(db_path)
    try:
        df = pd.read_sql_query(
            "SELECT id, timestamp, event_type, primary_val, secondary_val, day_of_week "
            "FROM events ORDER BY timestamp ASC",
            conn,
        )
    finally:
        conn.close()

    # Normalise timestamp to a proper datetime so we can extract the date.
    df["timestamp"] = pd.to_datetime(df["timestamp"], errors="coerce")
    df["date"] = df["timestamp"].dt.strftime("%Y-%m-%d")

    return df


def extract_features(df: pd.DataFrame) -> pd.DataFrame:
    """
    Build one feature row per calendar day found in *df*.

    Missing days within the overall date range are inserted with NaN values.
    """
    if df.empty:
        print("WARNING: No events found in the database.")
        return pd.DataFrame(
            columns=[
                "date", "first_unlock_hour", "call_count",
                "app_diversity", "social_score", "location_changes",
                "day_of_week",
            ]
        )

    # Build the full date range so gaps are visible.
    all_dates = pd.date_range(
        start=df["date"].min(),
        end=df["date"].max(),
        freq="D",
    )
    all_dates_str = [d.strftime("%Y-%m-%d") for d in all_dates]

    rows = []

    for date_str in all_dates_str:
        day_df = df[df["date"] == date_str]

        if day_df.empty:
            # Missing day — fill with NaN / 0 as specified.
            rows.append(
                {
                    "date": date_str,
                    "first_unlock_hour": float("nan"),
                    "call_count": 0,
                    "app_diversity": float("nan"),
                    "social_score": 0,
                    "location_changes": 0,
                    "day_of_week": pd.Timestamp(date_str).day_name(),
                }
            )
            continue

        # ── Slice by event type ──────────────────────────────────────────
        unlock_df   = day_df[day_df["event_type"] == "UNLOCK"].reset_index(drop=True)
        call_df     = day_df[day_df["event_type"] == "CALL"].reset_index(drop=True)
        location_df = day_df[day_df["event_type"] == "LOCATION"].reset_index(drop=True)
        whatsapp_df = day_df[day_df["event_type"] == "WHATSAPP"].reset_index(drop=True)

        # ── Features ────────────────────────────────────────────────────
        call_count      = len(call_df)
        whatsapp_count  = len(whatsapp_df)

        first_unlock    = _first_unlock_hour(unlock_df)
        diversity       = _app_diversity(unlock_df)
        social          = (call_count * 2) + (whatsapp_count * 1)
        loc_changes     = _location_changes(location_df)

        # day_of_week: prefer the value stored in the DB, fall back to computed.
        dow = (
            day_df["day_of_week"].dropna().iloc[0]
            if not day_df["day_of_week"].dropna().empty
            else pd.Timestamp(date_str).day_name()
        )

        rows.append(
            {
                "date": date_str,
                "first_unlock_hour": first_unlock,
                "call_count": call_count,
                "app_diversity": diversity,
                "social_score": social,
                "location_changes": loc_changes,
                "day_of_week": dow,
            }
        )

    result = pd.DataFrame(rows).sort_values("date").reset_index(drop=True)

    # Enforce column order.
    result = result[
        [
            "date", "first_unlock_hour", "call_count",
            "app_diversity", "social_score", "location_changes",
            "day_of_week",
        ]
    ]

    return result


# ── Summary printing ──────────────────────────────────────────────────────────

def print_summary(features: pd.DataFrame) -> None:
    total_days = len(features)
    print(f"\n{'─'*50}")
    print(f"  SilentGuardian — Feature Extraction Summary")
    print(f"{'─'*50}")
    print(f"  Days extracted      : {total_days}")

    if total_days == 0:
        print("  (no data)\n")
        return

    print(f"  Date range          : {features['date'].iloc[0]}  →  {features['date'].iloc[-1]}")

    # Days with no unlock data.
    no_unlock = features["first_unlock_hour"].isna().sum()
    if no_unlock:
        print(f"  Days w/ no unlocks  : {no_unlock}  (first_unlock_hour = NaN)")

    # Days with no location data.
    no_loc = (features["location_changes"] == 0).sum()
    print(f"  Days w/ 0 loc chgs  : {no_loc}")

    # Days where EVERY feature is NaN / 0 (fully missing days).
    numeric_cols = ["first_unlock_hour", "call_count", "app_diversity",
                    "social_score", "location_changes"]
    missing_mask = (
        features["first_unlock_hour"].isna()
        & (features["call_count"] == 0)
        & features["app_diversity"].isna()
        & (features["social_score"] == 0)
        & (features["location_changes"] == 0)
    )
    missing_days = missing_mask.sum()
    if missing_days:
        missing_dates = features.loc[missing_mask, "date"].tolist()
        print(f"\n  ⚠  Fully-missing days ({missing_days}):")
        for d in missing_dates:
            print(f"       {d}")

    print(f"{'─'*50}\n")


# ── Entry point ───────────────────────────────────────────────────────────────

def main():
    print(f"Loading events from: {Path(DB_PATH).resolve()}")
    events = load_events(DB_PATH)
    print(f"  → {len(events):,} event rows loaded.")

    features = extract_features(events)

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    features.to_csv(OUTPUT_PATH, index=False)
    print(f"\nFeatures saved → {OUTPUT_PATH}")

    print_summary(features)

    return features  # handy when imported as a module


if __name__ == "__main__":
    main()
