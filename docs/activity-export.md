# Activity file export

Nakvali exports the Rust-derived processed track as FIT, TCX or GPX. The export
sheet has independent **5 Hz detail**, **Gzip compression**, and **Exclude transport**
controls. The same options apply to **Save file** and **Share**. Scope can be the
whole activity or an existing Rust-authored riding run.

## Sampling and compression

5 Hz detail is on by default: all available canonical points are kept, including
original GPS anchors. Turning it off selects existing points approximately once
per second, keeping both endpoints of every run and recording-gap section. Short
runs can therefore contain multiple points in one second. No new positions, gate
times, classifications or statistics are calculated. Raw recordings and cached
canonical artifacts are untouched; selected-run odometers retain the existing
Rust-derived rebase.

Gzip is lossless stream compression with names `.fit.gz`, `.tcx.gz`, `.gpx.gz`.
Android receives `application/gzip` for these files; plain formats retain their
own MIME type. Original GPS and health-log exports can also be compressed. Raw
sensor `.jsonl.gz` archives are already compressed and are not gzipped twice.
The receiving app must support the compressed format.

## FIT encoding and precision

The serializer uses Garmin's official `com.garmin:fit:21.214.0` SDK, protocol 2.0.
It writes File ID, Device Info, Record, Lap, Session, Activity and timer Event
messages. Existing run boundaries become laps; timer stop/start events separate
removed shuttle/pause spans. Positions, height, distance and time come from the
existing export points. MTB/e-MTB sport metadata follows the selected bike.
Session/lap distance uses the Rust odometer, not distances recomputed from
resampled coordinates. No invented heart rate, calories or jumping metrics.

FIT fields have their own precision: coordinates are signed semicircles, altitude
uses the SDK's enhanced-altitude encoding, and distance uses its standard scaled
field. This is not a bit-for-bit floating-point copy of XML. Each record has the
standard whole-second timestamp and `time128` fraction (1/128-second resolution).
The exact original remainder is also written as a declared developer field:

- Application ID bytes: ASCII `NakvaliExportV01` (16 bytes), developer index 0.
- Field definition 0: `timestamp_fraction_ms`, `uint16`, units `ms`.
- Value: 0–999 milliseconds to add to the standard timestamp's whole second.
- Present on Record and timer Event messages.

Readers unaware of fractional/developer fields can lose sub-second timing; the
sheet recommends 1 Hz for broader compatibility. Canonical timing remains in Rust
and is not inferred back from FIT. The title is limited to 60 Unicode code points
to stay within a FIT string field's byte limit. The official decoder verifies CRC,
message structure, all samples, exact developer timestamps, lap distance and timer
exclusion in tests.

On the same deterministic 54,000-point fixture (roughly three hours at 5 Hz),
compact TCX is 14,189,169 bytes, TCX.GZ 940,839 bytes, FIT 1,296,764 bytes and
FIT.GZ 575,432 bytes. These are measured fixture sizes, not promises for all rides.

## BIKEYARD delivery

The BIKEYARD action is separate from the file controls and uploads the whole
riding-only TCX.GZ with `Prefer: respond-async`. The provider contract verified on
2026-09-22 accepts gzip up to 20 MB transmitted / 128 MB unpacked. Android retains
the frozen TCX snapshot, compresses its exact bytes for transfer, persists the
processing receipt and polls until a terminal status. FIT API delivery still
requires receiver verification of fractional time and timer/lap semantics.

Future Air Time, jump count and related ride metrics are tracked in
`docs/ROADMAP.md`. Their field names, units, algorithm version, quality and update
semantics need agreement with BIKEYARD before transmission. The timestamp field
above is real serialization metadata, not a placeholder for those features.

## References

- [Garmin FIT Java SDK](https://github.com/garmin/fit-java-sdk)
- [Activity file structure](https://developer.garmin.com/fit/articles/file-types/activity.html)
- [Encoding activity files](https://developer.garmin.com/fit/articles/cookbook/encoding_activity_files.html)
