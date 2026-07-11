//! Raw recording (`.jsonl.gz`) parser.
//!
//! Format: `proto/raw-recording-format.md` — one gzip-compressed JSON Lines
//! file per recording, every line tagged with a `type` discriminator.
//!
//! Robustness requirements (all hit in practice):
//! - **Multi-member gzip**: a recording resumed after an app crash is several
//!   concatenated gzip members; `MultiGzDecoder` reads them all
//!   (`GzDecoder` would silently stop after the first member).
//! - **Truncated tail**: the process can die mid-write, leaving either a
//!   half-written JSON line or a truncated gzip stream. Both are tolerated:
//!   everything successfully decoded up to that point is kept.
//! - **Unknown line types / extra fields**: skipped, for forward compatibility.
//! - **Missing optional fields**: handled by the `Option` fields on the
//!   serde types.

use std::fs::File;
use std::io::{BufRead, BufReader, Read};
use std::path::Path;

use flate2::read::MultiGzDecoder;
use serde::Deserialize;

use crate::{BaroSample, FusionError, GpsPoint, ImuSample};

/// The `meta` header line of a recording.
#[derive(Debug, Clone, PartialEq, Deserialize)]
pub struct RecordingMeta {
    pub version: Option<u32>,
    pub activity_id: Option<String>,
    pub device: Option<String>,
    pub os: Option<String>,
    pub app_version: Option<String>,
    pub started_at_ms: Option<i64>,
}

/// One line of the recording file, discriminated by `type`.
#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all = "lowercase")]
enum RecordLine {
    Meta(RecordingMeta),
    Gps(GpsPoint),
    Imu(ImuSample),
    Baro(BaroSample),
    /// Forward compatibility: any line type this version does not know.
    #[serde(other)]
    Unknown,
}

/// A fully parsed raw recording, samples grouped by kind.
///
/// Sample vectors preserve file order, which is only best-effort
/// chronological; callers that need strict ordering must sort.
#[derive(Debug, Default)]
pub struct ParsedRecording {
    pub meta: Option<RecordingMeta>,
    pub gps: Vec<GpsPoint>,
    pub imu: Vec<ImuSample>,
    pub baro: Vec<BaroSample>,
    /// Lines that were present but not parseable/known (diagnostics only).
    pub skipped_lines: u64,
}

impl ParsedRecording {
    pub fn is_empty(&self) -> bool {
        self.gps.is_empty() && self.imu.is_empty() && self.baro.is_empty()
    }
}

/// Parses a raw recording file (`.jsonl.gz`) from disk.
pub fn parse_recording_file(path: &Path) -> Result<ParsedRecording, FusionError> {
    let file = File::open(path).map_err(|e| FusionError::Io {
        msg: format!("open {}: {e}", path.display()),
    })?;
    parse_recording(file)
}

/// Parses a raw recording from any reader of gzipped JSONL bytes.
pub fn parse_recording<R: Read>(reader: R) -> Result<ParsedRecording, FusionError> {
    let mut lines = BufReader::new(MultiGzDecoder::new(reader));
    let mut out = ParsedRecording::default();
    let mut buf = String::new();

    loop {
        buf.clear();
        match lines.read_line(&mut buf) {
            Ok(0) => break, // clean EOF
            Ok(_) => {}
            // Truncated gzip stream (crash mid-write): keep what we decoded.
            Err(_) => break,
        }
        let line = buf.trim();
        if line.is_empty() {
            continue;
        }
        match serde_json::from_str::<RecordLine>(line) {
            Ok(RecordLine::Meta(m)) => out.meta = Some(m),
            Ok(RecordLine::Gps(p)) => out.gps.push(p),
            Ok(RecordLine::Imu(s)) => out.imu.push(s),
            Ok(RecordLine::Baro(b)) => out.baro.push(b),
            // Unknown line type or malformed/truncated line: skip.
            Ok(RecordLine::Unknown) | Err(_) => out.skipped_lines += 1,
        }
    }

    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use flate2::Compression;
    use flate2::write::GzEncoder;
    use std::io::Write;

    fn gzip(content: &str) -> Vec<u8> {
        let mut enc = GzEncoder::new(Vec::new(), Compression::fast());
        enc.write_all(content.as_bytes()).unwrap();
        enc.finish().unwrap()
    }

    const MEMBER_1: &str = concat!(
        r#"{"type":"meta","version":1,"activity_id":"a","device":"d","os":"android-16","app_version":"0.1.0","started_at_ms":1000}"#,
        "\n",
        r#"{"type":"gps","timestamp_ms":1000,"lat":41.7,"lon":44.8,"altitude_m":712.4,"accuracy_m":3.9,"speed_mps":8.2,"bearing_deg":184.0}"#,
        "\n",
        r#"{"type":"imu","timestamp_ms":1005,"accel":[0.1,-0.03,9.79],"gyro":[0.01,0.0,-0.02]}"#,
        "\n",
    );

    const MEMBER_2: &str = concat!(
        r#"{"type":"baro","timestamp_ms":2010,"pressure_hpa":934.2}"#,
        "\n",
        r#"{"type":"gps","timestamp_ms":2000,"lat":41.71,"lon":44.81}"#,
        "\n",
        r#"{"type":"wibble","timestamp_ms":2020,"whatever":true}"#,
        "\n",
    );

    #[test]
    fn multi_member_gzip_is_read_to_the_end() {
        // A crash-resumed recording = two gzip members concatenated.
        let mut bytes = gzip(MEMBER_1);
        bytes.extend_from_slice(&gzip(MEMBER_2));

        let rec = parse_recording(bytes.as_slice()).unwrap();
        assert_eq!(rec.gps.len(), 2, "second member must be decoded too");
        assert_eq!(rec.imu.len(), 1);
        assert_eq!(rec.baro.len(), 1);
        assert_eq!(rec.skipped_lines, 1, "unknown line type skipped");
        let meta = rec.meta.unwrap();
        assert_eq!(meta.started_at_ms, Some(1000));
        // Optional fields missing on the second GPS point.
        assert_eq!(rec.gps[1].altitude_m, None);
        assert_eq!(rec.gps[1].accuracy_m, None);
    }

    #[test]
    fn truncated_last_line_is_skipped() {
        let content = format!(
            "{MEMBER_1}{}",
            r#"{"type":"gps","timestamp_ms":3000,"lat":4"#
        );
        let rec = parse_recording(gzip(&content).as_slice()).unwrap();
        assert_eq!(rec.gps.len(), 1);
        assert_eq!(rec.skipped_lines, 1);
    }

    #[test]
    fn truncated_gzip_stream_keeps_decoded_prefix() {
        let bytes = gzip(MEMBER_1);
        // Cut the gzip stream mid-deflate (drop trailer + some data).
        let cut = &bytes[..bytes.len() - 12];
        let rec = parse_recording(cut).unwrap();
        // Whatever was decodable is kept; no error surfaces.
        assert!(rec.gps.len() <= 1);
    }

    #[test]
    fn empty_input_yields_empty_recording() {
        let rec = parse_recording(gzip("").as_slice()).unwrap();
        assert!(rec.is_empty());
        assert!(rec.meta.is_none());
    }
}
