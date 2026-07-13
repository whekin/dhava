//! GPS-bounded post-ride interpolation.
//!
//! This is deliberately not free inertial navigation. Accepted GPS fixes are
//! immutable anchors; GPS geometry supplies safe tangents, while IMU yaw and
//! endpoint speeds only distribute 5 Hz samples inside each bounded interval.

use std::collections::HashMap;

use crate::orientation::Mahony;
use crate::replay::DiagnosticTrackPoint;
use crate::{GpsPoint, ImuSample};

const EARTH_RADIUS_M: f64 = 6_371_000.0;
const OUTPUT_INTERVAL_MS: i64 = 200;
const MAX_INTERPOLATED_GAP_MS: i64 = 2_500;
const MIN_MOVING_DISTANCE_M: f64 = 0.35;
const DEPARTURE_SPEED_MPS: f64 = 0.3;
const DEPARTURE_BACKTRACK_TOLERANCE_M: f64 = 1.5;
const YAW_RATE_DEADBAND_RAD_S: f64 = 0.06;
const MIN_YAW_EVIDENCE_RAD: f64 = 0.08;
const MAX_YAW_TIME_WEIGHT: f64 = 0.3;
const MAX_CORRIDOR_M: f64 = 6.0;

#[derive(Debug, Clone, Copy)]
struct Anchor {
    timestamp_ms: i64,
    lat: f64,
    lon: f64,
    raw_lat: f64,
    raw_lon: f64,
    accuracy_m: f64,
    speed_mps: Option<f64>,
    stationary: bool,
    section_id: i32,
}

#[derive(Debug, Clone, Copy)]
struct YawTracePoint {
    timestamp_ms: i64,
    positive_rad: f64,
    negative_rad: f64,
}

pub(crate) fn finalized_track(
    gps: &[GpsPoint],
    live_track: &[DiagnosticTrackPoint],
    section_ids: &[i32],
    imu: &[ImuSample],
) -> Vec<DiagnosticTrackPoint> {
    let live_by_timestamp: HashMap<_, _> = live_track
        .iter()
        .map(|point| (point.timestamp_ms, point))
        .collect();
    let mut anchors: Vec<_> = gps
        .iter()
        .zip(section_ids)
        .filter_map(|(raw, section_id)| {
            let live = live_by_timestamp.get(&raw.timestamp_ms)?;
            Some(Anchor {
                timestamp_ms: raw.timestamp_ms,
                lat: live.lat,
                lon: live.lon,
                raw_lat: raw.lat,
                raw_lon: raw.lon,
                accuracy_m: live.accuracy_m.unwrap_or(20.0),
                speed_mps: raw.speed_mps.map(f64::from),
                stationary: live.stationary.unwrap_or(false),
                section_id: *section_id,
            })
        })
        .collect();
    backfill_confirmed_departures(&mut anchors);
    interpolate_anchors(&anchors, &build_yaw_trace(imu))
}

/// Live STILL is causal and intentionally conservative. Once a later GPS fix
/// proves that the rider departed, restore the monotonic moving tail which led
/// to that release instead of drawing a chord over those GPS anchors.
fn backfill_confirmed_departures(anchors: &mut [Anchor]) {
    let mut start = 0;
    while start < anchors.len() {
        if !anchors[start].stationary {
            start += 1;
            continue;
        }
        let section_id = anchors[start].section_id;
        let mut end = start;
        while end + 1 < anchors.len()
            && anchors[end + 1].stationary
            && anchors[end + 1].section_id == section_id
        {
            end += 1;
        }
        let release = end + 1;
        if release < anchors.len()
            && anchors[release].section_id == section_id
            && !anchors[release].stationary
        {
            let stop = [anchors[start].lat, anchors[start].lon];
            let release_raw = [anchors[release].raw_lat, anchors[release].raw_lon];
            let release_distance = geographic_distance(stop, release_raw);
            let uncertainty = anchors[start]
                .accuracy_m
                .hypot(anchors[release].accuracy_m)
                .max(3.0);
            if release_distance > uncertainty {
                let mut departure = end;
                while departure > start {
                    let candidate = departure - 1;
                    if anchors[candidate].speed_mps.unwrap_or(0.0) < DEPARTURE_SPEED_MPS {
                        break;
                    }
                    let candidate_distance = geographic_distance(
                        stop,
                        [anchors[candidate].raw_lat, anchors[candidate].raw_lon],
                    );
                    let next_distance = geographic_distance(
                        stop,
                        [anchors[departure].raw_lat, anchors[departure].raw_lon],
                    );
                    if candidate_distance > next_distance + DEPARTURE_BACKTRACK_TOLERANCE_M {
                        break;
                    }
                    departure = candidate;
                }
                for anchor in &mut anchors[departure..=end] {
                    anchor.lat = anchor.raw_lat;
                    anchor.lon = anchor.raw_lon;
                    anchor.stationary = false;
                }
            }
        }
        start = end + 1;
    }
}

fn interpolate_anchors(
    anchors: &[Anchor],
    yaw_trace: &[YawTracePoint],
) -> Vec<DiagnosticTrackPoint> {
    if anchors.is_empty() {
        return Vec::new();
    }
    let mut output = Vec::with_capacity(anchors.len().saturating_mul(5));
    for (index, anchor) in anchors.iter().enumerate() {
        output.push(to_diagnostic(*anchor));
        let Some(next) = anchors.get(index + 1).copied() else {
            continue;
        };
        let dt_ms = next.timestamp_ms - anchor.timestamp_ms;
        if next.section_id != anchor.section_id
            || !(OUTPUT_INTERVAL_MS + 1..=MAX_INTERPOLATED_GAP_MS).contains(&dt_ms)
        {
            continue;
        }
        let chord = project(next.lat, next.lon, anchor.lat, anchor.lon);
        let chord_length = norm(chord);
        if chord_length < MIN_MOVING_DISTANCE_M || (anchor.stationary && next.stationary) {
            continue;
        }

        let previous = previous_distinct_anchor(anchors, index);
        let following = next_distinct_anchor(anchors, index + 1);
        let tangent_start = tangent(
            previous.map(|point| project(point.lat, point.lon, anchor.lat, anchor.lon)),
            chord,
            anchor.speed_mps,
            dt_ms,
            chord_length,
        );
        let tangent_end = tangent(
            Some([0.0, 0.0]),
            following.map_or(chord, |point| {
                project(point.lat, point.lon, anchor.lat, anchor.lon)
            }),
            next.speed_mps,
            dt_ms,
            chord_length,
        );
        let turn_angle = signed_angle(tangent_start, tangent_end);
        let corridor_m = anchor
            .accuracy_m
            .hypot(next.accuracy_m)
            .clamp(1.5, MAX_CORRIDOR_M);
        let mut previous_along = 0.0;

        let mut timestamp_ms = anchor.timestamp_ms + OUTPUT_INTERVAL_MS;
        while timestamp_ms < next.timestamp_ms {
            let time_fraction = (timestamp_ms - anchor.timestamp_ms) as f64 / dt_ms as f64;
            let speed_fraction = speed_progress(time_fraction, anchor.speed_mps, next.speed_mps);
            let curve_fraction = yaw_progress(
                yaw_trace,
                anchor.timestamp_ms,
                timestamp_ms,
                next.timestamp_ms,
                turn_angle,
            )
            .map_or(speed_fraction, |yaw_fraction| {
                let weight = (turn_angle.abs() / std::f64::consts::FRAC_PI_2).clamp(0.0, 1.0)
                    * MAX_YAW_TIME_WEIGHT;
                speed_fraction * (1.0 - weight) + yaw_fraction * weight
            })
            .clamp(0.0, 1.0);
            let candidate = hermite(chord, tangent_start, tangent_end, curve_fraction);
            let constrained = constrain_to_corridor(
                candidate,
                chord,
                chord_length,
                corridor_m,
                &mut previous_along,
            );
            let (lat, lon) = unproject(constrained, anchor.lat, anchor.lon);
            output.push(DiagnosticTrackPoint {
                timestamp_ms,
                lat,
                lon,
                accuracy_m: Some(
                    anchor.accuracy_m + (next.accuracy_m - anchor.accuracy_m) * time_fraction,
                ),
                stationary: Some(false),
                section_id: anchor.section_id,
            });
            timestamp_ms += OUTPUT_INTERVAL_MS;
        }
    }
    output.sort_by_key(|point| point.timestamp_ms);
    output
}

fn previous_distinct_anchor(anchors: &[Anchor], index: usize) -> Option<Anchor> {
    let current = anchors[index];
    anchors[..index].iter().rev().copied().find(|point| {
        point.section_id == current.section_id
            && geographic_distance([point.lat, point.lon], [current.lat, current.lon])
                >= MIN_MOVING_DISTANCE_M
    })
}

fn next_distinct_anchor(anchors: &[Anchor], index: usize) -> Option<Anchor> {
    let current = anchors[index];
    anchors[index + 1..].iter().copied().find(|point| {
        point.section_id == current.section_id
            && geographic_distance([point.lat, point.lon], [current.lat, current.lon])
                >= MIN_MOVING_DISTANCE_M
    })
}

fn tangent(
    from: Option<[f64; 2]>,
    to: [f64; 2],
    speed_mps: Option<f64>,
    dt_ms: i64,
    chord_length: f64,
) -> [f64; 2] {
    let direction = from.map_or(to, |from| [to[0] - from[0], to[1] - from[1]]);
    let direction_norm = norm(direction);
    if direction_norm < 1e-6 {
        return [0.0, 0.0];
    }
    let expected_distance = speed_mps
        .filter(|speed| speed.is_finite() && *speed >= 0.0)
        .map_or(chord_length, |speed| speed * dt_ms as f64 / 1_000.0)
        .clamp(0.0, chord_length * 1.5);
    [
        direction[0] / direction_norm * expected_distance,
        direction[1] / direction_norm * expected_distance,
    ]
}

fn hermite(chord: [f64; 2], start: [f64; 2], end: [f64; 2], u: f64) -> [f64; 2] {
    let u2 = u * u;
    let u3 = u2 * u;
    let h10 = u3 - 2.0 * u2 + u;
    let h01 = -2.0 * u3 + 3.0 * u2;
    let h11 = u3 - u2;
    [
        h10 * start[0] + h01 * chord[0] + h11 * end[0],
        h10 * start[1] + h01 * chord[1] + h11 * end[1],
    ]
}

fn constrain_to_corridor(
    candidate: [f64; 2],
    chord: [f64; 2],
    chord_length: f64,
    corridor_m: f64,
    previous_along: &mut f64,
) -> [f64; 2] {
    let forward = [chord[0] / chord_length, chord[1] / chord_length];
    let lateral = [-forward[1], forward[0]];
    let along = dot(candidate, forward).clamp(*previous_along, chord_length);
    *previous_along = along;
    let side = dot(candidate, lateral).clamp(-corridor_m, corridor_m);
    [
        forward[0] * along + lateral[0] * side,
        forward[1] * along + lateral[1] * side,
    ]
}

fn speed_progress(u: f64, start: Option<f64>, end: Option<f64>) -> f64 {
    let (Some(start), Some(end)) = (start, end) else {
        return u;
    };
    let start = start.max(0.0);
    let end = end.max(0.0);
    let total = 0.5 * (start + end);
    if total < 0.1 {
        u
    } else {
        (start * u + 0.5 * (end - start) * u * u) / total
    }
}

fn build_yaw_trace(imu: &[ImuSample]) -> Vec<YawTracePoint> {
    let mut attitude = Mahony::new();
    let mut previous_ms = None;
    let mut positive_rad = 0.0;
    let mut negative_rad = 0.0;
    let mut trace = Vec::with_capacity(imu.len());
    for sample in imu {
        let Some(dt) =
            previous_ms.map(|previous| (sample.timestamp_ms - previous) as f64 / 1_000.0)
        else {
            previous_ms = Some(sample.timestamp_ms);
            continue;
        };
        previous_ms = Some(sample.timestamp_ms);
        let accel = sample.accel.map(f64::from);
        let gyro = sample.gyro.map(f64::from);
        attitude.update(accel, gyro, dt);
        if !(0.0..=0.5).contains(&dt) || !attitude.is_initialized() {
            continue;
        }
        let vertical_rate = attitude.vertical_angular_rate(gyro);
        if vertical_rate > YAW_RATE_DEADBAND_RAD_S {
            positive_rad += (vertical_rate - YAW_RATE_DEADBAND_RAD_S) * dt;
        } else if vertical_rate < -YAW_RATE_DEADBAND_RAD_S {
            negative_rad += (-vertical_rate - YAW_RATE_DEADBAND_RAD_S) * dt;
        }
        trace.push(YawTracePoint {
            timestamp_ms: sample.timestamp_ms,
            positive_rad,
            negative_rad,
        });
    }
    trace
}

fn yaw_progress(
    trace: &[YawTracePoint],
    start_ms: i64,
    timestamp_ms: i64,
    end_ms: i64,
    turn_angle: f64,
) -> Option<f64> {
    if turn_angle.abs() < 0.08 {
        return None;
    }
    let start = yaw_value_at(trace, start_ms, turn_angle.is_sign_positive());
    let current = yaw_value_at(trace, timestamp_ms, turn_angle.is_sign_positive());
    let end = yaw_value_at(trace, end_ms, turn_angle.is_sign_positive());
    let total = end - start;
    (total >= MIN_YAW_EVIDENCE_RAD).then(|| ((current - start) / total).clamp(0.0, 1.0))
}

fn yaw_value_at(trace: &[YawTracePoint], timestamp_ms: i64, positive: bool) -> f64 {
    let index = trace.partition_point(|point| point.timestamp_ms <= timestamp_ms);
    let Some(point) = index.checked_sub(1).and_then(|index| trace.get(index)) else {
        return 0.0;
    };
    if positive {
        point.positive_rad
    } else {
        point.negative_rad
    }
}

fn signed_angle(a: [f64; 2], b: [f64; 2]) -> f64 {
    (a[0] * b[1] - a[1] * b[0]).atan2(dot(a, b))
}

fn to_diagnostic(anchor: Anchor) -> DiagnosticTrackPoint {
    DiagnosticTrackPoint {
        timestamp_ms: anchor.timestamp_ms,
        lat: anchor.lat,
        lon: anchor.lon,
        accuracy_m: Some(anchor.accuracy_m),
        stationary: Some(anchor.stationary),
        section_id: anchor.section_id,
    }
}

fn project(lat: f64, lon: f64, lat0: f64, lon0: f64) -> [f64; 2] {
    [
        (lon - lon0).to_radians() * lat0.to_radians().cos() * EARTH_RADIUS_M,
        (lat - lat0).to_radians() * EARTH_RADIUS_M,
    ]
}

fn unproject(en: [f64; 2], lat0: f64, lon0: f64) -> (f64, f64) {
    (
        lat0 + (en[1] / EARTH_RADIUS_M).to_degrees(),
        lon0 + (en[0] / (EARTH_RADIUS_M * lat0.to_radians().cos())).to_degrees(),
    )
}

fn geographic_distance(a: [f64; 2], b: [f64; 2]) -> f64 {
    norm(project(b[0], b[1], a[0], a[1]))
}

fn dot(a: [f64; 2], b: [f64; 2]) -> f64 {
    a[0] * b[0] + a[1] * b[1]
}

fn norm(value: [f64; 2]) -> f64 {
    dot(value, value).sqrt()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn anchor(timestamp_ms: i64, en: [f64; 2], stationary: bool) -> Anchor {
        let (lat, lon) = unproject(en, 41.7, 44.8);
        Anchor {
            timestamp_ms,
            lat,
            lon,
            raw_lat: lat,
            raw_lon: lon,
            accuracy_m: 4.0,
            speed_mps: Some(if stationary { 0.0 } else { 10.0 }),
            stationary,
            section_id: 0,
        }
    }

    #[test]
    fn interpolation_adds_five_hz_points_and_preserves_every_anchor() {
        let anchors = vec![
            anchor(0, [0.0, 0.0], false),
            anchor(1_000, [0.0, 10.0], false),
            anchor(2_000, [10.0, 20.0], false),
            anchor(3_000, [20.0, 20.0], false),
        ];
        let track = interpolate_anchors(&anchors, &[]);

        assert_eq!(track.len(), 16);
        for expected in &anchors {
            let actual = track
                .iter()
                .find(|point| point.timestamp_ms == expected.timestamp_ms)
                .unwrap();
            assert!(
                geographic_distance([actual.lat, actual.lon], [expected.lat, expected.lon]) < 0.001
            );
        }
        let middle = track
            .iter()
            .find(|point| point.timestamp_ms == 1_400)
            .unwrap();
        let chord_fraction = 0.4;
        let linear = unproject(
            [10.0 * chord_fraction, 10.0 + 10.0 * chord_fraction],
            41.7,
            44.8,
        );
        assert!(geographic_distance([middle.lat, middle.lon], [linear.0, linear.1]) > 0.05);
    }

    #[test]
    fn confirmed_departure_restores_moving_gps_tail() {
        let mut anchors = vec![
            anchor(0, [0.0, 0.0], true),
            anchor(1_000, [0.0, 0.0], true),
            anchor(2_000, [0.0, 0.0], true),
            anchor(3_000, [9.0, 0.0], false),
        ];
        let departing_raw = unproject([3.0, 0.0], 41.7, 44.8);
        anchors[2].raw_lat = departing_raw.0;
        anchors[2].raw_lon = departing_raw.1;
        anchors[2].speed_mps = Some(2.0);

        backfill_confirmed_departures(&mut anchors);

        assert!(!anchors[2].stationary);
        assert!(
            geographic_distance(
                [anchors[2].lat, anchors[2].lon],
                [departing_raw.0, departing_raw.1],
            ) < 0.001
        );
        assert!(anchors[1].stationary);
    }

    #[test]
    fn yaw_progress_uses_turn_timing_inside_interval() {
        let trace = vec![
            YawTracePoint {
                timestamp_ms: 0,
                positive_rad: 0.0,
                negative_rad: 0.0,
            },
            YawTracePoint {
                timestamp_ms: 200,
                positive_rad: 0.0,
                negative_rad: 0.4,
            },
            YawTracePoint {
                timestamp_ms: 1_000,
                positive_rad: 0.0,
                negative_rad: 0.5,
            },
        ];
        let progress = yaw_progress(&trace, 0, 200, 1_000, -0.8).unwrap();
        assert!((progress - 0.8).abs() < 1e-9);
        assert_eq!(yaw_progress(&trace, 0, 200, 1_000, 0.8), None);
    }
}
