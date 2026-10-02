# Scroll-Strava: Technical Implementation Plan & Algorithm Specification

This document provides the full technical specification and architectural roadmap for integrating the **Habit Taper Index (HTI)** de-addiction engine into the **Scroll-Strava** platform.

---

## 1. Metric Mapping: Strava to De-Addiction Mechanics

Scroll-Strava translates physical athletic metrics into scrolling telemetry. Rather than treating screen time as a single flat duration, every session is ingested as an "Activity" with cadence, intensity, and focus scores.

| Strava Athletic Metric | Scroll-Strava Behavioral Equivalent | Telemetry Formula | De-Addiction Significance |
| --- | --- | --- | --- |
| **Cadence (RPM)** | **Scroll Cadence (SPM)** | $\text{SPM} = \frac{\text{Stroke Count}}{\text{Session Duration (mins)}}$ | Measures interaction speed. Low SPM ($< 10$) = deliberate reading; High SPM ($> 25$) = frantic dopamine-seeking. |
| **Suffer Score / Intensity** | **Agitation Ratio ($F_{\text{agitation}}$)** | $\frac{\text{Time with SPM } > 25}{\text{Total Active Time}} \times 100$ | Quantifies neurological over-stimulation during interaction loops. |
| **Pace / Split Times** | **Dwell Time per Stroke** | $\text{Dwell Time} = \frac{\text{Active Scroll Time (sec)}}{\text{Total Strokes}}$ | High dwell time indicates long pauses spent digesting content rather than flicking. |
| **Interval Training** | **Session Classification** | *Micro-Check* ($< 1\text{m}$), *Standard*, *Marathon* ($> 20\text{m}$) | Exposes whether usage occurs in quick compulsive pops or long, uncontrolled benders. |
| **Recovery / Rest Score** | **Habit Taper Index ($\text{HTI}_d$)** | Composite $0\text{--}100$ daily rating vs. $M_{7\text{d}}$ baseline | Master progress score. Higher scores reflect self-control, longer inter-session gaps, and tapered total volume. |

---

## 2. Core Algorithm Specification: Habit Taper Index ($\text{HTI}_d$)

### Master Equation

$$\text{HTI}_d = \max\left(0, \min\left(100, 100 - \left[ 0.35 \cdot F_{\text{volume}} + 0.30 \cdot F_{\text{compulsion}} + 0.20 \cdot F_{\text{marathon}} + 0.15 \cdot F_{\text{agitation}} \right]\right)\right)$$

### Sub-Factor Calculations

#### A. Volume Surge Factor ($F_{\text{volume}}$)

Evaluates total active duration relative to the user's **7-day rolling median** ($M_{7\text{d}}$), neutralizing single-day extreme outliers:

$$M_{7\text{d}}(T) = \text{Median}\left(T_{d-7}, T_{d-6}, \dots, T_{d-1}\right)$$

$$F_{\text{volume}} = \max\left(0, \frac{T_d - M_{7\text{d}}(T)}{M_{7\text{d}}(T)}\right) \times 100$$

*Where $T_d$ is total active scroll time on day $d$.*

#### B. Compulsion & Relapse Factor ($F_{\text{compulsion}}$)

Measures involuntary habit loops by combining **Rapid Re-Entries** ($RR$, inter-session gaps $< 5\text{ mins}$) and **Micro-Checks** ($MC$, session duration $< 60\text{s}$):

$$F_{\text{compulsion}} = \left( \frac{RR_d + 0.5 \cdot MC_d}{\max(1, V_d)} \right) \times 100$$

*Where $V_d$ is total app visits/launches on day $d$.*

#### C. Marathon Session Ratio Factor ($F_{\text{marathon}}$)

Quantifies temporal dissociation by tracking continuous sessions exceeding 20 minutes ($1,200\text{ seconds}$):

$$F_{\text{marathon}} = \left( \frac{MT_d}{\max(1, T_d)} \right) \times 100$$

*Where $MT_d$ is total time accumulated within sessions lasting $> 20\text{ mins}$.*

#### D. Scroll Agitation Factor ($F_{\text{agitation}}$)

Measures physical hyperactivity and frantic flicking:

$$F_{\text{agitation}} = \left( \frac{AT_d}{\max(1, T_d)} \right) \times 100$$

*Where $AT_d$ is time accumulated while scrolling at speeds exceeding 25 Strokes Per Minute ($\text{SPM} > 25$).*

---

## 3. Local Data Model & Database Schema

To support privacy-first, on-device telemetry and Strava-like activity logs, store session payloads and daily rollups locally using SQL/Room/SQLite.

### A. Session/Activity Table (`scroll_activities`)

```sql
CREATE TABLE scroll_activities (
    activity_id TEXT PRIMARY KEY,
    target_app_package TEXT NOT NULL,
    start_timestamp INTEGER NOT NULL,      -- Epoch milliseconds
    end_timestamp INTEGER NOT NULL,        -- Epoch milliseconds
    duration_seconds INTEGER NOT NULL,
    stroke_count INTEGER NOT NULL,
    avg_cadence_spm REAL NOT NULL,
    agitation_duration_seconds INTEGER NOT NULL,
    is_micro_check INTEGER DEFAULT 0,      -- 1 if duration < 60s
    is_marathon INTEGER DEFAULT 0,         -- 1 if duration > 1200s
    gap_from_previous_seconds INTEGER,     -- Time elapsed since last session
    is_rapid_reentry INTEGER DEFAULT 0     -- 1 if gap < 300s
);

```

### B. Daily Rollup Table (`daily_habit_rollups`)

```sql
CREATE TABLE daily_habit_rollups (
    date_key TEXT PRIMARY KEY,             -- YYYY-MM-DD
    total_active_seconds INTEGER NOT NULL,
    total_visits INTEGER NOT NULL,
    rapid_reentry_count INTEGER NOT NULL,
    micro_check_count INTEGER NOT NULL,
    marathon_duration_seconds INTEGER NOT NULL,
    agitation_duration_seconds INTEGER NOT NULL,
    rolling_7d_median_seconds INTEGER NOT NULL,
    hti_score REAL NOT NULL,               -- Calculated HTI (0 - 100)
    last_updated_timestamp INTEGER NOT NULL
);

```

---

## 4. Technical Execution Roadmap

1. **Phase 1: Telemetry & Gesture Detection Layer:** Platform Low-Level Event Hooks.
   Implement OS-level listeners to capture application switches and screen touch dynamics without interrupting user interaction:

* **Android:** Build a foreground service wrapping `AccessibilityService` (`TYPE_VIEW_SCROLLED`, `TYPE_WINDOW_STATE_CHANGED`) to capture stroke velocities, package names, and app focus events.
* **iOS:** Integrate `ScreenTime` API and `FamilyControls` framework to track app usage intervals and session boundaries.
* **Local Buffer:** Flush raw stroke events to an in-memory ring buffer, committing to the `scroll_activities` SQLite database when the app moves to background.


2. **Phase 2: Activity Processing & Classification:** On-Device Aggregation Engine.
   Classify each finished session into Strava-equivalent workout metrics:

* Calculate inter-session gap $g_i = t_{\text{start}, i} - t_{\text{end}, i-1}$. If $g_i < 300\text{s}$, flag as `is_rapid_reentry = 1`.
* Compute session SPM. If $\text{SPM} > 25$, record elapsed active time into `agitation_duration_seconds`.
* Tag activity type: **Micro-Check** ($<60\text{s}$), **Focused Session** ($1\text{m}\text{--}20\text{m}$), or **Marathon** ($>20\text{m}$).


3. **Phase 3: HTI Score Pipeline & Daily Rollups:** Rolling Math & Taper Engine.
   Run local background jobs (e.g., `WorkManager` on Android / `BGTaskScheduler` on iOS) at 00:00 local time:

* Query completed totals for $\{d-7, d-6, \dots, d-1\}$ to derive $M_{7\text{d}}(T)$.
* Compute sub-factors $F_{\text{volume}}$, $F_{\text{compulsion}}$, $F_{\text{marathon}}$, $F_{\text{agitation}}$.
* Execute master formula to persist `hti_score` in `daily_habit_rollups`.


4. **Phase 4: UI Feed & Cooling-Off Friction:** Strava-Style UX & Real-Time Intervention.
   Translate telemetry into visual feedback and real-time friction:

* **Activity Feed:** Display daily scroll sessions as "Workouts" featuring Pace (SPM), Suffer Index (Agitation), and Gap Times.
* **Cooling-Off Gate:** If `is_rapid_reentry = 1`, inject a subtle 10-second breathing overlay on target app launch (*"Pause: Re-opening within 3 minutes"*).
* **Marathon Break:** If active session duration hits 20 minutes, fire a non-intrusive full-screen notification to break the loop.


---

## 5. Statistical Safeguards & Edge Cases

1. **Cold-Start Handling (Days 1–7):**
* Before accumulating 7 days of telemetry, $M_{7\text{d}}$ cannot be calculated accurately.
* *Fallback:* Compute an expanding median across available days ($\text{Days } 1\text{--}6$). For Day 1, lock $F_{\text{volume}} = 0$ and calculate $\text{HTI}_1$ using only $F_{\text{compulsion}}$, $F_{\text{marathon}}$, and $F_{\text{agitation}}$.


2. **Zero-Usage / Detox Days ($T_d < 5\text{ mins}$):**
* Complete detox days pull down the rolling median, making normal, healthy usage on subsequent days look artificially like a "surge."
* *Fallback:* Exclude days with $T_d < 300\text{ seconds}$ from the 7-day median baseline calculation.


3. **Upper Bound Factor Clamping:**
* Severe single-day binging could produce sub-factor values well above 100%.
* *Fallback:* Individual sub-factors are normalized and clamped to $100.0$ max before weight application, ensuring score stability.