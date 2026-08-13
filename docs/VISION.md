# Nakvali — Vision

Strava is route-first and does downhill badly. Nakvali is **segment-first, downhill-first**.

## Core differentiators

- **Smart start/finish gates.** A run starts when the rider moves *through* the start
  gate in the segment's direction (not when hanging at the start line within a GPS
  radius), and ends at the finish-gate crossing. Stops right after the finish don't
  pollute the time. Gate crossing time is interpolated between GPS fixes and refined
  with IMU (Kalman fusion: GPS 1 Hz + IMU 100–400 Hz + barometer).
- **Honest timing.** Every result carries an uncertainty estimate ("55 s ± 2 s")
  derived from GPS accuracy and sample density at the gates.
- **Combo segments.** A parent segment made of child trails (e.g. Right Side → Twin →
  Bonsai → Pontius). Combo time = sum of child segment times, transits excluded.
  Separate stat from full top-to-bottom time — two distinct riding modes.
- **Segmented trails** (a trail split into parts) and **short segments** allowed
  (< ~300 m, configurable; some features like leaderboards may be off there).
- **Segment quality via multiple recordings.** New segments require several
  recordings/GPX files, including a *slow registration ride* (dense points).
  Trajectories are averaged (DTW + median) for a precise reference line.
- **Leaderboard resets on trail changes.** When a trail is confirmed changed, its
  leaderboard resets; old boards are archived as history/reference. This removes the
  "can't ever beat the old line" pain and lets builders make trails *funnier*, not
  just faster.
- **Memories.** "You were the KOM here from X to Y" is preserved forever, even after
  being beaten.
- **Segment-only tracking.** After the last trail, the app stops caring: transits,
  city riding, lifts are gray/ignored (user-configurable). Results = trails hit, PRs,
  KOMs — not a giant route line.

## Leaderboards

- Default filter = rider's class (bike type: full-sus vs hardtail, etc.), not "all".
- Separate men/women boards + a combined board.
- E-bike: v1 treats most DH trails as no-advantage (single board); trails with flat/
  uphill sections get a separate e-bike board (v2).
- Per-condition boards (dry/wet/snow/fresh-shaped/broken berms). Condition is set by
  riders after runs; consensus (most-chosen per time window) wins. General weather can
  be set globally, but trails may differ (snow on top, dry at the bottom).

## Recording intelligence

- Live data while riding: current speed, delta to KOM/PR, "you took the KOM" right
  after the trail — not at upload time. Requires offline segment + leaderboard cache.
- IMU extras: airtime (|accel| → ~0 in freefall), G-forces, landing impacts.
- IMU-aided speed profile for run comparison (where exactly I lost/gained vs rival) —
  GPS alone is too jumpy in forests.
- On-bike detection; car detection (never count); lift/cable-car detection for
  snowboarding stats (v2); power-save mode far from trails; "stop recording?" nudge
  when leaving segment areas.
- Bad GPS handling: auto-adjustment against trusted segment geometry; graceful
  handling of trails that run close to each other (prompt the user if ambiguous).
- **Danger alerts.** A fallen tree, a washed-out lip, a broken bridge. The rider stops
  mid-segment, and the app already knows that: a stop *inside* a segment is unusual and
  nothing else in the app cares about it. So it offers, right there, "what happened?" —
  and if the answer is a hazard the rider cannot clear alone, it is marked on the spot,
  with position and photo, while standing next to it.
  - Trails carrying a live hazard are flagged on the map, readable before dropping in.
  - Approaching a marked hazard mid-run, the phone warns by *haptics* — a long, hard
    buzz. Nobody reads a screen at speed; the wrist and the bars are the only channel.
  - Must work with zero connectivity, like everything else on the trail: hazards ride
    along in the offline segment cache.
  - Open questions, not decided: who clears a hazard and how it expires; how to avoid
    crying wolf on a stale mark; whether the warning fires on approach or on entering
    the segment. A warning that distracts a rider mid-descent is itself a hazard, so
    this needs real care before it is built.
- Anti-cheat: raw IMU stream is the natural signature — forging a GPX is easy,
  forging a consistent IMU stream is not.

## Social

- Kudos + comments on activities; dedicated recent-achievements page (PRs, KOMs),
  also commentable.
- Segment discussion pages + trail state/status.
- Trailbuilding: log building activity on trails, kudos for builders; crypto tips (v3).
- Betting (virtual points, not money): "I'll take this KOM in 2 days", "I'll beat X".
- Race mode: organized events tracked in-app (v2).

## Look & feel

Beautiful and simple. Material 3 Expressive, dark-first, oversized live-timing
typography. Bike selection is interactive/visual (cards with images; 3D maybe later),
not a dropdown.

## Reference

IMU/GPS fusion paper: https://www.mdpi.com/1424-8220/24/18/5873
(magnetometer as weak heading constraint, no calibration required from user).
