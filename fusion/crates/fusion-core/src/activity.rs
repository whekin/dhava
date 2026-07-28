//! Conservative post-ride activity classification.
//!
//! Classification deliberately runs only after the canonical 5 Hz geometry,
//! altitude and speed passes are complete. It is evidence-based rather than a
//! mode detector: speed alone can establish movement, but can never establish
//! motorized transport.

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
const MIN_SHORT_ISLAND_MS: i64 = 3_000;
const MIN_DOWNHILL_RUN_MS: i64 = 3_000;
const MIN_MOTORIZED_RUN_MS: i64 = 12_000;
const MOTION_SAMPLE_INTERVAL_MS: i64 = 50;
const MAX_SMOOTH_IMU_GAP_MS: i64 = 250;
const MIN_SMOOTH_IMU_SAMPLES: usize = 100;
const SMOOTH_ACCEL_P90_MPS2: f64 = 0.45;
const SMOOTH_GYRO_P90_RAD_S: f64 = 0.12;
const STANDARD_GRAVITY_MPS2: f64 = 9.806_65;

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
struct MotionSample {
    timestamp_ms: i64,
    accel_error_mps2: f64,
    gyro_rad_s: f64,
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
    if fast_climb || smooth_vehicle {
        let confidence = if fast_climb && smooth_vehicle {
            0.94
        } else if fast_climb {
            0.82
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

fn motion_samples(imu: &[ImuSample]) -> Vec<MotionSample> {
    let mut ordered: Vec<_> = imu.iter().collect();
    ordered.sort_by_key(|sample| sample.timestamp_ms);

    // Raw capture is around 200 Hz. Vehicle smoothness only needs a coarse
    // envelope, so retain one conservative (max-error) bucket at 20 Hz. This
    // bounds both memory and rolling-window work on multi-hour recordings.
    let mut samples = Vec::with_capacity(ordered.len() / 20 + 1);
    let mut bucket: Option<MotionSample> = None;
    for sample in ordered {
        let accel = sample.accel.map(f64::from);
        let gyro = sample.gyro.map(f64::from);
        let accel_magnitude = (accel[0].powi(2) + accel[1].powi(2) + accel[2].powi(2)).sqrt();
        let gyro_magnitude = (gyro[0].powi(2) + gyro[1].powi(2) + gyro[2].powi(2)).sqrt();
        if !accel_magnitude.is_finite() || !gyro_magnitude.is_finite() {
            continue;
        }
        let candidate = MotionSample {
            timestamp_ms: sample.timestamp_ms,
            accel_error_mps2: (accel_magnitude - STANDARD_GRAVITY_MPS2).abs(),
            gyro_rad_s: gyro_magnitude,
        };
        match bucket.as_mut() {
            Some(current)
                if candidate.timestamp_ms - current.timestamp_ms < MOTION_SAMPLE_INTERVAL_MS =>
            {
                current.accel_error_mps2 = current.accel_error_mps2.max(candidate.accel_error_mps2);
                current.gyro_rad_s = current.gyro_rad_s.max(candidate.gyro_rad_s);
            }
            Some(_) => {
                samples.push(bucket.replace(candidate).expect("bucket exists"));
            }
            None => bucket = Some(candidate),
        }
    }
    samples.extend(bucket);
    samples
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
}
