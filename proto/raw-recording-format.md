# Raw recording format v1

One recording = one **gzip-compressed JSON Lines** file (`.jsonl.gz`).
Field names match the serde types in `fusion/crates/fusion-core` exactly.
Operational process/writer telemetry is deliberately stored in the separate
local sidecar described by `proto/recording-health-format.md`; it is never
mixed into immutable fusion input.

Every line is a JSON object with a `type` discriminator:

```jsonl
{"type":"meta","version":1,"activity_id":"<uuid>","device":"Pixel 8","os":"android-15","app_version":"0.1.0","started_at_ms":1770000000000}
{"type":"gps","timestamp_ms":1770000001000,"lat":41.7151,"lon":44.8271,"altitude_m":712.4,"accuracy_m":3.9,"speed_mps":8.2,"bearing_deg":184.0}
{"type":"imu","timestamp_ms":1770000001005,"accel":[0.12,-0.03,9.79],"gyro":[0.01,0.0,-0.02],"mag":[22.1,-4.3,41.0]}
{"type":"baro","timestamp_ms":1770000001010,"pressure_hpa":934.2}
{"type":"event","timestamp_ms":1770000002000,"action":"pause"}
{"type":"event","timestamp_ms":1770000005000,"action":"resume"}
```

Rules:

- `timestamp_ms` — Unix epoch milliseconds, from the same monotonic-anchored clock
  for all sample types (device must map sensor-event timestamps to epoch consistently).
- Optional fields (`altitude_m`, `accuracy_m`, `speed_mps`, `bearing_deg`, `mag`)
  are omitted or `null` when unavailable.
- `meta` line first; order of other lines is best-effort chronological, readers
  must not assume strict global ordering (sensor callbacks interleave).
- Units: SI. accel m/s² (raw, gravity included), gyro rad/s, mag µT, pressure hPa.
- Acquisition and live-processing rates are distinct from the rows persisted to
  this file. GPS remains at the device's high-accuracy rate (~1 Hz), including
  while stationary. Accelerometer/gyroscope acquisition is capped at 200 Hz
  (5 ms), while Rust live fusion consumes a 50 Hz reduction. Barometer remains
  approximately 10 Hz.
- While Rust live fusion reports confirmed `STILL`, IMU disk persistence is
  reduced to 20 Hz. Acquisition and the 50 Hz live detector continue unchanged,
  so a calm phone moving in transport can still be released from `STILL` by the
  continuing 1 Hz earth-relative GPS fixes.
- The recorder retains the most recent two seconds of stationary IMU in a
  process-local full-rate 200 Hz pre-roll. It writes that complete pre-roll before the first
  moving sample and flushes it before manual pause or Finish. Older stationary
  samples are represented by the persisted 20 Hz cadence. This adaptive
  persistence is not a pause: GPS/barometer continue, timestamps retain their
  original monotonic-anchored values, and no pause section is created.
- `event` lines mark manual `pause` / `resume`. No sensor samples are written while
  paused. An unmatched `pause` extends to the end of the recording. Analysis must
  not add distance, moving time or airtime across paused intervals.
- `event` actions `activity:in_vehicle`, `activity:in_vehicle:exit`, `activity:on_bicycle`
  and `activity:on_foot` record Android's own activity recognition when the optional
  permission is granted. They are platform hints kept as evidence for later analysis,
  never a substitute for sensor evidence: classification must stay reproducible from
  GPS/IMU/baro alone, and a reader that ignores these lines must reach the same result.
- While the recorder judges the rider to be in a vehicle, GPS drops to a 5 s balanced
  fix and accelerometer/gyroscope acquisition to 25 Hz. This is a deliberate, bounded
  loss of transit fidelity; the rate returns to full as soon as a descent, trail-like
  motion, a long stop or a manual pause ends the vehicle state.
- `event` action `imu_overflow:<count>` is diagnostic only: it reports IMU rows
  dropped since the previous writer health checkpoint because the bounded queue
  was full. Readers must not treat it as a pause boundary.

## Upload API (Phase 1, no auth yet)

- `POST /api/v1/activities` `{"sport":"downhill","started_at_ms":...}` → `201 {"id":"<uuid>"}`
- `PUT /api/v1/activities/{id}/raw` — body: the `.jsonl.gz` bytes,
  `Content-Type: application/gzip` → `204`. Stored to object storage
  (`raw-recordings/{activity_id}.jsonl.gz`), row added to `raw_recordings`.
- `POST /api/v1/activities/{id}/finish`
  `{"ended_at_ms":..., "title":"...", "description":"...", "bike":"...", "bike_type":"full_sus"}`
  → `204` (status → `uploaded`). Everything except `ended_at_ms` is optional
  user-entered metadata; omitted or empty fields are stored as NULL.
  `bike_type` must be one of `full_sus|hardtail|ebike|other`.
  Length caps: title 200, description 5000, bike 100 characters.
