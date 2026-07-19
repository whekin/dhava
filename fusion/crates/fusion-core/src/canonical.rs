//! Versioned post-ride artifact produced from the immutable raw recording.
//!
//! Horizontal geometry comes from the GPS-bounded 5 Hz finalizer. Vertical
//! geometry uses barometric relative movement anchored to median-filtered GPS
//! altitude, with GPS-only interpolation as a fallback. The Android layer may
//! cache this result, but it never owns or reimplements these algorithms.

use std::collections::HashMap;
use std::path::Path;

use crate::analysis::{ALGORITHM_VERSION, RideAnalysis, analyze};
use crate::recording::{ParsedRecording, parse_recording_file};
use crate::replay::{DiagnosticTrackPoint, replay_parsed};
use crate::{BaroSample, FusionError};

const MAX_GPS_ACCURACY_M: f64 = 20.0;
const GPS_GAP_MIN_S: f64 = 5.0;
const GPS_ALTITUDE_MEDIAN_WINDOW: usize = 5;
const GPS_NET_ENDPOINT_WINDOW: usize = 5;
const ALTITUDE_HYSTERESIS_M: f64 = 2.0;
const GPS_NET_UNCERTAINTY_MULTIPLIER: f64 = 2.0;
const BAROMETRIC_SCALE_M: f64 = 44_330.0;
const BAROMETRIC_EXPONENT: f64 = 0.190_294_957;

/// One point in either the raw GPS view or finalized 5 Hz track.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct CanonicalTrackPoint {
    pub timestamp_ms: i64,
    pub lat: f64,
    pub lon: f64,
    pub altitude_m: Option<f64>,
    pub accuracy_m: Option<f64>,
    pub speed_mps: Option<f64>,
    pub stationary: Option<bool>,
    /// Continuous recording section; changes at each manual pause.
    pub section_id: i32,
}

/// Complete derived activity. Safe to delete and rebuild from the raw file.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct CanonicalActivity {
    pub algorithm_version: String,
    pub analysis: RideAnalysis,
    pub raw_track: Vec<CanonicalTrackPoint>,
    pub finalized_track: Vec<CanonicalTrackPoint>,
    pub quality: QualitySummary,
}

/// Which signal the finalized vertical profile is actually built from.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ElevationSource {
    /// Barometric relative movement anchored to median-filtered GPS altitude.
    Barometric,
    /// Section-aware linear interpolation of accepted GPS altitudes only.
    GpsInterpolated,
    /// The recording has no usable altitude information at all.
    None,
}

/// Signal-quality indicators for one canonical activity.
///
/// `elevation_source` is threaded out of the vertical pass itself, so it
/// reports what [`finalize`] actually used rather than a re-derivation.
/// `elevation_uncertainty_m` is an honest but coarse heuristic (v0) meant for
/// UI display only — never feed it back into timing or segment math.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct QualitySummary {
    pub elevation_source: ElevationSource,
    /// Barometer samples parsed from the raw recording.
    pub baro_sample_count: u32,
    /// All GPS fixes in the raw recording.
    pub gps_fix_count: u32,
    /// Fixes passing the same ≤ 20 m accuracy gate used by the vertical pass
    /// (fixes without a reported accuracy also pass, matching that gate).
    pub gps_accepted_count: u32,
    pub median_accuracy_m: Option<f64>,
    pub p90_accuracy_m: Option<f64>,
    /// Gaps > 5 s between consecutive fixes inside one recording section.
    /// Manual pause boundaries change the section id and never count.
    pub gps_gap_count: u32,
    /// Longest within-section gap in seconds; 0 when there are no gaps.
    pub longest_gap_s: f64,
    /// Coarse ± estimate of the reported vertical metric, meters.
    ///
    /// This describes accumulated ascent/descent for barometric recordings and
    /// section-wise net change for GPS-only recordings.
    pub elevation_uncertainty_m: Option<f64>,
}

/// Parses one raw recording once and produces its canonical processed result.
#[uniffi::export]
pub fn finalize_recording(path: String) -> Result<CanonicalActivity, FusionError> {
    let recording = parse_recording_file(Path::new(&path))?;
    finalize(&recording)
}

pub fn finalize(recording: &ParsedRecording) -> Result<CanonicalActivity, FusionError> {
    let mut analysis = analyze(recording)?;
    let replay = replay_parsed(recording);

    let mut gps = recording.gps.clone();
    gps.sort_by_key(|point| point.timestamp_ms);
    let raw_track: Vec<_> = gps
        .iter()
        .zip(&replay.raw_track)
        .map(|(gps, replay_point)| CanonicalTrackPoint {
            timestamp_ms: gps.timestamp_ms,
            lat: gps.lat,
            lon: gps.lon,
            altitude_m: finite(gps.altitude_m),
            accuracy_m: gps
                .accuracy_m
                .map(f64::from)
                .filter(|value| value.is_finite()),
            speed_mps: gps
                .speed_mps
                .map(f64::from)
                .filter(|value| value.is_finite()),
            stationary: None,
            section_id: replay_point.section_id,
        })
        .collect();

    let vertical = finalized_altitudes(&raw_track, &recording.baro, &replay.finalized_track);
    let quality = quality_summary(&raw_track, recording.baro.len(), &vertical);
    let speeds = finalized_speeds(&raw_track, &replay.finalized_track);
    let finalized_track: Vec<_> = replay
        .finalized_track
        .iter()
        .zip(vertical.altitudes.into_iter().zip(speeds))
        .map(|(point, (altitude_m, speed_mps))| CanonicalTrackPoint {
            timestamp_ms: point.timestamp_ms,
            lat: point.lat,
            lon: point.lon,
            altitude_m,
            accuracy_m: point.accuracy_m,
            speed_mps,
            stationary: point.stationary,
            section_id: point.section_id,
        })
        .collect();

    (analysis.ascent_m, analysis.descent_m) = match vertical.source {
        ElevationSource::Barometric => ascent_descent(&finalized_track),
        ElevationSource::GpsInterpolated => gps_net_ascent_descent(&raw_track),
        ElevationSource::None => (0.0, 0.0),
    };
    analysis.algorithm_version = ALGORITHM_VERSION.to_owned();

    Ok(CanonicalActivity {
        algorithm_version: ALGORITHM_VERSION.to_owned(),
        analysis,
        raw_track,
        finalized_track,
        quality,
    })
}

/// Vertical-pass output plus what it actually used, so the quality summary
/// never has to re-derive the elevation source heuristically.
struct VerticalPass {
    altitudes: Vec<Option<f64>>,
    source: ElevationSource,
    /// Standard deviation of the raw baro-vs-GPS anchor offsets, if the pass
    /// was barometric and had at least two anchors.
    anchor_spread_m: Option<f64>,
}

fn finalized_altitudes(
    raw: &[CanonicalTrackPoint],
    baro: &[BaroSample],
    finalized: &[DiagnosticTrackPoint],
) -> VerticalPass {
    let gps_anchors = smoothed_gps_altitudes(raw);
    if gps_anchors.is_empty() {
        return VerticalPass {
            altitudes: vec![None; finalized.len()],
            source: ElevationSource::None,
            anchor_spread_m: None,
        };
    }

    let barometric = relative_barometric_altitudes(baro);
    let offset_anchors: Vec<_> = gps_anchors
        .iter()
        .filter_map(|anchor| {
            let relative = interpolate_unsectioned(&barometric, anchor.timestamp_ms)?;
            Some(AltitudeAnchor {
                timestamp_ms: anchor.timestamp_ms,
                altitude_m: anchor.altitude_m - relative,
                section_id: anchor.section_id,
            })
        })
        .collect();
    let smoothed_offsets = median_filter_by_section(&offset_anchors, GPS_ALTITUDE_MEDIAN_WINDOW);
    let gps_by_section = values_by_section(&gps_anchors);
    let offsets_by_section = values_by_section(&smoothed_offsets);

    let mut barometric_points = 0usize;
    let mut gps_points = 0usize;
    let altitudes: Vec<_> = finalized
        .iter()
        .map(|point| {
            let gps_fallback =
                interpolate_sectioned(&gps_by_section, point.timestamp_ms, point.section_id);
            let barometric_altitude = interpolate_unsectioned(&barometric, point.timestamp_ms)
                .and_then(|relative| {
                    interpolate_sectioned(&offsets_by_section, point.timestamp_ms, point.section_id)
                        .and_then(|offset| finite(Some(relative + offset)))
                });
            if barometric_altitude.is_some() {
                barometric_points += 1;
            } else if gps_fallback.is_some() {
                gps_points += 1;
            }
            barometric_altitude.or(gps_fallback)
        })
        .collect();

    // A barometric profile may still fall back to GPS for a few edge points
    // outside the baro time range; the majority signal names the source.
    let source = if barometric_points > 0 && barometric_points >= gps_points {
        ElevationSource::Barometric
    } else if gps_points > 0 {
        ElevationSource::GpsInterpolated
    } else {
        ElevationSource::None
    };
    let anchor_spread_m = (source == ElevationSource::Barometric)
        .then(|| stddev(offset_anchors.iter().map(|anchor| anchor.altitude_m)))
        .flatten();

    VerticalPass {
        altitudes,
        source,
        anchor_spread_m,
    }
}

fn quality_summary(
    raw: &[CanonicalTrackPoint],
    baro_sample_count: usize,
    vertical: &VerticalPass,
) -> QualitySummary {
    let gps_accepted_count = raw
        .iter()
        .filter(|point| {
            point
                .accuracy_m
                .is_none_or(|accuracy| accuracy <= MAX_GPS_ACCURACY_M)
        })
        .count();

    let mut accuracies: Vec<_> = raw.iter().filter_map(|point| point.accuracy_m).collect();
    accuracies.sort_by(f64::total_cmp);
    let median_accuracy_m = percentile(&accuracies, 0.5);
    let p90_accuracy_m = percentile(&accuracies, 0.9);

    let mut gps_gap_count = 0u32;
    let mut longest_gap_s = 0.0f64;
    for pair in raw.windows(2) {
        if pair[0].section_id != pair[1].section_id {
            continue;
        }
        let gap_s = (pair[1].timestamp_ms - pair[0].timestamp_ms) as f64 / 1_000.0;
        if gap_s > GPS_GAP_MIN_S {
            gps_gap_count += 1;
            longest_gap_s = longest_gap_s.max(gap_s);
        }
    }

    // Heuristic v0, for UI display only (see the field documentation).
    let elevation_uncertainty_m = match vertical.source {
        ElevationSource::Barometric => Some(2.0 + vertical.anchor_spread_m.unwrap_or(3.0)),
        ElevationSource::GpsInterpolated => Some(
            p90_accuracy_m
                .map(|p90| (p90 * GPS_NET_UNCERTAINTY_MULTIPLIER).max(7.0))
                .unwrap_or(7.0),
        ),
        ElevationSource::None => None,
    };

    QualitySummary {
        elevation_source: vertical.source,
        baro_sample_count: baro_sample_count as u32,
        gps_fix_count: raw.len() as u32,
        gps_accepted_count: gps_accepted_count as u32,
        median_accuracy_m,
        p90_accuracy_m,
        gps_gap_count,
        longest_gap_s,
        elevation_uncertainty_m,
    }
}

/// Linear-interpolated percentile of an ascending slice; `None` when empty.
fn percentile(sorted: &[f64], fraction: f64) -> Option<f64> {
    if sorted.is_empty() {
        return None;
    }
    let rank = fraction * (sorted.len() - 1) as f64;
    let below = rank.floor() as usize;
    let above = rank.ceil() as usize;
    let weight = rank - below as f64;
    Some(sorted[below] + (sorted[above.min(sorted.len() - 1)] - sorted[below]) * weight)
}

fn stddev(values: impl Iterator<Item = f64> + Clone) -> Option<f64> {
    let count = values.clone().count();
    if count < 2 {
        return None;
    }
    let mean = values.clone().sum::<f64>() / count as f64;
    let variance = values.map(|value| (value - mean).powi(2)).sum::<f64>() / count as f64;
    Some(variance.sqrt())
}

#[derive(Debug, Clone, Copy)]
struct AltitudeAnchor {
    timestamp_ms: i64,
    altitude_m: f64,
    section_id: i32,
}

#[derive(Debug, Clone, Copy)]
struct TimedValue {
    timestamp_ms: i64,
    value: f64,
}

fn smoothed_gps_altitudes(raw: &[CanonicalTrackPoint]) -> Vec<AltitudeAnchor> {
    median_filter_by_section(&gps_altitude_anchors(raw), GPS_ALTITUDE_MEDIAN_WINDOW)
}

fn gps_altitude_anchors(raw: &[CanonicalTrackPoint]) -> Vec<AltitudeAnchor> {
    raw.iter()
        .filter(|point| {
            point
                .accuracy_m
                .is_none_or(|accuracy| accuracy <= MAX_GPS_ACCURACY_M)
        })
        .filter_map(|point| {
            Some(AltitudeAnchor {
                timestamp_ms: point.timestamp_ms,
                altitude_m: point.altitude_m?,
                section_id: point.section_id,
            })
        })
        .collect()
}

/// GPS altitude has low-frequency bias that survives short median filters and
/// looks like real climbing when accumulated. Without a barometer, report only
/// robust endpoint change inside each continuous recording section.
fn gps_net_ascent_descent(raw: &[CanonicalTrackPoint]) -> (f64, f64) {
    let anchors = gps_altitude_anchors(raw);
    let mut ascent_m = 0.0;
    let mut descent_m = 0.0;
    let mut start = 0;
    while start < anchors.len() {
        let section_id = anchors[start].section_id;
        let mut end = start + 1;
        while end < anchors.len() && anchors[end].section_id == section_id {
            end += 1;
        }
        let section = &anchors[start..end];
        if section.len() >= 2 {
            let edge_count = GPS_NET_ENDPOINT_WINDOW.min(section.len() / 2).max(1);
            let start_altitude_m = median_altitude(&section[..edge_count]);
            let end_altitude_m = median_altitude(&section[section.len() - edge_count..]);
            let delta_m = end_altitude_m - start_altitude_m;
            if delta_m >= ALTITUDE_HYSTERESIS_M {
                ascent_m += delta_m;
            } else if delta_m <= -ALTITUDE_HYSTERESIS_M {
                descent_m -= delta_m;
            }
        }
        start = end;
    }
    (ascent_m, descent_m)
}

fn median_altitude(anchors: &[AltitudeAnchor]) -> f64 {
    let mut values: Vec<_> = anchors.iter().map(|anchor| anchor.altitude_m).collect();
    values.sort_by(f64::total_cmp);
    let middle = values.len() / 2;
    if values.len() % 2 == 0 {
        (values[middle - 1] + values[middle]) / 2.0
    } else {
        values[middle]
    }
}

fn relative_barometric_altitudes(baro: &[BaroSample]) -> Vec<TimedValue> {
    let mut samples: Vec<_> = baro
        .iter()
        .filter_map(|sample| {
            let pressure = f64::from(sample.pressure_hpa);
            (pressure.is_finite() && pressure > 0.0).then_some((sample.timestamp_ms, pressure))
        })
        .collect();
    samples.sort_by_key(|sample| sample.0);
    let Some(reference_pressure) = samples.first().map(|sample| sample.1) else {
        return Vec::new();
    };
    samples
        .into_iter()
        .map(|(timestamp_ms, pressure)| TimedValue {
            timestamp_ms,
            value: BAROMETRIC_SCALE_M
                * (1.0 - (pressure / reference_pressure).powf(BAROMETRIC_EXPONENT)),
        })
        .collect()
}

fn median_filter_by_section(anchors: &[AltitudeAnchor], window: usize) -> Vec<AltitudeAnchor> {
    let mut output = Vec::with_capacity(anchors.len());
    let mut start = 0;
    while start < anchors.len() {
        let section_id = anchors[start].section_id;
        let mut end = start + 1;
        while end < anchors.len() && anchors[end].section_id == section_id {
            end += 1;
        }
        let section = &anchors[start..end];
        if section.len() < window {
            output.extend_from_slice(section);
            start = end;
            continue;
        }
        let half = window / 2;
        for (index, anchor) in section.iter().enumerate() {
            let lo = index.saturating_sub(half);
            let hi = (index + half + 1).min(section.len());
            let mut values: Vec<_> = section[lo..hi]
                .iter()
                .map(|sample| sample.altitude_m)
                .collect();
            values.sort_by(f64::total_cmp);
            output.push(AltitudeAnchor {
                altitude_m: values[values.len() / 2],
                ..*anchor
            });
        }
        start = end;
    }
    output
}

fn values_by_section(anchors: &[AltitudeAnchor]) -> HashMap<i32, Vec<TimedValue>> {
    let mut sections: HashMap<i32, Vec<TimedValue>> = HashMap::new();
    for anchor in anchors {
        sections
            .entry(anchor.section_id)
            .or_default()
            .push(TimedValue {
                timestamp_ms: anchor.timestamp_ms,
                value: anchor.altitude_m,
            });
    }
    sections
}

fn interpolate_sectioned(
    sections: &HashMap<i32, Vec<TimedValue>>,
    timestamp_ms: i64,
    section_id: i32,
) -> Option<f64> {
    interpolate(sections.get(&section_id)?, timestamp_ms)
}

fn interpolate_unsectioned(samples: &[TimedValue], timestamp_ms: i64) -> Option<f64> {
    interpolate(samples, timestamp_ms)
}

fn interpolate(samples: &[TimedValue], timestamp_ms: i64) -> Option<f64> {
    let index = samples.partition_point(|sample| sample.timestamp_ms < timestamp_ms);
    if let Some(exact) = samples
        .get(index)
        .filter(|sample| sample.timestamp_ms == timestamp_ms)
    {
        return Some(exact.value);
    }
    let before = index.checked_sub(1).and_then(|index| samples.get(index))?;
    let after = samples.get(index)?;
    let span = after.timestamp_ms - before.timestamp_ms;
    if span <= 0 {
        return Some(before.value);
    }
    let fraction = (timestamp_ms - before.timestamp_ms) as f64 / span as f64;
    Some(before.value + (after.value - before.value) * fraction)
}

fn finalized_speeds(
    raw: &[CanonicalTrackPoint],
    finalized: &[DiagnosticTrackPoint],
) -> Vec<Option<f64>> {
    let mut sections: HashMap<i32, Vec<TimedValue>> = HashMap::new();
    for sample in raw {
        let Some(speed) = sample.speed_mps else {
            continue;
        };
        sections
            .entry(sample.section_id)
            .or_default()
            .push(TimedValue {
                timestamp_ms: sample.timestamp_ms,
                value: speed,
            });
    }
    finalized
        .iter()
        .map(|point| {
            if point.stationary == Some(true) {
                Some(0.0)
            } else {
                interpolate_sectioned(&sections, point.timestamp_ms, point.section_id)
            }
        })
        .collect()
}

fn ascent_descent(track: &[CanonicalTrackPoint]) -> (f64, f64) {
    let mut ascent = 0.0;
    let mut descent = 0.0;
    let mut reference: Option<(i32, f64)> = None;
    for point in track {
        let Some(altitude) = point.altitude_m else {
            continue;
        };
        let Some((section_id, previous)) = reference else {
            reference = Some((point.section_id, altitude));
            continue;
        };
        if section_id != point.section_id {
            reference = Some((point.section_id, altitude));
            continue;
        }
        let delta = altitude - previous;
        if delta >= ALTITUDE_HYSTERESIS_M {
            ascent += delta;
            reference = Some((point.section_id, altitude));
        } else if delta <= -ALTITUDE_HYSTERESIS_M {
            descent += -delta;
            reference = Some((point.section_id, altitude));
        }
    }
    (ascent, descent)
}

fn finite(value: Option<f64>) -> Option<f64> {
    value.filter(|value| value.is_finite())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::GpsPoint;
    use crate::recording::RecordingEvent;

    fn gps(timestamp_ms: i64, lon: f64, altitude_m: f64) -> GpsPoint {
        GpsPoint {
            timestamp_ms,
            lat: 41.7,
            lon,
            altitude_m: Some(altitude_m),
            accuracy_m: Some(4.0),
            speed_mps: Some(5.0),
            bearing_deg: Some(90.0),
        }
    }

    fn pressure_for_relative_altitude(reference_hpa: f64, altitude_m: f64) -> f32 {
        (reference_hpa * (1.0 - altitude_m / BAROMETRIC_SCALE_M).powf(1.0 / BAROMETRIC_EXPONENT))
            as f32
    }

    #[test]
    fn gps_only_altitude_is_interpolated_at_five_hz() {
        let recording = ParsedRecording {
            gps: vec![gps(1_000, 44.8, 100.0), gps(2_000, 44.8001, 90.0)],
            ..ParsedRecording::default()
        };

        let canonical = finalize(&recording).unwrap();
        let midpoint = canonical
            .finalized_track
            .iter()
            .find(|point| point.timestamp_ms == 1_600)
            .unwrap();
        assert!((midpoint.altitude_m.unwrap() - 94.0).abs() < 0.01);
        assert_eq!(canonical.algorithm_version, ALGORITHM_VERSION);
        assert_eq!(canonical.raw_track.len(), 2);

        let quality = &canonical.quality;
        assert_eq!(quality.elevation_source, ElevationSource::GpsInterpolated);
        assert_eq!(quality.baro_sample_count, 0);
        assert_eq!(quality.gps_fix_count, 2);
        assert_eq!(quality.gps_accepted_count, 2);
        assert_eq!(quality.median_accuracy_m, Some(4.0));
        assert_eq!(quality.p90_accuracy_m, Some(4.0));
        assert_eq!(quality.gps_gap_count, 0);
        assert_eq!(quality.longest_gap_s, 0.0);
        // GPS-only net uncertainty: max(7.0, p90 × 2) with p90 = 4 m.
        assert_eq!(quality.elevation_uncertainty_m, Some(8.0));
        assert_eq!(canonical.analysis.ascent_m, 0.0);
        assert!((canonical.analysis.descent_m - 10.0).abs() < 0.01);
    }

    #[test]
    fn gps_only_statistics_use_section_net_change_not_accumulated_noise() {
        let altitudes = [
            100.0, 120.0, 90.0, 125.0, 80.0, 110.0, 75.0, 105.0, 70.0, 95.0, 65.0, 60.0,
        ];
        let recording = ParsedRecording {
            gps: altitudes
                .into_iter()
                .enumerate()
                .map(|(index, altitude_m)| {
                    gps(
                        1_000 + index as i64 * 1_000,
                        44.8 + index as f64 * 0.0001,
                        altitude_m,
                    )
                })
                .collect(),
            ..ParsedRecording::default()
        };

        let canonical = finalize(&recording).unwrap();

        assert_eq!(
            canonical.quality.elevation_source,
            ElevationSource::GpsInterpolated
        );
        assert_eq!(canonical.analysis.ascent_m, 0.0);
        assert!((canonical.analysis.descent_m - 30.0).abs() < 0.01);
    }

    #[test]
    fn barometer_preserves_relative_vertical_detail_between_gps_anchors() {
        let reference_hpa = 1_000.0;
        let recording = ParsedRecording {
            gps: vec![gps(1_000, 44.8, 100.0), gps(3_000, 44.8002, 100.0)],
            baro: vec![
                BaroSample {
                    timestamp_ms: 1_000,
                    pressure_hpa: reference_hpa as f32,
                },
                BaroSample {
                    timestamp_ms: 2_000,
                    pressure_hpa: pressure_for_relative_altitude(reference_hpa, 10.0),
                },
                BaroSample {
                    timestamp_ms: 3_000,
                    pressure_hpa: reference_hpa as f32,
                },
            ],
            ..ParsedRecording::default()
        };

        let canonical = finalize(&recording).unwrap();
        let peak = canonical
            .finalized_track
            .iter()
            .find(|point| point.timestamp_ms == 2_000)
            .unwrap()
            .altitude_m
            .unwrap();
        assert!((peak - 110.0).abs() < 0.2, "barometric peak was {peak}");
        assert_eq!(
            canonical.quality.elevation_source,
            ElevationSource::Barometric
        );
        assert_eq!(canonical.quality.baro_sample_count, 3);
        assert!((canonical.analysis.ascent_m - 10.0).abs() < 0.2);
        assert!((canonical.analysis.descent_m - 10.0).abs() < 0.2);
        let uncertainty = canonical.quality.elevation_uncertainty_m.unwrap();
        assert!(
            (2.0..=5.0).contains(&uncertainty),
            "barometric uncertainty was {uncertainty}"
        );
    }

    #[test]
    fn gps_hole_within_a_section_counts_as_gap() {
        let recording = ParsedRecording {
            gps: vec![
                gps(1_000, 44.8, 100.0),
                gps(2_000, 44.8001, 100.0),
                gps(14_000, 44.8002, 100.0),
                gps(15_000, 44.8003, 100.0),
            ],
            ..ParsedRecording::default()
        };

        let quality = finalize(&recording).unwrap().quality;
        assert_eq!(quality.gps_gap_count, 1);
        assert!((quality.longest_gap_s - 12.0).abs() < 1e-9);
        assert_eq!(quality.gps_fix_count, 4);
        assert_eq!(quality.gps_accepted_count, 4);
    }

    #[test]
    fn gps_hole_spanning_a_manual_pause_is_not_a_gap() {
        let recording = ParsedRecording {
            gps: vec![
                gps(1_000, 44.8, 100.0),
                gps(2_000, 44.8001, 100.0),
                gps(14_000, 44.81, 100.0),
                gps(15_000, 44.8101, 100.0),
            ],
            events: vec![
                RecordingEvent {
                    timestamp_ms: 2_500,
                    action: "pause".into(),
                },
                RecordingEvent {
                    timestamp_ms: 13_000,
                    action: "resume".into(),
                },
            ],
            ..ParsedRecording::default()
        };

        let quality = finalize(&recording).unwrap().quality;
        assert_eq!(quality.gps_gap_count, 0);
        assert_eq!(quality.longest_gap_s, 0.0);
    }

    #[test]
    fn inaccurate_fixes_fail_the_accepted_gate_and_widen_uncertainty() {
        let mut inaccurate = gps(2_000, 44.8001, 90.0);
        inaccurate.accuracy_m = Some(25.0);
        let recording = ParsedRecording {
            gps: vec![
                gps(1_000, 44.8, 100.0),
                inaccurate,
                gps(3_000, 44.8002, 80.0),
            ],
            ..ParsedRecording::default()
        };

        let quality = finalize(&recording).unwrap().quality;
        assert_eq!(quality.gps_fix_count, 3);
        assert_eq!(quality.gps_accepted_count, 2);
        assert_eq!(quality.median_accuracy_m, Some(4.0));
        // p90 over [4, 4, 25]: interpolated between the two largest values.
        let p90 = quality.p90_accuracy_m.unwrap();
        assert!((p90 - 20.8).abs() < 1e-9, "p90 was {p90}");
        assert_eq!(quality.elevation_source, ElevationSource::GpsInterpolated);
        let uncertainty = quality.elevation_uncertainty_m.unwrap();
        assert!(
            (uncertainty - 41.6).abs() < 1e-9,
            "uncertainty was {uncertainty}"
        );
    }

    #[test]
    fn no_altitude_information_yields_none_source() {
        let mut a = gps(1_000, 44.8, 0.0);
        let mut b = gps(2_000, 44.8001, 0.0);
        a.altitude_m = None;
        b.altitude_m = None;
        let recording = ParsedRecording {
            gps: vec![a, b],
            ..ParsedRecording::default()
        };

        let quality = finalize(&recording).unwrap().quality;
        assert_eq!(quality.elevation_source, ElevationSource::None);
        assert_eq!(quality.elevation_uncertainty_m, None);
    }

    #[test]
    fn ascent_and_descent_never_bridge_pause_sections() {
        let recording = ParsedRecording {
            gps: vec![
                gps(1_000, 44.8, 100.0),
                gps(2_000, 44.8001, 90.0),
                gps(6_000, 44.81, 200.0),
                gps(7_000, 44.8101, 190.0),
            ],
            events: vec![
                RecordingEvent {
                    timestamp_ms: 2_500,
                    action: "pause".into(),
                },
                RecordingEvent {
                    timestamp_ms: 5_000,
                    action: "resume".into(),
                },
            ],
            ..ParsedRecording::default()
        };

        let canonical = finalize(&recording).unwrap();
        assert!((canonical.analysis.descent_m - 20.0).abs() < 0.01);
        assert!(canonical.analysis.ascent_m < 0.01);
    }
}
