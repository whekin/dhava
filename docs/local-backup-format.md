# Nakvali local backup format

Nakvali backups are user-owned ZIP archives created and opened through Android's
Storage Access Framework. The suggested filename is
`nakvali-backup-YYYY-MM-DD-HHmm.zip`.

The archive schema does not contain an application/package identifier. Format v1
archives exported by the retired prototype therefore remain valid Nakvali restore
inputs even when their filename uses the former brand.

## Format version 1

`manifest.json` is always the first ZIP entry. It contains:

```json
{
  "format_version": 1,
  "created_at_ms": 1785675930877,
  "recording_count": 3,
  "segment_count": 2,
  "imported_trace_count": 1,
  "entries": [
    {
      "path": "recordings/example.jsonl.gz",
      "size": 123456,
      "sha256": "..."
    }
  ]
}
```

Every listed payload is stored without another compression pass. Raw recordings
are already gzip streams; avoiding nested deflate keeps export fast and makes the
ZIP CRC an independent transport check. SHA-256 in the manifest is the content
identity used by restore.

Included durable inputs:

- `recordings.json` and `bikes.json`;
- `recordings/*.jsonl.gz` raw GPS, IMU and barometer streams;
- `recordings/*.health.jsonl` operational diagnostics;
- `segments/*.segment.json` rider-authored segment definitions;
- `imported-traces/*.gpx` and their `*.json` metadata.

Deliberately excluded:

- canonical activity artifacts and segment results, because they are recomputed;
- map tiles and other caches;
- WorkManager/upload process state;
- Strava device credentials, API keys and every other secret.

## Restore rules

Restore first reads the bounded manifest, then extracts every payload into an
app-private staging directory and verifies its declared size, ZIP CRC and SHA-256.
Only after the whole archive passes verification does Nakvali merge it into local
storage.

- Existing rides, metadata, bikes, segments, GPX sources and diagnostic tails win.
- Missing items are added.
- An existing raw filename with different bytes aborts the restore before any merge;
  immutable sensor evidence is never overwritten.
- Active recordings block both export and restore.
- A fresh install therefore reconstructs the archive exactly, while restoring onto
  a used installation cannot silently roll back newer local data.

Import accepts at most 10,000 payload files, a 2 MB manifest and 50 GB of declared
payload data. Entry names use an allowlist and cannot contain nested or parent paths.
Nakvali also keeps 64 MB of free internal space beyond the staging requirement.

## Transport annotations

Each recording-index entry can contain `transport_episodes`: absent/null uses
automatic detection, an empty list explicitly means no transport, and a nonempty
list contains `{started_at_ms, ended_at_ms}` intervals in absolute recording time.
These annotations are backed up with the existing index, not written into raw
sensor files. `transport_revision` invalidates local segment-match caches after
an edit. Fresh restores preserve annotations; the existing merge policy still
keeps authored metadata already present on the destination device.

## Trim annotations

Each entry can also contain `ride_bounds`: absent/null means the whole recording
is the ride, and a value is `{started_at_ms, ended_at_ms}` in absolute recording
time. The entry's own `started_at_ms`/`ended_at_ms` are the recording's true
bounds and never move. There is no trim revision, because a trim changes what
counts as the ride and not the track segment matching consumes.

Both fields are optional, so an index written before them decodes unchanged and
the format version stays 1. As with transport annotations, restoring onto a
device that already holds the ride keeps that device's entry, so a trim authored
on another phone is not imported.

### BIKEYARD connection and save-time consent

BIKEYARD tokens, rider profile, delivery receipts and prepared upload snapshots
live under Android `noBackupFilesDir` and are not included in the local archive.
The recording index may contain an optional `bikeyard_auto_request` with the
account/environment key, opt-in identifier and selected visibility from the save.
This is a crash-recovery marker, not a credential. Restore explicitly clears it
on imported entries: importing an archive never authorizes automatic uploads.
