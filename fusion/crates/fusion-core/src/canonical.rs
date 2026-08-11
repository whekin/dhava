//! Versioned post-ride artifact produced from the immutable raw recording.
//!
//! Horizontal geometry comes from the GPS-bounded 5 Hz finalizer. Vertical
//! geometry uses barometric relative movement anchored to median-filtered GPS
//! altitude, with GPS-only interpolation as a fallback. The Android layer may
//! cache this result, but it never owns or reimplements these algorithms.
//!
//! The vertical pass separates two signals with very different physics and
//! filters each on its own timescale:
//!
//! - the *offset* between barometer and GPS altitude is a weather field. It
//!   drifts by about a metre over ten minutes and never by ten metres in five
//!   seconds, so anything faster than that in the GPS anchors is an artifact
//!   and is filtered out ([`OFFSET_MEDIAN_HALF_WINDOW_MS`]);
//! - the *relative barometric movement* carries the real terrain, but is open
//!   to impulses that have nothing to do with the ground: a phone pulled out of
//!   a mount or a pocket moves the pressure by ten metres in a second. These
//!   are rejected point by point against a short running median, and only the
//!   points that disagree with it by more than a rider could are replaced.
//!   Everything else is passed through exactly as measured — a filter that
//!   rewrote every sample would clip the crest of every real roller and cost
//!   descent at each one.
//!
//! The one thing that genuinely moves a rider faster than that test allows is
//! being airborne, so around a detected airtime window the test stands down.

use std::collections::HashMap;
use std::path::Path;

use crate::activity::{ActivityState, classify_activity};
use crate::analysis::{
    ALGORITHM_VERSION, AirtimeWindow, MAX_MOVING_GAP_MS, MIN_MOVE_M, MOVING_SPEED_MPS,
    RideAnalysis, analyze,
};
use crate::gps_quality::geographic_distance_m;

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

/// Half-width of the median that flattens the baro-vs-GPS offset series.
///
/// Chosen from the offset's own physics rather than from the GPS cadence, so
/// changing the fix rate — a power-saving profile, a tunnel — does not change
/// how hard the offset is filtered. A minute-wide window still tracks weather
/// drift closely while a treeline altitude excursion, which lasts seconds,
/// cannot reach the finalized profile.
const OFFSET_MEDIAN_HALF_WINDOW_MS: i64 = 30_000;

/// Half-width of the running median a pressure sample is judged against.
///
/// Sized to the disturbance it is aimed at rather than to a noise model: a
/// phone taken out of its mount at the end of a run swings the pressure for
/// about a second, and a median only needs the window to be more than twice
/// that to see through it.
const PRESSURE_MEDIAN_HALF_WINDOW_MS: i64 = 2_000;
/// How far a sample may sit from that median and still be believed.
///
/// This is the height a rider can genuinely gain or lose relative to where they
/// were two seconds ago, drops included; beyond it the barometer is describing
/// something other than the ground. Deliberately generous, because the cost of
/// rejecting a real feature is a lie about the ride, while the cost of keeping
/// a small artifact is a slightly rough chart.
const PRESSURE_OUTLIER_M: f64 = 3.0;
/// Half-width of the mean that removes sub-metre pressure hash.
///
/// A barometer carries a few tenths of a metre of jitter with a period of a
/// second or two, which no ground produces and which draws a smooth road as a
/// hairy line. Nothing a rider rides turns around that fast: the smallest real
/// feature — a roller, a compression — takes a couple of seconds, and a mean is
/// exactly transparent to a constant grade, so what this removes is curvature
/// shorter than about three seconds and nothing else. Anything faster and
/// steeper is a jump, and jumps are exempted below.
///
/// Measured cost of the width, swept over the rider's own recordings: going
/// from ±0.5 s to ±1.5 s halves the residual jitter on tarmac (0.022 → 0.011 m
/// per sample) and takes 8% off the accumulated totals of a six-hour shuttle
/// day, most of which is the same wander seen from the other side. Past ±1.5 s
/// the jitter barely moves and only the totals keep falling.
const PRESSURE_MEAN_HALF_WINDOW_MS: i64 = 1_500;
/// How far either side of an airtime window the filter stands down.
const AIRTIME_GUARD_MS: i64 = 1_000;
/// The filter never reaches across a hole this long in the pressure trace.
const MAX_PRESSURE_GAP_MS: i64 = 3_000;

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
    /// Conservative post-ride interpretation of this point.
    pub activity_state: ActivityState,
    /// Confidence in `activity_state`, in `[0, 1]`.
    pub activity_confidence: f64,
}

/// Complete derived activity. Safe to delete and rebuild from the raw file.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct CanonicalActivity {
    pub algorithm_version: String,
    pub analysis: RideAnalysis,
    /// Headline totals with transport removed. See [`RideTotals`].
    pub ride: RideTotals,
    pub raw_track: Vec<CanonicalTrackPoint>,
    pub finalized_track: Vec<CanonicalTrackPoint>,
    pub quality: QualitySummary,
}

/// What the rider did, with transport removed.
///
/// The ride's headline numbers must describe riding. A shuttle lap adds tens
/// of kilometres and hundreds of metres of climb that the rider did not
/// produce, and counting them makes every total meaningless. Spans the
/// classifier calls `LikelyMotorized` are therefore excluded from the ride
/// figures and reported separately, so the day is still fully accounted for.
///
/// Descent is the number that matters most here: it is the product's subject,
/// and a lift-served day would otherwise show its own climbs as achievements.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct RideTotals {
    pub distance_m: f64,
    pub moving_time_s: f64,
    pub ascent_m: f64,
    pub descent_m: f64,
    pub max_speed_mps: f64,
    pub avg_moving_speed_mps: f64,
    /// Ground covered in a vehicle, kept so the day still adds up.
    pub transport_distance_m: f64,
    pub transport_time_s: f64,
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
            // Raw GPS is immutable evidence, not a classified artifact.
            activity_state: ActivityState::Unknown,
            activity_confidence: 0.0,
        })
        .collect();

    let vertical = finalized_altitudes(
        &raw_track,
        &recording.baro,
        &analysis.airtime_windows,
        &replay.finalized_track,
    );
    let quality = quality_summary(&raw_track, recording.baro.len(), &vertical);
    let speeds = finalized_speeds(&raw_track, &replay.finalized_track);
    let mut finalized_track: Vec<_> = replay
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
            activity_state: point.activity_state.unwrap_or(ActivityState::Unknown),
            activity_confidence: point.activity_confidence.unwrap_or(0.0),
        })
        .collect();
    let classifications = classify_activity(&finalized_track, &recording.imu);
    for (point, classification) in finalized_track.iter_mut().zip(classifications) {
        point.activity_state = classification.state;
        point.activity_confidence = classification.confidence;
    }

    (analysis.ascent_m, analysis.descent_m) = match vertical.source {
        ElevationSource::Barometric => ascent_descent(&finalized_track),
        ElevationSource::GpsInterpolated => gps_net_ascent_descent(&raw_track),
        ElevationSource::None => (0.0, 0.0),
    };
    analysis.algorithm_version = ALGORITHM_VERSION.to_owned();

    let ride = ride_totals(&finalized_track);

    Ok(CanonicalActivity {
        algorithm_version: ALGORITHM_VERSION.to_owned(),
        analysis,
        ride,
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
    airtime: &[AirtimeWindow],
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

    let mut barometric = relative_barometric_altitudes(baro);
    reject_pressure_impulses(&mut barometric, airtime);
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
    let smoothed_offsets = timed_median_by_section(&offset_anchors, OFFSET_MEDIAN_HALF_WINDOW_MS);
    let gps_by_section = values_by_section(&gps_anchors);
    let offsets_by_section = values_by_section(&smoothed_offsets);

    let mut barometric_points = 0usize;
    let mut gps_points = 0usize;
    let altitudes: Vec<Option<f64>> = finalized
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

/// Removes pressure impulses the ground cannot have produced.
///
/// Runs on the relative barometric series before it is anchored to GPS, so the
/// decision is made once on the signal that actually carries the terrain. See
/// the module documentation for why this is a median and not a mean.
fn reject_pressure_impulses(series: &mut [TimedValue], airtime: &[AirtimeWindow]) {
    if series.len() < 3 {
        return;
    }
    let filtered: Vec<f64> = (0..series.len())
        .map(|index| impulse_free_value(series, airtime, index))
        .collect();
    for (sample, value) in series.iter_mut().zip(filtered) {
        sample.value = value;
    }

    let smoothed: Vec<f64> = (0..series.len())
        .map(|index| hash_free_value(series, airtime, index))
        .collect();
    for (sample, value) in series.iter_mut().zip(smoothed) {
        sample.value = value;
    }
}

/// Averages away pressure jitter too brief and too small to be ground.
fn hash_free_value(series: &[TimedValue], airtime: &[AirtimeWindow], index: usize) -> f64 {
    let center = series[index];
    if airborne_near(airtime, center.timestamp_ms) {
        return center.value;
    }
    let (lo, hi) = symmetric_window(series, index, PRESSURE_MEAN_HALF_WINDOW_MS);
    let window = &series[lo..=hi];
    window.iter().map(|sample| sample.value).sum::<f64>() / window.len() as f64
}

fn impulse_free_value(series: &[TimedValue], airtime: &[AirtimeWindow], index: usize) -> f64 {
    let center = series[index];
    if airborne_near(airtime, center.timestamp_ms) {
        return center.value;
    }

    let (lo, hi) = symmetric_window(series, index, PRESSURE_MEDIAN_HALF_WINDOW_MS);
    let mut window: Vec<f64> = series[lo..=hi].iter().map(|sample| sample.value).collect();
    window.sort_by(f64::total_cmp);
    // The window is symmetric, so its length is always odd and the middle
    // element is the median outright.
    let median = window[window.len() / 2];

    if (center.value - median).abs() > PRESSURE_OUTLIER_M {
        median
    } else {
        center.value
    }
}

/// Widest window around `index` that stays inside `half_window_ms`, never
/// reaches across a hole in the trace, and holds as many samples on one side as
/// the other.
///
/// The symmetry is what keeps both passes honest on a slope: a lopsided window
/// lags or leads a grade, and the places where it is forced lopsided — the ends
/// of the trace, the edges of a pause — are exactly where ascent and descent
/// are anchored.
fn symmetric_window(series: &[TimedValue], index: usize, half_window_ms: i64) -> (usize, usize) {
    let center_ms = series[index].timestamp_ms;
    let mut lo = index;
    while lo > 0
        && series[lo].timestamp_ms - series[lo - 1].timestamp_ms <= MAX_PRESSURE_GAP_MS
        && center_ms - series[lo - 1].timestamp_ms <= half_window_ms
    {
        lo -= 1;
    }
    let mut hi = index;
    while hi + 1 < series.len()
        && series[hi + 1].timestamp_ms - series[hi].timestamp_ms <= MAX_PRESSURE_GAP_MS
        && series[hi + 1].timestamp_ms - center_ms <= half_window_ms
    {
        hi += 1;
    }
    let reach = (index - lo).min(hi - index);
    (index - reach, index + reach)
}

/// Whether a detected jump covers this instant. Being airborne is the one way a
/// rider outruns the filter, so the filter yields to it rather than the other
/// way round.
fn airborne_near(airtime: &[AirtimeWindow], timestamp_ms: i64) -> bool {
    airtime.iter().any(|window| {
        (window.start_ms - AIRTIME_GUARD_MS
            ..window.start_ms + window.duration_ms + AIRTIME_GUARD_MS)
            .contains(&timestamp_ms)
    })
}

/// Median over a fixed span of time rather than a fixed number of samples, so
/// the filter's strength is a property of the signal and not of the fix rate.
fn timed_median_by_section(anchors: &[AltitudeAnchor], half_window_ms: i64) -> Vec<AltitudeAnchor> {
    let mut output = Vec::with_capacity(anchors.len());
    let mut start = 0;
    while start < anchors.len() {
        let section_id = anchors[start].section_id;
        let mut end = start + 1;
        while end < anchors.len() && anchors[end].section_id == section_id {
            end += 1;
        }
        let section = &anchors[start..end];
        for anchor in section {
            let lo = section
                .partition_point(|other| other.timestamp_ms < anchor.timestamp_ms - half_window_ms);
            let hi = section.partition_point(|other| {
                other.timestamp_ms <= anchor.timestamp_ms + half_window_ms
            });
            output.push(AltitudeAnchor {
                // The window always contains `anchor` itself.
                altitude_m: median_altitude(&section[lo..hi]),
                ..*anchor
            });
        }
        start = end;
    }
    output
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

/// Splits the finalized track into ride and transport, applying exactly the
/// accumulators the whole-ride figures use.
///
/// A pair of consecutive points counts as transport when either end is
/// `LikelyMotorized`, so a boundary is never credited to the rider. Everything
/// else — including `STILL` and `UNKNOWN` — stays with the ride, because a
/// stop in the middle of a lap is part of that lap.
pub(crate) fn ride_totals(track: &[CanonicalTrackPoint]) -> RideTotals {
    let motorized =
        |point: &CanonicalTrackPoint| point.activity_state == ActivityState::LikelyMotorized;

    let mut distance_m = 0.0;
    let mut transport_distance_m = 0.0;
    let mut moving_time_ms = 0i64;
    let mut transport_time_ms = 0i64;
    let mut max_speed_mps = 0.0f64;
    // The 1 m anchor filter needs its own anchor per stream, otherwise a lap
    // and the shuttle that follows it would share one and lose the boundary.
    let mut ride_anchor: Option<&CanonicalTrackPoint> = None;
    let mut transport_anchor: Option<&CanonicalTrackPoint> = None;

    for point in track {
        if !motorized(point)
            && let Some(speed) = point.speed_mps.filter(|value| value.is_finite())
        {
            max_speed_mps = max_speed_mps.max(speed);
        }
    }

    for pair in track.windows(2) {
        let (from, to) = (&pair[0], &pair[1]);
        let dt_ms = to.timestamp_ms - from.timestamp_ms;
        if from.section_id != to.section_id || !(1..=MAX_MOVING_GAP_MS).contains(&dt_ms) {
            ride_anchor = None;
            transport_anchor = None;
            continue;
        }
        let in_transport = motorized(from) || motorized(to);
        // Leaving a stream drops its anchor. Keeping it meant the first pair
        // after a shuttle measured from wherever the rider boarded, adding the
        // straight line across the whole climb to the ride's distance — nine
        // kilometres of it on one lift-served day.
        let (anchor, left_behind) = if in_transport {
            (&mut transport_anchor, &mut ride_anchor)
        } else {
            (&mut ride_anchor, &mut transport_anchor)
        };
        *left_behind = None;
        let step = match anchor {
            Some(previous) => geographic_distance_m(previous.lat, previous.lon, to.lat, to.lon),
            None => {
                *anchor = Some(from);
                geographic_distance_m(from.lat, from.lon, to.lat, to.lon)
            }
        };
        if step >= MIN_MOVE_M {
            *anchor = Some(to);
            if in_transport {
                transport_distance_m += step;
            } else {
                distance_m += step;
            }
        }

        let speed = to
            .speed_mps
            .filter(|value| value.is_finite())
            .unwrap_or(step / (dt_ms as f64 / 1_000.0));
        if speed > MOVING_SPEED_MPS {
            if in_transport {
                transport_time_ms += dt_ms;
            } else {
                moving_time_ms += dt_ms;
            }
        }
    }

    let ride_only: Vec<CanonicalTrackPoint> = track
        .iter()
        .filter(|point| !motorized(point))
        .cloned()
        .collect();
    let (ascent_m, descent_m) = ascent_descent(&ride_only);

    let moving_time_s = moving_time_ms as f64 / 1_000.0;
    RideTotals {
        distance_m,
        moving_time_s,
        ascent_m,
        descent_m,
        max_speed_mps,
        avg_moving_speed_mps: if moving_time_s > 0.0 {
            distance_m / moving_time_s
        } else {
            0.0
        },
        transport_distance_m,
        transport_time_s: transport_time_ms as f64 / 1_000.0,
    }
}

pub(crate) fn ascent_descent(track: &[CanonicalTrackPoint]) -> (f64, f64) {
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
    use crate::motion::STANDARD_GRAVITY_MPS2;
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

    const REFERENCE_HPA: f64 = 1_000.0;

    /// Builds a recording where GPS and barometer agree on `relative_altitude_m`
    /// at 1 Hz for `duration_s`, and the IMU reports `accel_magnitude_mps2` at
    /// 20 Hz. Both height sensors carry the same profile because in the field
    /// they do: a real descent shows up in each of them, and only the slow
    /// disagreement between them is offset drift.
    fn vertical_fixture(
        duration_s: i64,
        relative_altitude_m: impl Fn(f64) -> f64,
        accel_magnitude_mps2: impl Fn(f64) -> f64,
    ) -> ParsedRecording {
        let mut recording = ParsedRecording::default();
        for second in 0..=duration_s {
            let timestamp_ms = 1_000 + second * 1_000;
            let elapsed_s = second as f64;
            recording.gps.push(gps(
                timestamp_ms,
                44.8 + elapsed_s * 0.000_02,
                100.0 + relative_altitude_m(elapsed_s),
            ));
            recording.baro.push(BaroSample {
                timestamp_ms,
                pressure_hpa: pressure_for_relative_altitude(
                    REFERENCE_HPA,
                    relative_altitude_m(elapsed_s),
                ),
            });
            for tick in 0..20 {
                let sample_s = elapsed_s + f64::from(tick) / 20.0;
                recording.imu.push(crate::ImuSample {
                    timestamp_ms: timestamp_ms + i64::from(tick) * 50,
                    accel: [0.0, 0.0, accel_magnitude_mps2(sample_s) as f32],
                    gyro: [0.0, 0.0, 0.0],
                    mag: None,
                });
            }
        }
        recording
    }

    fn altitude_at(canonical: &CanonicalActivity, timestamp_ms: i64) -> f64 {
        canonical
            .finalized_track
            .iter()
            .find(|point| point.timestamp_ms == timestamp_ms)
            .unwrap_or_else(|| panic!("no finalized point at {timestamp_ms} ms"))
            .altitude_m
            .expect("finalized altitude")
    }

    #[test]
    fn a_pressure_impulse_never_reaches_the_profile() {
        // What the rider's own asphalt recording actually contains: a flat road
        // and, in the last seconds, the phone coming out of its mount — twelve
        // metres of pressure in one second, and back. No ground does that.
        let recording = vertical_fixture(
            60,
            |elapsed_s| {
                if (30.0..31.0).contains(&elapsed_s) {
                    -12.0
                } else {
                    0.0
                }
            },
            |_| STANDARD_GRAVITY_MPS2,
        );

        // Measured away from the ends of the trace, where the window is forced
        // to shrink to nothing rather than become lopsided (see `impulse_free_value`).
        let canonical = finalize(&recording).unwrap();
        let altitudes: Vec<_> = canonical
            .finalized_track
            .iter()
            .filter(|point| (7_000..=54_000).contains(&point.timestamp_ms))
            .filter_map(|point| point.altitude_m)
            .collect();
        let relief = altitudes.iter().cloned().fold(f64::MIN, f64::max)
            - altitudes.iter().cloned().fold(f64::MAX, f64::min);
        assert!(relief < 0.5, "flat road still has {relief:.2} m of relief");
    }

    #[test]
    fn an_airtime_window_suspends_the_filter() {
        // The same impulse, but this time the accelerometer is in free fall
        // through it. Being airborne is the one way a rider genuinely outruns
        // the filter, so the drop must survive intact.
        let airborne = |elapsed_s: f64| (30.0..31.0).contains(&elapsed_s);
        let recording = vertical_fixture(
            60,
            |elapsed_s| if airborne(elapsed_s) { -12.0 } else { 0.0 },
            |sample_s| {
                if airborne(sample_s) {
                    0.0
                } else {
                    STANDARD_GRAVITY_MPS2
                }
            },
        );

        assert!(
            !analyze(&recording).unwrap().airtime_windows.is_empty(),
            "fixture must contain a detected jump"
        );
        let canonical = finalize(&recording).unwrap();
        let bottom = altitude_at(&canonical, 31_000);
        assert!(
            (bottom - 88.0).abs() < 1.0,
            "airborne drop was filtered to {bottom:.2} m"
        );
    }

    #[test]
    fn a_steady_grade_passes_through_untouched() {
        // A symmetric median is transparent to a constant slope; this is what
        // lets the filter run everywhere without inventing or eating descent.
        let recording = vertical_fixture(60, |elapsed_s| -elapsed_s, |_| STANDARD_GRAVITY_MPS2);

        let canonical = finalize(&recording).unwrap();
        let middle = altitude_at(&canonical, 31_000);
        let start = altitude_at(&canonical, 1_000);
        assert!(
            (start - middle - 30.0).abs() < 0.5,
            "steady grade bent: {start:.2} m to {middle:.2} m over 30 s"
        );
        // 59 m of an exact 60 m ramp: the endpoints of the trace and the f32
        // pressures the device records, both present with the filter removed.
        assert!(
            (canonical.analysis.descent_m - 60.0).abs() < 1.5,
            "descent was {:.2} m",
            canonical.analysis.descent_m
        );
        assert!(canonical.analysis.ascent_m < 0.5);
    }

    #[test]
    fn a_real_drop_survives_as_a_step() {
        // Twenty seconds of nothing, a 3 m drop, then nothing again. A median
        // holds a step edge without needing to be told the drop is there — the
        // airtime exemption is for impulses, not for every feature.
        let drop_at_s = 20.0;
        let recording = vertical_fixture(
            40,
            |elapsed_s| {
                if elapsed_s <= drop_at_s { 0.0 } else { -3.0 }
            },
            |_| STANDARD_GRAVITY_MPS2,
        );

        let canonical = finalize(&recording).unwrap();
        let before = altitude_at(&canonical, 15_000);
        let after = altitude_at(&canonical, 27_000);
        assert!(
            (before - after - 3.0).abs() < 0.5,
            "drop flattened to {:.2} m",
            before - after
        );
    }

    #[test]
    fn a_gps_altitude_excursion_never_reaches_the_profile() {
        // The barometer is steady, so the ten-metre step in the GPS altitudes is
        // an artifact of the fix, not of the ground.
        let mut recording = vertical_fixture(120, |_| 0.0, |_| STANDARD_GRAVITY_MPS2);
        for point in &mut recording.gps {
            if (60_000..=66_000).contains(&point.timestamp_ms) {
                point.altitude_m = Some(111.0);
            }
        }

        let canonical = finalize(&recording).unwrap();
        let worst = canonical
            .finalized_track
            .iter()
            .filter_map(|point| point.altitude_m)
            .fold(0.0f64, |worst, altitude| {
                worst.max((altitude - 100.0).abs())
            });
        assert!(
            worst < 1.0,
            "GPS excursion moved the profile by {worst:.2} m"
        );
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
        assert!(canonical.raw_track.iter().all(|point| {
            point.activity_state == ActivityState::Unknown && point.activity_confidence == 0.0
        }));

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
        // A ten-metre rise and fall over twelve seconds — around 1.7 m/s, which
        // a rider produces and the filter must therefore keep. The shape is
        // carried by the barometer; the deliberately unphysical version of this
        // fixture, ten metres in one second and back, is now an impulse and is
        // covered by `a_pressure_impulse_never_reaches_the_profile` instead.
        let recording = vertical_fixture(
            12,
            |elapsed_s| 10.0 * (elapsed_s * std::f64::consts::PI / 12.0).sin(),
            |_| STANDARD_GRAVITY_MPS2,
        );

        let canonical = finalize(&recording).unwrap();
        let peak = canonical
            .finalized_track
            .iter()
            .find(|point| point.timestamp_ms == 7_000)
            .unwrap()
            .altitude_m
            .unwrap();
        assert!((peak - 110.0).abs() < 0.2, "barometric peak was {peak}");
        assert_eq!(
            canonical.quality.elevation_source,
            ElevationSource::Barometric
        );
        assert_eq!(canonical.quality.baro_sample_count, 13);
        // The totals come out about 1.3 m under the true 10 m because the GPS
        // anchors are median-filtered before they are used, which clips the
        // crest of a curve that turns around this fast. That is the anchor
        // filter's doing, not the pressure filter's, and it is unrelated to
        // what this test is about.
        assert!((canonical.analysis.ascent_m - 10.0).abs() < 1.5);
        assert!((canonical.analysis.descent_m - 10.0).abs() < 1.5);
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

    #[test]
    fn transport_is_excluded_from_the_ride_totals() {
        fn leg(
            start_ms: i64,
            start_m: f64,
            seconds: i64,
            start_altitude_m: f64,
            vertical_speed_mps: f64,
            state: ActivityState,
        ) -> Vec<CanonicalTrackPoint> {
            (0..=seconds)
                .map(|second| CanonicalTrackPoint {
                    timestamp_ms: start_ms + second * 1_000,
                    lat: 41.7 + ((start_m + second as f64 * 8.0) / 6_371_000.0).to_degrees(),
                    lon: 44.8,
                    altitude_m: Some(start_altitude_m + vertical_speed_mps * second as f64),
                    accuracy_m: Some(4.0),
                    speed_mps: Some(8.0),
                    stationary: Some(false),
                    section_id: 0,
                    activity_state: state,
                    activity_confidence: 0.9,
                })
                .collect()
        }

        // A shuttle up 200 m, then a run down 200 m. Only the run is the ride.
        let mut track = leg(0, 0.0, 100, 300.0, 2.0, ActivityState::LikelyMotorized);
        track.extend(leg(
            101_000,
            808.0,
            100,
            500.0,
            -2.0,
            ActivityState::Downhill,
        ));

        let totals = ride_totals(&track);

        assert!(
            totals.ascent_m < 5.0,
            "the shuttle's climb was credited to the rider: {} m",
            totals.ascent_m,
        );
        assert!(
            (190.0..=210.0).contains(&totals.descent_m),
            "the run's descent is wrong: {} m",
            totals.descent_m,
        );
        assert!(
            (750.0..=850.0).contains(&totals.distance_m),
            "ride distance {} m includes the shuttle",
            totals.distance_m,
        );
        assert!(
            (750.0..=850.0).contains(&totals.transport_distance_m),
            "the shuttle was not accounted for: {} m",
            totals.transport_distance_m,
        );
        assert!(totals.moving_time_s < 105.0 && totals.moving_time_s > 90.0);
        assert!(totals.transport_time_s > 90.0);
    }
}
