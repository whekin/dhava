//! Stateful, low-cost fusion for the recording screen.
//!
//! This uses the same orientation and EKF primitives as canonical analysis.
//! Android may feed IMU at a reduced rate; snapshots are produced only for
//! GPS fixes, so background recording does not pay any map/UI cost.

use std::collections::VecDeque;
use std::sync::Mutex;

use crate::ekf::Ekf;
use crate::orientation::{GRAVITY, Mahony};

const EARTH_RADIUS_M: f64 = 6_371_000.0;
const MAX_GPS_ACCURACY_M: f64 = 20.0;
const STATIONARY_WINDOW_MS: i64 = 700;
// Window means, not per-sample maxima: the OnePlus 9 Pro gyro produces
// isolated ~0.3 rad/s spikes while sitting untouched (median ~0.03). Requiring
// every sample to pass prevented ZUPT forever on real hardware.
const STATIONARY_ACCEL_MEAN_MAX_MPS2: f64 = 0.45;
const STATIONARY_GYRO_MEAN_MAX_RAD_S: f64 = 0.15;
const MIN_STATIONARY_SAMPLES: usize = 12;
const ENTER_STILL_MS: i64 = 500;
const EXIT_STILL_MS: i64 = 250;
/// GPS motion wins over a calm IMU. A phone resting in a bus can have nearly
/// perfect stationary accelerometer/gyro statistics while moving at 40 km/h.
const GPS_DERIVED_MOVING_SPEED_MPS: f64 = 0.7;
const GPS_SPEED_CORROBORATION_MPS: f64 = 1.5;
const GPS_MOTION_HOLD_MS: i64 = 2_500;
/// No trustworthy yaw/roughness model exists yet. Full horizontal IMU input
/// inflated three field tracks by 6-17x and produced 186-495 m sawteeth.
/// Keep the vertical inertial channel, but make horizontal live position a
/// GPS-smoothed estimate until a bounded bike-frame model is validated.
const HORIZONTAL_ACCEL_GAIN: f64 = 0.0;
/// A fused output may smooth within the GPS uncertainty region, never escape
/// hundreds of meters from a fresh fix.
const MIN_LIVE_GPS_ENVELOPE_M: f64 = 12.0;
const LIVE_GPS_ENVELOPE_SIGMA: f64 = 2.5;

#[derive(Debug, Clone, uniffi::Record)]
pub struct LiveSnapshot {
    pub timestamp_ms: i64,
    pub lat: f64,
    pub lon: f64,
    pub altitude_m: Option<f64>,
    pub speed_mps: f64,
    pub stationary: bool,
    pub accuracy_m: f64,
}

#[derive(Debug, Clone, Copy)]
struct MotionSample {
    timestamp_ms: i64,
    accel_error: f64,
    gyro_norm: f64,
}

#[derive(Debug, Clone, Copy)]
struct GpsFix {
    timestamp_ms: i64,
    lat: f64,
    lon: f64,
    accuracy_m: f64,
}

#[derive(Debug)]
struct State {
    orientation: Mahony,
    ekf: Option<Ekf>,
    anchor: Option<(f64, f64, Option<f64>)>,
    last_imu_ms: Option<i64>,
    motion: VecDeque<MotionSample>,
    stationary: bool,
    calm_since_ms: Option<i64>,
    motion_since_ms: Option<i64>,
    gps_motion_hold_until_ms: i64,
    last_gps_fix: Option<GpsFix>,
    still_gps_anchor: Option<GpsFix>,
}

#[derive(Debug, uniffi::Object)]
pub struct LiveFusion {
    state: Mutex<State>,
}

#[uniffi::export]
impl LiveFusion {
    #[uniffi::constructor]
    pub fn new() -> Self {
        Self {
            state: Mutex::new(State {
                orientation: Mahony::new(),
                ekf: None,
                anchor: None,
                last_imu_ms: None,
                motion: VecDeque::new(),
                stationary: false,
                calm_since_ms: None,
                motion_since_ms: None,
                gps_motion_hold_until_ms: i64::MIN,
                last_gps_fix: None,
                still_gps_anchor: None,
            }),
        }
    }

    pub fn push_imu(&self, timestamp_ms: i64, accel: Vec<f64>, gyro: Vec<f64>) -> bool {
        if accel.len() != 3 || gyro.len() != 3 {
            return false;
        }
        let a = [accel[0], accel[1], accel[2]];
        let g = [gyro[0], gyro[1], gyro[2]];
        let mut s = self.state.lock().expect("live fusion mutex poisoned");
        let dt = s.last_imu_ms.map(|t| (timestamp_ms - t) as f64 / 1000.0);
        s.last_imu_ms = Some(timestamp_ms);
        let Some(dt) = dt else { return false };
        s.orientation.update(a, g, dt);

        // A process restart, manual pause or sensor stall must not integrate
        // stale acceleration across the gap. Mahony re-anchors itself; the
        // motion window and EKF prediction restart on the next normal sample.
        if dt > 0.5 {
            s.motion.clear();
            s.calm_since_ms = None;
            s.motion_since_ms = None;
            s.stationary = false;
            return false;
        }

        let accel_norm = norm(a);
        s.motion.push_back(MotionSample {
            timestamp_ms,
            accel_error: (accel_norm - GRAVITY).abs(),
            gyro_norm: norm(g),
        });
        while s
            .motion
            .front()
            .is_some_and(|x| timestamp_ms - x.timestamp_ms > STATIONARY_WINDOW_MS)
        {
            s.motion.pop_front();
        }
        if s.motion.len() >= MIN_STATIONARY_SAMPLES {
            let count = s.motion.len() as f64;
            let accel_mean = s.motion.iter().map(|x| x.accel_error).sum::<f64>() / count;
            let gyro_mean = s.motion.iter().map(|x| x.gyro_norm).sum::<f64>() / count;
            let calm = accel_mean < STATIONARY_ACCEL_MEAN_MAX_MPS2
                && gyro_mean < STATIONARY_GYRO_MEAN_MAX_RAD_S;
            let gps_reports_motion = timestamp_ms <= s.gps_motion_hold_until_ms;
            if calm && !gps_reports_motion {
                s.motion_since_ms = None;
                let since = *s.calm_since_ms.get_or_insert(timestamp_ms);
                if timestamp_ms - since >= ENTER_STILL_MS {
                    s.stationary = true;
                    if s.still_gps_anchor.is_none() {
                        s.still_gps_anchor = s.last_gps_fix;
                    }
                }
            } else {
                s.calm_since_ms = None;
                let since = *s.motion_since_ms.get_or_insert(timestamp_ms);
                if gps_reports_motion || timestamp_ms - since >= EXIT_STILL_MS {
                    s.stationary = false;
                    s.still_gps_anchor = None;
                }
            }
        } else {
            s.stationary = false;
        }

        let mut linear = s.orientation.world_linear_accel(a);
        linear[0] *= HORIZONTAL_ACCEL_GAIN;
        linear[1] *= HORIZONTAL_ACCEL_GAIN;
        let stationary = s.stationary;
        if let Some(ekf) = &mut s.ekf {
            ekf.predict(linear, dt.min(0.2));
            if stationary {
                ekf.update_zupt();
            }
        }
        s.stationary
    }

    #[allow(clippy::too_many_arguments)]
    pub fn push_gps(
        &self,
        timestamp_ms: i64,
        lat: f64,
        lon: f64,
        altitude_m: Option<f64>,
        accuracy_m: Option<f64>,
        speed_mps: Option<f64>,
        bearing_deg: Option<f64>,
    ) -> Option<LiveSnapshot> {
        let accuracy = accuracy_m.unwrap_or(MAX_GPS_ACCURACY_M);
        if !lat.is_finite() || !lon.is_finite() {
            return None;
        }
        let mut s = self.state.lock().expect("live fusion mutex poisoned");
        let reported_velocity = velocity_en(speed_mps, bearing_deg);
        let motion_anchor = if s.stationary {
            s.still_gps_anchor.or(s.last_gps_fix)
        } else {
            s.last_gps_fix
        };
        let derived_velocity = motion_anchor.and_then(|previous| {
            derived_gps_velocity(
                previous,
                timestamp_ms,
                lat,
                lon,
                accuracy,
                speed_mps
                    .is_some_and(|speed| speed.is_finite() && speed >= GPS_SPEED_CORROBORATION_MPS),
            )
        });
        // Android's reported speed is deliberately not enough by itself: a
        // stationary OnePlus reported up to 2.8 m/s. Earth-relative position
        // displacement beyond the accuracy radius is the corroboration that
        // distinguishes a smooth bus from a drifting chair.
        let gps_reports_motion = derived_velocity
            .is_some_and(|velocity| velocity[0].hypot(velocity[1]) >= GPS_DERIVED_MOVING_SPEED_MPS);
        let gps_released_stationary = gps_reports_motion && s.stationary;
        if gps_reports_motion {
            s.gps_motion_hold_until_ms = timestamp_ms.saturating_add(GPS_MOTION_HOLD_MS);
            s.stationary = false;
            s.calm_since_ms = None;
            s.still_gps_anchor = None;
        }
        if accuracy > MAX_GPS_ACCURACY_M {
            return None;
        }
        let current_fix = GpsFix {
            timestamp_ms,
            lat,
            lon,
            accuracy_m: accuracy,
        };
        s.last_gps_fix = Some(current_fix);
        let measured_velocity = reported_velocity.or(derived_velocity);
        if s.ekf.is_none() {
            let vel = if s.stationary {
                Some([0.0, 0.0])
            } else {
                measured_velocity
            };
            s.ekf = Some(Ekf::new(accuracy, vel));
            s.anchor = Some((lat, lon, altitude_m));
        } else {
            let anchor = s.anchor.expect("EKF has anchor");
            let en = project(lat, lon, anchor.0, anchor.1);
            let stationary = s.stationary;
            let ekf = s.ekf.as_mut().expect("checked above");
            if stationary {
                // True earth-relative STILL: GPS jitter is a noisy
                // measurement of a position we already know. Holding the
                // state prevents the live map drawing flowers around a stop.
                ekf.update_zupt();
            } else if gps_released_stationary {
                // A calm device on a moving platform can accumulate an
                // extremely confident ZUPT state. Once earth-relative GPS
                // displacement disproves STILL, recover position and velocity
                // atomically instead of rejecting the real speed for several
                // more fixes.
                ekf.reseat_horizontal(en, measured_velocity, accuracy);
            } else {
                ekf.update_gps_position(en, accuracy);
                if let (Some(alt), Some(anchor_alt)) = (altitude_m, anchor.2) {
                    ekf.update_gps_altitude(alt - anchor_alt, accuracy);
                }
                if let Some(vel) = measured_velocity {
                    ekf.update_gps_velocity(vel);
                }
                let p = ekf.position();
                let offset_m = (p[0] - en[0]).hypot(p[1] - en[1]);
                let envelope_m = (accuracy * LIVE_GPS_ENVELOPE_SIGMA).max(MIN_LIVE_GPS_ENVELOPE_M);
                if offset_m > envelope_m {
                    ekf.reseat_horizontal(en, measured_velocity, accuracy);
                }
            }
        }

        let anchor = s.anchor.expect("initialized above");
        let stationary = s.stationary;
        let ekf = s.ekf.as_ref().expect("initialized above");
        let p = ekf.position();
        let v = ekf.velocity();
        let (out_lat, out_lon) = unproject(p[0], p[1], anchor.0, anchor.1);
        let fused_speed = v[0].hypot(v[1]).max(0.0);
        Some(LiveSnapshot {
            timestamp_ms,
            lat: out_lat,
            lon: out_lon,
            altitude_m: anchor.2.map(|a| a + p[2]),
            speed_mps: if stationary { 0.0 } else { fused_speed },
            stationary,
            accuracy_m: accuracy,
        })
    }
}

fn derived_gps_velocity(
    previous: GpsFix,
    timestamp_ms: i64,
    lat: f64,
    lon: f64,
    accuracy_m: f64,
    speed_corroborates_motion: bool,
) -> Option<[f64; 2]> {
    let dt = (timestamp_ms - previous.timestamp_ms) as f64 / 1000.0;
    if !(0.2..=5.0).contains(&dt) {
        return None;
    }
    let en = project(lat, lon, previous.lat, previous.lon);
    // Only derive motion when displacement clears both fixes' uncertainty;
    // otherwise stationary GPS jitter would continuously veto STILL.
    let uncertainty = previous.accuracy_m.max(accuracy_m);
    let displacement_gate = if speed_corroborates_motion {
        (uncertainty * 0.5).max(2.0)
    } else {
        uncertainty
    };
    if en[0].hypot(en[1]) <= displacement_gate {
        return None;
    }
    Some([en[0] / dt, en[1] / dt])
}

impl Default for LiveFusion {
    fn default() -> Self {
        Self::new()
    }
}

fn norm(v: [f64; 3]) -> f64 {
    (v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).sqrt()
}

fn velocity_en(speed: Option<f64>, bearing: Option<f64>) -> Option<[f64; 2]> {
    let (speed, bearing) = (speed?, bearing?);
    if !speed.is_finite() || !bearing.is_finite() {
        return None;
    }
    let r = bearing.to_radians();
    Some([speed * r.sin(), speed * r.cos()])
}

fn project(lat: f64, lon: f64, lat0: f64, lon0: f64) -> [f64; 2] {
    [
        (lon - lon0).to_radians() * lat0.to_radians().cos() * EARTH_RADIUS_M,
        (lat - lat0).to_radians() * EARTH_RADIUS_M,
    ]
}

fn unproject(e: f64, n: f64, lat0: f64, lon0: f64) -> (f64, f64) {
    (
        lat0 + (n / EARTH_RADIUS_M).to_degrees(),
        lon0 + (e / (EARTH_RADIUS_M * lat0.to_radians().cos())).to_degrees(),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stationary_imu_pins_false_gps_speed_to_zero() {
        let fusion = LiveFusion::new();
        for i in 0..100 {
            fusion.push_imu(i * 10, vec![0.0, 0.0, GRAVITY], vec![0.0; 3]);
        }
        let first = fusion.push_gps(
            1_000,
            41.7,
            44.8,
            Some(700.0),
            Some(4.0),
            Some(2.8),
            Some(90.0),
        );
        assert!(first.is_some());
        let second = fusion
            .push_gps(
                2_000,
                41.70001,
                44.80001,
                Some(700.0),
                Some(4.0),
                Some(2.8),
                Some(90.0),
            )
            .unwrap();
        assert!(second.stationary);
        assert_eq!(second.speed_mps, 0.0);
    }

    #[test]
    fn bad_accuracy_fix_is_ignored() {
        let fusion = LiveFusion::new();
        assert!(
            fusion
                .push_gps(0, 41.7, 44.8, None, Some(60.0), None, None)
                .is_none()
        );
    }

    #[test]
    fn sustained_rotation_is_not_stationary() {
        let fusion = LiveFusion::new();
        for i in 0..100 {
            fusion.push_imu(i * 10, vec![0.0, 0.0, GRAVITY], vec![0.0, 0.0, 0.5]);
        }
        let snapshot = fusion
            .push_gps(1_000, 41.7, 44.8, None, Some(4.0), Some(3.0), Some(0.0))
            .unwrap();
        assert!(!snapshot.stationary);
        assert!(snapshot.speed_mps > 2.0);
    }

    #[test]
    fn returns_to_still_after_motion() {
        let fusion = LiveFusion::new();
        for i in 0..100 {
            fusion.push_imu(i * 10, vec![0.0, 0.0, GRAVITY], vec![0.0; 3]);
        }
        for i in 100..200 {
            fusion.push_imu(i * 10, vec![3.0, 0.0, GRAVITY], vec![0.0, 0.0, 1.0]);
        }
        let moving = fusion
            .push_gps(2_000, 41.7, 44.8, None, Some(4.0), Some(1.0), Some(0.0))
            .unwrap();
        assert!(!moving.stationary);
        for i in 200..400 {
            fusion.push_imu(i * 10, vec![0.0, 0.0, GRAVITY], vec![0.0; 3]);
        }
        let stopped = fusion
            .push_gps(4_000, 41.7, 44.8, None, Some(4.0), Some(1.0), Some(0.0))
            .unwrap();
        assert!(stopped.stationary);
        assert_eq!(stopped.speed_mps, 0.0);
    }

    #[test]
    fn smooth_vehicle_motion_overrides_calm_imu() {
        let fusion = LiveFusion::new();
        for i in 0..100 {
            fusion.push_imu(i * 10, vec![0.0, 0.0, GRAVITY], vec![0.0; 3]);
        }
        // One fix with a high reported speed is not enough: stationary GPS
        // speed was proven false on this device. The second displaced fix is.
        assert!(
            fusion
                .push_gps(1_000, 41.7, 44.8, None, Some(4.0), Some(10.0), Some(90.0))
                .is_some_and(|snapshot| snapshot.stationary)
        );
        for i in 101..300 {
            fusion.push_imu(i * 10, vec![0.0, 0.0, GRAVITY], vec![0.0; 3]);
        }
        let moving = fusion
            .push_gps(
                3_000,
                41.7,
                44.80024,
                None,
                Some(4.0),
                Some(10.0),
                Some(90.0),
            )
            .unwrap();
        assert!(!moving.stationary);
        assert!(moving.speed_mps > 5.0);
        for i in 301..450 {
            assert!(!fusion.push_imu(i * 10, vec![0.0, 0.0, GRAVITY], vec![0.0; 3]));
        }
    }

    #[test]
    fn stationary_gps_jitter_does_not_draw_a_pattern() {
        let fusion = LiveFusion::new();
        for i in 0..150 {
            fusion.push_imu(i * 10, vec![0.0, 0.0, GRAVITY], vec![0.0; 3]);
        }
        let first = fusion
            .push_gps(1_500, 41.7, 44.8, None, Some(4.0), Some(0.0), Some(0.0))
            .unwrap();
        let jittered = fusion
            .push_gps(
                2_500,
                41.70002,
                44.80002,
                None,
                Some(4.0),
                Some(0.1),
                Some(20.0),
            )
            .unwrap();
        assert!(jittered.stationary);
        assert_eq!(jittered.lat, first.lat);
        assert_eq!(jittered.lon, first.lon);
    }

    #[test]
    fn rough_horizontal_acceleration_cannot_launch_the_track() {
        let fusion = LiveFusion::new();
        fusion.push_imu(0, vec![0.0, 0.0, GRAVITY], vec![0.0; 3]);
        fusion
            .push_gps(10, 41.7, 44.8, None, Some(4.0), Some(5.0), Some(90.0))
            .unwrap();
        for second in 1..20 {
            for step in 0..50 {
                let t = second * 1_000 + step * 20;
                let horizontal = if step % 2 == 0 { 25.0 } else { -25.0 };
                fusion.push_imu(t, vec![horizontal, 0.0, GRAVITY], vec![0.0, 0.0, 1.0]);
            }
            let lon = 44.8
                + (second as f64 * 5.0 / (EARTH_RADIUS_M * 41.7_f64.to_radians().cos()))
                    .to_degrees();
            let snapshot = fusion
                .push_gps(
                    second * 1_000 + 999,
                    41.7,
                    lon,
                    None,
                    Some(4.0),
                    Some(5.0),
                    Some(90.0),
                )
                .unwrap();
            let offset = project(snapshot.lat, snapshot.lon, 41.7, lon);
            assert!(offset[0].hypot(offset[1]) <= MIN_LIVE_GPS_ENVELOPE_M);
        }
    }
}
