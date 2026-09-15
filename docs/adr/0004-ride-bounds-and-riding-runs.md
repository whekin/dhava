# Ride bounds and riding runs

Accepted 2026-09-15. A trim is a rider annotation that narrows what counts as
the ride without changing the track, and a riding run becomes a first-class
Rust-owned unit that both the run picker and a TCX lap read.

## Context

Riders record before the ride starts and after it finishes — walking to the
trailhead, standing at the bottom — and want that out of the activity. They also
want one descent out of a shuttle day rather than the whole file. The Russian ask
was "обрезать/скрывать": both trim and hide, which points at the display and not
only at the exported file.

Runs already existed, but only in Kotlin: `TrackExport.processedPoints` invented
`runId` while `ride_breakdown` decided the identical boundaries in Rust to keep
its ride and transport anchors apart. Two definitions were tolerable while a run
was an internal detail of TCX laps. A run the rider can see and pick is not.

## Decision

A trim is `RideBounds { started_at_ms, ended_at_ms }`, absolute recording
timestamps, stored as `ride_bounds` on the recording index entry. Null means the
whole recording is the ride. It follows ADR 0003 exactly: validated and applied
in Rust, never modifying raw or position geometry, backed up with the index, and
resettable by discarding the annotation.

Bounds narrow accounting, not the track. `ride_within` recomputes `RideTotals`
and the odometer over the kept span; the finalized track keeps every point and
every label. This is the load-bearing choice. A transport correction relabels
without removing, and the app's index correspondences — profile positions,
segment attempt slices, the odometer's alignment — all assume the track keeps its
length. A trim that deleted points would break every one of them.

A pair reaching outside the bounds counts toward neither the ride nor the
transport: the rider said that stretch is not part of the day, not that they were
driven through it. Totals are recomputed rather than subtracted, because ascent
and descent carry a reference altitude forward and the parts of a ride never sum
to the whole.

Segment matching deliberately does not see the trim. Matching runs on the
unchanged finalized track, so a timed crossing inside a trimmed head still
stands, a personal record cannot be destroyed by a presentation choice, and no
rider can shop for a faster time by trimming. A trim therefore needs no revision
counter: it changes nothing matching consumes. Transport overrides still do, and
keep theirs.

Displayed duration follows the trim explicitly through
`LocalRecording.ridingDurationMs`, because duration is the one headline number
that comes from the index entry rather than from Rust totals. The recording's own
`started_at_ms`/`ended_at_ms` never move: continuation, crash recovery, backup
validation and list ordering all depend on them.

What a trim leaves out is stated on the activity, mirroring the transport line,
per the 2026-08-10 decision that exclusions are stated and not hidden.

`ride_runs` moves run enumeration into Rust and returns each run's own bounds,
index range and figures, measured with the same accumulators the whole ride uses.
A run breaks on transport or a manual pause and never on a dropped fix. Kotlin
labels its export points from that list instead of deciding boundaries again, so
the run a rider taps and the lap that gets written are one descent.

A run is addressed by its start time, never by its ordinal: any annotation edit
renumbers runs, and an ordinal would quietly export a different descent.

Single-run export rebases the odometer so the file opens at zero — TCX states an
absolute distance per trackpoint, and a run lifted out of the middle of a day
would otherwise begin at several kilometres. The Strava `external_id` carries the
trim, because without it a re-export after trimming finds the existing upload and
returns success without sending a byte.

## Alternatives and consequences

- Trimming as an export-only filter was rejected: it cannot satisfy "скрывать",
  and it makes the rider re-choose the same boundaries on every export.
- A new `ActivityState` for trimmed points was rejected: it ripples through every
  exhaustive match, map style and classifier for no gain, since accounting alone
  is what a trim changes.
- Re-running fusion over a shortened raw window was rejected outright. Filter
  warm-up, the baro-vs-GPS offset field and the elevation anchor would all start
  somewhere else, so the same descent could score differently before and after a
  trim — a divergence the architecture forbids and a knob for gate shopping.
- Per-run Strava upload was rejected for now: the index entry holds one export
  status, upload id and activity id, and the worker's unique work name is per
  recording. A single run is file-only, and the sheet says so.
- `ALGORITHM_VERSION` does not change. Both features are post-finalize views; the
  canonical math producing the artifact is untouched, and a bump would force every
  recording on the device to be rebuilt from raw for nothing.

A trimmed activity still lists segment attempts from the trimmed span. That is
the intended consequence of keeping matching honest, and the trim sheet says the
segment times still count.
