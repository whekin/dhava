# Recording health format v1

Each ride recorded by builds that support operational telemetry has an
append-only `recordings/<activity-id>.health.jsonl` sidecar beside its immutable
raw `recordings/<activity-id>.jsonl.gz`.

The sidecar is local diagnostics, **not fusion input and not uploaded**. It may
evolve independently without invalidating canonical activity artifacts. An
explicit user deletion removes the raw file, derived artifact and health
sidecar together.

Each line is a JSON object. `kind` is one of:

- `start` — fresh recording began;
- `heartbeat` — durable once-per-wall-clock-minute checkpoint, including while
  the ride is paused;
- `process_exit` — Android `ApplicationExitInfo` attached during interrupted
  recording recovery;
- `restart` — the sticky foreground service resumed the repaired recording;
- `stop` — explicit finalization completed.

Common checkpoint fields include:

- process PSS/RSS, Java heap and native heap in KiB;
- process uptime and CPU time;
- raw file size;
- GPS/IMU/barometer counts since the current process began;
- age of the last GPS callback;
- writer critical/IMU queue depths and cumulative IMU overflow count;
- Android thermal status, battery percentage and charging state;
- active session time, pause state and restart gap.

Exit records preserve the public Android reason, status, importance, PSS/RSS
and description. Unknown or unavailable values are omitted rather than written
as false zeroes.

Every append is flushed and `fsync`ed. If a process dies halfway through a
line, the next append first inserts a newline; readers skip the damaged tail
and retain all later valid checkpoints.
