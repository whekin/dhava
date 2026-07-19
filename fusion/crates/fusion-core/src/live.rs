//! Stateful, low-cost fusion for the recording screen.
//!
//! This uses the same orientation and EKF primitives as canonical analysis.
//! Android may feed IMU at a reduced rate; snapshots are produced only for
//! GPS fixes, so background recording does not pay any map/UI cost.

use std::collections::VecDeque;
use std::sync::Mutex;

use crate::ekf::Ekf;
use crate::gps_quality::{HorizontalFix, kinematically_plausible};
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
/// Keep the vertical inertial channel, but make each accepted GPS fix the
/// authoritative horizontal output until a bounded segment model is validated.
const HORIZONTAL_ACCEL_GAIN: f64 = 0.0;
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
    gps_motion_hold_until_ms: i64,
    last_gps_fix: Option<HorizontalFix>,
    still_gps_anchor: Option<HorizontalFix>,
    gps_stop_anchor: Option<HorizontalFix>,
    gps_stop_rearm_anchor: Option<HorizontalFix>,
    horizontal_reseat_pending: bool,
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
                gps_stop_anchor: None,
                gps_stop_rearm_anchor: None,
                horizontal_reseat_pending: false,
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
            s.gps_stop_anchor = None;
            s.gps_stop_rearm_anchor = None;
            if let Some(ekf) = &mut s.ekf {
                // Do not let the next normal-rate IMU samples propagate the
                // pre-pause velocity before GPS has had a chance to re-anchor
                // the horizontal state.
                ekf.reset_horizontal_velocity();
                s.horizontal_reseat_pending = true;
            }
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
        if !lat.is_finite()
            || !lon.is_finite()
            || !accuracy.is_finite()
            || !(0.0..=MAX_GPS_ACCURACY_M).contains(&accuracy)
        {
            return None;
        }
        let mut s = self.state.lock().expect("live fusion mutex poisoned");
        let reported_velocity = velocity_en(speed_mps, bearing_deg);
        let gps_reports_zero_speed = reported_velocity == Some([0.0, 0.0]);
        let horizontal_reseat_pending = s.horizontal_reseat_pending;
        let current_fix = HorizontalFix {
            timestamp_ms,
            lat,
            lon,
            accuracy_m: accuracy,
            speed_mps: speed_mps.filter(|speed| speed.is_finite() && *speed >= 0.0),
        };
        if !horizontal_reseat_pending
            && s.last_gps_fix
                .is_some_and(|previous| !kinematically_plausible(previous, current_fix))
        {
            return None;
        }
        let stop_anchor_moved = s
            .gps_stop_anchor
            .is_some_and(|anchor| gps_fix_moved_beyond_uncertainty(anchor, current_fix));
        let rearm_anchor_moved = s
            .gps_stop_rearm_anchor
            .is_some_and(|anchor| gps_fix_moved_beyond_uncertainty(anchor, current_fix));
        // The first exact zero describes velocity at the current fix. The
        // displacement from the preceding moving fix describes how we arrived
        // here, so it must not override the stop and carry stale velocity past
        // the endpoint. Hold this position until earth-relative displacement
        // clears the combined uncertainty of the stop anchor and current fix.
        // After a zero-speed anchor has been disproved by real displacement,
        // repeated zero reports on a bus must not alternate hold/release every
        // other fix. Rearm only after those zero-speed fixes stabilize again.
        let gps_stop_started = gps_reports_zero_speed
            && s.gps_stop_anchor.is_none()
            && (s.gps_stop_rearm_anchor.is_none() || !rearm_anchor_moved);
        let gps_stop_active =
            gps_stop_started || (s.gps_stop_anchor.is_some() && !stop_anchor_moved);
        let motion_anchor = if horizontal_reseat_pending {
            // Samples on opposite sides of a manual pause or sensor stall do
            // not describe continuous motion and must never produce a
            // derived velocity.
            None
        } else if s.stationary {
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
        let gps_reports_motion = !gps_stop_active
            && (stop_anchor_moved
                || rearm_anchor_moved
                || derived_velocity.is_some_and(|velocity| {
                    velocity[0].hypot(velocity[1]) >= GPS_DERIVED_MOVING_SPEED_MPS
                }));
        let gps_released_stationary = gps_reports_motion && s.stationary;
        if gps_stop_started {
            s.gps_stop_anchor = Some(current_fix);
            s.gps_stop_rearm_anchor = None;
        } else if stop_anchor_moved {
            s.gps_stop_anchor = None;
            s.gps_stop_rearm_anchor = gps_reports_zero_speed.then_some(current_fix);
        } else if s.gps_stop_rearm_anchor.is_some() {
            if !gps_reports_zero_speed {
                s.gps_stop_rearm_anchor = None;
            } else if rearm_anchor_moved {
                s.gps_stop_rearm_anchor = Some(current_fix);
            }
        }
        if gps_reports_motion {
            s.gps_motion_hold_until_ms = timestamp_ms.saturating_add(GPS_MOTION_HOLD_MS);
            s.stationary = false;
            s.calm_since_ms = None;
            s.still_gps_anchor = None;
        }
        s.last_gps_fix = Some(current_fix);
        // Android may report an exact zero speed without a bearing at a real
        // stop. It may also briefly report zero on a smoothly moving vehicle.
        // Earth-relative displacement wins in the latter case; otherwise the
        // zero measurement must clear stale pre-stop velocity.
        let measured_velocity = if gps_stop_active {
            Some([0.0, 0.0])
        } else {
            match (reported_velocity, derived_velocity) {
                (Some([0.0, 0.0]), Some(derived)) if gps_reports_motion => Some(derived),
                (Some(reported), _) => Some(reported),
                (None, derived) => derived,
            }
        };
        if s.ekf.is_none() {
            let vel = if s.stationary || gps_stop_active {
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
            s.horizontal_reseat_pending = false;
            let ekf = s.ekf.as_mut().expect("checked above");
            if horizontal_reseat_pending {
                // The first accepted GPS fix after a pause/process stall is
                // authoritative. Position and velocity must recover together
                // so a stale velocity cannot draw a loop before the normal
                // measurement gates converge again.
                ekf.reseat_horizontal(en, measured_velocity, accuracy);
            } else if gps_stop_started {
                // A stop is a discontinuous state change. Anchor both position
                // and velocity at the first exact-zero fix immediately, before
                // the previous moving state can predict a visible loop.
                ekf.reseat_horizontal(en, Some([0.0, 0.0]), accuracy);
            } else if gps_stop_active {
                // Ignore raw GPS flowers and false non-zero speed while the
                // fixes remain inside the stop anchor's uncertainty region.
                ekf.reset_horizontal_velocity();
            } else if stationary {
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
                // Off a known segment there is no external horizontal truth
                // that can justify bending a good GPS polyline. The EKF's
                // velocity prediction can cut a turn by most of the reported
                // accuracy radius before the normal update catches up. Keep
                // dynamics in velocity/vertical state, but make every accepted
                // moving GPS fix authoritative for rendered XY.
                if measured_velocity.is_some() {
                    ekf.reseat_horizontal(en, measured_velocity, accuracy);
                } else {
                    ekf.reseat_horizontal_position(en, accuracy);
                }
                if let (Some(alt), Some(anchor_alt)) = (altitude_m, anchor.2) {
                    ekf.update_gps_altitude(alt - anchor_alt, accuracy);
                }
            }
        }

        let anchor = s.anchor.expect("initialized above");
        let stationary = s.stationary || gps_stop_active;
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

fn gps_fix_moved_beyond_uncertainty(anchor: HorizontalFix, current: HorizontalFix) -> bool {
    let en = project(current.lat, current.lon, anchor.lat, anchor.lon);
    // Each Android accuracy is a radius around its own fix. Root-sum-square
    // gives a conservative combined gate without making movement recovery as
    // sluggish as adding the two radii. A bus clears it on the next fix; a
    // bike at 15 km/h normally clears it within two fixes.
    let combined_accuracy = anchor.accuracy_m.hypot(current.accuracy_m).max(3.0);
    en[0].hypot(en[1]) > combined_accuracy
}

fn derived_gps_velocity(
    previous: HorizontalFix,
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

impl LiveFusion {
    /// Manual pause/resume starts a new earth-relative section. The next GPS
    /// fix must reseat position instead of being compared with the old section.
    pub(crate) fn start_new_section(&self) {
        let mut state = self.state.lock().expect("live fusion mutex poisoned");
        state.stationary = false;
        state.calm_since_ms = None;
        state.motion_since_ms = None;
        state.last_gps_fix = None;
        state.still_gps_anchor = None;
        state.gps_stop_anchor = None;
        state.gps_stop_rearm_anchor = None;
        state.horizontal_reseat_pending = state.ekf.is_some();
        if let Some(ekf) = &mut state.ekf {
            ekf.reset_horizontal_velocity();
        }
    }
}

fn norm(v: [f64; 3]) -> f64 {
    (v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).sqrt()
}

fn velocity_en(speed: Option<f64>, bearing: Option<f64>) -> Option<[f64; 2]> {
    let speed = speed?;
    if !speed.is_finite() || speed < 0.0 {
        return None;
    }
    if speed == 0.0 {
        return Some([0.0, 0.0]);
    }
    let bearing = bearing?;
    if !bearing.is_finite() {
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
        let stopped = fusion
            .push_gps(0, 41.7, 44.8, None, Some(4.0), Some(0.0), None)
            .unwrap();
        assert!(
            fusion
                .push_gps(
                    1_000,
                    41.71,
                    44.81,
                    None,
                    Some(60.0),
                    Some(15.0),
                    Some(45.0)
                )
                .is_none()
        );
        let held = fusion
            .push_gps(2_000, 41.7, 44.8, None, Some(4.0), Some(0.0), None)
            .unwrap();
        assert!(held.stationary);
        assert_eq!(held.lat, stopped.lat);
        assert_eq!(held.lon, stopped.lon);
    }

    #[test]
    fn contradictory_low_speed_teleport_is_ignored_and_recovers() {
        let fusion = LiveFusion::new();
        fusion
            .push_gps(0, 41.7, 44.8, None, Some(3.8), Some(4.31), Some(90.0))
            .unwrap();
        let longitude_at = |east_m: f64| {
            44.8 + (east_m / (EARTH_RADIUS_M * 41.7_f64.to_radians().cos())).to_degrees()
        };

        assert!(
            fusion
                .push_gps(
                    1_000,
                    41.7,
                    longitude_at(16.8),
                    None,
                    Some(3.9),
                    Some(2.91),
                    Some(90.0),
                )
                .is_none()
        );

        let recovered = fusion
            .push_gps(
                2_000,
                41.7,
                longitude_at(8.0),
                None,
                Some(3.9),
                Some(4.0),
                Some(90.0),
            )
            .unwrap();
        let offset = project(recovered.lat, recovered.lon, 41.7, longitude_at(8.0));
        assert!(offset[0].hypot(offset[1]) < 0.01);
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
    fn zero_speed_without_bearing_stops_horizontal_prediction() {
        let fusion = LiveFusion::new();
        fusion.push_imu(0, vec![0.0, 0.0, GRAVITY], vec![0.0, 0.0, 0.5]);
        fusion
            .push_gps(0, 41.7, 44.8, None, Some(4.0), Some(8.0), Some(90.0))
            .unwrap();

        let stopped_lon =
            44.8 + (8.0 / (EARTH_RADIUS_M * 41.7_f64.to_radians().cos())).to_degrees();
        for step in 1..=50 {
            fusion.push_imu(step * 20, vec![0.0, 0.0, GRAVITY], vec![0.0, 0.0, 0.5]);
        }
        // The displacement describes the interval before this fix, while the
        // exact zero describes the state at the fix. Arrival must stop at the
        // current position immediately instead of carrying velocity beyond it.
        let arrival = fusion
            .push_gps(1_000, 41.7, stopped_lon, None, Some(4.0), Some(0.0), None)
            .unwrap();
        let arrival_offset = project(arrival.lat, arrival.lon, 41.7, stopped_lon);
        assert!(arrival.stationary);
        assert_eq!(arrival.speed_mps, 0.0);
        assert!(arrival_offset[0].hypot(arrival_offset[1]) < 0.01);

        for step in 51..=100 {
            fusion.push_imu(step * 20, vec![0.0, 0.0, GRAVITY], vec![0.0, 0.0, 0.5]);
        }
        let stopped = fusion
            .push_gps(2_000, 41.7, stopped_lon, None, Some(4.0), Some(0.0), None)
            .unwrap();
        assert!(
            stopped.speed_mps < 1.0,
            "stale speed: {}",
            stopped.speed_mps
        );

        for step in 101..=150 {
            fusion.push_imu(step * 20, vec![0.0, 0.0, GRAVITY], vec![0.0, 0.0, 0.5]);
        }
        let held = fusion
            .push_gps(3_000, 41.7, stopped_lon, None, Some(4.0), Some(0.0), None)
            .unwrap();
        let offset = project(held.lat, held.lon, 41.7, stopped_lon);
        assert!(
            offset[0].hypot(offset[1]) < 4.0,
            "stationary prediction escaped by {offset:?}"
        );
    }

    #[test]
    fn moving_vehicle_releases_false_zero_speed_anchor() {
        let fusion = LiveFusion::new();
        let first = fusion
            .push_gps(0, 41.7, 44.8, None, Some(4.0), Some(0.0), None)
            .unwrap();
        assert!(first.stationary);

        let moved_lon = 44.8 + (12.0 / (EARTH_RADIUS_M * 41.7_f64.to_radians().cos())).to_degrees();
        let moving = fusion
            .push_gps(1_000, 41.7, moved_lon, None, Some(4.0), Some(0.0), None)
            .unwrap();
        let offset = project(moving.lat, moving.lon, 41.7, moved_lon);
        assert!(!moving.stationary);
        assert!(moving.speed_mps > 5.0);
        assert!(offset[0].hypot(offset[1]) < 0.01);

        let moved_again_lon =
            44.8 + (24.0 / (EARTH_RADIUS_M * 41.7_f64.to_radians().cos())).to_degrees();
        let still_moving = fusion
            .push_gps(
                2_000,
                41.7,
                moved_again_lon,
                None,
                Some(4.0),
                Some(0.0),
                None,
            )
            .unwrap();
        assert!(!still_moving.stationary);
        assert!(still_moving.speed_mps > 5.0);

        // Once coordinates stop changing, exact zeros become trustworthy
        // again and may establish a new stop at the vehicle's destination.
        let stopped = fusion
            .push_gps(
                3_000,
                41.7,
                moved_again_lon,
                None,
                Some(4.0),
                Some(0.0),
                None,
            )
            .unwrap();
        assert!(stopped.stationary);
        assert_eq!(stopped.speed_mps, 0.0);
    }

    #[test]
    fn false_speed_and_gps_jitter_stay_at_zero_speed_anchor() {
        let fusion = LiveFusion::new();
        let stopped = fusion
            .push_gps(0, 41.7, 44.8, None, Some(4.0), Some(0.0), None)
            .unwrap();
        let jittered_lon =
            44.8 + (3.0 / (EARTH_RADIUS_M * 41.7_f64.to_radians().cos())).to_degrees();
        let held = fusion
            .push_gps(
                1_000,
                41.7,
                jittered_lon,
                None,
                Some(4.0),
                Some(2.8),
                Some(90.0),
            )
            .unwrap();
        assert!(held.stationary);
        assert_eq!(held.speed_mps, 0.0);
        assert_eq!(held.lat, stopped.lat);
        assert_eq!(held.lon, stopped.lon);
    }

    #[test]
    fn long_imu_gap_reseats_position_and_velocity_on_next_gps() {
        let fusion = LiveFusion::new();
        fusion.push_imu(0, vec![0.0, 0.0, GRAVITY], vec![0.0, 0.0, 0.5]);
        fusion
            .push_gps(0, 41.7, 44.8, None, Some(4.0), Some(10.0), Some(90.0))
            .unwrap();
        for step in 1..=25 {
            fusion.push_imu(step * 20, vec![0.0, 0.0, GRAVITY], vec![0.0, 0.0, 0.5]);
        }

        // Simulates a manual pause or a sensor stall. Normal IMU samples may
        // resume before GPS, but they must not propagate the old 10 m/s state.
        assert!(!fusion.push_imu(2_000, vec![0.0, 0.0, GRAVITY], vec![0.0, 0.0, 0.5]));
        for step in 101..=125 {
            fusion.push_imu(step * 20, vec![0.0, 0.0, GRAVITY], vec![0.0, 0.0, 0.5]);
        }
        let resumed_lon =
            44.8 + (6.0 / (EARTH_RADIUS_M * 41.7_f64.to_radians().cos())).to_degrees();
        let resumed = fusion
            .push_gps(2_500, 41.7, resumed_lon, None, Some(4.0), Some(0.0), None)
            .unwrap();
        let offset = project(resumed.lat, resumed.lon, 41.7, resumed_lon);
        assert!(
            offset[0].hypot(offset[1]) < 0.01,
            "not re-seated: {offset:?}"
        );
        assert_eq!(resumed.speed_mps, 0.0);
    }

    #[test]
    fn exact_zero_speed_does_not_require_bearing() {
        assert_eq!(velocity_en(Some(0.0), None), Some([0.0, 0.0]));
        assert_eq!(velocity_en(Some(-1.0), Some(90.0)), None);
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
            assert!(offset[0].hypot(offset[1]) < 0.01);
        }
    }

    #[test]
    fn accepted_moving_fix_is_authoritative_through_a_sharp_turn() {
        let fusion = LiveFusion::new();
        fusion
            .push_gps(0, 41.7, 44.8, None, Some(10.0), Some(10.0), Some(0.0))
            .unwrap();

        for step in 1..=50 {
            fusion.push_imu(step * 20, vec![0.0, 0.0, GRAVITY], vec![0.0, 0.0, 0.5]);
        }
        let north_lat = 41.7 + (10.0 / EARTH_RADIUS_M).to_degrees();
        fusion
            .push_gps(
                1_000,
                north_lat,
                44.8,
                None,
                Some(10.0),
                Some(10.0),
                Some(0.0),
            )
            .unwrap();

        for step in 51..=100 {
            fusion.push_imu(step * 20, vec![0.0, 0.0, GRAVITY], vec![0.0, 0.0, 0.5]);
        }
        let east_lon = 44.8 + (10.0 / (EARTH_RADIUS_M * 41.7_f64.to_radians().cos())).to_degrees();
        let turned = fusion
            .push_gps(
                2_000,
                north_lat,
                east_lon,
                None,
                Some(10.0),
                Some(10.0),
                Some(90.0),
            )
            .unwrap();
        let offset = project(turned.lat, turned.lon, north_lat, east_lon);
        assert!(
            offset[0].hypot(offset[1]) < 0.01,
            "turn was cut by {offset:?}"
        );
    }
}
