//! On-device ride analysis: the first real analysis API exposed to Android.
//!
//! # Algorithm status: `gps-bounded-0.5`
//!
//! Everything in this module is a deliberately NAIVE, GPS-first v0 baseline,
//! to be replaced by proper GPS+IMU+baro Kalman fusion. Every result is
//! tagged with [`ALGORITHM_VERSION`] product-wide so tracks/segments can be
//! recomputed (on-device — raw stays on the phone) when the algorithms
//! improve.
//!
//! v0 choices, documented so the next version knows what to beat:
//! - **Accuracy gate**: GPS points with `accuracy_m > 20` are dropped
//!   entirely (real forest data showed 60 m outliers). Points without an
//!   accuracy estimate are kept.
//! - **Distance**: haversine between accepted points, with an anchor filter —
//!   moves shorter than 1 m from the last accepted point are treated as
//!   jitter and ignored (the anchor does not advance, so slow real movement
//!   still accumulates). Short coordinate jumps that exceed both fixes'
//!   accuracy radii and their reported Doppler speed are ignored.
//! - **Moving time**: sum of inter-fix intervals whose speed exceeds
//!   0.7 m/s (reported GPS speed if present, otherwise derived
//!   distance/time). Gaps longer than 10 s never count as moving.
//! - **Ascent/descent**: GPS altitude is very noisy, so the altitude series
//!   is median-filtered (window 5) and then accumulated with a +/-2 m
//!   hysteresis: reversals smaller than 2 m are ignored.
//! - **Airtime**: free-fall signature on raw accelerometer magnitude — the
//!   150 ms sliding-window mean of |accel| dropping below 4.0 m/s^2 marks
//!   airborne time (a true free fall reads ~0, riding reads ~9.8 plus
//!   vibration). Windows closer than 100 ms are merged; windows shorter
//!   than 150 ms are discarded. `landing_peak_g` is the max |accel|/9.81
//!   within 300 ms after the window ends.

use std::path::Path;

use crate::gps_quality::{HorizontalFix, geographic_distance_m, kinematically_plausible};
use crate::recording::{ParsedRecording, parse_recording_file};
use crate::{FusionError, GpsPoint, ImuSample};

/// Version tag applied to every analysis result, product-wide.
pub const ALGORITHM_VERSION: &str = "gps-bounded-0.5";

/// Standard gravity, m/s^2.
const G: f64 = 9.81;
/// GPS fixes with a worse horizontal accuracy estimate are dropped.
const MAX_ACCURACY_M: f64 = 20.0;
/// Moves shorter than this are GPS jitter, not distance.
const MIN_MOVE_M: f64 = 1.0;
/// Speed above which the rider counts as moving.
const MOVING_SPEED_MPS: f64 = 0.7;
/// Inter-fix gaps longer than this never count as moving time.
const MAX_MOVING_GAP_MS: i64 = 10_000;
/// Median filter window (samples) for the altitude series.
const ALTITUDE_MEDIAN_WINDOW: usize = 5;
/// Altitude reversals smaller than this are ignored (hysteresis).
const ALTITUDE_HYSTERESIS_M: f64 = 2.0;
/// Mean |accel| below this over the airtime window means airborne.
const AIRTIME_ACCEL_THRESHOLD: f64 = 4.0;
/// Sliding-window length and minimum airtime window duration.
const AIRTIME_MIN_MS: i64 = 150;
/// Airtime windows closer than this are merged into one.
const AIRTIME_MERGE_GAP_MS: i64 = 100;
/// Landing peak is searched within this span after the airtime window.
const LANDING_SEARCH_MS: i64 = 300;
/// Track output is decimated to roughly this interval.
const TRACK_DECIMATION_MS: i64 = 950;
/// One detected airborne window (jump / drop).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct AirtimeWindow {
    /// Window start, Unix epoch milliseconds.
    pub start_ms: i64,
    /// Airborne duration, milliseconds.
    pub duration_ms: i64,
    /// Peak |accel| within 300 ms after landing, in g (9.81 m/s^2).
    pub landing_peak_g: f64,
}

/// One decimated track point for map display (~1 Hz).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct TrackPoint {
    /// Unix epoch milliseconds.
    pub timestamp_ms: i64,
    /// Latitude, degrees (WGS84).
    pub lat: f64,
    /// Longitude, degrees (WGS84).
    pub lon: f64,
    /// Altitude, meters, if the fix had one.
    pub altitude_m: Option<f64>,
    /// Ground speed, m/s, if the fix had one.
    pub speed_mps: Option<f64>,
}

/// Full analysis of one raw recording.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct RideAnalysis {
    /// Recording start (meta line if present, else earliest sample), epoch ms.
    pub started_at_ms: i64,
    /// Latest sample timestamp, epoch ms.
    pub ended_at_ms: i64,
    /// Time spent moving (speed > 0.7 m/s), seconds.
    pub moving_time_s: f64,
    /// Total horizontal distance, meters.
    pub distance_m: f64,
    /// Upward elevation, meters.
    ///
    /// Canonical finalization reports accumulated movement with barometer data
    /// and robust section-wise net change for GPS-only recordings.
    pub ascent_m: f64,
    /// Downward elevation, with the same source-dependent semantics as ascent.
    pub descent_m: f64,
    /// Maximum speed, m/s.
    pub max_speed_mps: f64,
    /// Average speed while moving, m/s.
    pub avg_moving_speed_mps: f64,
    /// Sum of all airtime window durations, milliseconds.
    pub airtime_total_ms: i64,
    /// Detected airborne windows, chronological.
    pub airtime_windows: Vec<AirtimeWindow>,
    /// Accuracy-filtered track decimated to ~1 Hz for map display.
    pub track: Vec<TrackPoint>,
    /// Raw GPS fix count in the file (before filtering).
    pub gps_count: u32,
    /// Raw IMU sample count in the file.
    pub imu_count: u32,
    /// Algorithm version tag ([`ALGORITHM_VERSION`]).
    pub algorithm_version: String,
}

/// Returns the analysis algorithm version tag; results product-wide are
/// tagged with this value.
#[uniffi::export]
pub fn algorithm_version() -> String {
    ALGORITHM_VERSION.to_owned()
}

/// Parses and analyzes a raw recording file (`.jsonl.gz`).
///
/// `path` is an absolute filesystem path to the recording. Blocking (file IO
/// + number crunching): call from a background thread.
#[uniffi::export]
pub fn analyze_recording(path: String) -> Result<RideAnalysis, FusionError> {
    let recording = parse_recording_file(Path::new(&path))?;
    analyze(&recording)
}

/// Analyzes an already-parsed recording.
pub fn analyze(recording: &ParsedRecording) -> Result<RideAnalysis, FusionError> {
    if recording.is_empty() {
        return Err(FusionError::EmptyRecording {
            msg: "recording contains no gps/imu/baro samples".to_owned(),
        });
    }

    // File order is only best-effort chronological; sort defensively.
    let mut gps: Vec<GpsPoint> = recording.gps.clone();
    gps.sort_by_key(|p| p.timestamp_ms);
    let mut imu: Vec<ImuSample> = recording.imu.clone();
    imu.sort_by_key(|s| s.timestamp_ms);

    let accepted = accuracy_filter(&gps);

    let (distance_m, moving_time_ms, max_derived_mps) =
        distance_and_moving_time(&accepted, &recording.events);
    let moving_time_s = moving_time_ms as f64 / 1000.0;

    let max_reported_mps = accepted
        .iter()
        .filter_map(|p| p.speed_mps)
        .fold(0.0f64, |m, s| m.max(s as f64));
    let avg_moving_speed_mps = if moving_time_s > 0.0 {
        distance_m / moving_time_s
    } else {
        0.0
    };
    // A recording with Doppler speeds should not reinterpret position-radius
    // corrections as instantaneous velocity. The distance/time average is a
    // conservative floor so coarse or partially missing speed samples cannot
    // make the reported maximum physically lower than the ride average.
    let max_speed_mps = max_reported_mps
        .max(max_derived_mps)
        .max(avg_moving_speed_mps);

    let (ascent_m, descent_m) = ascent_descent(&accepted);
    let airtime_windows = detect_airtime(&imu);
    let airtime_total_ms = airtime_windows.iter().map(|w| w.duration_ms).sum();

    let first_sample_ms = [
        gps.first().map(|p| p.timestamp_ms),
        imu.first().map(|s| s.timestamp_ms),
        recording.baro.iter().map(|b| b.timestamp_ms).min(),
    ]
    .into_iter()
    .flatten()
    .min();
    let started_at_ms = recording
        .meta
        .as_ref()
        .and_then(|m| m.started_at_ms)
        .or(first_sample_ms)
        .unwrap_or(0);
    let ended_at_ms = [
        gps.last().map(|p| p.timestamp_ms),
        imu.last().map(|s| s.timestamp_ms),
        recording.baro.iter().map(|b| b.timestamp_ms).max(),
    ]
    .into_iter()
    .flatten()
    .max()
    .unwrap_or(started_at_ms);

    Ok(RideAnalysis {
        started_at_ms,
        ended_at_ms,
        moving_time_s,
        distance_m,
        ascent_m,
        descent_m,
        max_speed_mps,
        avg_moving_speed_mps,
        airtime_total_ms,
        airtime_windows,
        track: decimate_track(&accepted),
        gps_count: recording.gps.len() as u32,
        imu_count: recording.imu.len() as u32,
        algorithm_version: ALGORITHM_VERSION.to_owned(),
    })
}

/// Drops fixes whose reported horizontal accuracy is worse than 20 m.
fn accuracy_filter(gps: &[GpsPoint]) -> Vec<GpsPoint> {
    gps.iter()
        .filter(|p| p.accuracy_m.is_none_or(|a| (a as f64) <= MAX_ACCURACY_M))
        .cloned()
        .collect()
}

/// Great-circle distance between two fixes, meters (haversine).
fn haversine_m(a: &GpsPoint, b: &GpsPoint) -> f64 {
    geographic_distance_m(a.lat, a.lon, b.lat, b.lon)
}

/// Returns `(distance_m, moving_time_ms, max_derived_speed_mps)`.
///
/// Distance uses an anchor filter: a fix less than 1 m from the last
/// *accepted* fix is jitter and does not move the anchor, so creeping
/// movement still accumulates. Moving time sums inter-fix intervals whose
/// speed (reported, else derived) exceeds 0.7 m/s, skipping gaps > 10 s.
fn distance_and_moving_time(
    gps: &[GpsPoint],
    events: &[crate::recording::RecordingEvent],
) -> (f64, i64, f64) {
    let mut distance_m = 0.0;
    let mut moving_time_ms = 0i64;
    let mut max_derived_mps = 0.0f64;

    let Some(mut anchor) = gps.first() else {
        return (0.0, 0, 0.0);
    };
    for point in &gps[1..] {
        if crosses_manual_pause(anchor.timestamp_ms, point.timestamp_ms, events) {
            anchor = point;
            continue;
        }
        if !kinematically_plausible(
            HorizontalFix::from_gps(anchor, MAX_ACCURACY_M),
            HorizontalFix::from_gps(point, MAX_ACCURACY_M),
        ) {
            continue;
        }
        let step = haversine_m(anchor, point);
        if step >= MIN_MOVE_M {
            distance_m += step;
            anchor = point;
        }
    }

    for pair in gps.windows(2) {
        let (a, b) = (&pair[0], &pair[1]);
        if crosses_manual_pause(a.timestamp_ms, b.timestamp_ms, events) {
            continue;
        }
        let dt_ms = b.timestamp_ms - a.timestamp_ms;
        if dt_ms <= 0 || dt_ms > MAX_MOVING_GAP_MS {
            continue;
        }
        let derived = haversine_m(a, b) / (dt_ms as f64 / 1000.0);
        let plausible = kinematically_plausible(
            HorizontalFix::from_gps(a, MAX_ACCURACY_M),
            HorizontalFix::from_gps(b, MAX_ACCURACY_M),
        );
        // Doppler speed is independent from coordinate displacement and wins
        // whenever available. Derive a maximum only across consecutive fixes
        // that both lack it, and only after the shared kinematic gate.
        if a.speed_mps.is_none() && b.speed_mps.is_none() && plausible && derived < 40.0 {
            max_derived_mps = max_derived_mps.max(derived);
        }
        let speed = b.speed_mps.map(|s| s as f64).unwrap_or(derived);
        if speed > MOVING_SPEED_MPS {
            moving_time_ms += dt_ms;
        }
    }

    (distance_m, moving_time_ms, max_derived_mps)
}

fn crosses_manual_pause(
    from_ms: i64,
    to_ms: i64,
    events: &[crate::recording::RecordingEvent],
) -> bool {
    let mut paused = false;
    for event in events {
        if event.timestamp_ms > to_ms {
            break;
        }
        match event.action.as_str() {
            "pause" => paused = true,
            "resume" => {
                if paused && event.timestamp_ms > from_ms {
                    return true;
                }
                paused = false;
            }
            _ => {}
        }
        if paused && event.timestamp_ms > from_ms {
            return true;
        }
    }
    paused
}

/// Centered median filter over a series (window must be odd).
fn median_filter(series: &[f64], window: usize) -> Vec<f64> {
    debug_assert!(window % 2 == 1);
    let half = window / 2;
    series
        .iter()
        .enumerate()
        .map(|(i, _)| {
            let lo = i.saturating_sub(half);
            let hi = (i + half + 1).min(series.len());
            let mut w: Vec<f64> = series[lo..hi].to_vec();
            w.sort_by(|a, b| a.total_cmp(b));
            w[w.len() / 2]
        })
        .collect()
}

/// Ascent/descent from GPS altitude: median filter (window 5) plus a 2 m
/// hysteresis accumulator — the reference altitude only moves when the
/// series escapes a +/-2 m band around it, killing small noise reversals.
fn ascent_descent(gps: &[GpsPoint]) -> (f64, f64) {
    let altitudes: Vec<f64> = gps.iter().filter_map(|p| p.altitude_m).collect();
    if altitudes.len() < 2 {
        return (0.0, 0.0);
    }
    let smoothed = median_filter(&altitudes, ALTITUDE_MEDIAN_WINDOW);

    let mut ascent = 0.0;
    let mut descent = 0.0;
    let mut reference = smoothed[0];
    for &alt in &smoothed[1..] {
        let delta = alt - reference;
        if delta >= ALTITUDE_HYSTERESIS_M {
            ascent += delta;
            reference = alt;
        } else if delta <= -ALTITUDE_HYSTERESIS_M {
            descent += -delta;
            reference = alt;
        }
    }
    (ascent, descent)
}

/// Airtime detection on raw accelerometer magnitude.
///
/// A sample is "airborne" when the mean |accel| over the trailing 150 ms
/// window is below 4.0 m/s^2. Contiguous airborne runs closer than 100 ms
/// are merged; merged runs shorter than 150 ms are dropped. The landing peak
/// is the max |accel| within 300 ms after the run ends.
fn detect_airtime(imu: &[ImuSample]) -> Vec<AirtimeWindow> {
    if imu.len() < 2 {
        return Vec::new();
    }
    let mag: Vec<f64> = imu
        .iter()
        .map(|s| {
            let [x, y, z] = s.accel;
            ((x as f64).powi(2) + (y as f64).powi(2) + (z as f64).powi(2)).sqrt()
        })
        .collect();

    // Trailing-window mean via two pointers over (irregular) timestamps.
    let mut prefix = vec![0.0f64; mag.len() + 1];
    for (i, m) in mag.iter().enumerate() {
        prefix[i + 1] = prefix[i] + m;
    }
    let mut airborne = vec![false; mag.len()];
    let mut lo = 0usize;
    for i in 0..mag.len() {
        while imu[i].timestamp_ms - imu[lo].timestamp_ms > AIRTIME_MIN_MS {
            lo += 1;
        }
        let mean = (prefix[i + 1] - prefix[lo]) / (i + 1 - lo) as f64;
        airborne[i] = mean < AIRTIME_ACCEL_THRESHOLD;
    }

    // Collect contiguous airborne runs as (start_ms, end_ms).
    // The trailing window makes the marked run lag reality by up to a window
    // length (the landing spike itself sits inside a still-low trailing
    // mean); shift both edges back by half a window as a cheap centering fix.
    let mut runs: Vec<(i64, i64)> = Vec::new();
    let mut run_start: Option<i64> = None;
    for (i, &a) in airborne.iter().enumerate() {
        if a && run_start.is_none() {
            run_start = Some(imu[i].timestamp_ms - AIRTIME_MIN_MS / 2);
        } else if !a && run_start.is_some() {
            let end = imu[i - 1].timestamp_ms - AIRTIME_MIN_MS / 2;
            runs.push((run_start.take().unwrap(), end));
        }
    }
    if let Some(s) = run_start {
        runs.push((s, imu.last().unwrap().timestamp_ms));
    }

    // Merge runs separated by less than 100 ms.
    let mut merged: Vec<(i64, i64)> = Vec::new();
    for (start, end) in runs {
        match merged.last_mut() {
            Some((_, last_end)) if start - *last_end < AIRTIME_MERGE_GAP_MS => {
                *last_end = (*last_end).max(end);
            }
            _ => merged.push((start, end)),
        }
    }

    merged
        .into_iter()
        .filter(|(start, end)| end - start >= AIRTIME_MIN_MS)
        .map(|(start, end)| {
            let landing_peak = imu
                .iter()
                .zip(&mag)
                .filter(|(s, _)| s.timestamp_ms > end && s.timestamp_ms <= end + LANDING_SEARCH_MS)
                .map(|(_, &m)| m)
                .fold(0.0f64, f64::max);
            AirtimeWindow {
                start_ms: start,
                duration_ms: end - start,
                landing_peak_g: landing_peak / G,
            }
        })
        .collect()
}

/// Decimates the accepted track to roughly 1 Hz for map display.
fn decimate_track(gps: &[GpsPoint]) -> Vec<TrackPoint> {
    let mut out = Vec::new();
    let mut last_kept: Option<i64> = None;
    for p in gps {
        if last_kept.is_some_and(|t| p.timestamp_ms - t < TRACK_DECIMATION_MS) {
            continue;
        }
        last_kept = Some(p.timestamp_ms);
        out.push(TrackPoint {
            timestamp_ms: p.timestamp_ms,
            lat: p.lat,
            lon: p.lon,
            altitude_m: p.altitude_m,
            speed_mps: p.speed_mps.map(|s| s as f64),
        });
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn gps_at(timestamp_ms: i64, east_m: f64, accuracy_m: f32, speed_mps: Option<f32>) -> GpsPoint {
        let lat = 41.7;
        GpsPoint {
            timestamp_ms,
            lat,
            lon: 44.8 + (east_m / (6_371_000.0 * lat.to_radians().cos())).to_degrees(),
            altitude_m: None,
            accuracy_m: Some(accuracy_m),
            speed_mps,
            bearing_deg: Some(90.0),
        }
    }

    #[test]
    fn kojoring_style_jump_does_not_inflate_distance_or_max_speed() {
        let recording = ParsedRecording {
            gps: vec![
                gps_at(0, 0.0, 3.8, Some(4.31)),
                gps_at(1_000, 16.8, 3.9, Some(2.91)),
                gps_at(2_000, 8.0, 3.9, Some(4.0)),
            ],
            ..ParsedRecording::default()
        };

        let analysis = analyze(&recording).unwrap();

        assert!((7.5..=8.5).contains(&analysis.distance_m));
        assert!((analysis.max_speed_mps - 4.31).abs() < 0.001);
    }

    #[test]
    fn derived_max_speed_remains_available_without_doppler_speed() {
        let recording = ParsedRecording {
            gps: vec![gps_at(0, 0.0, 3.0, None), gps_at(1_000, 10.0, 3.0, None)],
            ..ParsedRecording::default()
        };

        let analysis = analyze(&recording).unwrap();

        assert!((analysis.max_speed_mps - 10.0).abs() < 0.1);
    }

    #[test]
    fn manual_pause_does_not_bridge_distance() {
        let point = |timestamp_ms, lon| GpsPoint {
            timestamp_ms,
            lat: 41.7,
            lon,
            altitude_m: None,
            accuracy_m: Some(3.0),
            speed_mps: Some(5.0),
            bearing_deg: None,
        };
        let gps = vec![point(0, 44.8), point(1_000, 44.8001), point(61_000, 44.81)];
        let events = vec![
            crate::recording::RecordingEvent {
                timestamp_ms: 2_000,
                action: "pause".into(),
            },
            crate::recording::RecordingEvent {
                timestamp_ms: 60_000,
                action: "resume".into(),
            },
        ];
        let (distance, moving_ms, _) = distance_and_moving_time(&gps, &events);
        assert!(
            distance < 20.0,
            "paused jump leaked into distance: {distance}"
        );
        assert_eq!(moving_ms, 1_000);
    }

    #[test]
    fn median_filter_kills_single_spike() {
        let series = vec![700.0, 700.0, 760.0, 700.0, 700.0];
        let smoothed = median_filter(&series, 5);
        assert!(smoothed.iter().all(|&a| (a - 700.0).abs() < 1e-9));
    }

    #[test]
    fn hysteresis_ignores_small_reversals() {
        let gps: Vec<GpsPoint> = [700.0, 700.8, 699.5, 700.4, 699.9]
            .iter()
            .enumerate()
            .map(|(i, &alt)| GpsPoint {
                timestamp_ms: i as i64 * 1000,
                lat: 0.0,
                lon: 0.0,
                altitude_m: Some(alt),
                accuracy_m: None,
                speed_mps: None,
                bearing_deg: None,
            })
            .collect();
        let (ascent, descent) = ascent_descent(&gps);
        assert_eq!((ascent, descent), (0.0, 0.0));
    }

    #[test]
    fn synthetic_free_fall_is_detected() {
        // 2 s of riding, 400 ms of free fall, hard landing, 2 s of riding.
        // 200 Hz synthetic IMU.
        let mut imu = Vec::new();
        let mut t = 0i64;
        let push = |t: i64, a: f32| ImuSample {
            timestamp_ms: t,
            accel: [0.0, 0.0, a],
            gyro: [0.0, 0.0, 0.0],
            mag: None,
        };
        while t < 2000 {
            imu.push(push(t, 9.8));
            t += 5;
        }
        while t < 2400 {
            imu.push(push(t, 0.4));
            t += 5;
        }
        imu.push(push(t, 39.2)); // ~4 g landing spike
        t += 5;
        while t < 4400 {
            imu.push(push(t, 9.8));
            t += 5;
        }

        let windows = detect_airtime(&imu);
        assert_eq!(windows.len(), 1, "windows: {windows:?}");
        let w = &windows[0];
        assert!(
            (200..=600).contains(&w.duration_ms),
            "duration {}",
            w.duration_ms
        );
        assert!(w.landing_peak_g > 3.0, "peak {}", w.landing_peak_g);
    }

    #[test]
    fn empty_recording_is_an_error() {
        let rec = ParsedRecording::default();
        assert!(matches!(
            analyze(&rec),
            Err(FusionError::EmptyRecording { .. })
        ));
    }
}
