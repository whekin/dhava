# Nakvali — Vision

Nakvali is an offline-first downhill ride recorder and **personal timing tool**.
It should be valuable to one rider: reliable raw capture, private timed sections,
repeated-run comparison and sensor analysis on the phone. BIKEYARD is the home
for public trails, shared results and the riding community.

## What the rider gets

- **Reliable capture.** Record GPS, IMU and barometer data through a long ride,
  pauses, shuttles and loss of connectivity. Keep the raw recording on the
  phone so later algorithms can recompute the result.
- **My segments.** Define private, directed start and finish gates for a whole
  descent, a technical section or a combination of trails. Segment definitions
  and timing stay local; a segment does not claim to be a BIKEYARD trail.
- **Honest timing.** Crossing a gate matters more than lingering near a GPS
  radius. Show uncertainty, incomplete attempts and reasons a run did not
  count rather than silently awarding a record.
- **Personal progress.** Compare each countable attempt with the rider's own
  previous runs and personal record. Live feedback may show progress and a PR
  delta without needing other riders or a network connection.
- **Riding-first totals.** Descents lead. Shuttles, lifts, transport and pauses
  explain the day but are not credited as ridden distance or climb. Short and
  overlapping personal segments can be useful without a public catalogue.
- **Sensor context.** Show airtime and likely-jump estimates with their quality
  and phone-specific provenance. Repeated good passes may refine a segment's
  reference line without moving rider-authored gates.

## Relationship to BIKEYARD

Nakvali can send a processed ride to BIKEYARD when the rider opts in and show
BIKEYARD's matched trail results beside its own personal timing. BIKEYARD owns
public trail identity, discovery, community leaderboards, honours, conditions
and social features. Nakvali attributes those results and links back to BY.

The two geometries answer different questions. A BIKEYARD trail may contain
several personal timing segments; a personal segment may span several trails.
An optional external trail reference adds context, never authoritative gates
or an automatic one-to-one mapping.

Public Nakvali segment publishing, a second KOM system, public leaderboards
and a parallel trail/social catalogue are outside the product path. A private
segment stays local even if the ride that traverses it is uploaded; the ride's
track is still shared with BIKEYARD under the chosen visibility.

## Look & feel

Beautiful and simple. Material 3 Expressive, dark-first, oversized live-timing
typography. The map and current ride are working surfaces, not a dashboard of
social rankings.

## Reference

IMU/GPS fusion paper: https://www.mdpi.com/1424-8220/24/18/5873
(magnetometer as weak heading constraint, no calibration required from user).
