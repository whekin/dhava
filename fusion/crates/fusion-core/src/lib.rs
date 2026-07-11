//! Dhava sensor-fusion core.
//!
//! This crate is the shared fusion engine for downhill MTB ride recordings.
//! It will eventually contain:
//! - Kalman filtering fusing GPS + IMU + barometer into a smoothed track,
//! - segment gate-crossing detection (implemented: [`detect_gate_crossing`]),
//! - airtime (jump) detection from accelerometer free-fall signatures.
//!
//! The same library is intended to be compiled for Android via UniFFI and
//! used server-side by `fusion-worker`.

use serde::{Deserialize, Serialize};

uniffi::setup_scaffolding!();

pub mod analysis;
pub mod recording;

pub use analysis::{
    ALGORITHM_VERSION, AirtimeWindow, RideAnalysis, TrackPoint, algorithm_version,
    analyze_recording,
};
pub use recording::{ParsedRecording, RecordingMeta, parse_recording, parse_recording_file};

/// Errors surfaced across the FFI boundary (Kotlin exceptions).
#[derive(Debug, uniffi::Error)]
pub enum FusionError {
    /// The recording file could not be opened/read.
    Io { msg: String },
    /// The recording could not be interpreted at all.
    Parse { msg: String },
    /// The recording parsed but contains no samples to analyze.
    EmptyRecording { msg: String },
}

impl std::fmt::Display for FusionError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            FusionError::Io { msg } => write!(f, "io error: {msg}"),
            FusionError::Parse { msg } => write!(f, "parse error: {msg}"),
            FusionError::EmptyRecording { msg } => write!(f, "empty recording: {msg}"),
        }
    }
}

impl std::error::Error for FusionError {}

/// Mean Earth radius in meters, used for the local equirectangular projection.
const EARTH_RADIUS_M: f64 = 6_371_000.0;

/// A single GPS fix as reported by the phone.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct GpsPoint {
    /// Unix epoch milliseconds.
    pub timestamp_ms: i64,
    /// Latitude in degrees (WGS84).
    pub lat: f64,
    /// Longitude in degrees (WGS84).
    pub lon: f64,
    /// Altitude above the WGS84 ellipsoid, meters.
    pub altitude_m: Option<f64>,
    /// Horizontal accuracy estimate, meters.
    pub accuracy_m: Option<f32>,
    /// Ground speed, meters per second.
    pub speed_mps: Option<f32>,
    /// Course over ground, degrees clockwise from true north.
    pub bearing_deg: Option<f32>,
}

/// A single IMU sample (device coordinate frame).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ImuSample {
    /// Unix epoch milliseconds.
    pub timestamp_ms: i64,
    /// Accelerometer reading, m/s^2, [x, y, z].
    pub accel: [f32; 3],
    /// Gyroscope reading, rad/s, [x, y, z].
    pub gyro: [f32; 3],
    /// Magnetometer reading, microtesla, [x, y, z], if available.
    pub mag: Option<[f32; 3]>,
}

/// A single barometer sample.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct BaroSample {
    /// Unix epoch milliseconds.
    pub timestamp_ms: i64,
    /// Atmospheric pressure, hectopascal.
    pub pressure_hpa: f32,
}

/// A geographic coordinate (degrees, WGS84).
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct LatLon {
    pub lat: f64,
    pub lon: f64,
}

/// A segment gate: a short virtual line on the ground that riders must cross
/// in a given direction (e.g. a segment start or finish line).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Gate {
    /// One endpoint of the gate line.
    pub a: LatLon,
    /// The other endpoint of the gate line.
    pub b: LatLon,
    /// Required travel bearing when crossing, degrees clockwise from true north.
    pub crossing_bearing_deg: f64,
    /// Allowed deviation from `crossing_bearing_deg`, degrees (half-angle).
    pub tolerance_deg: f64,
}

/// The result of a detected gate crossing.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct GateCrossing {
    /// Interpolated crossing time, Unix epoch milliseconds.
    pub timestamp_ms: i64,
    /// Fraction along the track segment `[p_i, p_{i+1}]` where the crossing
    /// happened, in `[0, 1]`.
    pub interpolation_t: f64,
}

/// Projects a lat/lon point to local planar meters using an equirectangular
/// projection centered at `origin`. Accurate enough for gate-sized geometry
/// (tens of meters).
fn project(p: LatLon, origin: LatLon) -> [f64; 2] {
    let lat0 = origin.lat.to_radians();
    let x = (p.lon - origin.lon).to_radians() * lat0.cos() * EARTH_RADIUS_M;
    let y = (p.lat - origin.lat).to_radians() * EARTH_RADIUS_M;
    [x, y]
}

/// Smallest absolute difference between two bearings, degrees, in `[0, 180]`.
fn bearing_diff_deg(a: f64, b: f64) -> f64 {
    let d = (a - b).rem_euclid(360.0);
    if d > 180.0 { 360.0 - d } else { d }
}

/// 2D cross product of `(a, b)`.
fn cross(a: [f64; 2], b: [f64; 2]) -> f64 {
    a[0] * b[1] - a[1] * b[0]
}

/// Intersects segments `p -> p + r` and `q -> q + s`.
/// Returns `(t, u)` such that the intersection is at `p + t*r` and `q + u*s`,
/// with both parameters in `[0, 1]`. Collinear/parallel segments yield `None`.
fn segment_intersection(p: [f64; 2], r: [f64; 2], q: [f64; 2], s: [f64; 2]) -> Option<(f64, f64)> {
    let denom = cross(r, s);
    if denom.abs() < f64::EPSILON {
        return None;
    }
    let qp = [q[0] - p[0], q[1] - p[1]];
    let t = cross(qp, s) / denom;
    let u = cross(qp, r) / denom;
    if (0.0..=1.0).contains(&t) && (0.0..=1.0).contains(&u) {
        Some((t, u))
    } else {
        None
    }
}

/// Finds the first crossing of `gate` by `track`.
///
/// Consecutive track points are connected by straight segments in a local
/// equirectangular projection centered on the gate. A crossing is reported
/// when such a segment intersects the gate line *and* the travel bearing of
/// the segment is within `gate.tolerance_deg` of `gate.crossing_bearing_deg`.
/// The crossing timestamp is linearly interpolated between the two points.
pub fn detect_gate_crossing(track: &[GpsPoint], gate: &Gate) -> Option<GateCrossing> {
    let origin = LatLon {
        lat: (gate.a.lat + gate.b.lat) / 2.0,
        lon: (gate.a.lon + gate.b.lon) / 2.0,
    };
    let ga = project(gate.a, origin);
    let gb = project(gate.b, origin);
    let gate_vec = [gb[0] - ga[0], gb[1] - ga[1]];

    for pair in track.windows(2) {
        let (p0, p1) = (&pair[0], &pair[1]);
        let a = project(
            LatLon {
                lat: p0.lat,
                lon: p0.lon,
            },
            origin,
        );
        let b = project(
            LatLon {
                lat: p1.lat,
                lon: p1.lon,
            },
            origin,
        );
        let travel = [b[0] - a[0], b[1] - a[1]];

        let Some((t, _u)) = segment_intersection(a, travel, ga, gate_vec) else {
            continue;
        };

        // Bearing: degrees clockwise from north, i.e. atan2(east, north).
        let travel_bearing = travel[0].atan2(travel[1]).to_degrees().rem_euclid(360.0);
        if bearing_diff_deg(travel_bearing, gate.crossing_bearing_deg) > gate.tolerance_deg {
            continue;
        }

        let dt = (p1.timestamp_ms - p0.timestamp_ms) as f64;
        let timestamp_ms = p0.timestamp_ms + (t * dt).round() as i64;
        return Some(GateCrossing {
            timestamp_ms,
            interpolation_t: t,
        });
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    fn point(timestamp_ms: i64, lat: f64, lon: f64) -> GpsPoint {
        GpsPoint {
            timestamp_ms,
            lat,
            lon,
            altitude_m: None,
            accuracy_m: None,
            speed_mps: None,
            bearing_deg: None,
        }
    }

    /// A ~220 m north-south gate line on the prime meridian at the equator,
    /// requiring an eastward (90 degrees) crossing with 45 degrees tolerance.
    fn eastward_gate() -> Gate {
        Gate {
            a: LatLon {
                lat: -0.001,
                lon: 0.0,
            },
            b: LatLon {
                lat: 0.001,
                lon: 0.0,
            },
            crossing_bearing_deg: 90.0,
            tolerance_deg: 45.0,
        }
    }

    #[test]
    fn crossing_detected_with_interpolated_time() {
        let gate = eastward_gate();
        // Heading due east; the second segment crosses lon 0 exactly 1/4 of
        // the way between its endpoints (-0.0005 -> 0.0015).
        let track = vec![
            point(0, 0.0, -0.0020),
            point(1000, 0.0, -0.0005),
            point(3000, 0.0, 0.0015),
        ];
        let crossing = detect_gate_crossing(&track, &gate).expect("gate should be crossed");
        assert!((crossing.interpolation_t - 0.25).abs() < 1e-9);
        assert_eq!(crossing.timestamp_ms, 1500);
    }

    #[test]
    fn wrong_direction_crossing_rejected() {
        let gate = eastward_gate();
        // Same geometry but travelling west (bearing 270), outside tolerance.
        let track = vec![point(0, 0.0, 0.0015), point(2000, 0.0, -0.0005)];
        assert_eq!(detect_gate_crossing(&track, &gate), None);
    }

    #[test]
    fn non_crossing_track_returns_none() {
        let gate = eastward_gate();
        // Eastward track that passes well north of the gate line's extent.
        let track = vec![point(0, 0.0050, -0.0020), point(2000, 0.0050, 0.0020)];
        assert_eq!(detect_gate_crossing(&track, &gate), None);
    }
}
