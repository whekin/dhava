# BIKEYARD sensor metrics — alignment with receiver draft

Reviewed 2026-09-23 against Pavel Fedoruk's draft supplied in the conversation.
On 2026-09-25 the [published OpenAPI](https://yard.bike/developer-api/docs/openapi.json)
added `PUT`/`GET /v1/rides/{ride_id}/sensor-metrics`; an unauthenticated GET
reached the live route and returned 401. The app prepares private-ride
candidate documents for manual submission or a separately opted-in automatic
sync of new saved rides. This document retains design notes for later quality
and public semantics.

## Agreed direction

1. Nakvali derives events on the phone in Rust. It owns detection, classification,
   scope filtering and quality. BIKEYARD does not need raw IMU or barometer data.
2. Upload the processed ride first. Once its asynchronous import is complete, use
   the returned `ride_id` to attach a separate JSON document with `rides:write`.
   If BIKEYARD confirms an existing ride owned by this rider, the same attachment
   rule can apply when metrics are absent. A duplicate owned by someone else gives
   Nakvali no authorized ride to attach to.
3. The document contains absolute start/end timestamps and no GPS coordinates.
   BIKEYARD places events against its **stored** ride track, applies its privacy
   zone and derives totals from the same accepted events it shows.
4. A rejected or absent metrics document must leave the track upload intact.
   The sender handles private, confirmed rides only and sends candidate timing
   and sensor availability, with phone G omitted. Automatic metrics require
   their own explicit opt-in on top of automatic ride uploads.

### Identity: remove track SHA-256 from v1

Our earlier `track_sha256` guarded against associating a recomputed metrics payload
with a different immutable track revision. It does not improve normal v1 linking:
BIKEYARD chooses the ride by its authenticated URL `ride_id` and owns that track.
Even a hash of uncompressed bytes changes with FIT versus TCX, export rate
or XML formatting when the ridden line is equivalent. Gzip alone would not change
such an uncompressed-byte hash, but it also provides no extra ride identity. Do not include `track_sha256` in the v1 document.

`recording_id` stays on the phone for finding the raw source; `external_id` already
belongs to the track upload and its duplicate protection. Neither needs to be
repeated in this metrics document. A future endpoint that replaces track geometry
can expose its own semantic `track_revision` or ETag and require it when replacing
metrics. That problem should be solved with explicit ride revisions, not an
uninterpreted hash of file bytes.

## Proposed v1 transport

The published contract uses authenticated `PUT /v1/rides/{ride_id}/sensor-metrics`
and `GET` at the same path, scope `rides:write`. Ownership is checked on every
request. The receiver derives positions and statistics; Nakvali does not send a
separate summary. The body limit is **20 MB** and there may be at most 2000
events. First `PUT` returns 201; an identical retry returns 200 without a new
revision, while a changed document replaces the previous one and returns 200.

The lost-201 ambiguity is now resolved by the published 200 response for an
identical retry. Nakvali freezes the exact JSON before the first PUT and keeps
it through retries; a failed metrics request does not change track-upload state.

Replacement permits correcting candidates after Nakvali recomputes from its raw
archive. For the pilot it is manual; before automatic revision sync or public
counts, agree how concurrent replacements are handled (e.g. ETag/If-Match with
one active revision and audit history). Do not create a duplicate ride as a
workaround.

## Payload and current evidence

Pavel's `bikeyard.sensor-metrics` v1 document has `generator`, `sensor` and
`events`, no GPS. That direction is good. His illustrated app version `1.4.0`,
algorithm version `freefall-0.3`, sample rate `100`, coverage `0.98`, and
`validated` jump are example values, not measurements or claims Nakvali can send.

The current Rust `AirtimeWindow` detects a candidate low-acceleration interval
lasting at least 150 ms; nearby windows are merged. It provides `start_ms`,
`duration_ms`, and a peak |acceleration|/9.81 in the 300 ms after the window.
`end_ms` can be derived from start and duration when a document is prepared in
Rust. That peak includes gravity and describes the **phone**, not rider load.
The updated detector splits events at IMU gaps and omits an unfinished event
without a post-window sample instead of fabricating a numeric zero for landing.
This is a first integrity guard, not a validated landing classifier.

Use `kind: "airtime"`, `status: "candidate"` for the initial field trial. Do not
emit `kind: "jump"` or `"drop"`, `status: "validated"`, `jump_count`, or a
measured jump height until classification is field-validated. BIKEYARD's draft
correctly keeps candidate events visible only to the owner and out of public
stats. A stable detector-specific `algorithm_version` is needed; the current
product-wide `gps-bounded-0.17` does not separately version free-fall semantics.

The local event detail also reports an optional peak phone acceleration magnitude
in the 300 ms before the candidate interval. It is withheld when pre-air sensor
history is shorter than 100 ms. This is explicitly a **phone** measurement,
including gravity, not a takeoff force on the rider or a BIKEYARD v1 field.
Neither takeoff angle nor landing smoothness is currently measured: arbitrary
phone mounting leaves its axes unaligned with the bike, and those derived
quantities need a calibrated model and field validation.

`sample_rate_hz` must come from timestamps of usable samples in the **uploaded
ride scope**, not nominal acquisition rate. The pilot Rust projection measures
coverage across the frozen TCX riding sections and computes observed sample
rate from continuous IMU intervals. It excludes pauses and shuttles because
those sections were not exported. The sender refuses an empty event list when
coverage is below 0.8. Mounting is selected by the rider for the pilot; it
defaults to `unknown`, never guessed from the phone model.

Nakvali's sender projection must filter events to the exact uploaded trim,
transport exclusions, recording sections and manual pause boundaries. An event
that crosses a boundary or missing-sensor interval needs a defined incomplete
rule, never a whole validated jump. Event IDs need only be unique within the
write-once document; timestamps, not exported point indices, place the event.

## Placement and receiver questions

BIKEYARD can interpolate a candidate's start/end or midpoint against its stored
track. It should never interpolate across a recorded segment/lap/timer break or
an excluded shuttle, even when the break is under the draft's `> 30 s AND > 100 m`
threshold. Otherwise a short pause or nearby shuttle can give an invented jump
position. An event without two positions in the **same continuous section** stays
stored but unplaced; the receiver can still show its time and owner-only status.
GPS accuracy limits how precisely such a marker can be drawn, even on a 5 Hz path
whose intermediate points were interpolated on the phone.

Pavel confirmed the effective JSON body limit is 20 MB; the draft's 256 KB
reference should be corrected. A separate 2000-event cap can coexist with that
byte cap. The draft allows durations from
50 ms, while Nakvali currently emits only candidates of at least 150 ms. That is
compatible but should not be mistaken for a change to our detector.

Before automatic or broader release, clarify with BIKEYARD:

- discontinuity rules for map placement and whether normalized rides retain
  pause/run breaks from TCX or FIT;
- owner-only visibility of candidate events and whether they affect any public
  statistic on a public ride;
- ownership/access to metrics for private rides and after disconnect/deletion.

No FIT developer fields or TCX extensions are needed for this v1 API: one JSON
attachment is the semantic source. Add file mappings only for confirmed consumers
or offline interchange, keeping units, event status and algorithm version equal to
the JSON contract. No raw sensor stream or GPS point is part of the attachment.
