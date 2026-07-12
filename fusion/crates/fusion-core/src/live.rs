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
            if calm {
                s.motion_since_ms = None;
                let since = *s.calm_since_ms.get_or_insert(timestamp_ms);
                if timestamp_ms - since >= ENTER_STILL_MS {
                    s.stationary = true;
                }
            } else {
                s.calm_since_ms = None;
                let since = *s.motion_since_ms.get_or_insert(timestamp_ms);
                if timestamp_ms - since >= EXIT_STILL_MS {
                    s.stationary = false;
                }
            }
        } else {
            s.stationary = false;
        }

        let linear = s.orientation.world_linear_accel(a);
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
        if !lat.is_finite() || !lon.is_finite() || accuracy > MAX_GPS_ACCURACY_M {
            return None;
        }
        let mut s = self.state.lock().expect("live fusion mutex poisoned");
        if s.ekf.is_none() {
            let vel = if s.stationary {
                Some([0.0, 0.0])
            } else {
                velocity_en(speed_mps, bearing_deg)
            };
            s.ekf = Some(Ekf::new(accuracy, vel));
            s.anchor = Some((lat, lon, altitude_m));
        } else {
            let anchor = s.anchor.expect("EKF has anchor");
            let en = project(lat, lon, anchor.0, anchor.1);
            let stationary = s.stationary;
            let ekf = s.ekf.as_mut().expect("checked above");
            ekf.update_gps_position(en, accuracy);
            if let (Some(alt), Some(anchor_alt)) = (altitude_m, anchor.2) {
                ekf.update_gps_altitude(alt - anchor_alt, accuracy);
            }
            // Android's reported speed is useful while moving but is exactly
            // the source of false chair-speed. The IMU stationarity gate wins.
            if !stationary {
                if let Some(vel) = velocity_en(speed_mps, bearing_deg) {
                    ekf.update_gps_velocity(vel);
                }
            } else {
                ekf.update_zupt();
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
}
