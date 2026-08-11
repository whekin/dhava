//! Conservative post-ride activity classification.
//!
//! Classification deliberately runs only after the canonical 5 Hz geometry,
//! altitude and speed passes are complete. It is evidence-based rather than a
//! mode detector: speed alone can establish movement, but can never establish
//! motorized transport.

use crate::canonical::ascent_descent;
use crate::motion::{MotionSample, motion_samples};
use crate::{CanonicalTrackPoint, ImuSample};

const MAX_CONTIGUOUS_GAP_MS: i64 = 3_000;
const TARGET_WINDOW_MS: i64 = 10_000;
const MIN_EVIDENCE_DURATION_MS: i64 = 6_000;
const MIN_EVIDENCE_POINTS: usize = 5;
const CLEAR_MOVEMENT_SPEED_MPS: f64 = 1.2;
const FAST_MOVEMENT_SPEED_MPS: f64 = 7.0;
const SMOOTH_VEHICLE_SPEED_MPS: f64 = 10.0;
const DOWNHILL_GRADE: f64 = -0.02;
const DOWNHILL_VERTICAL_SPEED_MPS: f64 = -0.10;
const DOWNHILL_DROP_M: f64 = -1.5;
const FAST_CLIMB_GRADE: f64 = 0.025;
const FAST_CLIMB_VERTICAL_SPEED_MPS: f64 = 0.25;
const FAST_CLIMB_GAIN_M: f64 = 2.5;
/// Sustained rate of climb no rider produces under their own power.
///
/// Elite road climbing tops out near 0.5 m/s (1 800 m/h) and a mountain bike
/// with pads is well below that; a shuttle van or a bus on the same road
/// climbs at 1.5–4 m/s. This is the evidence the old rules were missing: they
/// only recognized a vehicle above 25 km/h, which a switchback fire road never
/// reaches, so the middle of every shuttle lap fell back to plain transit.
const VEHICLE_CLIMB_VERTICAL_SPEED_MPS: f64 = 0.6;
/// Minimum gain behind that rate, so altitude noise alone cannot claim it.
const VEHICLE_CLIMB_GAIN_M: f64 = 5.0;
const MIN_SHORT_ISLAND_MS: i64 = 3_000;
const MIN_DOWNHILL_RUN_MS: i64 = 3_000;
const MIN_MOTORIZED_RUN_MS: i64 = 12_000;
/// Longest non-descending interruption that still belongs to the same ride in
/// the same vehicle: a traffic light, a flat stretch, a passenger stop.
const MAX_MOTORIZED_BRIDGE_MS: i64 = 90_000;
/// Longest congestion a vehicle span may absorb when the motion evidence keeps
/// saying "vehicle" throughout. Far beyond a traffic light, far short of a
/// lunch stop.
const MAX_MOTORIZED_TRAFFIC_BRIDGE_MS: i64 = 900_000;
/// Above this share of confirmed STILL the interruption is a stop, not a crawl,
/// and the rider may well have got out.
const MAX_TRAFFIC_STILL_FRACTION: f64 = 0.6;
/// The shortest a genuine "got out, rode down, got back in" can take.
///
/// Getting out of a shuttle to ride is not just the descent. It is unloading
/// the bike, riding, then standing at the bottom until the vehicle comes back
/// round for you — and that last part dominates. Measured across a real
/// Kojori shuttle day: every genuine run put 600 s or more between the two
/// motorized spans on either side of it (a 105–162 s descent followed by
/// 300–500 s of waiting), while the dips *inside* a shuttle leg — where the
/// road crosses a ridge and drops into the next valley — took 72 s and 122 s
/// and the vehicle resumed climbing immediately.
///
/// So a gap this short cannot contain a ride, whatever its shape: the rider
/// had no time to get out and back in. That is the one question geometry
/// cannot answer here, because those road dips (-35 m, -80 m) are the same
/// size as a short run.
///
/// Set well below the fastest observed turnaround rather than at the midpoint.
/// The two errors are not equally bad: absorbing a real run into a lift erases
/// descent, the number the product exists to report, while leaving a road dip
/// unabsorbed only fragments a transfer.
const MIN_SHUTTLE_TURNAROUND_MS: i64 = 240_000;
/// Height a walk between two vehicles cannot give up, but a ridden descent —
/// even a slow technical one — does.
const RIDING_DROP_M: f64 = 30.0;
/// Nobody walks a bike this fast, so sustained rough motion above it is riding.
const WALKING_SPEED_MAX_MPS: f64 = 2.5;
const MIN_RIDING_FRACTION: f64 = 0.3;
const MAX_SMOOTH_IMU_GAP_MS: i64 = 250;
const MIN_SMOOTH_IMU_SAMPLES: usize = 100;
const SMOOTH_ACCEL_P90_MPS2: f64 = 0.45;
const SMOOTH_GYRO_P90_RAD_S: f64 = 0.12;

/// Mutually exclusive interpretation of one canonical track point.
///
/// `LikelyMotorized` is intentionally tentative: it means that the post-ride
/// evidence looks vehicle-like, not that the classifier has proved a vehicle
/// was used.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ActivityState {
    Unknown,
    Still,
    Downhill,
    Transit,
    LikelyMotorized,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub(crate) struct ActivityClassification {
    pub state: ActivityState,
    pub confidence: f64,
}

impl ActivityClassification {
    const UNKNOWN: Self = Self {
        state: ActivityState::Unknown,
        confidence: 0.0,
    };

    fn new(state: ActivityState, confidence: f64) -> Self {
        Self {
            state,
            confidence: confidence.clamp(0.0, 1.0),
        }
    }
}

#[derive(Debug, Clone, Copy)]
struct AltitudeTrend {
    delta_m: f64,
    vertical_speed_mps: f64,
    grade: f64,
}

#[derive(Debug, Clone, Copy)]
struct WindowEvidence {
    duration_ms: i64,
    median_speed_mps: Option<f64>,
    moving_fraction: f64,
    altitude: Option<AltitudeTrend>,
    strongly_smooth: bool,
}

/// Classifies finalized points without crossing manual-pause sections or GPS
/// gaps. The returned vector always has the same length as `track`.
pub(crate) fn classify_activity(
    track: &[CanonicalTrackPoint],
    imu: &[ImuSample],
) -> Vec<ActivityClassification> {
    let mut output = vec![ActivityClassification::UNKNOWN; track.len()];
    if track.is_empty() {
        return output;
    }

    let motion_samples = motion_samples(imu);
    for (start, end) in contiguous_spans(track) {
        classify_span(track, &motion_samples, start, end, &mut output[start..end]);
        // Bridge before smoothing: a shuttle lap arrives as motorized evidence
        // separated by traffic lights and flat stretches, and joining those
        // fragments first is what makes the resulting run long enough to
        // survive the minimum-duration rule below.
        bridge_motorized_runs(track, &motion_samples, start, &mut output[start..end]);
        smooth_short_islands(track, start, end, &mut output[start..end]);
    }
    output
}

fn contiguous_spans(track: &[CanonicalTrackPoint]) -> Vec<(usize, usize)> {
    let mut spans = Vec::new();
    let mut start = 0;
    for index in 1..track.len() {
        let gap_ms = track[index].timestamp_ms - track[index - 1].timestamp_ms;
        if track[index].section_id != track[index - 1].section_id
            || !(1..=MAX_CONTIGUOUS_GAP_MS).contains(&gap_ms)
        {
            spans.push((start, index));
            start = index;
        }
    }
    spans.push((start, track.len()));
    spans
}

fn classify_span(
    track: &[CanonicalTrackPoint],
    motion_samples: &[MotionSample],
    start: usize,
    end: usize,
    output: &mut [ActivityClassification],
) {
    let span = &track[start..end];
    let span_start_ms = span[0].timestamp_ms;
    let span_end_ms = span[span.len() - 1].timestamp_ms;
    let span_duration_ms = span_end_ms - span_start_ms;

    for (local_index, point) in span.iter().enumerate() {
        // Confirmed stationarity is direct evidence and always outranks every
        // movement inference, even inside a downhill or vehicle-like window.
        if point.stationary == Some(true) {
            output[local_index] = ActivityClassification::new(ActivityState::Still, 0.99);
            continue;
        }
        if span_duration_ms < MIN_EVIDENCE_DURATION_MS {
            continue;
        }

        let window_duration_ms = TARGET_WINDOW_MS.min(span_duration_ms);
        let earliest_start_ms = span_end_ms - window_duration_ms;
        let window_start_ms =
            (point.timestamp_ms - window_duration_ms / 2).clamp(span_start_ms, earliest_start_ms);
        let window_end_ms = window_start_ms + window_duration_ms;
        let window_start =
            span.partition_point(|candidate| candidate.timestamp_ms < window_start_ms);
        let window_end = span.partition_point(|candidate| candidate.timestamp_ms <= window_end_ms);
        let window = &span[window_start..window_end];
        let Some(evidence) =
            window_evidence(window, motion_samples, window_start_ms, window_end_ms)
        else {
            continue;
        };
        output[local_index] = classify_evidence(evidence);
    }
}

fn window_evidence(
    window: &[CanonicalTrackPoint],
    motion_samples: &[MotionSample],
    start_ms: i64,
    end_ms: i64,
) -> Option<WindowEvidence> {
    if window.len() < MIN_EVIDENCE_POINTS || end_ms - start_ms < MIN_EVIDENCE_DURATION_MS {
        return None;
    }

    let speeds: Vec<_> = window
        .iter()
        .filter_map(|point| point.speed_mps)
        .filter(|speed| speed.is_finite() && *speed >= 0.0)
        .collect();
    let median_speed_mps = median(&speeds);
    let moving_fraction = if speeds.is_empty() {
        0.0
    } else {
        speeds
            .iter()
            .filter(|speed| **speed >= CLEAR_MOVEMENT_SPEED_MPS)
            .count() as f64
            / speeds.len() as f64
    };
    let altitude = altitude_trend(window, median_speed_mps);
    let strongly_smooth = strongly_smooth_motion(motion_samples, start_ms, end_ms);

    Some(WindowEvidence {
        duration_ms: end_ms - start_ms,
        median_speed_mps,
        moving_fraction,
        altitude,
        strongly_smooth,
    })
}

fn classify_evidence(evidence: WindowEvidence) -> ActivityClassification {
    let Some(speed_mps) = evidence.median_speed_mps else {
        return ActivityClassification::UNKNOWN;
    };
    let clear_movement = speed_mps >= CLEAR_MOVEMENT_SPEED_MPS && evidence.moving_fraction >= 0.65;
    if !clear_movement {
        return ActivityClassification::UNKNOWN;
    }

    // Motorized classification always needs evidence independent from speed:
    // a sustained fast climb, or exceptionally smooth continuous IMU motion.
    let fast_climb = evidence.altitude.is_some_and(|trend| {
        speed_mps >= FAST_MOVEMENT_SPEED_MPS
            && trend.grade >= FAST_CLIMB_GRADE
            && trend.vertical_speed_mps >= FAST_CLIMB_VERTICAL_SPEED_MPS
            && trend.delta_m >= FAST_CLIMB_GAIN_M
    });
    let smooth_vehicle = speed_mps >= SMOOTH_VEHICLE_SPEED_MPS
        && evidence.duration_ms >= TARGET_WINDOW_MS
        && evidence.strongly_smooth;
    // Rate of climb is evidence on its own, at any road speed: it is the
    // product of speed and grade, and no rider sustains it.
    let vehicle_climb = evidence.altitude.is_some_and(|trend| {
        trend.vertical_speed_mps >= VEHICLE_CLIMB_VERTICAL_SPEED_MPS
            && trend.delta_m >= VEHICLE_CLIMB_GAIN_M
    });
    if fast_climb || smooth_vehicle || vehicle_climb {
        let confidence = if fast_climb && smooth_vehicle {
            0.94
        } else if fast_climb {
            0.82
        } else if vehicle_climb && evidence.strongly_smooth {
            0.88
        } else if vehicle_climb {
            0.80
        } else {
            0.76
        };
        return ActivityClassification::new(ActivityState::LikelyMotorized, confidence);
    }

    let downhill = evidence.altitude.is_some_and(|trend| {
        trend.grade <= DOWNHILL_GRADE
            && trend.vertical_speed_mps <= DOWNHILL_VERTICAL_SPEED_MPS
            && trend.delta_m <= DOWNHILL_DROP_M
    });
    if downhill {
        let trend = evidence.altitude.expect("downhill requires altitude");
        let grade_strength = ((-trend.grade - -DOWNHILL_GRADE) / 0.08).clamp(0.0, 1.0);
        return ActivityClassification::new(ActivityState::Downhill, 0.72 + 0.22 * grade_strength);
    }

    let speed_strength = ((speed_mps - CLEAR_MOVEMENT_SPEED_MPS) / 5.0).clamp(0.0, 1.0);
    ActivityClassification::new(ActivityState::Transit, 0.58 + 0.22 * speed_strength)
}

/// Robust endpoint trend. Medians over the first and last fifth reject an
/// isolated altitude spike without hiding a sustained grade.
fn altitude_trend(
    window: &[CanonicalTrackPoint],
    median_speed_mps: Option<f64>,
) -> Option<AltitudeTrend> {
    let altitude: Vec<_> = window
        .iter()
        .filter_map(|point| {
            point
                .altitude_m
                .filter(|value| value.is_finite())
                .map(|value| (point.timestamp_ms, value))
        })
        .collect();
    if altitude.len() < MIN_EVIDENCE_POINTS {
        return None;
    }
    let total_duration_ms = window.last()?.timestamp_ms - window.first()?.timestamp_ms;
    let altitude_duration_ms = altitude.last()?.0 - altitude.first()?.0;
    if total_duration_ms <= 0
        || altitude_duration_ms < MIN_EVIDENCE_DURATION_MS
        || altitude_duration_ms as f64 / (total_duration_ms as f64) < 0.8
    {
        return None;
    }

    let edge_count = (altitude.len() / 5).max(3).min(altitude.len() / 2);
    let start = &altitude[..edge_count];
    let end = &altitude[altitude.len() - edge_count..];
    let start_altitude = median(&start.iter().map(|sample| sample.1).collect::<Vec<_>>())?;
    let end_altitude = median(&end.iter().map(|sample| sample.1).collect::<Vec<_>>())?;
    let start_timestamp_ms =
        start.iter().map(|sample| sample.0 as i128).sum::<i128>() / edge_count as i128;
    let end_timestamp_ms =
        end.iter().map(|sample| sample.0 as i128).sum::<i128>() / edge_count as i128;
    let trend_duration_s = (end_timestamp_ms - start_timestamp_ms) as f64 / 1_000.0;
    if trend_duration_s <= 0.0 {
        return None;
    }

    let delta_m = end_altitude - start_altitude;
    let vertical_speed_mps = delta_m / trend_duration_s;
    let grade = vertical_speed_mps / median_speed_mps.filter(|speed| *speed > 0.0)?;
    Some(AltitudeTrend {
        delta_m,
        vertical_speed_mps,
        grade,
    })
}

fn strongly_smooth_motion(samples: &[MotionSample], start_ms: i64, end_ms: i64) -> bool {
    let start = samples.partition_point(|sample| sample.timestamp_ms < start_ms);
    let end = samples.partition_point(|sample| sample.timestamp_ms <= end_ms);
    let window = &samples[start..end];
    if window.len() < MIN_SMOOTH_IMU_SAMPLES {
        return false;
    }
    let requested_duration_ms = end_ms - start_ms;
    let covered_duration_ms = window[window.len() - 1].timestamp_ms - window[0].timestamp_ms;
    if requested_duration_ms < TARGET_WINDOW_MS
        || covered_duration_ms as f64 / (requested_duration_ms as f64) < 0.8
        || window
            .windows(2)
            .any(|pair| pair[1].timestamp_ms - pair[0].timestamp_ms > MAX_SMOOTH_IMU_GAP_MS)
    {
        return false;
    }

    let accel_errors: Vec<_> = window
        .iter()
        .map(|sample| sample.accel_error_mps2)
        .collect();
    let gyro: Vec<_> = window.iter().map(|sample| sample.gyro_rad_s).collect();
    percentile(&accel_errors, 0.9).is_some_and(|p90| p90 <= SMOOTH_ACCEL_P90_MPS2)
        && percentile(&gyro, 0.9).is_some_and(|p90| p90 <= SMOOTH_GYRO_P90_RAD_S)
}

/// Joins motorized runs separated by a short gap of anything that is not a
/// descent.
///
/// A shuttle lap does not produce one continuous block of vehicle evidence: a
/// bus waits at a light (STILL), rolls a flat kilometre at bicycle speed
/// (TRANSIT) and loses GPS under trees (UNKNOWN), then climbs again. Those
/// interruptions are part of the same ride in the same vehicle, so the label
/// should span them.
///
/// Deliberately one-directional: a gap is only filled when motorized evidence
/// exists on *both* sides, and never across a DOWNHILL point. Getting out at
/// the top and riding down therefore ends the span, and a single vehicle-like
/// window can never spread over a ride on its own.
fn bridge_motorized_runs(
    track: &[CanonicalTrackPoint],
    motion_samples: &[MotionSample],
    span_start: usize,
    classifications: &mut [ActivityClassification],
) {
    let mut index = 0;
    let mut previous_motorized_end: Option<usize> = None;
    while index < classifications.len() {
        if classifications[index].state != ActivityState::LikelyMotorized {
            index += 1;
            continue;
        }
        let run_start = index;
        while index < classifications.len()
            && classifications[index].state == ActivityState::LikelyMotorized
        {
            index += 1;
        }

        if let Some(gap_start) = previous_motorized_end {
            let gap = &classifications[gap_start..run_start];
            let gap_start_ms = track[span_start + gap_start - 1].timestamp_ms;
            let gap_end_ms = track[span_start + run_start].timestamp_ms;
            let gap_ms = gap_end_ms - gap_start_ms;
            let descends = gap
                .iter()
                .any(|candidate| candidate.state == ActivityState::Downhill);
            // A short flat interruption needs nothing more than its brevity.
            // A descent, or anything long, has to keep looking like a vehicle
            // all the way through — that is what separates a dip in a shuttle
            // road from the rider getting out and riding down it.
            let plain_short_gap = !descends && gap_ms <= MAX_MOTORIZED_BRIDGE_MS;
            if plain_short_gap
                || vehicle_like_interruption(track, motion_samples, gap, gap_start_ms, gap_end_ms)
            {
                // The bridge is only ever as confident as the weaker side.
                let confidence = classifications[gap_start - 1]
                    .confidence
                    .min(classifications[run_start].confidence);
                for classification in &mut classifications[gap_start..run_start] {
                    *classification =
                        ActivityClassification::new(ActivityState::LikelyMotorized, confidence);
                }
            }
        }
        previous_motorized_end = Some(index);
    }
}

/// Whether an interruption between two vehicle spans is still the vehicle.
///
/// This covers the two things a fixed duration limit cannot judge. Congestion:
/// a bus can crawl and stop for ten minutes without ever producing the speed
/// or the rate of climb that identifies a vehicle, so the leg arrives as a
/// mess of short TRANSIT, UNKNOWN and STILL. And a shuttle road that is not
/// monotonic: a serpentine has dips and flat shelves, and the descent between
/// two switchbacks was being read as a run and credited to the rider.
///
/// Two things separate both from a rider who got out:
///
///  * the motion stays vehicle-smooth throughout — a mountain bike descending
///    a trail, pushing, walking or pedalling never is, and
///  * the gap is stop *and go*, not one long stop. Waiting at the bottom for
///    the next shuttle is a real stop and stays STILL.
///
/// Without IMU evidence (a GPS-only recording) this is never claimed, so a
/// descent keeps splitting the span rather than being absorbed on a guess.
fn vehicle_like_interruption(
    track: &[CanonicalTrackPoint],
    motion_samples: &[MotionSample],
    gap: &[ActivityClassification],
    start_ms: i64,
    end_ms: i64,
) -> bool {
    if end_ms - start_ms > MAX_MOTORIZED_TRAFFIC_BRIDGE_MS || gap.is_empty() {
        return false;
    }
    let still = gap
        .iter()
        .filter(|candidate| candidate.state == ActivityState::Still)
        .count();
    if still as f64 / gap.len() as f64 > MAX_TRAFFIC_STILL_FRACTION {
        return false;
    }
    // Too quick to have been a ride at all. The stop test above has already
    // ruled out the rider standing at the bottom waiting, so what is left is
    // the vehicle still moving — and it was never empty.
    if end_ms - start_ms < MIN_SHUTTLE_TURNAROUND_MS {
        return true;
    }
    !contains_riding(track, motion_samples, start_ms, end_ms)
}

/// Whether the rider was on the bike during an interruption.
///
/// A shuttle leg is one leg: nobody gets out mid-transfer, rides down, and
/// gets back in. What does happen is walking — to a gate, around a barrier,
/// between two vans — and that is part of the transfer, not a ride. So the
/// question is not whether the gap looks like a vehicle, it is whether it
/// contains riding.
///
/// Vehicle-smooth motion is never riding. Rough motion is riding only when it
/// also does something a walk cannot: give up real height, or hold a speed no
/// one walks at. The height test matters most, because a slow technical
/// descent is ridden at walking pace and must never be absorbed into a lift.
fn contains_riding(
    track: &[CanonicalTrackPoint],
    motion_samples: &[MotionSample],
    start_ms: i64,
    end_ms: i64,
) -> bool {
    if strongly_smooth_motion(motion_samples, start_ms, end_ms) {
        return false;
    }
    let window: Vec<CanonicalTrackPoint> = track
        .iter()
        .filter(|point| (start_ms..=end_ms).contains(&point.timestamp_ms))
        .cloned()
        .collect();
    if window.is_empty() {
        // Rough motion with nothing to explain it: treat it as riding rather
        // than quietly folding it into a lift.
        return true;
    }
    // Net loss, not gross descent. A shuttle road is not monotonic: a
    // serpentine gives up forty metres between two switchbacks and takes them
    // straight back, and reading the gross figure called every one of those
    // dips a run. A rider who gets out to ride does not come back up to the
    // same height to continue the transfer — they leave, and the interruption
    // ends hundreds of metres lower.
    let (ascent_m, descent_m) = ascent_descent(&window);
    if descent_m - ascent_m >= RIDING_DROP_M {
        return true;
    }
    // Anything else that ends level or higher is the shuttle continuing, and
    // must not reach the speed test below: that test asks whether motion is
    // faster than walking, which was built to separate riding from pushing a
    // bike, and a van holding 20 km/h through a switchback answers yes.
    if ascent_m >= VEHICLE_CLIMB_GAIN_M || descent_m < RIDING_DROP_M {
        return false;
    }
    let speeds: Vec<f64> = window
        .iter()
        .filter_map(|point| point.speed_mps)
        .filter(|speed| speed.is_finite())
        .collect();
    if speeds.is_empty() {
        return false;
    }
    let above_walking = speeds
        .iter()
        .filter(|speed| **speed >= WALKING_SPEED_MAX_MPS)
        .count();
    above_walking as f64 / speeds.len() as f64 >= MIN_RIDING_FRACTION
}

/// Removes short non-stationary label islands while preserving every direct
/// STILL observation. Special labels that never become sustained degrade to
/// ordinary movement rather than creating a misleading flash on the map.
fn smooth_short_islands(
    track: &[CanonicalTrackPoint],
    span_start: usize,
    span_end: usize,
    classifications: &mut [ActivityClassification],
) {
    let mut run_start = 0;
    while run_start < classifications.len() {
        let state = classifications[run_start].state;
        let mut run_end = run_start + 1;
        while run_end < classifications.len() && classifications[run_end].state == state {
            run_end += 1;
        }
        let duration_ms = track[span_start + run_end - 1].timestamp_ms
            - track[span_start + run_start].timestamp_ms;
        let minimum_run_ms = match state {
            ActivityState::Downhill => MIN_DOWNHILL_RUN_MS,
            ActivityState::LikelyMotorized => MIN_MOTORIZED_RUN_MS,
            _ => MIN_SHORT_ISLAND_MS,
        };
        if state != ActivityState::Still && duration_ms < minimum_run_ms {
            let previous = run_start.checked_sub(1).map(|index| classifications[index]);
            let next = classifications.get(run_end).copied();
            if previous.is_some_and(|candidate| {
                Some(candidate.state) == next.map(|candidate| candidate.state)
                    && candidate.state != ActivityState::Still
            }) {
                let replacement = previous.expect("matching neighbor exists");
                for classification in &mut classifications[run_start..run_end] {
                    *classification = replacement;
                }
            } else if matches!(
                state,
                ActivityState::Downhill | ActivityState::LikelyMotorized
            ) {
                for (offset, classification) in
                    classifications[run_start..run_end].iter_mut().enumerate()
                {
                    let point = &track[span_start + run_start + offset];
                    *classification = if point
                        .speed_mps
                        .is_some_and(|speed| speed >= CLEAR_MOVEMENT_SPEED_MPS)
                    {
                        ActivityClassification::new(ActivityState::Transit, 0.55)
                    } else {
                        ActivityClassification::UNKNOWN
                    };
                }
            }
        }
        run_start = run_end;
    }

    debug_assert_eq!(span_end - span_start, classifications.len());
}

fn median(values: &[f64]) -> Option<f64> {
    percentile(values, 0.5)
}

fn percentile(values: &[f64], fraction: f64) -> Option<f64> {
    if values.is_empty() {
        return None;
    }
    let mut sorted = values.to_vec();
    sorted.sort_by(f64::total_cmp);
    let rank = fraction.clamp(0.0, 1.0) * (sorted.len() - 1) as f64;
    let below = rank.floor() as usize;
    let above = rank.ceil() as usize;
    let weight = rank - below as f64;
    Some(sorted[below] + (sorted[above] - sorted[below]) * weight)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::motion::STANDARD_GRAVITY_MPS2;

    fn point(
        timestamp_ms: i64,
        altitude_m: Option<f64>,
        speed_mps: f64,
        stationary: bool,
        section_id: i32,
    ) -> CanonicalTrackPoint {
        CanonicalTrackPoint {
            timestamp_ms,
            lat: 41.7,
            lon: 44.8 + timestamp_ms as f64 * 1e-9,
            altitude_m,
            accuracy_m: Some(4.0),
            speed_mps: Some(speed_mps),
            stationary: Some(stationary),
            section_id,
            activity_state: ActivityState::Unknown,
            activity_confidence: 0.0,
        }
    }

    fn linear_track(
        start_ms: i64,
        seconds: i64,
        start_altitude_m: Option<f64>,
        vertical_speed_mps: f64,
        speed_mps: f64,
        section_id: i32,
    ) -> Vec<CanonicalTrackPoint> {
        (0..=seconds)
            .map(|second| {
                point(
                    start_ms + second * 1_000,
                    start_altitude_m.map(|altitude| altitude + vertical_speed_mps * second as f64),
                    speed_mps,
                    false,
                    section_id,
                )
            })
            .collect()
    }

    /// Standing at the bottom of a run waiting to be picked up.
    fn waiting_track(
        start_ms: i64,
        seconds: i64,
        altitude_m: f64,
        section_id: i32,
    ) -> Vec<CanonicalTrackPoint> {
        (0..=seconds)
            .map(|second| {
                point(
                    start_ms + second * 1_000,
                    Some(altitude_m),
                    0.0,
                    true,
                    section_id,
                )
            })
            .collect()
    }

    #[test]
    fn confirmed_still_wins_inside_a_downhill_window() {
        let mut track = linear_track(0, 12, Some(100.0), -1.0, 8.0, 0);
        track[6].stationary = Some(true);

        let classified = classify_activity(&track, &[]);

        assert_eq!(classified[6].state, ActivityState::Still);
        assert!(classified[6].confidence > 0.95);
        assert!(
            classified
                .iter()
                .enumerate()
                .filter(|(index, _)| *index != 6)
                .any(|(_, point)| point.state == ActivityState::Downhill)
        );
    }

    #[test]
    fn isolated_altitude_spike_does_not_create_downhill() {
        let mut track = linear_track(0, 12, Some(100.0), 0.0, 6.0, 0);
        track[6].altitude_m = Some(70.0);

        let classified = classify_activity(&track, &[]);

        assert!(
            classified
                .iter()
                .all(|point| point.state == ActivityState::Transit)
        );
    }

    #[test]
    fn classification_never_bridges_pause_sections() {
        let mut track = linear_track(0, 10, Some(100.0), -1.0, 5.0, 0);
        track.extend(linear_track(20_000, 10, Some(20.0), 0.5, 5.0, 1));

        let classified = classify_activity(&track, &[]);

        assert!(
            classified[..11]
                .iter()
                .all(|point| point.state == ActivityState::Downhill)
        );
        assert!(
            classified[11..]
                .iter()
                .all(|point| point.state == ActivityState::Transit)
        );
    }

    #[test]
    fn classification_never_bridges_gps_gaps() {
        let mut track = linear_track(0, 6, Some(100.0), 0.0, 5.0, 0);
        track.extend(linear_track(10_000, 6, Some(20.0), 0.0, 5.0, 0));

        let classified = classify_activity(&track, &[]);

        assert!(
            classified
                .iter()
                .all(|point| point.state == ActivityState::Transit)
        );
    }

    #[test]
    fn flat_fast_movement_is_not_motorized_from_speed_alone() {
        let track = linear_track(0, 12, Some(100.0), 0.0, 14.0, 0);

        let classified = classify_activity(&track, &[]);

        assert!(
            classified
                .iter()
                .all(|point| point.state == ActivityState::Transit)
        );
    }

    #[test]
    fn sustained_fast_climb_is_likely_motorized() {
        let track = linear_track(0, 12, Some(100.0), 0.8, 9.0, 0);

        let classified = classify_activity(&track, &[]);

        assert!(
            classified
                .iter()
                .all(|point| point.state == ActivityState::LikelyMotorized)
        );
        assert!(classified.iter().all(|point| point.confidence >= 0.8));
    }

    #[test]
    fn missing_altitude_still_allows_clear_transit() {
        let track = linear_track(0, 12, None, 0.0, 5.0, 0);

        let classified = classify_activity(&track, &[]);

        assert!(
            classified
                .iter()
                .all(|point| point.state == ActivityState::Transit)
        );
    }

    #[test]
    fn sustained_fast_smooth_motion_is_likely_motorized() {
        let track = linear_track(0, 12, Some(100.0), 0.0, 12.0, 0);
        let imu: Vec<_> = (0..=240)
            .map(|index| ImuSample {
                timestamp_ms: index * 50,
                accel: [0.0, 0.0, STANDARD_GRAVITY_MPS2 as f32],
                gyro: [0.01, 0.01, 0.01],
                mag: None,
            })
            .collect();

        let classified = classify_activity(&track, &imu);

        assert!(
            classified
                .iter()
                .all(|point| point.state == ActivityState::LikelyMotorized)
        );
    }

    #[test]
    fn short_likely_motorized_island_degrades_to_transit() {
        let track = linear_track(0, 9, Some(100.0), 0.0, 12.0, 0);
        let mut classifications =
            vec![ActivityClassification::new(ActivityState::LikelyMotorized, 0.8); track.len()];
        classifications[0] = ActivityClassification::new(ActivityState::Still, 0.99);
        classifications[9] = ActivityClassification::new(ActivityState::Downhill, 0.8);

        smooth_short_islands(&track, 0, track.len(), &mut classifications);

        assert!(
            classifications[1..9]
                .iter()
                .all(|point| point.state == ActivityState::Transit)
        );
    }

    #[test]
    fn twelve_second_likely_motorized_run_is_retained() {
        let track = linear_track(0, 12, Some(100.0), 0.0, 12.0, 0);
        let mut classifications =
            vec![ActivityClassification::new(ActivityState::LikelyMotorized, 0.8); track.len()];

        smooth_short_islands(&track, 0, track.len(), &mut classifications);

        assert!(
            classifications
                .iter()
                .all(|point| point.state == ActivityState::LikelyMotorized)
        );
    }

    #[test]
    fn a_shuttle_climb_at_road_speed_is_motorized_for_its_whole_length() {
        // 4 minutes of switchback fire road: 15 km/h, climbing 1.8 m/s. Both
        // the old speed thresholds (25 and 36 km/h) reject this outright.
        let track = linear_track(0, 240, Some(300.0), 1.8, 4.2, 0);

        let classified = classify_activity(&track, &[]);

        let motorized = classified
            .iter()
            .filter(|point| point.state == ActivityState::LikelyMotorized)
            .count();
        assert!(
            motorized as f64 / classified.len() as f64 > 0.9,
            "only {motorized}/{} of the shuttle climb was motorized",
            classified.len(),
        );
    }

    #[test]
    fn a_rider_climbing_hard_is_never_motorized() {
        // 0.45 m/s of climb at 9 km/h — a strong rider on a steep fire road,
        // right below the rate the rule treats as impossible.
        let track = linear_track(0, 240, Some(300.0), 0.45, 2.5, 0);

        let classified = classify_activity(&track, &[]);

        assert!(
            classified
                .iter()
                .all(|point| point.state != ActivityState::LikelyMotorized),
            "a human climb was called a vehicle",
        );
    }

    #[test]
    fn a_traffic_light_does_not_split_one_bus_ride() {
        let mut track = linear_track(0, 60, Some(100.0), 1.5, 6.0, 0);
        // Stopped at a light for 40 s, then climbing again.
        track.extend((61..=100).map(|second| point(second * 1_000, Some(190.0), 0.0, true, 0)));
        track.extend(linear_track(101_000, 60, Some(190.0), 1.5, 6.0, 0));

        let classified = classify_activity(&track, &[]);

        let stopped = &classified[61..=100];
        assert!(
            stopped
                .iter()
                .all(|point| point.state == ActivityState::LikelyMotorized),
            "the light split the ride: {:?}",
            stopped.first(),
        );
    }

    #[test]
    fn getting_off_at_the_top_ends_the_motorized_span() {
        // With the wait for the pickup that a real turnaround always has.
        // Riding down and being back in the vehicle a minute later is not a
        // thing a rider can do, and a fixture that claims it describes a road
        // dip taken at speed by the van rather than anyone getting out.
        let mut track = linear_track(0, 90, Some(100.0), 1.5, 6.0, 0);
        track.extend(linear_track(91_000, 60, Some(235.0), -1.5, 6.0, 0));
        track.extend(waiting_track(152_000, 300, 145.0, 0));
        track.extend(linear_track(453_000, 90, Some(145.0), 1.5, 6.0, 0));

        let classified = classify_activity(&track, &[]);

        assert!(
            classified[91..=150]
                .iter()
                .any(|point| point.state == ActivityState::Downhill),
            "the descent between two lifts was swallowed",
        );
    }

    /// Vehicle-smooth IMU at the raw 200 Hz rate over a time range.
    fn smooth_imu(start_ms: i64, end_ms: i64) -> Vec<ImuSample> {
        (0..)
            .map(|step| start_ms + step * 5)
            .take_while(|stamp| *stamp <= end_ms)
            .map(|timestamp_ms| ImuSample {
                timestamp_ms,
                accel: [0.02, -0.01, 9.81],
                gyro: [0.005, 0.002, -0.004],
                mag: None,
            })
            .collect()
    }

    #[test]
    fn a_bus_crawling_in_traffic_stays_one_unbroken_span() {
        // Climb, then six minutes of stop-and-go congestion with no climb at
        // all, then the climb resumes. The fixed 90 s bridge cannot cover it.
        let mut track = linear_track(0, 90, Some(100.0), 1.5, 6.0, 0);
        for step in 0..180 {
            let second = 91 + step;
            let crawling = step % 3 != 0;
            track.push(point(
                second * 1_000,
                Some(235.0),
                if crawling { 1.5 } else { 0.0 },
                !crawling,
                0,
            ));
        }
        track.extend(linear_track(271_000, 90, Some(235.0), 1.5, 6.0, 0));
        let imu = smooth_imu(0, 361_000);

        let classified = classify_activity(&track, &imu);

        let jam = &classified[91..=270];
        let motorized = jam
            .iter()
            .filter(|point| point.state == ActivityState::LikelyMotorized)
            .count();
        assert_eq!(
            motorized,
            jam.len(),
            "the jam broke the span into pieces: {motorized}/{} motorized",
            jam.len(),
        );
    }

    #[test]
    fn waiting_for_the_next_shuttle_is_not_bridged() {
        // Same shape, but the rider is actually standing still the whole time
        // between two lifts rather than crawling in traffic.
        let mut track = linear_track(0, 90, Some(100.0), 1.5, 6.0, 0);
        track.extend((91..=270).map(|second| point(second * 1_000, Some(235.0), 0.0, true, 0)));
        track.extend(linear_track(271_000, 90, Some(235.0), 1.5, 6.0, 0));
        let imu = smooth_imu(0, 361_000);

        let classified = classify_activity(&track, &imu);

        assert!(
            classified[120..=240]
                .iter()
                .all(|point| point.state == ActivityState::Still),
            "a real wait was labelled as riding in a vehicle",
        );
    }

    /// Rough motion at the raw rate: on foot or on a bike, never a vehicle.
    fn walking_imu(start_ms: i64, end_ms: i64) -> Vec<ImuSample> {
        (0..)
            .map(|step| start_ms + step * 5)
            .take_while(|stamp| *stamp <= end_ms)
            .map(|timestamp_ms| ImuSample {
                timestamp_ms,
                accel: [1.4, -0.9, 11.2],
                gyro: [0.4, -0.3, 0.2],
                mag: None,
            })
            .collect()
    }

    #[test]
    fn walking_between_two_vehicles_stays_part_of_the_transfer() {
        // Out at a gate, three minutes on foot, back in. A shuttle leg is one
        // leg: walking is part of the transfer, not a ride.
        let mut track = linear_track(0, 90, Some(100.0), 1.5, 6.0, 0);
        track.extend(linear_track(91_000, 180, Some(235.0), 0.0, 1.2, 0));
        track.extend(linear_track(272_000, 90, Some(235.0), 1.5, 6.0, 0));

        let classified = classify_activity(&track, &walking_imu(0, 362_000));

        assert!(
            classified[120..=240]
                .iter()
                .all(|point| point.state == ActivityState::LikelyMotorized),
            "a walk between two vans broke the shuttle leg apart",
        );
    }

    #[test]
    fn a_slow_technical_descent_between_lifts_is_never_absorbed() {
        // Ridden at walking pace, which is why speed alone cannot settle it —
        // but it gives up 60 m, and a walk does not.
        let mut track = linear_track(0, 90, Some(100.0), 1.5, 6.0, 0);
        track.extend(linear_track(91_000, 180, Some(235.0), -0.35, 2.0, 0));
        track.extend(waiting_track(272_000, 300, 172.0, 0));
        track.extend(linear_track(573_000, 90, Some(172.0), 1.5, 6.0, 0));

        let classified = classify_activity(&track, &walking_imu(0, 663_000));

        assert!(
            classified[120..=240]
                .iter()
                .any(|point| point.state != ActivityState::LikelyMotorized),
            "a ridden descent was swallowed by the lift either side of it",
        );
    }

    #[test]
    fn a_road_dip_too_quick_to_ride_stays_inside_the_shuttle_leg() {
        // The shape a Kojori shuttle road actually makes: the van climbs, the
        // road crosses a ridge and gives up 35 m into the next valley, and the
        // climb resumes at once. Geometry alone cannot tell this from a short
        // run — the give-up is the same size. The timeline can: there is no
        // stop, so nobody got out.
        let mut track = linear_track(0, 90, Some(100.0), 1.5, 6.0, 0);
        track.extend(linear_track(91_000, 35, Some(235.0), -1.0, 8.0, 0));
        track.extend(linear_track(127_000, 90, Some(200.0), 1.5, 6.0, 0));

        let classified = classify_activity(&track, &[]);

        assert!(
            classified[91..=125]
                .iter()
                .all(|point| point.state == ActivityState::LikelyMotorized),
            "a road dip broke the shuttle leg apart",
        );
    }
}
