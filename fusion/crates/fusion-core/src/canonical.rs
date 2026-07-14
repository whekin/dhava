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
const GPS_ALTITUDE_MEDIAN_WINDOW: usize = 5;
const ALTITUDE_HYSTERESIS_M: f64 = 2.0;
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

    let altitudes = finalized_altitudes(&raw_track, &recording.baro, &replay.finalized_track);
    let speeds = finalized_speeds(&raw_track, &replay.finalized_track);
    let finalized_track: Vec<_> = replay
        .finalized_track
        .iter()
        .zip(altitudes.into_iter().zip(speeds))
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

    if finalized_track
        .iter()
        .filter_map(|point| point.altitude_m)
        .count()
        >= 2
    {
        (analysis.ascent_m, analysis.descent_m) = ascent_descent(&finalized_track);
    }
    analysis.algorithm_version = ALGORITHM_VERSION.to_owned();

    Ok(CanonicalActivity {
        algorithm_version: ALGORITHM_VERSION.to_owned(),
        analysis,
        raw_track,
        finalized_track,
    })
}

fn finalized_altitudes(
    raw: &[CanonicalTrackPoint],
    baro: &[BaroSample],
    finalized: &[DiagnosticTrackPoint],
) -> Vec<Option<f64>> {
    let gps_anchors = smoothed_gps_altitudes(raw);
    if gps_anchors.is_empty() {
        return vec![None; finalized.len()];
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

    finalized
        .iter()
        .map(|point| {
            let gps_fallback =
                interpolate_sectioned(&gps_by_section, point.timestamp_ms, point.section_id);
            let barometric_altitude = interpolate_unsectioned(&barometric, point.timestamp_ms)
                .and_then(|relative| {
                    interpolate_sectioned(&offsets_by_section, point.timestamp_ms, point.section_id)
                        .and_then(|offset| finite(Some(relative + offset)))
                });
            barometric_altitude.or(gps_fallback)
        })
        .collect()
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
    let anchors: Vec<_> = raw
        .iter()
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
        .collect();
    median_filter_by_section(&anchors, GPS_ALTITUDE_MEDIAN_WINDOW)
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
