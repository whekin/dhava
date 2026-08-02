# Dhava local backup format

Dhava backups are user-owned ZIP archives created and opened through Android's
Storage Access Framework. The suggested filename is
`dhava-backup-YYYY-MM-DD-HHmm.zip`.

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
Only after the whole archive passes verification does Dhava merge it into local
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
Dhava also keeps 64 MB of free internal space beyond the staging requirement.
