# Dhava

Dhava is a downhill-first ride tracker organized around timed trail sections rather
than complete routes.

## Language

**Segment**:
A directed, timed trail section bounded by start and finish gates and represented by reference geometry.
_Avoid_: Route, activity

**Draft segment**:
A segment whose reference geometry comes from one ride and is not yet trusted for correcting rider positions.
_Avoid_: Trusted segment, published segment

**Candidate descent**:
A continuous rideable descent found in one ride that the editor offers as a ready-made segment selection, without a stop, a recording gap or motorised evidence inside it.
_Avoid_: Downhill run, proposal, suggestion

**Segment attempt**:
One continuous traversal evaluated independently against one segment from its directed start crossing until a finish or rejection.
_Avoid_: Ride, recording, segment

**Local segment**:
A segment authored and stored on the rider's device that is not published or discoverable by other riders.
_Avoid_: Public segment, nearby segment

**Published segment**:
A segment accepted for shared use, so that other riders can discover it and be timed on it.
_Avoid_: Draft segment, local segment, trusted segment

**Segment overlap**:
The relationship between two segment definitions covering substantially the same trail in the same direction. It warns a rider who is about to author a duplicate and constrains whether a segment can be published; it never merges definitions and never affects how attempts are timed.
_Avoid_: Duplicate segment, same segment

**Difficulty grade**:
The riding difficulty of a segment on the colour scale riders already read off trail signage, and the only meaning a segment's colour carries.
_Avoid_: Segment colour, rating, quality

**Segment library**:
The rider-facing collection of locally authored and downloaded segments currently available for offline browsing and timing.
_Avoid_: Segment feed, global catalog

**On-device segment catalog**:
The segment definitions currently available without network access, including locally authored and previously downloaded segments.
_Avoid_: Global segment catalog, server catalog

**Active segment set**:
The geographic subset of the on-device segment catalog relevant to the rider's current area and eligible for on-ride detection.
_Avoid_: All segments, global segments

**Riding area**:
A named place whose segment catalog and leaderboard snapshots can be downloaded and updated together for offline riding.
_Avoid_: Map tile, city filter

**On-ride segment result**:
A locally computed segment result available shortly after the finish gate is confirmed, while the enclosing ride recording continues.
_Avoid_: Live delta, provisional time, post-upload result

**Countable attempt**:
A segment attempt measured well enough to stand as a result, so it may set a personal record or enter a leaderboard.
_Avoid_: Valid attempt, successful attempt

**Uncertain attempt**:
A segment attempt that completed the segment but is not countable, always presented together with the reason it does not count.
_Avoid_: Failed attempt, invalid attempt, rejected attempt

**Personal record (PR)**:
The rider's fastest countable attempt known on the device for the current segment definition, including results not yet synchronized.
_Avoid_: KOM, latest result

**Leaderboard snapshot**:
A versioned, time-stamped copy of a segment leaderboard available on the device for offline comparison.
_Avoid_: Live leaderboard, final leaderboard

**Active leaderboard**:
The explicitly identified category leaderboard used for the rider's immediate segment comparison.
_Avoid_: Overall leaderboard, unnamed KOM

**Potential KOM**:
An unverified attempt faster than the KOM in the device's latest leaderboard snapshot.
_Avoid_: KOM, confirmed KOM

**KOM**:
The fastest eligible segment attempt confirmed by the server for a particular leaderboard.
_Avoid_: Potential KOM, local best
