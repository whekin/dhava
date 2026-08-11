//! Streaming segment timing for the recording screen.
//!
//! This is the live half of [`crate::segment::match_segment`], not a second
//! implementation of it: the gates, the direction tolerance, the corridor, the
//! backtrack allowance and the coverage bins all come from that module, and
//! the rules are applied to the same fused positions the live map draws.
//!
//! What it deliberately cannot do is see the future. Canonical matching runs
//! the bounded post-pass over the whole ride before it decides anything, so a
//! live result is provisional by construction: it is the rider's answer at the
//! finish gate, and the canonical answer replaces it after Finish. Every event
//! below says so, and the UI must not present a live time as final.

use std::sync::Mutex;

use crate::segment::{
    DIRECTION_TOLERANCE_DEG, GateLine, MAX_ATTEMPT_GAP_MS, MAX_BACKTRACK_FRACTION,
    MIN_COVERAGE_FRACTION, arclength, coverage_bin_index, coverage_bins, gate_at_finish,
    gate_at_start, nearest_on_polyline,
};
use crate::{LatLon, SegmentDefinition, bearing_diff_deg, project, segment_intersection};

/// One segment the rider can be timed on, with the personal record it has to
/// beat. `best_elapsed_ms` is the caller's countable PR; a segment without a
/// countable run passes `None` and its first completion is not called a record.
#[derive(Debug, Clone, uniffi::Record)]
pub struct LiveSegmentArm {
    pub definition: SegmentDefinition,
    pub best_elapsed_ms: Option<i64>,
}

/// Why a run that had started stopped counting.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum LiveRunEnd {
    /// Left the corridor — a different trail, or a crash off the line.
    OffCorridor,
    /// Too much backward progress: the rider turned around.
    Backtracked,
    /// Reached the finish gate without covering the segment.
    Incomplete,
    /// Manual pause or a recording gap between the gates.
    Interrupted,
}

#[derive(Debug, Clone, uniffi::Enum)]
pub enum LiveSegmentEvent {
    /// The rider crossed the start gate.
    Started {
        segment_id: String,
        name: String,
        timestamp_ms: i64,
    },
    /// The rider crossed the finish gate and the run held up.
    ///
    /// `delta_ms` is negative when this run beat the personal record; it is
    /// `None` when there was no countable record to compare against.
    Finished {
        segment_id: String,
        name: String,
        timestamp_ms: i64,
        elapsed_ms: i64,
        delta_ms: Option<i64>,
        personal_record: bool,
    },
    /// The rider is inside a segment and this is how far the finish is.
    ///
    /// Emitted on every accepted fix during a run. Distance along the
    /// centreline rather than straight-line, because that is what the rider
    /// still has to ride, and it is the same arclength the corridor test
    /// already computes.
    Progress {
        segment_id: String,
        elapsed_ms: i64,
        remaining_m: f64,
    },
    /// A started run stopped counting before it could finish.
    Ended {
        segment_id: String,
        name: String,
        timestamp_ms: i64,
        reason: LiveRunEnd,
    },
}

#[derive(Debug)]
struct Run {
    started_at_ms: i64,
    visited: Vec<bool>,
    last_progress_m: f64,
    backtrack_m: f64,
}

#[derive(Debug)]
struct Armed {
    id: String,
    name: String,
    origin: LatLon,
    centerline: Vec<[f64; 2]>,
    arclength: Vec<f64>,
    total_length_m: f64,
    corridor_m: f64,
    bins: usize,
    start_gate: GateLine,
    finish_gate: GateLine,
    reach: Reach,
    best_elapsed_ms: Option<i64>,
    last: Option<Fix>,
    run: Option<Run>,
}

/// Padded box around a segment, in its own projected frame.
///
/// Every armed segment is tested against every fused fix, and the corridor
/// test walks the whole centerline. On a phone carrying a trail's worth of
/// segments that is the recorder's most repetitive work, so a run that has
/// not started is dismissed by four comparisons first. The padding covers the
/// corridor and both gates, so nothing inside it can be missed: a fix that
/// cannot reach the box cannot cross a gate or enter the corridor.
#[derive(Debug, Clone, Copy)]
struct Reach {
    min_x: f64,
    min_y: f64,
    max_x: f64,
    max_y: f64,
}

impl Reach {
    /// True when the travel between two fixes can touch the box at all.
    ///
    /// Compares whole boxes rather than endpoints, so a fast vehicle crossing
    /// the area between two distant fixes is still handed to the real test.
    fn touched_by(&self, from: [f64; 2], to: [f64; 2]) -> bool {
        from[0].min(to[0]) <= self.max_x
            && from[0].max(to[0]) >= self.min_x
            && from[1].min(to[1]) <= self.max_y
            && from[1].max(to[1]) >= self.min_y
    }
}

#[derive(Debug, Clone, Copy)]
struct Fix {
    timestamp_ms: i64,
    section_id: i32,
    position: [f64; 2],
}

/// Times the armed segments against the live fused track.
///
/// Construct one per ride from every local segment; arming is cheap because a
/// segment that is nowhere near the rider simply never has a gate crossed.
#[derive(Debug, uniffi::Object)]
pub struct LiveSegmentTracker {
    armed: Mutex<Vec<Armed>>,
}

#[uniffi::export]
impl LiveSegmentTracker {
    #[uniffi::constructor]
    pub fn new(segments: Vec<LiveSegmentArm>) -> Self {
        Self {
            armed: Mutex::new(segments.into_iter().filter_map(arm).collect()),
        }
    }

    /// Number of segments this tracker can actually time.
    pub fn armed_count(&self) -> u32 {
        self.armed
            .lock()
            .expect("live segment mutex poisoned")
            .len() as u32
    }

    /// Feeds one fused position and returns everything that just happened.
    ///
    /// `section_id` is the live recording section: a manual pause starts a new
    /// one, and a run may never bridge two, exactly as canonical matching
    /// refuses to bridge a pause.
    pub fn push(
        &self,
        timestamp_ms: i64,
        lat: f64,
        lon: f64,
        section_id: i32,
    ) -> Vec<LiveSegmentEvent> {
        let mut events = Vec::new();
        let mut armed = self.armed.lock().expect("live segment mutex poisoned");
        for segment in armed.iter_mut() {
            let fix = Fix {
                timestamp_ms,
                section_id,
                position: project(LatLon { lat, lon }, segment.origin),
            };
            advance(segment, fix, &mut events);
            segment.last = Some(fix);
        }
        events
    }
}

fn arm(input: LiveSegmentArm) -> Option<Armed> {
    let definition = input.definition;
    if definition.centerline.len() < 2 {
        return None;
    }
    let origin = definition.centerline[definition.centerline.len() / 2];
    let centerline: Vec<[f64; 2]> = definition
        .centerline
        .iter()
        .map(|point| project(*point, origin))
        .collect();
    let arclength = arclength(&centerline);
    let total_length_m = *arclength.last().unwrap_or(&0.0);
    if total_length_m <= 0.0 {
        return None;
    }
    let start_gate = gate_at_start(
        &centerline,
        project(definition.start_gate_center, origin),
        definition.gate_half_width_m,
    )?;
    let finish_gate = gate_at_finish(
        &centerline,
        project(definition.finish_gate_center, origin),
        definition.gate_half_width_m,
    )?;
    let pad_m = definition.corridor_m.max(definition.gate_half_width_m);
    let reach = Reach {
        min_x: centerline.iter().fold(f64::MAX, |low, p| low.min(p[0])) - pad_m,
        min_y: centerline.iter().fold(f64::MAX, |low, p| low.min(p[1])) - pad_m,
        max_x: centerline.iter().fold(f64::MIN, |high, p| high.max(p[0])) + pad_m,
        max_y: centerline.iter().fold(f64::MIN, |high, p| high.max(p[1])) + pad_m,
    };
    Some(Armed {
        id: definition.id,
        name: definition.name,
        origin,
        centerline,
        arclength,
        bins: coverage_bins(total_length_m),
        total_length_m,
        corridor_m: definition.corridor_m,
        start_gate,
        finish_gate,
        reach,
        best_elapsed_ms: input.best_elapsed_ms,
        last: None,
        run: None,
    })
}

fn advance(segment: &mut Armed, fix: Fix, events: &mut Vec<LiveSegmentEvent>) {
    let Some(previous) = segment.last else {
        return;
    };
    let dt_ms = fix.timestamp_ms - previous.timestamp_ms;
    let continuous =
        previous.section_id == fix.section_id && (1..=MAX_ATTEMPT_GAP_MS).contains(&dt_ms);
    if !continuous {
        // A pause or a stalled GPS between two fixes cannot be timed through,
        // and it cannot invent a gate crossing either.
        if segment.run.is_some() {
            segment.run = None;
            events.push(ended(segment, fix.timestamp_ms, LiveRunEnd::Interrupted));
        }
        return;
    }

    // A segment the rider is nowhere near cannot start, and a run in progress
    // must always be measured — including the fix that leaves the corridor.
    if segment.run.is_none() && !segment.reach.touched_by(previous.position, fix.position) {
        return;
    }

    let travel = [
        fix.position[0] - previous.position[0],
        fix.position[1] - previous.position[1],
    ];
    let moved = travel[0] != 0.0 || travel[1] != 0.0;
    let travel_bearing = travel[0].atan2(travel[1]).to_degrees().rem_euclid(360.0);

    if segment.run.is_some()
        && let Some(reason) = accumulate(segment, fix.position)
    {
        segment.run = None;
        events.push(ended(segment, fix.timestamp_ms, reason));
        return;
    }

    if !moved {
        return;
    }

    // Finish is tested before start so a run cannot be restarted by the same
    // pair of fixes that completed it.
    if segment.run.is_some()
        && let Some(crossed_ms) = crossing_ms(
            previous,
            travel,
            travel_bearing,
            &segment.finish_gate,
            dt_ms,
        )
    {
        finish(segment, crossed_ms, events);
        return;
    }

    // Still inside: report how much trail is left, which is what the
    // approach haptics count down.
    if let Some(run) = &segment.run {
        events.push(LiveSegmentEvent::Progress {
            segment_id: segment.id.clone(),
            elapsed_ms: fix.timestamp_ms - run.started_at_ms,
            remaining_m: (segment.total_length_m - run.last_progress_m).max(0.0),
        });
        return;
    }

    if segment.run.is_none()
        && let Some(crossed_ms) =
            crossing_ms(previous, travel, travel_bearing, &segment.start_gate, dt_ms)
    {
        let mut run = Run {
            started_at_ms: crossed_ms,
            visited: vec![false; segment.bins],
            last_progress_m: 0.0,
            backtrack_m: 0.0,
        };
        // Seed from the fix before the gate, exactly as canonical validation
        // starts at the first point of the crossing pair.
        let (_, entry_progress) =
            nearest_on_polyline(previous.position, &segment.centerline, &segment.arclength);
        run.last_progress_m = entry_progress;
        segment.run = Some(run);
        let started_at = crossed_ms;
        if accumulate(segment, fix.position).is_some() {
            // Off the corridor on the very first step: never a run at all.
            segment.run = None;
            return;
        }
        events.push(LiveSegmentEvent::Started {
            segment_id: segment.id.clone(),
            name: segment.name.clone(),
            timestamp_ms: started_at,
        });
    }
}

/// Adds one inside-the-gates position to the active run, or returns why the
/// run stopped counting.
fn accumulate(segment: &mut Armed, position: [f64; 2]) -> Option<LiveRunEnd> {
    let corridor_m = segment.corridor_m;
    let total_length_m = segment.total_length_m;
    let bins = segment.bins;
    let (deviation_m, progress_m) =
        nearest_on_polyline(position, &segment.centerline, &segment.arclength);
    let run = segment.run.as_mut()?;
    if deviation_m > corridor_m {
        return Some(LiveRunEnd::OffCorridor);
    }
    run.backtrack_m += (run.last_progress_m - progress_m).max(0.0);
    run.last_progress_m = progress_m;
    run.visited[coverage_bin_index(progress_m, total_length_m, bins)] = true;
    if run.backtrack_m > total_length_m * MAX_BACKTRACK_FRACTION {
        return Some(LiveRunEnd::Backtracked);
    }
    None
}

fn finish(segment: &mut Armed, timestamp_ms: i64, events: &mut Vec<LiveSegmentEvent>) {
    let Some(run) = segment.run.take() else {
        return;
    };
    let covered = run.visited.iter().filter(|seen| **seen).count() as f64 / segment.bins as f64;
    if covered < MIN_COVERAGE_FRACTION {
        events.push(ended(segment, timestamp_ms, LiveRunEnd::Incomplete));
        return;
    }
    let elapsed_ms = timestamp_ms - run.started_at_ms;
    if elapsed_ms <= 0 {
        events.push(ended(segment, timestamp_ms, LiveRunEnd::Incomplete));
        return;
    }
    let delta_ms = segment.best_elapsed_ms.map(|best| elapsed_ms - best);
    let personal_record = segment
        .best_elapsed_ms
        .is_some_and(|best| elapsed_ms < best);
    // The rider's own record moves immediately: a second run down the same
    // trail in one ride is compared against the faster of the two.
    if segment.best_elapsed_ms.is_none_or(|best| elapsed_ms < best) {
        segment.best_elapsed_ms = Some(elapsed_ms);
    }
    events.push(LiveSegmentEvent::Finished {
        segment_id: segment.id.clone(),
        name: segment.name.clone(),
        timestamp_ms,
        elapsed_ms,
        delta_ms,
        personal_record,
    });
}

fn ended(segment: &Armed, timestamp_ms: i64, reason: LiveRunEnd) -> LiveSegmentEvent {
    LiveSegmentEvent::Ended {
        segment_id: segment.id.clone(),
        name: segment.name.clone(),
        timestamp_ms,
        reason,
    }
}

/// Timestamp at which `travel` crosses `gate` in the segment's direction.
fn crossing_ms(
    from: Fix,
    travel: [f64; 2],
    travel_bearing: f64,
    gate: &GateLine,
    dt_ms: i64,
) -> Option<i64> {
    let gate_vec = [gate.b[0] - gate.a[0], gate.b[1] - gate.a[1]];
    let (t, _) = segment_intersection(from.position, travel, gate.a, gate_vec)?;
    if bearing_diff_deg(travel_bearing, gate.bearing_deg) > DIRECTION_TOLERANCE_DEG {
        return None;
    }
    Some(from.timestamp_ms + (t * dt_ms as f64).round() as i64)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::SegmentDefinition;

    /// A straight 200 m northbound segment starting at the origin.
    fn definition() -> SegmentDefinition {
        let centerline: Vec<LatLon> = (0..=20)
            .map(|step| LatLon {
                lat: north_of(41.7, step as f64 * 10.0),
                lon: 44.8,
            })
            .collect();
        SegmentDefinition {
            id: "seg".to_string(),
            name: "Test trail".to_string(),
            source_recording_id: "ride".to_string(),
            geometry_version: 2,
            start_gate_center: centerline[0],
            finish_gate_center: centerline[centerline.len() - 1],
            centerline,
            gate_half_width_m: 15.0,
            corridor_m: 20.0,
            length_m: 200.0,
            ascent_m: Some(0.0),
            descent_m: Some(40.0),
            elevation_profile: Vec::new(),
            trusted: false,
        }
    }

    fn north_of(base_lat: f64, metres: f64) -> f64 {
        base_lat + (metres / crate::EARTH_RADIUS_M).to_degrees()
    }

    fn tracker(best_elapsed_ms: Option<i64>) -> LiveSegmentTracker {
        LiveSegmentTracker::new(vec![LiveSegmentArm {
            definition: definition(),
            best_elapsed_ms,
        }])
    }

    /// Rides from 20 m before the start gate to 20 m past the finish gate,
    /// one fix per second at `step_m` per fix.
    fn ride(tracker: &LiveSegmentTracker, step_m: f64, lon: f64) -> Vec<LiveSegmentEvent> {
        let mut events = Vec::new();
        let mut travelled = -20.0;
        let mut timestamp_ms = 0;
        while travelled <= 220.0 {
            events.extend(tracker.push(timestamp_ms, north_of(41.7, travelled), lon, 0));
            travelled += step_m;
            timestamp_ms += 1_000;
        }
        events
    }

    #[test]
    fn a_complete_run_is_timed_and_compared_with_the_record() {
        let tracker = tracker(Some(25_000));
        let events = ride(&tracker, 10.0, 44.8);
        assert!(matches!(
            events.first(),
            Some(LiveSegmentEvent::Started { .. })
        ));
        match events.last() {
            Some(LiveSegmentEvent::Finished {
                elapsed_ms,
                delta_ms,
                personal_record,
                ..
            }) => {
                // 200 m at 10 m per second, gates interpolated between fixes.
                assert!(
                    (19_000..=21_000).contains(elapsed_ms),
                    "elapsed {elapsed_ms} ms is not the ridden 20 s",
                );
                assert_eq!(*delta_ms, Some(elapsed_ms - 25_000));
                assert!(personal_record, "20 s did not beat a 25 s record");
            }
            other => panic!("expected a finished run, got {other:?}"),
        }
    }

    #[test]
    fn a_first_run_is_timed_but_is_not_called_a_record() {
        let tracker = tracker(None);
        let events = ride(&tracker, 10.0, 44.8);
        match events.last() {
            Some(LiveSegmentEvent::Finished {
                delta_ms,
                personal_record,
                ..
            }) => {
                assert_eq!(*delta_ms, None);
                assert!(!personal_record, "a first run has nothing to beat");
            }
            other => panic!("expected a finished run, got {other:?}"),
        }
    }

    #[test]
    fn a_slower_second_run_reports_a_positive_delta() {
        let tracker = tracker(Some(25_000));
        ride(&tracker, 10.0, 44.8);
        let events = ride(&tracker, 5.0, 44.8);
        match events.last() {
            Some(LiveSegmentEvent::Finished {
                delta_ms,
                personal_record,
                ..
            }) => {
                // Compared against this ride's own 20 s run, not the old 25 s.
                assert!(
                    delta_ms.is_some_and(|delta| delta > 15_000),
                    "delta {delta_ms:?} did not use the run just ridden",
                );
                assert!(!personal_record);
            }
            other => panic!("expected a finished run, got {other:?}"),
        }
    }

    #[test]
    fn a_parallel_trail_outside_the_corridor_is_never_timed() {
        let tracker = tracker(Some(25_000));
        // 60 m east of the centerline: past the 20 m corridor and the gates.
        let east =
            44.8 + (60.0 / (crate::EARTH_RADIUS_M * 41.7_f64.to_radians().cos())).to_degrees();
        let events = ride(&tracker, 10.0, east);
        assert!(events.is_empty(), "off-corridor ride produced {events:?}");
    }

    #[test]
    fn a_manual_pause_between_the_gates_ends_the_run() {
        let tracker = tracker(None);
        tracker.push(0, north_of(41.7, -10.0), 44.8, 0);
        let started = tracker.push(1_000, north_of(41.7, 10.0), 44.8, 0);
        assert!(matches!(
            started.first(),
            Some(LiveSegmentEvent::Started { .. })
        ));
        let paused = tracker.push(400_000, north_of(41.7, 30.0), 44.8, 1);
        assert!(
            matches!(
                paused.first(),
                Some(LiveSegmentEvent::Ended {
                    reason: LiveRunEnd::Interrupted,
                    ..
                })
            ),
            "pause produced {paused:?}",
        );
    }

    #[test]
    fn turning_back_inside_the_segment_ends_the_run() {
        let tracker = tracker(None);
        tracker.push(0, north_of(41.7, -10.0), 44.8, 0);
        tracker.push(1_000, north_of(41.7, 10.0), 44.8, 0);
        let mut events = Vec::new();
        for step in 0..8 {
            // Ride up to 90 m, then roll back down the same line.
            events.extend(tracker.push(
                2_000 + step * 1_000,
                north_of(41.7, 90.0 - step as f64 * 10.0),
                44.8,
                0,
            ));
        }
        assert!(
            events.iter().any(|event| matches!(
                event,
                LiveSegmentEvent::Ended {
                    reason: LiveRunEnd::Backtracked,
                    ..
                }
            )),
            "backtracking produced {events:?}",
        );
    }

    #[test]
    fn a_run_in_progress_reports_the_trail_left_to_ride() {
        let tracker = tracker(None);
        let mut remaining = Vec::new();
        let mut travelled = -20.0;
        let mut timestamp_ms = 0;
        while travelled <= 150.0 {
            for event in tracker.push(timestamp_ms, north_of(41.7, travelled), 44.8, 0) {
                if let LiveSegmentEvent::Progress { remaining_m, .. } = event {
                    remaining.push(remaining_m);
                }
            }
            travelled += 10.0;
            timestamp_ms += 1_000;
        }

        assert!(remaining.len() > 5, "no progress was reported");
        assert!(
            remaining.windows(2).all(|pair| pair[1] <= pair[0] + 0.001),
            "the finish stopped getting closer: {remaining:?}",
        );
        let last = *remaining.last().expect("checked above");
        assert!(
            (40.0..=70.0).contains(&last),
            "150 m into a 200 m segment left {last} m",
        );
    }
}
