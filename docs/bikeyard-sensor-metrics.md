# Proposal: versioned Nakvali sensor metrics for BIKEYARD

Status: proposal for discussion with BIKEYARD, 2026-09-22. Not transmitted by the
current client. No custom upload field is assumed to exist in the current API.

## What exists today

`fusion-core/src/analysis.rs` contains an experimental free-fall detector: a 150 ms
accelerometer-magnitude window below 4 m/s², merging gaps below 100 ms and rejecting
windows shorter than 150 ms. It emits `start_ms`, `duration_ms`, `landing_peak_g`,
and a summed `airtime_total_ms`. The peak is |accel|/9.81 in the 300 ms after the
window ends, including gravity. It is device-specific force at the phone, not
rider-body force, gravity-removed acceleration, or a validated bike jump height.

Current limitations: no per-event confidence or explicit sensor-gap/saturation
quality, no mounting-position validation, no reliable distinction between a bike
jump/drop and a tossed/moving phone. A missing landing sample can currently yield
zero rather than an unavailable result. Whole-recording events are not yet a
versioned projection of the selected trimmed/transport-excluded upload scope.
Counting windows is therefore a candidate-event count, not yet a production jump
count. Neither zero nor a confident jump count should be exported from this state.

## Recommended canonical interchange

Agree a small JSON document independent of GPX/TCX/FIT. Keep it beside the track
internally; ask BIKEYARD to accept it as an optional multipart `metrics` part or a
separate authenticated attachment endpoint after the ride is created. Neither
endpoint exists in the documented contract yet. Track upload should not fail
because optional metrics are absent or unsupported.

Suggested schema:

- `schema`: `nakvali.sensor-metrics`, `schema_version`: 1.
- `recording_id`, `external_id`, `track_sha256` of the **uncompressed** exact uploaded
  track bytes, and explicit scope (whole activity/run, trim bounds, transport rule).
- `algorithm_version` and a separate `metrics_algorithm_version`, `generated_at`.
- Sensor provenance: sensor type, units, actual sample coverage/rate, mounting
  position if known; avoid device serials and raw sensor streams.
- `summary`: optional `jump_count`, `airtime_total_ms`, `max_airtime_ms`,
  `max_landing_specific_force_g`; each with validity/availability status.
- `events`: stable event identity within a metrics revision, start/end Unix ms,
  `airtime_ms`, optional `landing_specific_force_peak_g`, and status/quality reasons.
  Position is optional and linked to the processed track with interpolation/accuracy
  noted; do not invent precise coordinates in a GPS gap.
- `quality`: coverage, missing samples, clipping/saturation, unknown mounting,
  provisional algorithm. Numeric confidence is omitted unless calibrated.

Missing/unavailable values are null or omitted with an explicit reason. Zero means
measured zero with adequate coverage. Event classifications should distinguish
candidate airborne intervals, validated jumps and drops if the future algorithm
supports that distinction. Do not populate measured jump height/distance from
ballistic assumptions without a validated method and an explicitly estimated label.

Use matching start/end timestamps, not file sample indices: 1/5 Hz export and
compression must not change event identity or which event belongs to the ride.
Filtering to exported scope and aggregation happen in Rust. Exclude events crossing
trim/pause/transport/gap boundaries or mark them incomplete under a documented rule;
never silently count a partial event as a whole jump. Summaries must aggregate the
same accepted events the receiver displays.

## File interoperability

Use the JSON schema as the agreed semantic source, then map to FIT native fields
where their definitions genuinely match, and documented developer fields otherwise.
GPX/TCX can carry a versioned Nakvali XML extension namespace. Keep that mapping
consistent across formats, with clear duplicate handling if both file extensions
and JSON are present. Unknown extensions are often ignored by receivers: emitting
fields does not mean BIKEYARD imports them. Do not store metrics in free-text Notes.

FIT alone is not a sufficient contract: native jump fields have specific meanings,
and unit/quality/provenance requirements still need agreement. The existing exact
FIT timestamp-fraction field is serialization metadata, not a sensor-metrics API.

## Receiver and update rules to agree

1. Which endpoint/optional part accepts versioned metrics, size limits and capabilities?
2. How are pending async uploads associated with metrics and later ride IDs?
3. Which statuses are visible as provisional and which can enter public totals?
4. How does a new metrics revision replace an earlier one without duplicating a ride
   or event, including duplicate-track responses owned by the same rider?
5. Do unsupported metrics fail independently of the valid track upload?
6. Which revisions are retained, and how does rider deletion remove derived metrics?

Start with one consenting rider's test recording and annotated takeoff/landing
intervals. Validate gaps, phone mounting, clipped accelerometer peaks, paused/trimmed
rides and transport before publishing counts. No raw sensor upload is required by
this integration; share diagnostic raw files only through a separate explicit action.

References: current local Rust `analysis.rs`, `docs/ROADMAP.md`,
[BIKEYARD uploads](https://yard.bike/developers/docs/uploads),
[Garmin developer fields](https://developer.garmin.com/fit/articles/cookbook/developer-data.html).
