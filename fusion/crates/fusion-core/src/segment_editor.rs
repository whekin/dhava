//! Authoring support for the segment editor.
//!
//! The editor needs three things that are all geometry, and geometry lives
//! here rather than in the UI: the elevation and gradient story of a whole
//! ride, the descents worth offering as ready-made selections, and whether a
//! selection duplicates a segment that already exists.
//!
//! Every value the editor draws or snaps to therefore comes from the same code
//! that later authors the definition, so the chart the rider trims on and the
//! persisted segment can never disagree.

use crate::canonical::ascent_descent;
use crate::segment::{
    MAX_ATTEMPT_GAP_MS, SegmentDefinition, arclength, continuous_selection, nearest_on_polyline,
    polyline_length_m,
};
use crate::{ActivityState, CanonicalTrackPoint, LatLon, project};

/// Enough samples for a smooth phone-width chart of a multi-hour ride without
/// shipping the full 5 Hz track across the FFI boundary on every redraw.
const MAX_RIDE_PROFILE_POINTS: usize = 720;
/// Gradient is measured over a window, not between neighbouring 5 Hz samples:
/// adjacent fixes are dominated by altitude noise.
const GRADIENT_WINDOW_M: f64 = 25.0;

/// Shortest descent worth proposing. Below this a segment is dominated by gate
/// geometry and GPS uncertainty rather than by riding.
const MIN_CANDIDATE_LENGTH_M: f64 = 300.0;
/// A candidate may contain some climbing — a flat or pedalled link between two
/// steep pitches is part of the trail — but not enough to make it a loop.
const MAX_CANDIDATE_ASCENT_FRACTION: f64 = 0.15;
/// Non-descending stretches shorter than this are bridged instead of splitting
/// one trail into fragments that each fall under the length floor.
const BRIDGE_MAX_MS: i64 = 8_000;
const BRIDGE_MAX_M: f64 = 40.0;
/// A stationary wait may last much longer than an ordinary trail link. It only
/// joins two downhill spans when the canonical held position confirms that the
/// rider stayed in essentially the same place.
const STILL_BRIDGE_MAX_M: f64 = 12.0;
/// Coverage above which a selection is reported as duplicating an existing
/// segment. It warns the rider; it never blocks authoring.
const SUBSTANTIAL_OVERLAP_FRACTION: f64 = 0.6;

/// One sample of a whole ride's elevation and gradient story.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct RideProfilePoint {
    /// Continuous position in the finalized track this sample came from, so the
    /// editor can map a point on the chart back to a gate position without
    /// doing geometry of its own.
    pub position: f64,
    /// Distance actually ridden from the start of the ride, meters. Manual
    /// pauses and recording gaps contribute nothing, so the axis never contains
    /// a jump the rider did not ride.
    pub distance_m: f64,
    pub altitude_m: Option<f64>,
    /// Signed gradient over a [`GRADIENT_WINDOW_M`] window, percent. Negative
    /// is descending.
    pub gradient_percent: Option<f64>,
    /// Continuous recording section this sample belongs to.
    pub section_id: i32,
    /// False when the previous sample is separated by a manual pause or a
    /// recording gap, so a chart can break the line instead of drawing across.
    pub continues: bool,
}

/// A whole ride, reduced to what an elevation chart needs.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct RideProfile {
    pub points: Vec<RideProfilePoint>,
    pub length_m: f64,
    pub min_altitude_m: Option<f64>,
    pub max_altitude_m: Option<f64>,
}

/// A descent the editor can offer as a ready-made selection.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct CandidateDescent {
    pub start_position: f64,
    pub end_position: f64,
    pub length_m: f64,
    pub ascent_m: Option<f64>,
    pub descent_m: Option<f64>,
    /// Mean gradient over the candidate, percent. Negative is descending.
    pub gradient_percent: Option<f64>,
}

/// How much a selection duplicates a segment that already exists.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SelectionOverlap {
    pub segment_id: String,
    pub segment_name: String,
    /// Fraction of the selection lying inside that segment's own corridor while
    /// running in the same direction.
    pub coverage: f64,
}

/// Elevation and gradient of a whole finalized track, sampled for a chart.
#[uniffi::export]
pub fn ride_profile(track: Vec<CanonicalTrackPoint>) -> RideProfile {
    if track.len() < 2 {
        return RideProfile {
            points: Vec::new(),
            length_m: 0.0,
            min_altitude_m: None,
            max_altitude_m: None,
        };
    }
    let distances = ridden_distances(&track);
    let length_m = *distances.last().unwrap_or(&0.0);

    let stride = (track.len() - 1).div_ceil(MAX_RIDE_PROFILE_POINTS.max(2) - 1);
    let mut points: Vec<RideProfilePoint> = Vec::with_capacity(MAX_RIDE_PROFILE_POINTS + 8);
    let mut previous: Option<usize> = None;
    for index in 0..track.len() {
        // Sample on a fixed stride, but never skip the first or last point of a
        // section: those carry the pause boundaries the chart must break at.
        let boundary = index == 0
            || index == track.len() - 1
            || !connects(&track, index)
            || !connects(&track, index + 1);
        if index % stride != 0 && !boundary {
            continue;
        }
        let continues = previous.is_some_and(|previous| {
            ((previous + 1)..=index).all(|between| connects(&track, between))
        });
        points.push(RideProfilePoint {
            position: index as f64,
            distance_m: distances[index],
            altitude_m: track[index].altitude_m.filter(|value| value.is_finite()),
            gradient_percent: gradient_percent_at(&track, &distances, index),
            section_id: track[index].section_id,
            continues,
        });
        previous = Some(index);
    }

    let altitudes: Vec<f64> = points.iter().filter_map(|point| point.altitude_m).collect();
    RideProfile {
        points,
        length_m,
        min_altitude_m: altitudes.iter().copied().reduce(f64::min),
        max_altitude_m: altitudes.iter().copied().reduce(f64::max),
    }
}

/// Every descent in `track` worth offering, longest first.
///
/// Reuses the conservative [`ActivityState`] pass rather than inventing a
/// gradient heuristic, because a car descending a switchback road is
/// geometrically indistinguishable from a rider descending a trail. Pauses,
/// recording gaps and motorised evidence always end a candidate; a true
/// stationary wait and short non-descending links inside one trail do not.
/// Position along `track` closest to `point`, in continuous track index units.
///
/// Dragging a gate marker on the map has to move the selection with it. The
/// gate's authored centre stays wherever the rider dropped it — it is
/// deliberately independent from the centreline — but the span the map draws,
/// the trimmer handles and the preview all key off positions, so the position
/// has to follow the finger too. Doing the projection here keeps it the same
/// geometry the matcher uses rather than a second one in Kotlin.
#[uniffi::export]
pub fn nearest_track_position(track: Vec<CanonicalTrackPoint>, point: LatLon) -> f64 {
    if track.len() < 2 {
        return 0.0;
    }
    let middle = &track[track.len() / 2];
    let origin = LatLon {
        lat: middle.lat,
        lon: middle.lon,
    };
    let target = project(point, origin);
    let mut best = (f64::MAX, 0.0);
    for index in 0..track.len() - 1 {
        let a = project(
            LatLon {
                lat: track[index].lat,
                lon: track[index].lon,
            },
            origin,
        );
        let b = project(
            LatLon {
                lat: track[index + 1].lat,
                lon: track[index + 1].lon,
            },
            origin,
        );
        let ab = [b[0] - a[0], b[1] - a[1]];
        let length_sq = ab[0] * ab[0] + ab[1] * ab[1];
        let t = if length_sq > 0.0 {
            (((target[0] - a[0]) * ab[0] + (target[1] - a[1]) * ab[1]) / length_sq).clamp(0.0, 1.0)
        } else {
            0.0
        };
        let closest = [a[0] + ab[0] * t, a[1] + ab[1] * t];
        let distance = (target[0] - closest[0]).hypot(target[1] - closest[1]);
        if distance < best.0 {
            best = (distance, index as f64 + t);
        }
    }
    best.1
}

#[uniffi::export]
pub fn propose_descents(track: Vec<CanonicalTrackPoint>) -> Vec<CandidateDescent> {
    let mut candidates = Vec::new();
    let mut run: Option<(usize, usize)> = None;

    for index in 0..track.len() {
        let point = &track[index];
        let hard_stop =
            !connects(&track, index) || point.activity_state == ActivityState::LikelyMotorized;
        if hard_stop {
            if let Some((start, last)) = run.take() {
                push_candidate(&track, start, last, &mut candidates);
            }
            if point.activity_state == ActivityState::LikelyMotorized {
                continue;
            }
        }
        if point.activity_state != ActivityState::Downhill {
            continue;
        }
        match run {
            None => run = Some((index, index)),
            Some((start, last)) => {
                if bridgeable(&track, last, index) {
                    run = Some((start, index));
                } else {
                    push_candidate(&track, start, last, &mut candidates);
                    run = Some((index, index));
                }
            }
        }
    }
    if let Some((start, last)) = run {
        push_candidate(&track, start, last, &mut candidates);
    }

    candidates.sort_by(|a, b| {
        b.length_m
            .partial_cmp(&a.length_m)
            .unwrap_or(std::cmp::Ordering::Equal)
    });
    candidates
}

/// The existing segment a selection duplicates, if any.
///
/// Reported so the editor can warn a rider who is about to author the same
/// trail twice. It never merges definitions and never changes how attempts are
/// timed: whether two segments may cover one trail is decided when a segment is
/// published, not here.
#[uniffi::export]
pub fn selection_overlap(
    existing: Vec<SegmentDefinition>,
    track: Vec<CanonicalTrackPoint>,
    start_position: f64,
    end_position: f64,
) -> Option<SelectionOverlap> {
    if track.len() < 2 || start_position >= end_position {
        return None;
    }
    let selection = continuous_selection(&track, start_position, end_position).ok()?;
    if selection.len() < 2 {
        return None;
    }
    existing
        .iter()
        .filter_map(|definition| coverage_of(&selection, definition))
        .filter(|overlap| overlap.coverage >= SUBSTANTIAL_OVERLAP_FRACTION)
        .max_by(|a, b| {
            a.coverage
                .partial_cmp(&b.coverage)
                .unwrap_or(std::cmp::Ordering::Equal)
        })
}

fn coverage_of(
    selection: &[CanonicalTrackPoint],
    definition: &SegmentDefinition,
) -> Option<SelectionOverlap> {
    if definition.centerline.len() < 2 {
        return None;
    }
    let origin = definition.centerline[definition.centerline.len() / 2];
    let centerline: Vec<[f64; 2]> = definition
        .centerline
        .iter()
        .map(|point| project(*point, origin))
        .collect();
    let arc = arclength(&centerline);

    let mut inside = 0usize;
    let mut forward = 0i64;
    let mut previous_progress: Option<f64> = None;
    for point in selection {
        let planar = project(
            LatLon {
                lat: point.lat,
                lon: point.lon,
            },
            origin,
        );
        let (distance_m, progress_m) = nearest_on_polyline(planar, &centerline, &arc);
        if distance_m <= definition.corridor_m {
            inside += 1;
            if let Some(previous) = previous_progress {
                forward += if progress_m >= previous { 1 } else { -1 };
            }
            previous_progress = Some(progress_m);
        }
    }
    // A selection running the other way down the same trail is a different
    // segment, not a duplicate: gates are directed.
    if forward <= 0 {
        return None;
    }
    Some(SelectionOverlap {
        segment_id: definition.id.clone(),
        segment_name: definition.name.clone(),
        coverage: inside as f64 / selection.len() as f64,
    })
}

/// True when point `index` continues the one before it without a manual pause
/// or a recording gap.
fn connects(track: &[CanonicalTrackPoint], index: usize) -> bool {
    if index == 0 || index >= track.len() {
        return index == 0;
    }
    track[index].section_id == track[index - 1].section_id
        && (1..=MAX_ATTEMPT_GAP_MS)
            .contains(&(track[index].timestamp_ms - track[index - 1].timestamp_ms))
}

/// True when the non-descending stretch between two descending points is short
/// enough to be a link inside one trail rather than the end of it.
fn bridgeable(track: &[CanonicalTrackPoint], last: usize, index: usize) -> bool {
    if index <= last {
        return false;
    }
    if !((last + 1)..=index).all(|between| connects(track, between)) {
        return false;
    }
    let distance_m = polyline_length_m(&track[last..=index]);
    let bridge = &track[(last + 1)..index];
    if bridge
        .iter()
        .any(|point| point.activity_state == ActivityState::Still)
    {
        return bridge
            .iter()
            .all(|point| point.activity_state == ActivityState::Still)
            && distance_m <= STILL_BRIDGE_MAX_M;
    }
    track[index].timestamp_ms - track[last].timestamp_ms <= BRIDGE_MAX_MS
        && distance_m <= BRIDGE_MAX_M
}

fn push_candidate(
    track: &[CanonicalTrackPoint],
    start: usize,
    end: usize,
    candidates: &mut Vec<CandidateDescent>,
) {
    if end <= start {
        return;
    }
    let selection = &track[start..=end];
    let length_m = polyline_length_m(selection);
    if length_m < MIN_CANDIDATE_LENGTH_M {
        return;
    }
    let has_altitude = selection.iter().any(|point| point.altitude_m.is_some());
    let (ascent_m, descent_m) = ascent_descent(selection);
    if has_altitude {
        // Without a real drop it is not a descent, and too much climbing inside
        // means the selection is a loop rather than one trail.
        if descent_m <= 0.0 || ascent_m > descent_m * MAX_CANDIDATE_ASCENT_FRACTION {
            return;
        }
    }
    let gradient_percent = has_altitude.then(|| {
        let drop = selection
            .last()
            .and_then(|point| point.altitude_m)
            .zip(selection.first().and_then(|point| point.altitude_m))
            .map(|(last, first)| last - first)
            .unwrap_or(0.0);
        drop / length_m * 100.0
    });
    candidates.push(CandidateDescent {
        start_position: start as f64,
        end_position: end as f64,
        length_m,
        ascent_m: has_altitude.then_some(ascent_m),
        descent_m: has_altitude.then_some(descent_m),
        gradient_percent,
    });
}

/// Cumulative distance actually ridden, contributing nothing across a manual
/// pause or a recording gap.
fn ridden_distances(track: &[CanonicalTrackPoint]) -> Vec<f64> {
    let origin = LatLon {
        lat: track[track.len() / 2].lat,
        lon: track[track.len() / 2].lon,
    };
    let mut distances = Vec::with_capacity(track.len());
    let mut total = 0.0;
    distances.push(0.0);
    for index in 1..track.len() {
        if connects(track, index) {
            let a = project(
                LatLon {
                    lat: track[index - 1].lat,
                    lon: track[index - 1].lon,
                },
                origin,
            );
            let b = project(
                LatLon {
                    lat: track[index].lat,
                    lon: track[index].lon,
                },
                origin,
            );
            total += ((b[0] - a[0]).powi(2) + (b[1] - a[1]).powi(2)).sqrt();
        }
        distances.push(total);
    }
    distances
}

/// Gradient around `index`, measured over a window that never crosses a pause.
fn gradient_percent_at(
    track: &[CanonicalTrackPoint],
    distances: &[f64],
    index: usize,
) -> Option<f64> {
    let mut low = index;
    while low > 0 && connects(track, low) && distances[index] - distances[low] < GRADIENT_WINDOW_M {
        low -= 1;
    }
    let mut high = index;
    while high + 1 < track.len()
        && connects(track, high + 1)
        && distances[high] - distances[index] < GRADIENT_WINDOW_M
    {
        high += 1;
    }
    let run = distances[high] - distances[low];
    if run < 1.0 {
        return None;
    }
    let rise = track[high].altitude_m? - track[low].altitude_m?;
    if !rise.is_finite() {
        return None;
    }
    Some(rise / run * 100.0)
}

#[cfg(test)]
pub(crate) mod tests_support {
    use crate::{ActivityState, CanonicalTrackPoint};

    pub(crate) const LAT: f64 = 43.0;
    pub(crate) const LON: f64 = 42.0;
    pub(crate) const M_PER_DEG_LAT: f64 = 111_195.0;

    /// One 5 Hz sample, placed in meters north of the fixture origin. Going
    /// north descends, so a northbound leg is a descent.
    pub(crate) fn point(
        timestamp_ms: i64,
        north_m: f64,
        section_id: i32,
        activity_state: ActivityState,
    ) -> CanonicalTrackPoint {
        CanonicalTrackPoint {
            timestamp_ms,
            lat: LAT + north_m / M_PER_DEG_LAT,
            lon: LON,
            altitude_m: Some(1_000.0 - north_m * 0.1),
            accuracy_m: Some(5.0),
            speed_mps: Some(5.0),
            stationary: Some(false),
            section_id,
            activity_state,
            activity_confidence: 0.9,
        }
    }

    /// Appends `length_m` of northbound track sampled every meter at 5 Hz.
    pub(crate) fn append(
        track: &mut Vec<CanonicalTrackPoint>,
        length_m: i64,
        state: ActivityState,
    ) {
        let (mut timestamp_ms, mut north_m, section_id) = track
            .last()
            .map(|last| {
                (
                    last.timestamp_ms,
                    (last.lat - LAT) * M_PER_DEG_LAT,
                    last.section_id,
                )
            })
            .unwrap_or((0, 0.0, 0));
        for _ in 0..length_m {
            timestamp_ms += 200;
            north_m += 1.0;
            track.push(point(timestamp_ms, north_m, section_id, state));
        }
    }

    /// A straight descent of `length_m` meters.
    pub(crate) fn descending_track(length_m: i64) -> Vec<CanonicalTrackPoint> {
        let mut track = vec![point(0, 0.0, 0, ActivityState::Downhill)];
        append(&mut track, length_m, ActivityState::Downhill);
        track
    }
}

#[cfg(test)]
mod tests {
    use super::tests_support::{append, point};
    use super::*;

    /// Geometry fixtures are a couple of hundred metres — long enough to
    /// exercise gates and corridors, shorter than the production floor. The
    /// floor itself has its own test.
    const TEST_MIN_LENGTH_M: Option<f64> = Some(50.0);
    use crate::segment::build_segment;

    #[test]
    fn a_pause_breaks_the_profile_and_never_extends_the_distance_axis() {
        let mut track = vec![point(0, 0.0, 0, ActivityState::Downhill)];
        append(&mut track, 100, ActivityState::Downhill);
        // Resume 400 m away, one minute later, in a new section.
        let resume_ms = track.last().unwrap().timestamp_ms + 60_000;
        track.push(point(resume_ms, 500.0, 1, ActivityState::Downhill));
        append(&mut track, 100, ActivityState::Downhill);

        let profile = ride_profile(track);

        assert!(profile.points.iter().any(|point| !point.continues));
        // 200 m ridden, not the 400 m jump across the pause.
        assert!(
            (profile.length_m - 200.0).abs() < 5.0,
            "length_m was {}",
            profile.length_m
        );
        assert!(profile.points.iter().any(|point| point.section_id == 1));
        assert!(
            profile
                .points
                .iter()
                .filter_map(|point| point.gradient_percent)
                .all(|gradient| gradient < 0.0)
        );
    }

    #[test]
    fn a_short_flat_link_keeps_one_candidate() {
        let mut track = vec![point(0, 0.0, 0, ActivityState::Downhill)];
        append(&mut track, 150, ActivityState::Downhill);
        append(&mut track, 20, ActivityState::Transit);
        append(&mut track, 150, ActivityState::Downhill);

        let candidates = propose_descents(track);

        assert_eq!(candidates.len(), 1);
        assert!(
            candidates[0].length_m > 300.0,
            "length_m was {}",
            candidates[0].length_m
        );
        assert!(candidates[0].gradient_percent.is_some_and(|g| g < 0.0));
    }

    #[test]
    fn moving_while_labelled_still_splits_candidates() {
        let mut track = vec![point(0, 0.0, 0, ActivityState::Downhill)];
        append(&mut track, 350, ActivityState::Downhill);
        append(&mut track, 20, ActivityState::Still);
        append(&mut track, 350, ActivityState::Downhill);

        assert_eq!(propose_descents(track).len(), 2);
    }

    #[test]
    fn a_real_stationary_wait_does_not_split_one_trail() {
        let mut track = vec![point(0, 0.0, 0, ActivityState::Downhill)];
        append(&mut track, 250, ActivityState::Downhill);
        let stopped = track.last().unwrap().clone();
        for index in 1..=150 {
            track.push(CanonicalTrackPoint {
                timestamp_ms: stopped.timestamp_ms + index * 200,
                activity_state: ActivityState::Still,
                stationary: Some(true),
                speed_mps: Some(0.0),
                ..stopped.clone()
            });
        }
        append(&mut track, 250, ActivityState::Downhill);

        let candidates = propose_descents(track);
        assert_eq!(candidates.len(), 1);
        assert!(candidates[0].length_m > 490.0);
    }

    #[test]
    fn motorised_evidence_is_never_proposed() {
        let mut track = vec![point(0, 0.0, 0, ActivityState::LikelyMotorized)];
        append(&mut track, 400, ActivityState::LikelyMotorized);

        assert!(propose_descents(track).is_empty());
    }

    #[test]
    fn descents_shorter_than_the_floor_are_not_proposed() {
        let mut track = vec![point(0, 0.0, 0, ActivityState::Downhill)];
        append(&mut track, 250, ActivityState::Downhill);

        assert!(propose_descents(track).is_empty());
    }

    #[test]
    fn candidates_are_longest_first() {
        let mut track = vec![point(0, 0.0, 0, ActivityState::Downhill)];
        append(&mut track, 350, ActivityState::Downhill);
        append(&mut track, 30, ActivityState::Still);
        append(&mut track, 600, ActivityState::Downhill);

        let candidates = propose_descents(track);
        assert_eq!(candidates.len(), 2);
        assert!(candidates[0].length_m > candidates[1].length_m);
    }

    #[test]
    fn overlap_reports_a_duplicate_and_ignores_the_other_direction() {
        let mut track = vec![point(0, 0.0, 0, ActivityState::Downhill)];
        append(&mut track, 400, ActivityState::Downhill);
        let existing = build_segment(
            "seg".to_string(),
            "Existing".to_string(),
            "ride".to_string(),
            track.clone(),
            0,
            track.len() as i32 - 1,
            TEST_MIN_LENGTH_M,
        )
        .expect("definition builds");

        let overlap = selection_overlap(
            vec![existing.clone()],
            track.clone(),
            0.0,
            (track.len() - 1) as f64,
        )
        .expect("the same selection duplicates the segment");
        assert_eq!(overlap.segment_id, "seg");
        assert!(overlap.coverage > 0.9, "coverage was {}", overlap.coverage);

        // The same trail ridden the other way is a different segment: gates are
        // directed, so it must not be reported as a duplicate.
        let reversed: Vec<CanonicalTrackPoint> = track
            .iter()
            .rev()
            .enumerate()
            .map(|(index, point)| CanonicalTrackPoint {
                timestamp_ms: index as i64 * 200,
                ..point.clone()
            })
            .collect();
        assert!(
            selection_overlap(
                vec![existing],
                reversed.clone(),
                0.0,
                (reversed.len() - 1) as f64
            )
            .is_none()
        );
    }

    #[test]
    fn a_selection_elsewhere_is_not_a_duplicate() {
        let mut track = vec![point(0, 0.0, 0, ActivityState::Downhill)];
        append(&mut track, 400, ActivityState::Downhill);
        let existing = build_segment(
            "seg".to_string(),
            "Existing".to_string(),
            "ride".to_string(),
            track.clone(),
            0,
            200,
            TEST_MIN_LENGTH_M,
        )
        .expect("definition builds");

        let mut far = vec![point(0, 5_000.0, 0, ActivityState::Downhill)];
        append(&mut far, 400, ActivityState::Downhill);
        assert!(
            selection_overlap(vec![existing], far.clone(), 0.0, (far.len() - 1) as f64).is_none()
        );
    }
}

#[cfg(test)]
mod boundary_tests {
    use super::nearest_track_position;
    use super::tests_support::*;
    use crate::segment::build_segment_continuous;
    use crate::{ActivityState, CanonicalTrackPoint, LatLon};

    /// See the note in `tests`: fixtures are shorter than the real floor.
    const TEST_MIN_LENGTH_M: Option<f64> = Some(50.0);

    /// A gate a hair short of the next canonical sample used to round onto that
    /// sample's own timestamp and get the whole selection rejected.
    #[test]
    fn a_gate_next_to_a_canonical_sample_still_builds() {
        let track = descending_track(400);
        for offset in [0.999_9, 0.999, 0.99, 0.001, 0.000_1] {
            let result = build_segment_continuous(
                "seg".to_string(),
                "Test".to_string(),
                "ride".to_string(),
                track.clone(),
                100.0 + offset,
                300.0 + offset,
                TEST_MIN_LENGTH_M,
            );
            assert!(
                result.is_ok(),
                "offset {offset} was rejected: {:?}",
                result.err(),
            );
        }
    }

    #[test]
    fn a_dragged_gate_projects_onto_the_track() {
        let track: Vec<CanonicalTrackPoint> = (0..=20)
            .map(|step| point(step * 1_000, step as f64 * 10.0, 0, ActivityState::Downhill))
            .collect();

        // A finger dropped 20 m east of the track, between samples 10 and 11.
        let dropped = LatLon {
            lat: track[10].lat + 5.0 / M_PER_DEG_LAT,
            lon: track[10].lon + 0.0002,
        };

        let position = nearest_track_position(track, dropped);

        assert!(
            (10.0..=11.0).contains(&position),
            "projected to {position} instead of between 10 and 11",
        );
    }
}
