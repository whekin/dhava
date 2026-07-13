//! Deterministic post-ride replay of the exact live fusion path.
//!
//! This is a diagnostics API, not a second analysis implementation. It feeds
//! the stored raw stream back through [`crate::LiveFusion`] using Android's
//! same 50 Hz IMU reduction and returns raw/fused tracks for visual comparison.

use std::path::Path;

use crate::recording::parse_recording_file;
use crate::{FusionError, LiveFusion};

const LIVE_IMU_INTERVAL_MS: i64 = 20;

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct DiagnosticTrackPoint {
    pub timestamp_ms: i64,
    pub lat: f64,
    pub lon: f64,
    pub accuracy_m: Option<f64>,
    pub stationary: Option<bool>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct RecordingReplay {
    pub raw_track: Vec<DiagnosticTrackPoint>,
    pub fused_track: Vec<DiagnosticTrackPoint>,
}

#[derive(Debug, Clone, Copy)]
enum Item {
    Imu(usize),
    Gps(usize),
}

#[uniffi::export]
pub fn replay_recording(path: String) -> Result<RecordingReplay, FusionError> {
    let mut recording = parse_recording_file(Path::new(&path))?;
    recording.imu.sort_by_key(|sample| sample.timestamp_ms);
    recording.gps.sort_by_key(|sample| sample.timestamp_ms);

    let raw_track = recording
        .gps
        .iter()
        .map(|gps| DiagnosticTrackPoint {
            timestamp_ms: gps.timestamp_ms,
            lat: gps.lat,
            lon: gps.lon,
            accuracy_m: gps.accuracy_m.map(f64::from),
            stationary: None,
        })
        .collect();

    let mut items = Vec::with_capacity(recording.imu.len() + recording.gps.len());
    items.extend((0..recording.imu.len()).map(Item::Imu));
    items.extend((0..recording.gps.len()).map(Item::Gps));
    items.sort_by_key(|item| match item {
        Item::Imu(index) => recording.imu[*index].timestamp_ms,
        Item::Gps(index) => recording.gps[*index].timestamp_ms,
    });

    let fusion = LiveFusion::new();
    let mut last_live_imu_ms = i64::MIN;
    let mut fused_track = Vec::with_capacity(recording.gps.len());
    for item in items {
        match item {
            Item::Imu(index) => {
                let sample = &recording.imu[index];
                if last_live_imu_ms == i64::MIN
                    || sample.timestamp_ms - last_live_imu_ms >= LIVE_IMU_INTERVAL_MS
                {
                    last_live_imu_ms = sample.timestamp_ms;
                    fusion.push_imu(
                        sample.timestamp_ms,
                        sample.accel.iter().map(|value| f64::from(*value)).collect(),
                        sample.gyro.iter().map(|value| f64::from(*value)).collect(),
                    );
                }
            }
            Item::Gps(index) => {
                let gps = &recording.gps[index];
                if let Some(snapshot) = fusion.push_gps(
                    gps.timestamp_ms,
                    gps.lat,
                    gps.lon,
                    gps.altitude_m,
                    gps.accuracy_m.map(f64::from),
                    gps.speed_mps.map(f64::from),
                    gps.bearing_deg.map(f64::from),
                ) {
                    fused_track.push(DiagnosticTrackPoint {
                        timestamp_ms: snapshot.timestamp_ms,
                        lat: snapshot.lat,
                        lon: snapshot.lon,
                        accuracy_m: Some(snapshot.accuracy_m),
                        stationary: Some(snapshot.stationary),
                    });
                }
            }
        }
    }

    Ok(RecordingReplay {
        raw_track,
        fused_track,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use flate2::Compression;
    use flate2::write::GzEncoder;
    use std::fs::{File, remove_file};
    use std::io::Write;
    use std::time::{SystemTime, UNIX_EPOCH};

    #[test]
    fn replay_returns_raw_and_fused_tracks() {
        let unique = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let path = std::env::temp_dir().join(format!("dhava-replay-{unique}.jsonl.gz"));
        let file = File::create(&path).unwrap();
        let mut gzip = GzEncoder::new(file, Compression::fast());
        for line in [
            r#"{"type":"imu","timestamp_ms":0,"accel":[0.0,0.0,9.80665],"gyro":[0.0,0.0,0.0]}"#,
            r#"{"type":"gps","timestamp_ms":1000,"lat":41.7,"lon":44.8,"accuracy_m":4.0,"speed_mps":2.0,"bearing_deg":90.0}"#,
            r#"{"type":"imu","timestamp_ms":1020,"accel":[0.0,0.0,9.80665],"gyro":[0.0,0.0,0.0]}"#,
            r#"{"type":"gps","timestamp_ms":2000,"lat":41.7,"lon":44.80002,"accuracy_m":4.0,"speed_mps":2.0,"bearing_deg":90.0}"#,
        ] {
            writeln!(gzip, "{line}").unwrap();
        }
        gzip.finish().unwrap();

        let replay = replay_recording(path.to_string_lossy().into_owned()).unwrap();
        remove_file(path).unwrap();
        assert_eq!(replay.raw_track.len(), 2);
        assert_eq!(replay.fused_track.len(), 2);
    }
}
