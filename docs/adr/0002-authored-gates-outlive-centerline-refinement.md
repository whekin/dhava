# Authored gate centers outlive reference-centerline refinement

A segment needs two kinds of geometry with different ownership. Its reference
centerline is accumulated evidence about where the trail runs; its start and
finish gate centers are the author's timing intent. Geometry v3 stores the gate
centers explicitly rather than deriving them forever from the first and last
centerline points.

The editor may place either gate center at any valid map coordinate. It does not
snap the coordinate to a GPX point or to the reference centerline. Rust remains
the authority for gate direction and matching: a gate is perpendicular to the
nearby centerline tangent, so moving a center does not let Android invent a
different travel direction.

## Considered options

- **Keep gates tied to centerline endpoints.** Rejected: later multi-pass
  refinement could move a rider's chosen timing boundary without an explicit
  edit, changing every result under the same apparent segment.
- **Snap gates to source samples.** Rejected: 1 Hz GPX and GPS can put samples
  several metres apart even when the trail or road is clearly visible on the
  map. More interpolated samples do not add spatial evidence and still prevent
  precise authored placement.
- **Store only the two gates.** Rejected: gates cannot distinguish a parallel
  trail, shortcut or incomplete traversal. Matching still needs the directed
  reference centerline for corridor, progress and coverage checks.
- **Treat imported GPX as a ride.** Rejected: it lacks Dhava's raw GPS/IMU/baro
  evidence and cannot honestly produce an attempt, PR or leaderboard result.

## Consequences

- Old geometry v1/v2 segments remain readable; absent gate centers are derived
  once from their existing centerline endpoints.
- New segments use geometry v3 and matching rules `gates-0.3`. Search bounds
  include independently placed gate centers.
- An imported GPX trace is copied into durable local source storage and used
  only as seed geometry for a local draft segment. Deleting or refining the
  segment does not erase the original import.
- Future Dhava attempts may refine the reference centerline when their evidence
  quality is acceptable. That process increments the geometry version but keeps
  both authored gate centers unchanged; moving a gate is a separate explicit
  edit and necessarily creates a new scoring geometry version.
