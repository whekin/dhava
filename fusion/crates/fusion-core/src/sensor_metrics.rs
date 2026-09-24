//! Sensor evidence for the exact riding sections exported to BIKEYARD.
//!
//! This module returns only timing and quality. No GPS point or raw IMU sample
//! crosses the FFI boundary; the Android sender serializes the public document.

use std::path::Path;

use crate::FusionError;
use crate::analysis::{AIRTIME_MAX_SAMPLE_GAP_MS, AirtimeWindow, detect_airtime};
use crate::recording::{ParsedRecording, parse_recording_file};

/// One continuous, already-selected section of the exported ride.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct SensorTimeScope {
    pub started_at_ms: i64,
    pub ended_at_ms: i64,
}

/// Measured IMU availability and candidate events inside exported riding time.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SensorMetricsEvidence {
    pub coverage: f64,
    pub sample_rate_hz: Option<f64>,
    pub events: Vec<AirtimeWindow>,
}

#[uniffi::export]
pub fn sensor_metrics_evidence(
    path: String,
    scopes: Vec<SensorTimeScope>,
) -> Result<SensorMetricsEvidence, FusionError> {
    let recording = parse_recording_file(Path::new(&path))?;
    evidence_for_recording(&recording, &scopes)
}

fn evidence_for_recording(
    recording: &ParsedRecording,
    scopes: &[SensorTimeScope],
) -> Result<SensorMetricsEvidence, FusionError> {
    if scopes.is_empty()
        || scopes
            .iter()
            .any(|scope| scope.started_at_ms >= scope.ended_at_ms)
        || scopes
            .windows(2)
            .any(|pair| pair[0].ended_at_ms >= pair[1].started_at_ms)
    {
        return Err(FusionError::Parse {
            msg: "Sensor scopes must be ordered, disjoint riding intervals".into(),
        });
    }
    let total_ms: i64 = scopes
        .iter()
        .map(|scope| scope.ended_at_ms - scope.started_at_ms)
        .sum();
    let mut imu = recording.imu.clone();
    imu.sort_by_key(|sample| sample.timestamp_ms);

    let mut covered_ms = 0i64;
    let mut intervals = 0u64;
    for scope in scopes {
        let first = imu.partition_point(|sample| sample.timestamp_ms < scope.started_at_ms);
        let last = imu.partition_point(|sample| sample.timestamp_ms <= scope.ended_at_ms);
        for pair in imu[first..last].windows(2) {
            let delta = pair[1].timestamp_ms - pair[0].timestamp_ms;
            if (1..=AIRTIME_MAX_SAMPLE_GAP_MS).contains(&delta) {
                covered_ms += delta;
                intervals += 1;
            }
        }
    }
    let events = detect_airtime(&imu)
        .into_iter()
        .filter(|event| {
            scopes.iter().any(|scope| {
                event.start_ms >= scope.started_at_ms
                    && event
                        .start_ms
                        .checked_add(event.duration_ms)
                        .is_some_and(|end| end <= scope.ended_at_ms)
            })
        })
        .collect();
    Ok(SensorMetricsEvidence {
        coverage: (covered_ms as f64 / total_ms as f64).clamp(0.0, 1.0),
        sample_rate_hz: if covered_ms > 0 {
            Some(intervals as f64 * 1_000.0 / covered_ms as f64)
        } else {
            None
        },
        events,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ImuSample;

    fn imu(t: i64, z: f32) -> ImuSample {
        ImuSample {
            timestamp_ms: t,
            accel: [0.0, 0.0, z],
            gyro: [0.0, 0.0, 0.0],
            mag: None,
        }
    }

    #[test]
    fn coverage_excludes_missing_intervals_and_reports_observed_rate() {
        let mut recording = ParsedRecording::default();
        recording
            .imu
            .extend((0..=400).step_by(10).map(|t| imu(t, 9.8)));
        recording
            .imu
            .extend((600..=1_000).step_by(10).map(|t| imu(t, 9.8)));
        let evidence = evidence_for_recording(
            &recording,
            &[SensorTimeScope {
                started_at_ms: 0,
                ended_at_ms: 1_000,
            }],
        )
        .unwrap();
        assert!((evidence.coverage - 0.8).abs() < 1e-9);
        assert!((evidence.sample_rate_hz.unwrap() - 100.0).abs() < 1e-9);
        assert!(evidence.events.is_empty());
    }

    #[test]
    fn candidate_crossing_an_exported_break_is_not_sent() {
        let mut recording = ParsedRecording::default();
        recording
            .imu
            .extend((0..1_000).step_by(5).map(|t| imu(t, 9.8)));
        recording
            .imu
            .extend((1_000..1_400).step_by(5).map(|t| imu(t, 0.3)));
        recording
            .imu
            .extend((1_400..2_000).step_by(5).map(|t| imu(t, 9.8)));
        let whole = evidence_for_recording(
            &recording,
            &[SensorTimeScope {
                started_at_ms: 0,
                ended_at_ms: 1_999,
            }],
        )
        .unwrap();
        assert_eq!(whole.events.len(), 1);
        let split = evidence_for_recording(
            &recording,
            &[
                SensorTimeScope {
                    started_at_ms: 0,
                    ended_at_ms: 1_150,
                },
                SensorTimeScope {
                    started_at_ms: 1_250,
                    ended_at_ms: 1_999,
                },
            ],
        )
        .unwrap();
        assert!(split.events.is_empty());
    }
}
