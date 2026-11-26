**Yes, you can build this app on Android, but it requires using Android’s `AccessibilityService` API.** Standard Android APIs (like `UsageStatsManager`) can tell you how long an app was open, but not how much the user scrolled in it.

**What is measured:** for every app the user chooses to track, Scroll Starva records **all time spent in the app** and **every vertical scroll gesture in the app** — the home feed, Shorts/Reels, comments and any other screen all count. Short-form feed detection (below) is kept only for the on-device Debug log.

---

## 1. How the App Works Under the Hood

To track specific feeds inside third-party apps, your app needs to run an **`AccessibilityService` in the background**.

### A. Detecting the Tracked Apps (and, for diagnostics, their feeds)

Your service listens to UI window changes (`AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED`), scroll events (`TYPE_VIEW_SCROLLED`) and, for the app being tracked, content changes (`TYPE_WINDOW_CONTENT_CHANGED`):

1. **App Identification:** Check the active package name:
* TikTok: `com.zhiliaoapp.musically`
* YouTube: `com.google.android.youtube`
* Instagram: `com.instagram.android`
* Facebook: `com.facebook.katana`


   The user may also select other apps; they are tracked the same way.

2. **Screen Identification (diagnostics only):** Scan the visible screen layout (`AccessibilityNodeInfo`) for indicators of the Reels/Shorts player vs. the home feed and show the result in the Debug log. It does not affect time or scroll counts.

### B. Measuring Time Spent

* A **session** starts when a tracked app comes to the foreground (or is already in front when the screen is unlocked) and is timed with `SystemClock.elapsedRealtime()`.
* The session continues while the app stays in front, **including time spent watching without touching the screen**. Every 10 seconds the service checks which app is in the foreground rather than relying on a stream of events.
* The session ends when the user switches to another app (after a 2.5 s grace period, ending at the moment they left), opens Scroll Starva, or turns off the screen. If the foreground app can’t be determined, the session ends after 3 minutes without any events, at the time of the last event.
* Sessions that cross midnight are split so each day gets its own time.
* Time spent in a tracked app during a focus session still counts as screen time (Pip asks the user to return to focus).

### C. Counting Scrolls

* Listen for `AccessibilityEvent.TYPE_VIEW_SCROLLED` events from any screen of a tracked app and keep only vertical ones.
* Count **one scroll per gesture**: a swipe or fling produces a burst of scroll events, and a new gesture starts once events have been quiet for 300 ms. A snap in a short-video feed and a long fling through a list therefore both count as one.
* Some full-screen video pagers don’t send scroll events at all (YouTube Shorts, as of late 2026). For these, the service also listens to `TYPE_WINDOW_CONTENT_CHANGED` from the app already being tracked and counts one scroll each time a different page of the pager (`reel_recycler`) settles on screen. A looping video doesn’t change the page, so it isn’t counted. Content changes never start a session.
* Scrolls are counted in the session database, which is the single source for every figure (dashboard, goals, reminders and distance).

Short-form feeds use snap-scrolling (`ViewPager2`), so each scroll there advances about **one full screen height**; the distance estimate below uses that for every scroll.

---

## 2. Calculating Physical Scroll Distance (Kilometers)

To convert pixel scrolling into real-world physical distance (meters or kilometers), you use the device's physical screen metrics from Android's `DisplayMetrics`.

### Step-by-Step Calculation Formula

1. **Get Device Screen Properties:**
* Screen height in pixels: $H_{px}$ (`DisplayMetrics.heightPixels`)
* Vertical density in DPI: $DPI_y$ (`DisplayMetrics.ydpi`)


2. **Calculate Physical Height of One Scroll:**

$$\text{Height (inches)} = \frac{H_{px}}{DPI_y}$$


$$\text{Height (meters)} = \text{Height (inches)} \times 0.0254$$


3. **Calculate Total Distance:**

$$\text{Total Distance (km)} = \frac{\text{Scroll Count} \times \text{Height (meters)}}{1000}$$



### Example:

On a smartphone screen with **2400 × 1080 pixels** and a vertical screen density of **420 DPI**:

* **Screen physical height:** $\frac{2400}{420} \approx 5.71\text{ inches} \approx 0.145\text{ meters}$.
* **If the user performs 1,000 scrolls in a day:**

$$\text{Distance} = 1,000 \times 0.145\text{ m} = 145\text{ meters } (0.145\text{ km})$$


* **To scroll 1 kilometer (~1,000 meters),** the user would need approximately **6,896 swipes**.

---

## 3. Major Development Challenges & Considerations

| Challenge | Impact & Mitigation |
| --- | --- |
| **Frequent UI Updates** | TikTok, Meta, and YouTube frequently update their app layouts. When they change UI node IDs or class names, your app's detection rules will break and require an update. |
| **Play Store Policies** | Google strictly monitors `AccessibilityService` usage. Your app must clearly disclose to users why it needs accessibility permissions (digital wellbeing/tracking) to avoid rejection on the Play Store. |
| **Battery Optimization** | Custom Android OS skins (MIUI, OneUI, ColorOS) aggressively kill background accessibility services to save battery. Users will need to manually disable battery optimization for your app. |