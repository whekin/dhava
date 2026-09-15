# Transport episodes and rider overrides

Accepted 2026-09-15. Transport is modelled as a boarding-to-unloading episode
with explicit rider corrections, so the same boundaries govern the map, statistics
and export.

## Context

A shuttle journey remains transport while its road descends or levels out. Local
window classification and increasingly elaborate gap bridges fragmented journeys,
including under the recorder's own reduced GPS cadence. The owner confirmed that
the intended unit is a boarding-to-unloading episode and accepted editable bounds
because phone sensors cannot always distinguish unloading from a vehicle stop.

## Decision

Rust retains short-window movement evidence but uses a stateful transport-episode
pass instead of repeated vehicle-island bridging. Sustained vehicle evidence arms
an episode; road slope alone does not end it. Departure is backfilled only within
a bounded period and not over an earlier descent. Stops plus subsequent movement
resolve unloading. A substantial descending terminal tail is kept as riding when
unloading evidence was missed; this remains an estimate, not proof.

Initial policies are explicit tuning choices: 30 s unloading-stop candidates,
180 s long stops, 300 s lookahead for renewed vehicle evidence, 120 s maximum
entry backfill, and a 45 s / 30 m descending tail. A supported GNSS outage can
retain episode identity for up to 60 s with continuous IMU. Manual pauses and
unsupported gaps remain boundaries. These are not universal physical limits.
GPS geometry and segment timing keep their stricter continuity rules. An explicit
manual interval may cover a pause, but cannot create positions or a timed crossing
through it.

The rider can replace the complete episode list for one activity, including an
explicit empty list. Null means automatic detection. Corrections use absolute
recording timestamps, are validated and applied in Rust, and never modify raw or
position geometry. The original automatic artifact stays rebuildable; one bounded
in-memory cache holds a corrected projection. Resetting to automatic discards only
the annotation.

The recording index stores corrections and a transport revision, so existing local
backup/restore includes them. Segment match caches and live PR arming check this
revision. Map labels, ride totals, discovery, matching and GPX all consume the same
corrected canonical projection. Already uploaded activities are not edited.

## Alternatives and consequences

- More window-bridge thresholds were rejected: they do not represent boarding or
  unloading and make continuity depend on the density of GPS fixes.
- Manual-only classification was rejected for ordinary use, but manual bounds
  remain authoritative when automatic evidence is ambiguous.
- A server-owned classifier would conflict with offline recording and local raw
  ownership. All math and validation remain in fusion-core.

The current field recording yields three transport episodes with no internal DH or
Transit islands and unchanged known riding blocks. Other vehicles, phone placements
and missed unloading events still require field validation. Rider corrections are
personal annotations, not independent anti-cheat evidence.
