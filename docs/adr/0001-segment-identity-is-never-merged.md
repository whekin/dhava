# Segment identity is never merged; overlap is a publication concern

A rider can author a local draft segment on a trail that a published segment
already covers, so the same descent can produce two independent results. We
decided that Dhava never deduplicates or merges segment definitions: a draft and
a published segment are separate identities with separate geometry versions,
separate attempts and separate personal records, and both are timed. Whether two
segments may cover the same trail is decided at publication time — by moderation
and by the rider community — not by the timing engine.

## Considered options

- **Automatic deduplication on download.** Rejected: deciding that two lines are
  "the same trail" is exactly the trusted-centerline problem we deliberately
  deferred, and getting it wrong destroys the rider's own history.
- **Migrating attempts onto the surviving definition.** Rejected as dishonest:
  an attempt's time belongs to the gates and corridor of one geometry version.
  Different gates mean a different time, so a migrated `3:23.6` would be a
  fabricated number. Re-matching the raw recording is legitimate — and it
  already happens on its own under this decision.
- **Offering to link a draft to a look-alike published segment.** Kept as a
  possible hint, but it may only ever change what is shown, never silently
  retire a definition or move results between definitions.

## Consequences

Two nearly identical lines can appear on the library map, and one descent can
produce two results. That is deliberate, so the map and every result surface
must label which definition a result belongs to, and archiving a draft has to be
an explicit rider action rather than something the app performs on their behalf.
