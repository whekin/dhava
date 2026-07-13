//! Deterministic post-ride replay and GPS-bounded finalized fusion.
//!
//! The raw stream is first fed back through [`crate::LiveFusion`] using
//! Android's same 50 Hz IMU reduction. A bounded post-pass then restores
//! causally hidden motion onset and inserts 5 Hz points between immutable GPS
//! anchors. Both tracks remain Rust-owned diagnostics.

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
    /// Continuous recording section. Increments at each manual pause so
    /// renderers never draw a line across a paused interval.
    pub section_id: i32,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct RecordingReplay {
    pub raw_track: Vec<DiagnosticTrackPoint>,
    /// Exact causal output produced during recording, at accepted GPS rate.
    pub fused_track: Vec<DiagnosticTrackPoint>,
    /// GPS-bounded, delayed 5 Hz output intended for post-ride display.
    pub finalized_track: Vec<DiagnosticTrackPoint>,
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
    recording.events.sort_by_key(|event| event.timestamp_ms);

    let section_ids = section_ids_for_gps(&recording.gps, &recording.events);

    let raw_track = recording
        .gps
        .iter()
        .zip(&section_ids)
        .map(|(gps, section_id)| DiagnosticTrackPoint {
            timestamp_ms: gps.timestamp_ms,
            lat: gps.lat,
            lon: gps.lon,
            accuracy_m: gps.accuracy_m.map(f64::from),
            stationary: None,
            section_id: *section_id,
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
                        section_id: section_ids[index],
                    });
                }
            }
        }
    }

    let finalized_track =
        crate::bounded::finalized_track(&recording.gps, &fused_track, &section_ids, &recording.imu);

    Ok(RecordingReplay {
        raw_track,
        fused_track,
        finalized_track,
    })
}

fn section_ids_for_gps(
    gps: &[crate::GpsPoint],
    events: &[crate::recording::RecordingEvent],
) -> Vec<i32> {
    let mut section_id = 0i32;
    let mut paused = false;
    let mut event_index = 0usize;
    gps.iter()
        .map(|point| {
            while event_index < events.len()
                && events[event_index].timestamp_ms <= point.timestamp_ms
            {
                match events[event_index].action.as_str() {
                    "pause" if !paused => {
                        section_id = section_id.saturating_add(1);
                        paused = true;
                    }
                    "resume" => paused = false,
                    _ => {}
                }
                event_index += 1;
            }
            section_id
        })
        .collect()
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
        assert_eq!(replay.finalized_track.len(), 6);
        assert!(replay.raw_track.iter().all(|point| point.section_id == 0));
        assert!(replay.fused_track.iter().all(|point| point.section_id == 0));
        assert!(
            replay
                .finalized_track
                .iter()
                .all(|point| point.section_id == 0)
        );
    }

    #[test]
    fn replay_splits_tracks_at_manual_pause() {
        let unique = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let path = std::env::temp_dir().join(format!("dhava-replay-pause-{unique}.jsonl.gz"));
        let file = File::create(&path).unwrap();
        let mut gzip = GzEncoder::new(file, Compression::fast());
        for line in [
            r#"{"type":"imu","timestamp_ms":0,"accel":[0.0,0.0,9.80665],"gyro":[0.0,0.0,0.0]}"#,
            r#"{"type":"gps","timestamp_ms":1000,"lat":41.7,"lon":44.8,"accuracy_m":4.0,"speed_mps":2.0,"bearing_deg":90.0}"#,
            r#"{"type":"gps","timestamp_ms":2000,"lat":41.7,"lon":44.80002,"accuracy_m":4.0,"speed_mps":2.0,"bearing_deg":90.0}"#,
            r#"{"type":"event","timestamp_ms":2500,"action":"pause"}"#,
            r#"{"type":"event","timestamp_ms":5000,"action":"resume"}"#,
            r#"{"type":"imu","timestamp_ms":5020,"accel":[0.0,0.0,9.80665],"gyro":[0.0,0.0,0.0]}"#,
            r#"{"type":"gps","timestamp_ms":6000,"lat":41.71,"lon":44.81,"accuracy_m":4.0,"speed_mps":2.0,"bearing_deg":90.0}"#,
            r#"{"type":"gps","timestamp_ms":7000,"lat":41.71,"lon":44.81002,"accuracy_m":4.0,"speed_mps":2.0,"bearing_deg":90.0}"#,
        ] {
            writeln!(gzip, "{line}").unwrap();
        }
        gzip.finish().unwrap();

        let replay = replay_recording(path.to_string_lossy().into_owned()).unwrap();
        remove_file(path).unwrap();
        assert_eq!(
            replay
                .raw_track
                .iter()
                .map(|point| point.section_id)
                .collect::<Vec<_>>(),
            vec![0, 0, 1, 1],
        );
        assert_eq!(
            replay
                .fused_track
                .iter()
                .map(|point| point.section_id)
                .collect::<Vec<_>>(),
            vec![0, 0, 1, 1],
        );
        assert!(
            replay
                .finalized_track
                .windows(2)
                .all(|pair| pair[0].section_id == pair[1].section_id
                    || pair[0].timestamp_ms == 2_000 && pair[1].timestamp_ms == 6_000)
        );
        assert!(
            replay
                .finalized_track
                .iter()
                .all(|point| !(2_000 < point.timestamp_ms && point.timestamp_ms < 6_000))
        );
    }
}
