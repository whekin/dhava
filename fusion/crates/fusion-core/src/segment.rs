//! Local segment definitions, directed gates and attempt matching.
//!
//! A segment is a directed piece of trail with a start and a finish gate. The
//! first version is deliberately local and *draft only*: its geometry is a
//! single ride's finalized sub-track, so it is never treated as ground truth
//! and never corrects GPS. Multi-pass centerlines, uncertainty corridors and
//! GPS constraining come later, and every attempt already records which
//! geometry version scored it so a ride can never influence the version it was
//! matched against.
//!
//! Timing runs on the canonical finalized track, not on raw fixes: raw GPS is
//! roughly 1 Hz, which would quantize a run to ±0.5 s before any real
//! uncertainty is considered. Reported uncertainty is derived from horizontal
//! accuracy and crossing speed at each gate, never from the sample rate.
//!
//! Gate intersection alone is not sufficient evidence for an attempt. A start
//! and a finish line can both be crossed by a completely different trail, so a
//! candidate additionally has to stay inside the segment corridor, make
//! monotone forward progress and cover the segment.

use crate::canonical::ascent_descent;
use crate::{
    ActivityState, CanonicalTrackPoint, LatLon, bearing_diff_deg, project, segment_intersection,
};

/// Version of the matching rules. Stored on every attempt: results produced by
/// an older version must be recomputed rather than trusted.
pub const SEGMENT_MATCH_VERSION: &str = "gates-0.2";

/// Shortest acceptable segment. Below this, gate geometry and GPS uncertainty
/// dominate the result.
const MIN_SEGMENT_LENGTH_M: f64 = 50.0;
/// Gate half-widths derived from source-ride accuracy, clamped so a gate can
/// neither be missed by a normal fix error nor swallow a neighboring trail.
const GATE_MIN_HALF_WIDTH_M: f64 = 10.0;
const GATE_MAX_HALF_WIDTH_M: f64 = 30.0;
const GATE_ACCURACY_MULTIPLIER: f64 = 2.0;
/// Corridor half-width around the centerline that an attempt must stay inside.
const CORRIDOR_MIN_M: f64 = 15.0;
const CORRIDOR_MAX_M: f64 = 40.0;
const CORRIDOR_ACCURACY_MULTIPLIER: f64 = 3.0;
/// Crossing direction is compared against the local centerline tangent, which
/// already follows switchbacks, so the tolerance can stay tight-ish.
const DIRECTION_TOLERANCE_DEG: f64 = 60.0;
/// Minimum planar distance used to estimate a stable gate tangent.
const TANGENT_MIN_SPAN_M: f64 = 5.0;
/// An attempt may not bridge a manual pause or a sensor/GPS gap.
const MAX_ATTEMPT_GAP_MS: i64 = 3_000;
/// Allowed cumulative backward progress along the centerline, as a fraction of
/// segment length. Absorbs GPS noise without accepting a rider who turned back.
const MAX_BACKTRACK_FRACTION: f64 = 0.15;
/// An attempt must actually cover the segment, not shortcut between gates.
const MIN_COVERAGE_FRACTION: f64 = 0.85;
/// Centerline bin size used by the coverage test, meters.
const COVERAGE_BIN_M: f64 = 10.0;
/// Assumed horizontal accuracy when a fix does not report one.
const DEFAULT_ACCURACY_M: f64 = 10.0;
/// Speed floor for the uncertainty division; prevents a near-stop crossing from
/// producing an absurd number.
const MIN_GATE_SPEED_MPS: f64 = 0.5;
/// Uncertainty is a display value, so keep it bounded and honest.
const MAX_GATE_UNCERTAINTY_MS: i64 = 10_000;
const HIGH_UNCERTAINTY_MS: i64 = 2_000;
const HIGH_UNCERTAINTY_FRACTION: f64 = 0.05;
const LOW_QUALITY_ACCURACY_M: f64 = 15.0;
/// Enough samples for a smooth phone-width profile without duplicating the
/// already persisted full 5 Hz centerline in authored JSON.
const MAX_ELEVATION_PROFILE_POINTS: usize = 192;

/// Errors specific to authoring and matching segments.
#[derive(Debug, uniffi::Error)]
pub enum SegmentError {
    /// The requested start/finish selection cannot describe a segment.
    InvalidSelection { msg: String },
}

impl std::fmt::Display for SegmentError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            SegmentError::InvalidSelection { msg } => write!(f, "invalid selection: {msg}"),
        }
    }
}

impl std::error::Error for SegmentError {}

/// A directed segment as authored on the device.
///
/// `centerline` is an ordered start-to-finish polyline. In this draft version
/// it is copied verbatim from one ride's finalized track.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SegmentDefinition {
    pub id: String,
    pub name: String,
    /// Recording the draft geometry was authored from.
    pub source_recording_id: String,
    /// Geometry version. Increments whenever the centerline changes; attempts
    /// store the version that scored them.
    pub geometry_version: i32,
    pub centerline: Vec<LatLon>,
    /// Half-width of both gate lines, meters.
    pub gate_half_width_m: f64,
    /// Allowed lateral deviation from the centerline, meters.
    pub corridor_m: f64,
    pub length_m: f64,
    /// Accumulated climb over the selection using the canonical 2 m
    /// hysteresis, when altitude is available.
    pub ascent_m: Option<f64>,
    /// Accumulated descent over the selection using the canonical 2 m
    /// hysteresis, when altitude is available.
    pub descent_m: Option<f64>,
    /// Downsampled distance/elevation series for the local detail chart.
    pub elevation_profile: Vec<SegmentElevationPoint>,
    /// `false` while the geometry is a single-ride draft. A draft never
    /// corrects GPS and is not authoritative geometry.
    pub trusted: bool,
}

/// One sample of a segment's authored elevation profile.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct SegmentElevationPoint {
    /// Distance from the start gate along the source centerline.
    pub distance_m: f64,
    pub altitude_m: f64,
}

/// Geometry plus the source-ride time span selected by the editor.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SegmentBuildResult {
    pub definition: SegmentDefinition,
    pub started_at_ms: i64,
    pub finished_at_ms: i64,
}

/// A suggested selection for the segment editor.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SegmentProposal {
    /// Inclusive index into the finalized track the proposal came from.
    pub start_index: i32,
    /// Inclusive index into the finalized track the proposal came from.
    pub end_index: i32,
    pub length_m: f64,
    pub descent_m: Option<f64>,
}

/// Why a gate pair did not become a countable attempt.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum AttemptRejection {
    /// The start gate was crossed but the finish gate never was afterwards.
    NoFinish,
    /// A manual pause boundary lies inside the candidate.
    PausedInside,
    /// A sensor/GPS gap longer than the allowed bridge lies inside.
    GapInside,
    /// The ride left the segment corridor.
    OffCorridor,
    /// The ride moved backwards along the segment more than noise allows.
    Backtracked,
    /// The ride crossed both gates without covering the segment.
    Incomplete,
}

/// Non-fatal observations that make an attempt suspicious but keep it visible.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum AttemptFlag {
    /// This ride authored the segment geometry, so it cannot be an independent
    /// confirmation of it.
    DefiningRide,
    /// Median horizontal accuracy inside the attempt is poor.
    LowGpsQuality,
    /// The post-ride classifier saw tentative motorized evidence inside.
    LikelyMotorized,
    /// Timing uncertainty is large relative to the result.
    HighUncertainty,
}

/// Coarse verdict derived from [`AttemptFlag`]s.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum AttemptQuality {
    Good,
    Uncertain,
}

/// One completed run of a segment.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SegmentAttempt {
    pub recording_id: String,
    /// Interpolated start-gate crossing, Unix epoch milliseconds.
    pub started_at_ms: i64,
    /// Interpolated finish-gate crossing, Unix epoch milliseconds.
    pub finished_at_ms: i64,
    pub elapsed_ms: i64,
    /// Symmetric ± timing uncertainty, milliseconds.
    pub uncertainty_ms: i64,
    /// Continuous recording section the attempt belongs to.
    pub section_id: i32,
    /// Inclusive index range in the matched finalized track, for rendering.
    pub start_index: i32,
    pub end_index: i32,
    pub max_deviation_m: f64,
    pub median_accuracy_m: Option<f64>,
    pub quality: AttemptQuality,
    pub flags: Vec<AttemptFlag>,
    /// Geometry version this attempt was matched against.
    pub matched_geometry_version: i32,
    /// Matching rules that produced it.
    pub match_version: String,
}

/// A gate pair that was found but did not qualify.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct RejectedAttempt {
    pub recording_id: String,
    pub started_at_ms: i64,
    pub reason: AttemptRejection,
    /// Human-readable specifics, e.g. the measured deviation.
    pub detail: String,
}

/// Everything one recording contributed to one segment.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SegmentMatchResult {
    pub attempts: Vec<SegmentAttempt>,
    pub rejected: Vec<RejectedAttempt>,
}

/// Geographic bounds, used by callers as a cheap candidate prefilter.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct GeoBounds {
    pub min_lat: f64,
    pub min_lon: f64,
    pub max_lat: f64,
    pub max_lon: f64,
}

/// Current matching rules identifier.
#[uniffi::export]
pub fn segment_match_version() -> String {
    SEGMENT_MATCH_VERSION.to_string()
}

/// Suggests the longest continuous descent in `track` as a default selection.
///
/// Uses the existing conservative [`ActivityState`] pass, so the suggestion
/// never crosses a pause boundary or a gap and never invents its own notion of
/// downhill.
#[uniffi::export]
pub fn propose_segment(track: Vec<CanonicalTrackPoint>) -> Option<SegmentProposal> {
    let mut best: Option<(usize, usize, f64)> = None;
    let mut run_start: Option<usize> = None;

    for index in 0..track.len() {
        let continues = index > 0
            && track[index].section_id == track[index - 1].section_id
            && (1..=MAX_ATTEMPT_GAP_MS)
                .contains(&(track[index].timestamp_ms - track[index - 1].timestamp_ms));
        let downhill = track[index].activity_state == ActivityState::Downhill;

        // A run ends at the first non-downhill point, pause boundary or gap.
        if let Some(start) = run_start.take_if(|_| !downhill || !continues) {
            consider_run(&track, start, index - 1, &mut best);
        }
        if downhill && run_start.is_none() {
            run_start = Some(index);
        }
    }
    if let Some(start) = run_start {
        consider_run(&track, start, track.len() - 1, &mut best);
    }

    let (start, end, length_m) = best?;
    let selection = &track[start..=end];
    let (_, descent_m) = ascent_descent(selection);
    Some(SegmentProposal {
        start_index: start as i32,
        end_index: end as i32,
        length_m,
        descent_m: selection
            .iter()
            .any(|point| point.altitude_m.is_some())
            .then_some(descent_m),
    })
}

fn consider_run(
    track: &[CanonicalTrackPoint],
    start: usize,
    end: usize,
    best: &mut Option<(usize, usize, f64)>,
) {
    if end <= start {
        return;
    }
    let length_m = polyline_length_m(&track[start..=end]);
    if length_m < MIN_SEGMENT_LENGTH_M {
        return;
    }
    if best.is_none_or(|(_, _, best_length)| length_m > best_length) {
        *best = Some((start, end, length_m));
    }
}

/// Builds a draft segment definition from a selection on one finalized track.
///
/// Gate widths and the corridor are derived here, from the source ride's own
/// horizontal accuracy, so the matching policy stays in Rust.
#[uniffi::export]
pub fn build_segment(
    id: String,
    name: String,
    source_recording_id: String,
    track: Vec<CanonicalTrackPoint>,
    start_index: i32,
    end_index: i32,
) -> Result<SegmentDefinition, SegmentError> {
    let invalid = |msg: String| SegmentError::InvalidSelection { msg };
    if start_index < 0 || end_index < 0 {
        return Err(invalid("negative index".to_string()));
    }
    let (start, end) = (start_index as usize, end_index as usize);
    if end <= start {
        return Err(invalid("finish must come after start".to_string()));
    }
    if end >= track.len() {
        return Err(invalid(format!(
            "index {end} outside track of {} points",
            track.len()
        )));
    }
    let selection = &track[start..=end];
    build_segment_definition(id, name, source_recording_id, selection, 1)
}

/// Builds geometry v2 with gates at continuous positions on the finalized
/// polyline. The integer part identifies a canonical point; the fractional
/// part lies on the following edge.
#[uniffi::export]
pub fn build_segment_continuous(
    id: String,
    name: String,
    source_recording_id: String,
    track: Vec<CanonicalTrackPoint>,
    start_position: f64,
    end_position: f64,
) -> Result<SegmentBuildResult, SegmentError> {
    let invalid = |msg: String| SegmentError::InvalidSelection { msg };
    if track.len() < 2 {
        return Err(invalid("track has fewer than two points".to_string()));
    }
    if !start_position.is_finite() || !end_position.is_finite() {
        return Err(invalid("position is not finite".to_string()));
    }
    let last_position = (track.len() - 1) as f64;
    if start_position < 0.0 || end_position > last_position {
        return Err(invalid(format!(
            "positions {start_position:.3}..{end_position:.3} outside track 0..{last_position}"
        )));
    }
    if end_position <= start_position {
        return Err(invalid("finish must come after start".to_string()));
    }

    let selection = continuous_selection(&track, start_position, end_position)?;
    let started_at_ms = selection
        .first()
        .map(|point| point.timestamp_ms)
        .ok_or_else(|| invalid("empty selection".to_string()))?;
    let finished_at_ms = selection
        .last()
        .map(|point| point.timestamp_ms)
        .ok_or_else(|| invalid("empty selection".to_string()))?;
    let definition = build_segment_definition(id, name, source_recording_id, &selection, 2)?;
    Ok(SegmentBuildResult {
        definition,
        started_at_ms,
        finished_at_ms,
    })
}

fn build_segment_definition(
    id: String,
    name: String,
    source_recording_id: String,
    selection: &[CanonicalTrackPoint],
    geometry_version: i32,
) -> Result<SegmentDefinition, SegmentError> {
    let invalid = |msg: String| SegmentError::InvalidSelection { msg };
    if selection
        .windows(2)
        .any(|pair| pair[0].section_id != pair[1].section_id)
    {
        return Err(invalid(
            "selection crosses a manual pause boundary".to_string(),
        ));
    }
    if selection.windows(2).any(|pair| {
        !(1..=MAX_ATTEMPT_GAP_MS).contains(&(pair[1].timestamp_ms - pair[0].timestamp_ms))
    }) {
        return Err(invalid("selection crosses a recording gap".to_string()));
    }

    let length_m = polyline_length_m(selection);
    if length_m < MIN_SEGMENT_LENGTH_M {
        return Err(invalid(format!(
            "selection is {length_m:.0} m, minimum is {MIN_SEGMENT_LENGTH_M:.0} m"
        )));
    }

    let accuracy = p90_accuracy_m(selection).unwrap_or(DEFAULT_ACCURACY_M);
    let (ascent_m, descent_m) = ascent_descent(selection);
    let has_altitude = selection.iter().any(|point| point.altitude_m.is_some());
    Ok(SegmentDefinition {
        id,
        name,
        source_recording_id,
        geometry_version,
        centerline: selection
            .iter()
            .map(|point| LatLon {
                lat: point.lat,
                lon: point.lon,
            })
            .collect(),
        gate_half_width_m: (accuracy * GATE_ACCURACY_MULTIPLIER)
            .clamp(GATE_MIN_HALF_WIDTH_M, GATE_MAX_HALF_WIDTH_M),
        corridor_m: (accuracy * CORRIDOR_ACCURACY_MULTIPLIER).clamp(CORRIDOR_MIN_M, CORRIDOR_MAX_M),
        length_m,
        ascent_m: has_altitude.then_some(ascent_m),
        descent_m: has_altitude.then_some(descent_m),
        elevation_profile: elevation_profile(selection),
        trusted: false,
    })
}

fn continuous_selection(
    track: &[CanonicalTrackPoint],
    start_position: f64,
    end_position: f64,
) -> Result<Vec<CanonicalTrackPoint>, SegmentError> {
    let invalid = |msg: String| SegmentError::InvalidSelection { msg };
    let mut selection =
        Vec::with_capacity((end_position.ceil() - start_position.floor()) as usize + 2);
    selection.push(interpolate_track_position(track, start_position)?);

    let mut index = start_position.floor() as usize + 1;
    while (index as f64) < end_position {
        selection.push(track[index].clone());
        index += 1;
    }
    selection.push(interpolate_track_position(track, end_position)?);

    if selection
        .windows(2)
        .any(|pair| pair[1].timestamp_ms <= pair[0].timestamp_ms)
    {
        return Err(invalid(
            "selection endpoints are closer than the source timing resolution".to_string(),
        ));
    }
    Ok(selection)
}

fn interpolate_track_position(
    track: &[CanonicalTrackPoint],
    position: f64,
) -> Result<CanonicalTrackPoint, SegmentError> {
    let invalid = |msg: String| SegmentError::InvalidSelection { msg };
    let lower = position.floor() as usize;
    let fraction = position - lower as f64;
    if fraction <= f64::EPSILON {
        return track
            .get(lower)
            .cloned()
            .ok_or_else(|| invalid(format!("position {position:.3} outside track")));
    }
    let upper = lower + 1;
    let from = track
        .get(lower)
        .ok_or_else(|| invalid(format!("position {position:.3} outside track")))?;
    let to = track
        .get(upper)
        .ok_or_else(|| invalid(format!("position {position:.3} outside track")))?;
    if from.section_id != to.section_id {
        return Err(invalid(
            "position lies across a manual pause boundary".to_string(),
        ));
    }
    let gap_ms = to.timestamp_ms - from.timestamp_ms;
    if !(1..=MAX_ATTEMPT_GAP_MS).contains(&gap_ms) {
        return Err(invalid("position lies across a recording gap".to_string()));
    }

    Ok(CanonicalTrackPoint {
        timestamp_ms: from.timestamp_ms + ((gap_ms as f64) * fraction).round() as i64,
        lat: lerp(from.lat, to.lat, fraction),
        lon: lerp(from.lon, to.lon, fraction),
        altitude_m: lerp_optional(from.altitude_m, to.altitude_m, fraction),
        accuracy_m: lerp_optional(from.accuracy_m, to.accuracy_m, fraction),
        speed_mps: lerp_optional(from.speed_mps, to.speed_mps, fraction),
        stationary: nearest_optional(from.stationary, to.stationary, fraction),
        section_id: from.section_id,
        activity_state: if fraction < 0.5 {
            from.activity_state
        } else {
            to.activity_state
        },
        activity_confidence: lerp(from.activity_confidence, to.activity_confidence, fraction),
    })
}

fn lerp(from: f64, to: f64, fraction: f64) -> f64 {
    from + (to - from) * fraction
}

fn lerp_optional(from: Option<f64>, to: Option<f64>, fraction: f64) -> Option<f64> {
    match (from, to) {
        (Some(from), Some(to)) => Some(lerp(from, to, fraction)),
        _ => nearest_optional(from, to, fraction),
    }
}

fn nearest_optional<T: Copy>(from: Option<T>, to: Option<T>, fraction: f64) -> Option<T> {
    if fraction < 0.5 {
        from.or(to)
    } else {
        to.or(from)
    }
}

/// Search bounds for `definition`, already padded by its own corridor and gate
/// width. A recording whose track bounds do not intersect these can be skipped
/// without decompressing or matching it.
#[uniffi::export]
pub fn segment_search_bounds(definition: SegmentDefinition) -> Option<GeoBounds> {
    let first = definition.centerline.first()?;
    let mut bounds = GeoBounds {
        min_lat: first.lat,
        min_lon: first.lon,
        max_lat: first.lat,
        max_lon: first.lon,
    };
    for point in &definition.centerline {
        bounds.min_lat = bounds.min_lat.min(point.lat);
        bounds.min_lon = bounds.min_lon.min(point.lon);
        bounds.max_lat = bounds.max_lat.max(point.lat);
        bounds.max_lon = bounds.max_lon.max(point.lon);
    }
    let pad_m = definition.corridor_m.max(definition.gate_half_width_m);
    let lat_pad = pad_m / 111_320.0;
    let mid_lat = ((bounds.min_lat + bounds.max_lat) / 2.0).to_radians();
    let lon_pad = pad_m / (111_320.0 * mid_lat.cos().abs().max(0.01));
    Some(GeoBounds {
        min_lat: bounds.min_lat - lat_pad,
        min_lon: bounds.min_lon - lon_pad,
        max_lat: bounds.max_lat + lat_pad,
        max_lon: bounds.max_lon + lon_pad,
    })
}

/// Finds every attempt of `definition` inside one recording's finalized track.
#[uniffi::export]
pub fn match_segment(
    definition: SegmentDefinition,
    recording_id: String,
    track: Vec<CanonicalTrackPoint>,
) -> SegmentMatchResult {
    let mut result = SegmentMatchResult {
        attempts: Vec::new(),
        rejected: Vec::new(),
    };
    if definition.centerline.len() < 2 || track.len() < 2 {
        return result;
    }

    let origin = definition.centerline[definition.centerline.len() / 2];
    let centerline: Vec<[f64; 2]> = definition
        .centerline
        .iter()
        .map(|point| project(*point, origin))
        .collect();
    let arclength = arclength(&centerline);
    let total_length = *arclength.last().unwrap_or(&0.0);
    if total_length <= 0.0 {
        return result;
    }

    let Some(start_gate) = gate_at_start(&centerline, definition.gate_half_width_m) else {
        return result;
    };
    let Some(finish_gate) = gate_at_finish(&centerline, definition.gate_half_width_m) else {
        return result;
    };

    let planar: Vec<[f64; 2]> = track
        .iter()
        .map(|point| {
            project(
                LatLon {
                    lat: point.lat,
                    lon: point.lon,
                },
                origin,
            )
        })
        .collect();

    let events = gate_events(&track, &planar, &start_gate, &finish_gate);
    let mut pending: Option<GateEvent> = None;
    let mut pending_incomplete: Option<RejectedAttempt> = None;
    for event in events {
        match event.gate {
            // Keep the first directed start until it is completed or proven
            // impossible. A switchback can cross the extended start line
            // again inside a legitimate run; replacing the original event
            // would shorten or lose that attempt.
            GateKind::Start => {
                if pending.is_none() {
                    pending = Some(event);
                    pending_incomplete = None;
                }
            }
            GateKind::Finish => {
                let Some(start_event) = pending else {
                    continue;
                };
                if event.timestamp_ms <= start_event.timestamp_ms {
                    continue;
                }
                match validate(
                    &definition,
                    &recording_id,
                    &track,
                    &planar,
                    &centerline,
                    &arclength,
                    total_length,
                    &start_event,
                    &event,
                ) {
                    Ok(attempt) => {
                        result.attempts.push(attempt);
                        pending = None;
                        pending_incomplete = None;
                    }
                    // An extended finish line can be crossed by an earlier
                    // switchback. Coverage may become valid at a later finish,
                    // so do not consume the start yet.
                    Err((AttemptRejection::Incomplete, detail)) => {
                        pending_incomplete = Some(RejectedAttempt {
                            recording_id: recording_id.clone(),
                            started_at_ms: start_event.timestamp_ms,
                            reason: AttemptRejection::Incomplete,
                            detail,
                        });
                    }
                    Err((reason, detail)) => {
                        result.rejected.push(RejectedAttempt {
                            recording_id: recording_id.clone(),
                            started_at_ms: start_event.timestamp_ms,
                            reason,
                            detail,
                        });
                        pending = None;
                        pending_incomplete = None;
                    }
                }
            }
        }
    }
    if let Some(unpaired) = pending {
        result
            .rejected
            .push(pending_incomplete.unwrap_or(RejectedAttempt {
                recording_id: recording_id.clone(),
                started_at_ms: unpaired.timestamp_ms,
                reason: AttemptRejection::NoFinish,
                detail: "start gate crossed, finish gate never reached".to_string(),
            }));
    }
    result
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum GateKind {
    Start,
    Finish,
}

/// One directed gate line in projected meters.
#[derive(Debug, Clone, Copy)]
struct GateLine {
    a: [f64; 2],
    b: [f64; 2],
    bearing_deg: f64,
}

#[derive(Debug, Clone, Copy)]
struct GateEvent {
    gate: GateKind,
    timestamp_ms: i64,
    /// Index of the first point of the crossing pair.
    index: usize,
}

fn gate_at_start(centerline: &[[f64; 2]], half_width_m: f64) -> Option<GateLine> {
    let tangent = forward_tangent(centerline)?;
    Some(gate_line(centerline[0], tangent, half_width_m))
}

fn gate_at_finish(centerline: &[[f64; 2]], half_width_m: f64) -> Option<GateLine> {
    let mut reversed: Vec<[f64; 2]> = centerline.to_vec();
    reversed.reverse();
    // Tangent of the reversed polyline points backwards; flip it so the gate
    // still requires travel in the segment direction.
    let backward = forward_tangent(&reversed)?;
    let tangent = [-backward[0], -backward[1]];
    Some(gate_line(
        centerline[centerline.len() - 1],
        tangent,
        half_width_m,
    ))
}

/// Unit tangent leaving the first point, measured over at least
/// [`TANGENT_MIN_SPAN_M`] so a single noisy 5 Hz step cannot define direction.
fn forward_tangent(centerline: &[[f64; 2]]) -> Option<[f64; 2]> {
    let origin = *centerline.first()?;
    let mut chosen = None;
    for point in centerline.iter().skip(1) {
        let delta = [point[0] - origin[0], point[1] - origin[1]];
        let length = (delta[0] * delta[0] + delta[1] * delta[1]).sqrt();
        if length <= 0.0 {
            continue;
        }
        chosen = Some([delta[0] / length, delta[1] / length]);
        if length >= TANGENT_MIN_SPAN_M {
            break;
        }
    }
    chosen
}

fn gate_line(center: [f64; 2], tangent: [f64; 2], half_width_m: f64) -> GateLine {
    let normal = [-tangent[1], tangent[0]];
    GateLine {
        a: [
            center[0] - normal[0] * half_width_m,
            center[1] - normal[1] * half_width_m,
        ],
        b: [
            center[0] + normal[0] * half_width_m,
            center[1] + normal[1] * half_width_m,
        ],
        bearing_deg: tangent[0].atan2(tangent[1]).to_degrees().rem_euclid(360.0),
    }
}

/// Collects every directed crossing of both gates, in time order.
///
/// A pause boundary or a long gap is never bridged, so a crossing cannot be
/// invented from two points recorded minutes apart.
fn gate_events(
    track: &[CanonicalTrackPoint],
    planar: &[[f64; 2]],
    start_gate: &GateLine,
    finish_gate: &GateLine,
) -> Vec<GateEvent> {
    let mut events = Vec::new();
    for index in 0..track.len() - 1 {
        let (from, to) = (&track[index], &track[index + 1]);
        let dt_ms = to.timestamp_ms - from.timestamp_ms;
        if from.section_id != to.section_id || !(1..=MAX_ATTEMPT_GAP_MS).contains(&dt_ms) {
            continue;
        }
        let (a, b) = (planar[index], planar[index + 1]);
        let travel = [b[0] - a[0], b[1] - a[1]];
        if travel[0] == 0.0 && travel[1] == 0.0 {
            continue;
        }
        let travel_bearing = travel[0].atan2(travel[1]).to_degrees().rem_euclid(360.0);

        for (gate, line) in [
            (GateKind::Start, start_gate),
            (GateKind::Finish, finish_gate),
        ] {
            let gate_vec = [line.b[0] - line.a[0], line.b[1] - line.a[1]];
            let Some((t, _)) = segment_intersection(a, travel, line.a, gate_vec) else {
                continue;
            };
            if bearing_diff_deg(travel_bearing, line.bearing_deg) > DIRECTION_TOLERANCE_DEG {
                continue;
            }
            events.push(GateEvent {
                gate,
                timestamp_ms: from.timestamp_ms + (t * dt_ms as f64).round() as i64,
                index,
            });
        }
    }
    events.sort_by_key(|event| (event.timestamp_ms, event.index));
    events
}

type Rejection = (AttemptRejection, String);

#[allow(clippy::too_many_arguments)]
fn validate(
    definition: &SegmentDefinition,
    recording_id: &str,
    track: &[CanonicalTrackPoint],
    planar: &[[f64; 2]],
    centerline: &[[f64; 2]],
    arclength: &[f64],
    total_length: f64,
    start_event: &GateEvent,
    finish_event: &GateEvent,
) -> Result<SegmentAttempt, Rejection> {
    let first = start_event.index;
    let last = (finish_event.index + 1).min(track.len() - 1);
    let inside = &track[first..=last];

    if inside
        .windows(2)
        .any(|pair| pair[0].section_id != pair[1].section_id)
    {
        return Err((
            AttemptRejection::PausedInside,
            "manual pause between the gates".to_string(),
        ));
    }
    if let Some(gap_ms) = inside
        .windows(2)
        .map(|pair| pair[1].timestamp_ms - pair[0].timestamp_ms)
        .filter(|gap| !(1..=MAX_ATTEMPT_GAP_MS).contains(gap))
        .max()
    {
        return Err((
            AttemptRejection::GapInside,
            format!(
                "{:.1} s recording gap between the gates",
                gap_ms as f64 / 1000.0
            ),
        ));
    }

    let mut max_deviation_m: f64 = 0.0;
    let mut progress = Vec::with_capacity(inside.len());
    for point in &planar[first..=last] {
        let (deviation_m, s) = nearest_on_polyline(*point, centerline, arclength);
        max_deviation_m = max_deviation_m.max(deviation_m);
        progress.push(s);
    }
    if max_deviation_m > definition.corridor_m {
        return Err((
            AttemptRejection::OffCorridor,
            format!(
                "{max_deviation_m:.0} m off the segment, corridor is {:.0} m",
                definition.corridor_m
            ),
        ));
    }

    let backtrack_m: f64 = progress
        .windows(2)
        .map(|pair| (pair[0] - pair[1]).max(0.0))
        .sum();
    if backtrack_m > total_length * MAX_BACKTRACK_FRACTION {
        return Err((
            AttemptRejection::Backtracked,
            format!("{backtrack_m:.0} m of backward progress along the segment"),
        ));
    }

    let coverage = coverage_fraction(&progress, total_length);
    if coverage < MIN_COVERAGE_FRACTION {
        return Err((
            AttemptRejection::Incomplete,
            format!(
                "covered {:.0}% of the segment between the gates",
                coverage * 100.0
            ),
        ));
    }

    let elapsed_ms = finish_event.timestamp_ms - start_event.timestamp_ms;
    let uncertainty_ms = timing_uncertainty_ms(track, planar, start_event, finish_event);
    let median_accuracy_m = median_accuracy_m(inside);

    let mut flags = Vec::new();
    if recording_id == definition.source_recording_id {
        flags.push(AttemptFlag::DefiningRide);
    }
    if median_accuracy_m.is_some_and(|accuracy| accuracy > LOW_QUALITY_ACCURACY_M) {
        flags.push(AttemptFlag::LowGpsQuality);
    }
    if inside
        .iter()
        .any(|point| point.activity_state == ActivityState::LikelyMotorized)
    {
        flags.push(AttemptFlag::LikelyMotorized);
    }
    if uncertainty_ms > HIGH_UNCERTAINTY_MS
        || uncertainty_ms as f64 > elapsed_ms as f64 * HIGH_UNCERTAINTY_FRACTION
    {
        flags.push(AttemptFlag::HighUncertainty);
    }
    let quality = if flags
        .iter()
        .any(|flag| !matches!(flag, AttemptFlag::DefiningRide))
    {
        AttemptQuality::Uncertain
    } else {
        AttemptQuality::Good
    };

    Ok(SegmentAttempt {
        recording_id: recording_id.to_string(),
        started_at_ms: start_event.timestamp_ms,
        finished_at_ms: finish_event.timestamp_ms,
        elapsed_ms,
        uncertainty_ms,
        section_id: track[first].section_id,
        start_index: first as i32,
        end_index: last as i32,
        max_deviation_m,
        median_accuracy_m,
        quality,
        flags,
        matched_geometry_version: definition.geometry_version,
        match_version: SEGMENT_MATCH_VERSION.to_string(),
    })
}

/// Combined ± timing uncertainty of both gate crossings.
///
/// Each gate contributes `accuracy / speed`: the time it takes to ride through
/// its own horizontal position uncertainty. The two are independent, so they
/// combine as a root sum of squares.
fn timing_uncertainty_ms(
    track: &[CanonicalTrackPoint],
    planar: &[[f64; 2]],
    start_event: &GateEvent,
    finish_event: &GateEvent,
) -> i64 {
    let start = gate_uncertainty_ms(track, planar, start_event);
    let finish = gate_uncertainty_ms(track, planar, finish_event);
    let combined = ((start * start + finish * finish) as f64).sqrt();
    (combined.round() as i64).min(MAX_GATE_UNCERTAINTY_MS)
}

fn gate_uncertainty_ms(
    track: &[CanonicalTrackPoint],
    planar: &[[f64; 2]],
    event: &GateEvent,
) -> i64 {
    let (from, to) = (&track[event.index], &track[event.index + 1]);
    let accuracy_m = [from.accuracy_m, to.accuracy_m]
        .into_iter()
        .flatten()
        .filter(|value| value.is_finite() && *value > 0.0)
        .fold(f64::MIN, f64::max);
    let accuracy_m = if accuracy_m > 0.0 {
        accuracy_m
    } else {
        DEFAULT_ACCURACY_M
    };

    let reported: Vec<f64> = [from.speed_mps, to.speed_mps]
        .into_iter()
        .flatten()
        .filter(|value| value.is_finite() && *value >= 0.0)
        .collect();
    let speed_mps = if reported.is_empty() {
        let (a, b) = (planar[event.index], planar[event.index + 1]);
        let chord_m = ((b[0] - a[0]).powi(2) + (b[1] - a[1]).powi(2)).sqrt();
        let dt_s = (to.timestamp_ms - from.timestamp_ms) as f64 / 1000.0;
        if dt_s > 0.0 { chord_m / dt_s } else { 0.0 }
    } else {
        reported.iter().sum::<f64>() / reported.len() as f64
    };

    let seconds = accuracy_m / speed_mps.max(MIN_GATE_SPEED_MPS);
    ((seconds * 1000.0).round() as i64).min(MAX_GATE_UNCERTAINTY_MS)
}

/// Fraction of the segment actually ridden.
///
/// Deliberately not `max(s) - min(s)`: on a hairpin whose two legs are closer
/// together than the corridor, a rider can cross both gates, stay inside the
/// corridor and produce a full span while never riding the middle. Binning the
/// centerline and requiring visited bins catches exactly that shortcut.
fn coverage_fraction(progress: &[f64], total_length: f64) -> f64 {
    if total_length <= 0.0 || progress.is_empty() {
        return 0.0;
    }
    let bins = ((total_length / COVERAGE_BIN_M).round() as usize).clamp(8, 400);
    let mut visited = vec![false; bins];
    for s in progress {
        let bin = ((s / total_length) * bins as f64).floor();
        let bin = (bin.max(0.0) as usize).min(bins - 1);
        visited[bin] = true;
    }
    visited.iter().filter(|seen| **seen).count() as f64 / bins as f64
}

/// Perpendicular distance from `point` to `polyline`, plus the arclength of the
/// nearest position along it.
fn nearest_on_polyline(point: [f64; 2], polyline: &[[f64; 2]], arclength: &[f64]) -> (f64, f64) {
    let mut best = (f64::MAX, 0.0);
    for index in 0..polyline.len() - 1 {
        let (a, b) = (polyline[index], polyline[index + 1]);
        let ab = [b[0] - a[0], b[1] - a[1]];
        let ap = [point[0] - a[0], point[1] - a[1]];
        let length_sq = ab[0] * ab[0] + ab[1] * ab[1];
        let t = if length_sq > 0.0 {
            ((ap[0] * ab[0] + ap[1] * ab[1]) / length_sq).clamp(0.0, 1.0)
        } else {
            0.0
        };
        let closest = [a[0] + ab[0] * t, a[1] + ab[1] * t];
        let distance = ((point[0] - closest[0]).powi(2) + (point[1] - closest[1]).powi(2)).sqrt();
        if distance < best.0 {
            best = (distance, arclength[index] + length_sq.sqrt() * t);
        }
    }
    best
}

fn arclength(polyline: &[[f64; 2]]) -> Vec<f64> {
    let mut values = Vec::with_capacity(polyline.len());
    let mut total = 0.0;
    values.push(0.0);
    for index in 1..polyline.len() {
        let (a, b) = (polyline[index - 1], polyline[index]);
        total += ((b[0] - a[0]).powi(2) + (b[1] - a[1]).powi(2)).sqrt();
        values.push(total);
    }
    values
}

fn polyline_length_m(track: &[CanonicalTrackPoint]) -> f64 {
    if track.len() < 2 {
        return 0.0;
    }
    let origin = LatLon {
        lat: track[track.len() / 2].lat,
        lon: track[track.len() / 2].lon,
    };
    let planar: Vec<[f64; 2]> = track
        .iter()
        .map(|point| {
            project(
                LatLon {
                    lat: point.lat,
                    lon: point.lon,
                },
                origin,
            )
        })
        .collect();
    *arclength(&planar).last().unwrap_or(&0.0)
}

fn elevation_profile(track: &[CanonicalTrackPoint]) -> Vec<SegmentElevationPoint> {
    if track.len() < 2 {
        return Vec::new();
    }
    let origin = LatLon {
        lat: track[track.len() / 2].lat,
        lon: track[track.len() / 2].lon,
    };
    let planar: Vec<[f64; 2]> = track
        .iter()
        .map(|point| {
            project(
                LatLon {
                    lat: point.lat,
                    lon: point.lon,
                },
                origin,
            )
        })
        .collect();
    let distances = arclength(&planar);
    let available: Vec<_> = track
        .iter()
        .zip(distances)
        .filter_map(|(point, distance_m)| {
            point
                .altitude_m
                .filter(|altitude| altitude.is_finite())
                .map(|altitude_m| SegmentElevationPoint {
                    distance_m,
                    altitude_m,
                })
        })
        .collect();
    if available.len() <= MAX_ELEVATION_PROFILE_POINTS {
        return available;
    }

    let stride = (available.len() - 1).div_ceil(MAX_ELEVATION_PROFILE_POINTS - 1);
    let mut profile: Vec<_> = available.iter().step_by(stride).copied().collect();
    let last = *available.last().expect("available is non-empty");
    if profile.last() != Some(&last) {
        profile.push(last);
    }
    profile
}

fn p90_accuracy_m(track: &[CanonicalTrackPoint]) -> Option<f64> {
    let mut values: Vec<f64> = track
        .iter()
        .filter_map(|point| point.accuracy_m)
        .filter(|value| value.is_finite() && *value > 0.0)
        .collect();
    if values.is_empty() {
        return None;
    }
    values.sort_by(|a, b| a.total_cmp(b));
    let index = ((values.len() as f64 * 0.9).ceil() as usize).min(values.len()) - 1;
    Some(values[index])
}

fn median_accuracy_m(track: &[CanonicalTrackPoint]) -> Option<f64> {
    let mut values: Vec<f64> = track
        .iter()
        .filter_map(|point| point.accuracy_m)
        .filter(|value| value.is_finite() && *value > 0.0)
        .collect();
    if values.is_empty() {
        return None;
    }
    values.sort_by(|a, b| a.total_cmp(b));
    let middle = values.len() / 2;
    Some(if values.len().is_multiple_of(2) {
        (values[middle - 1] + values[middle]) / 2.0
    } else {
        values[middle]
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    const LAT: f64 = 43.0;
    const LON: f64 = 42.0;
    /// Meters per degree of latitude at [`LAT`], and of longitude at [`LAT`].
    const M_PER_DEG_LAT: f64 = 111_195.0;

    fn m_per_deg_lon() -> f64 {
        M_PER_DEG_LAT * LAT.to_radians().cos()
    }

    /// One point of a synthetic 5 Hz track, positioned in meters east/north of
    /// the fixture origin.
    fn point(timestamp_ms: i64, east_m: f64, north_m: f64) -> CanonicalTrackPoint {
        CanonicalTrackPoint {
            timestamp_ms,
            lat: LAT + north_m / M_PER_DEG_LAT,
            lon: LON + east_m / m_per_deg_lon(),
            altitude_m: Some(1_000.0 - north_m * 0.1),
            accuracy_m: Some(5.0),
            speed_mps: Some(5.0),
            stationary: Some(false),
            section_id: 0,
            activity_state: ActivityState::Downhill,
            activity_confidence: 0.9,
        }
    }

    /// A straight 200 m northbound track at 5 m/s, sampled at 5 Hz.
    fn straight_track(start_ms: i64) -> Vec<CanonicalTrackPoint> {
        (0..=200)
            .map(|step| point(start_ms + step * 200, 0.0, step as f64 * 1.0))
            .collect()
    }

    /// Appends a straight leg sampled roughly every meter at 5 Hz, continuing
    /// from the current end of `track`.
    fn append_leg(track: &mut Vec<CanonicalTrackPoint>, from: (f64, f64), to: (f64, f64)) {
        let (dx, dy) = (to.0 - from.0, to.1 - from.1);
        let length = (dx * dx + dy * dy).sqrt();
        let steps = length.round().max(1.0) as i64;
        let start_ms = track.last().map(|last| last.timestamp_ms).unwrap_or(0);
        for step in 0..=steps {
            if step == 0 && !track.is_empty() {
                continue;
            }
            let fraction = step as f64 / steps as f64;
            track.push(point(
                start_ms + step * 200,
                from.0 + dx * fraction,
                from.1 + dy * fraction,
            ));
        }
    }

    fn definition(track: &[CanonicalTrackPoint]) -> SegmentDefinition {
        build_segment(
            "seg".to_string(),
            "Test".to_string(),
            "source".to_string(),
            track.to_vec(),
            0,
            (track.len() - 1) as i32,
        )
        .expect("selection should be valid")
    }

    #[test]
    fn straight_run_is_one_attempt_with_uncertainty() {
        let track = straight_track(0);
        let segment = definition(&track);
        let result = match_segment(segment, "source".to_string(), track.clone());

        assert_eq!(result.rejected, vec![]);
        assert_eq!(result.attempts.len(), 1);
        let attempt = &result.attempts[0];
        // 200 m at 5 m/s; the gates sit on the first and last track point.
        assert!(
            (attempt.elapsed_ms - 40_000).abs() <= 400,
            "elapsed was {}",
            attempt.elapsed_ms
        );
        // 5 m accuracy at 5 m/s is 1 s per gate, RSS of two gates ~1.41 s.
        assert!(
            (attempt.uncertainty_ms - 1_414).abs() <= 50,
            "uncertainty was {}",
            attempt.uncertainty_ms
        );
        assert!(attempt.max_deviation_m < 0.5);
        assert_eq!(attempt.flags, vec![AttemptFlag::DefiningRide]);
        assert_eq!(attempt.quality, AttemptQuality::Good);
        assert_eq!(attempt.match_version, SEGMENT_MATCH_VERSION);
        assert_eq!(attempt.matched_geometry_version, 1);
    }

    #[test]
    fn reverse_direction_is_not_an_attempt() {
        let track = straight_track(0);
        let segment = definition(&track);
        let mut reversed: Vec<CanonicalTrackPoint> = track
            .iter()
            .rev()
            .enumerate()
            .map(|(index, original)| CanonicalTrackPoint {
                timestamp_ms: index as i64 * 200,
                ..original.clone()
            })
            .collect();
        reversed.iter_mut().for_each(|point| point.section_id = 0);

        let result = match_segment(segment, "other".to_string(), reversed);
        assert_eq!(result.attempts, vec![]);
        assert_eq!(result.rejected, vec![]);
    }

    #[test]
    fn two_laps_produce_two_attempts() {
        let track = straight_track(0);
        let segment = definition(&track);

        // Ride the segment, climb back south well outside the corridor, then
        // approach the start gate from below and ride it again.
        let mut lap_track = Vec::new();
        append_leg(&mut lap_track, (0.0, -20.0), (0.0, 220.0));
        append_leg(&mut lap_track, (0.0, 220.0), (80.0, 220.0));
        append_leg(&mut lap_track, (80.0, 220.0), (80.0, -20.0));
        append_leg(&mut lap_track, (80.0, -20.0), (0.0, -20.0));
        append_leg(&mut lap_track, (0.0, -20.0), (0.0, 220.0));

        let result = match_segment(segment, "other".to_string(), lap_track);
        assert_eq!(result.attempts.len(), 2, "rejected: {:?}", result.rejected);
        assert!(result.attempts[0].started_at_ms < result.attempts[1].started_at_ms);
    }

    #[test]
    fn early_incomplete_finish_does_not_consume_the_real_attempt() {
        // The final gate is a horizontal line around (10, 200). The first
        // northbound leg touches its west endpoint long before the looping
        // centerline reaches the real finish. That early crossing is
        // incomplete, but the later crossing covers the full segment.
        let mut looping = Vec::new();
        append_leg(&mut looping, (0.0, 0.0), (0.0, 210.0));
        append_leg(&mut looping, (0.0, 210.0), (50.0, 210.0));
        append_leg(&mut looping, (50.0, 210.0), (50.0, 0.0));
        append_leg(&mut looping, (50.0, 0.0), (10.0, 0.0));
        append_leg(&mut looping, (10.0, 0.0), (10.0, 200.0));
        let segment = definition(&looping);

        let result = match_segment(segment, "source".to_string(), looping);
        assert_eq!(result.attempts.len(), 1, "rejected: {:?}", result.rejected);
        assert_eq!(result.rejected, vec![]);
    }

    #[test]
    fn parallel_trail_crossing_both_gates_is_rejected() {
        // The reference segment runs north. A different trail also runs north
        // but 60 m to the east: it crosses neither gate line (they are only
        // ±10..30 m wide), so nothing is reported at all.
        let track = straight_track(0);
        let segment = definition(&track);
        let parallel: Vec<CanonicalTrackPoint> = (0..=200)
            .map(|step| point(step * 200, 60.0, step as f64 * 1.0))
            .collect();

        let result = match_segment(segment, "other".to_string(), parallel);
        assert_eq!(result.attempts, vec![]);
    }

    #[test]
    fn hairpin_shortcut_between_gates_is_incomplete() {
        // A hairpin whose two legs are 24 m apart: north 120 m, across 24 m,
        // south 120 m. The legs are closer together than the 15 m corridor is
        // wide, so a rider can cross the start gate, cut straight across at the
        // bottom and cross the finish gate without ever riding the hairpin.
        // Only the coverage test can reject this.
        let mut hairpin = Vec::new();
        append_leg(&mut hairpin, (0.0, 0.0), (0.0, 120.0));
        append_leg(&mut hairpin, (0.0, 120.0), (24.0, 120.0));
        append_leg(&mut hairpin, (24.0, 120.0), (24.0, 0.0));
        let segment = definition(&hairpin);
        assert!(segment.corridor_m >= 15.0);

        let mut shortcut = Vec::new();
        append_leg(&mut shortcut, (0.0, -10.0), (0.0, 10.0));
        append_leg(&mut shortcut, (0.0, 10.0), (24.0, 10.0));
        append_leg(&mut shortcut, (24.0, 10.0), (24.0, -10.0));

        let result = match_segment(segment, "other".to_string(), shortcut);
        assert_eq!(result.attempts, vec![]);
        assert!(
            result
                .rejected
                .iter()
                .any(|rejected| rejected.reason == AttemptRejection::Incomplete),
            "rejected: {:?}",
            result.rejected
        );
    }

    #[test]
    fn leaving_the_corridor_rejects_the_attempt() {
        // Both gates are crossed, but the ride bulges 40 m east of the
        // reference line — a different trail rejoining the same trailhead.
        let track = straight_track(0);
        let segment = definition(&track);
        let mut detour = Vec::new();
        append_leg(&mut detour, (0.0, 0.0), (0.0, 50.0));
        append_leg(&mut detour, (0.0, 50.0), (40.0, 100.0));
        append_leg(&mut detour, (40.0, 100.0), (0.0, 150.0));
        append_leg(&mut detour, (0.0, 150.0), (0.0, 200.0));

        let result = match_segment(segment, "other".to_string(), detour);
        assert_eq!(result.attempts, vec![]);
        assert!(
            result
                .rejected
                .iter()
                .any(|rejected| rejected.reason == AttemptRejection::OffCorridor),
            "rejected: {:?}",
            result.rejected
        );
    }

    #[test]
    fn manual_pause_inside_rejects_the_attempt() {
        let track = straight_track(0);
        let segment = definition(&track);
        let mut paused = track.clone();
        for point in paused.iter_mut().skip(100) {
            point.section_id = 1;
            point.timestamp_ms += 60_000;
        }

        let result = match_segment(segment, "other".to_string(), paused);
        assert_eq!(result.attempts, vec![]);
        assert!(
            result.rejected.iter().any(|rejected| matches!(
                rejected.reason,
                AttemptRejection::PausedInside | AttemptRejection::NoFinish
            )),
            "rejected: {:?}",
            result.rejected
        );
    }

    #[test]
    fn gap_inside_rejects_the_attempt() {
        let track = straight_track(0);
        let segment = definition(&track);
        let mut gapped = track.clone();
        for point in gapped.iter_mut().skip(100) {
            point.timestamp_ms += 8_000;
        }

        let result = match_segment(segment, "other".to_string(), gapped);
        assert_eq!(result.attempts, vec![]);
        assert!(
            result.rejected.iter().any(|rejected| matches!(
                rejected.reason,
                AttemptRejection::GapInside | AttemptRejection::NoFinish
            )),
            "rejected: {:?}",
            result.rejected
        );
    }

    #[test]
    fn motorized_evidence_only_flags_the_attempt() {
        let track = straight_track(0);
        let segment = definition(&track);
        let mut suspicious = track.clone();
        for point in suspicious.iter_mut().skip(50).take(60) {
            point.activity_state = ActivityState::LikelyMotorized;
        }

        let result = match_segment(segment, "other".to_string(), suspicious);
        assert_eq!(result.attempts.len(), 1);
        assert!(
            result.attempts[0]
                .flags
                .contains(&AttemptFlag::LikelyMotorized)
        );
        assert_eq!(result.attempts[0].quality, AttemptQuality::Uncertain);
    }

    #[test]
    fn poor_accuracy_widens_the_gates_and_flags_quality() {
        let mut coarse = straight_track(0);
        for point in coarse.iter_mut() {
            point.accuracy_m = Some(18.0);
        }
        let segment = definition(&coarse);
        assert!((segment.gate_half_width_m - 30.0).abs() < 1e-9);
        assert!((segment.corridor_m - 40.0).abs() < 1e-9);

        let result = match_segment(segment, "other".to_string(), coarse);
        assert_eq!(result.attempts.len(), 1);
        assert!(
            result.attempts[0]
                .flags
                .contains(&AttemptFlag::LowGpsQuality)
        );
    }

    #[test]
    fn authored_profile_is_bounded_and_keeps_both_elevation_directions() {
        let mut track = straight_track(0);
        for (index, point) in track.iter_mut().enumerate() {
            point.altitude_m = Some(if index <= 100 {
                1_000.0 - index as f64 * 0.1
            } else {
                990.0 + (index - 100) as f64 * 0.1
            });
        }

        let segment = definition(&track);
        assert!(segment.ascent_m.is_some_and(|value| value >= 8.0));
        assert!(segment.descent_m.is_some_and(|value| value >= 8.0));
        assert!(segment.elevation_profile.len() <= MAX_ELEVATION_PROFILE_POINTS);
        assert_eq!(
            segment
                .elevation_profile
                .first()
                .map(|point| point.distance_m),
            Some(0.0)
        );
        assert!(
            segment
                .elevation_profile
                .last()
                .is_some_and(|point| (point.distance_m - segment.length_m).abs() < 0.5)
        );
    }

    #[test]
    fn continuous_authoring_interpolates_gate_geometry_and_time() {
        let track = straight_track(1_000);
        let result = build_segment_continuous(
            "seg".to_string(),
            "Continuous".to_string(),
            "source".to_string(),
            track,
            0.25,
            199.75,
        )
        .expect("continuous selection should be valid");

        assert_eq!(result.definition.geometry_version, 2);
        assert_eq!(result.started_at_ms, 1_050);
        assert_eq!(result.finished_at_ms, 40_950);
        assert!((result.definition.length_m - 199.5).abs() < 0.2);
        let first = result.definition.centerline.first().unwrap();
        let last = result.definition.centerline.last().unwrap();
        assert!((first.lat - (LAT + 0.25 / M_PER_DEG_LAT)).abs() < 1e-10);
        assert!((last.lat - (LAT + 199.75 / M_PER_DEG_LAT)).abs() < 1e-10);
    }

    #[test]
    fn continuous_authoring_rejects_a_fractional_pause_edge() {
        let mut track = straight_track(0);
        track[100].section_id = 1;
        let error = build_segment_continuous(
            "seg".to_string(),
            "Pause".to_string(),
            "source".to_string(),
            track,
            99.5,
            199.0,
        )
        .expect_err("fractional position cannot cross a pause");
        assert!(matches!(error, SegmentError::InvalidSelection { .. }));
    }

    #[test]
    fn short_selection_is_rejected() {
        let track: Vec<CanonicalTrackPoint> = (0..=20)
            .map(|step| point(step * 200, 0.0, step as f64))
            .collect();
        let error = build_segment(
            "seg".to_string(),
            "Short".to_string(),
            "source".to_string(),
            track,
            0,
            20,
        )
        .expect_err("20 m selection must be rejected");
        assert!(matches!(error, SegmentError::InvalidSelection { .. }));
    }

    #[test]
    fn selection_across_a_pause_is_rejected() {
        let mut track = straight_track(0);
        for point in track.iter_mut().skip(100) {
            point.section_id = 1;
        }
        let error = build_segment(
            "seg".to_string(),
            "Paused".to_string(),
            "source".to_string(),
            track,
            0,
            200,
        )
        .expect_err("a selection crossing a pause must be rejected");
        assert!(matches!(error, SegmentError::InvalidSelection { .. }));
    }

    #[test]
    fn proposal_picks_the_longest_downhill_run() {
        let mut track = straight_track(0);
        // Transit for the first 60 points, downhill for the rest.
        for point in track.iter_mut().take(60) {
            point.activity_state = ActivityState::Transit;
        }
        let proposal = propose_segment(track).expect("a downhill run should be proposed");
        assert_eq!(proposal.start_index, 60);
        assert_eq!(proposal.end_index, 200);
        assert!(proposal.length_m > 130.0 && proposal.length_m < 145.0);
        assert!(proposal.descent_m.is_some_and(|drop| drop > 13.0));
    }

    #[test]
    fn proposal_never_crosses_a_pause() {
        let mut track = straight_track(0);
        for point in track.iter_mut().skip(100) {
            point.section_id = 1;
        }
        let proposal = propose_segment(track).expect("one side should still qualify");
        assert!(
            proposal.end_index <= 99 || proposal.start_index >= 100,
            "proposal {}..{} crossed the pause",
            proposal.start_index,
            proposal.end_index
        );
    }

    #[test]
    fn search_bounds_are_padded_by_the_corridor() {
        let track = straight_track(0);
        let segment = definition(&track);
        let bounds = segment_search_bounds(segment.clone()).expect("bounds exist");
        assert!(bounds.min_lat < LAT);
        assert!(bounds.max_lat > LAT + 200.0 / M_PER_DEG_LAT);
        let lat_pad_m = (LAT - bounds.min_lat) * M_PER_DEG_LAT;
        assert!(
            (lat_pad_m - segment.corridor_m).abs() < 1.0,
            "pad was {lat_pad_m} m for a {} m corridor",
            segment.corridor_m
        );
    }
}
