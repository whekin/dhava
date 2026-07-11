# Dhava fusion

Sensor-fusion core for the Dhava downhill MTB ride-recording app.

- `crates/fusion-core` — library: GPS/IMU/baro types, segment gate-crossing
  detection (implemented), and future Kalman filtering + airtime detection.
- `crates/fusion-worker` — binary skeleton: server-side worker that will poll
  Postgres for raw recordings, run them through `fusion-core`, and store results.

Future plan: expose `fusion-core` to Android via UniFFI bindings so the phone
and the server run the exact same fusion code.
