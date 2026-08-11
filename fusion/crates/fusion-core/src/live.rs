//! Stateful, low-cost fusion for the recording screen.
//!
//! This uses the same orientation and EKF primitives as canonical analysis.
//! Android may feed IMU at a reduced rate; snapshots are produced only for
//! GPS fixes, so background recording does not pay any map/UI cost.

use std::collections::VecDeque;
use std::path::Path;
use std::sync::Mutex;

use crate::FusionError;
use crate::ekf::Ekf;
use crate::gps_quality::{HorizontalFix, kinematically_plausible};
use crate::orientation::{GRAVITY, Mahony};
use crate::recording::parse_recording_file;

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
/// Live ride totals use the canonical accumulator rules so the recording
/// screen and the finalized activity cannot disagree about what counts:
/// displacement below [`MIN_MOVE_M`] is jitter and never moves the anchor,
/// and altitude only moves the reference once it escapes the hysteresis band.
/// The live values stay provisional — they are fed by causal fusion, while
/// canonical results run the bounded post-pass over the same raw ride.
const MIN_MOVE_M: f64 = 1.0;
const ALTITUDE_HYSTERESIS_M: f64 = 2.0;
/// Same window canonical analysis uses. Live cannot centre a filter on a
/// sample it has not received yet, so the accumulator simply trails the newest
/// fix by two: the median it consumes is the canonical one, two fixes late.
const ALTITUDE_MEDIAN_WINDOW: usize = 5;
/// Live transport hint. The thresholds mirror the post-ride classifier's
/// vehicle evidence: a rate of climb no rider produces, held long enough that
/// altitude noise cannot fake it. See `activity.rs` for why 0.6 m/s is the
/// line. Entering only lowers sampling rates, so the cost of a wrong enter is
/// a coarser transit, and every exit condition is deliberately fast.
const TRANSPORT_CLIMB_MPS: f64 = 0.6;
const TRANSPORT_ENTER_WINDOW_MS: i64 = 45_000;
const TRANSPORT_CLIMB_HISTORY_MS: i64 = 150_000;
/// A descent under way is the strongest possible "the rider is riding again" —
/// but only when it does not look like a vehicle. A shuttle road is not
/// monotonic: a serpentine has dips and flat shelves, and treating every one
/// of them as the start of a run made power saving flap all the way up the
/// mountain. A rough descent is a rider and ends power saving at once; a
/// smooth one has to go deep enough that no road dip explains it.
const TRANSPORT_EXIT_DESCENT_MPS: f64 = -0.2;
const TRANSPORT_EXIT_WINDOW_MS: i64 = 25_000;
/// Height given up since the highest point recently reached. A dip between
/// switchbacks gives back a few metres; leaving the mountain gives back this
/// much, however smooth the ride is.
const TRANSPORT_EXIT_DROP_M: f64 = 40.0;
/// Motion at or below this is a vehicle floor rather than a trail.
const TRANSPORT_VEHICLE_SMOOTH_MPS2: f64 = 0.5;
/// Vehicles are smooth; trail riding is not. Well above the 0.45 m/s^2 the
/// post-ride classifier calls strongly smooth, so ordinary road bumps do not
/// leave power save on their own.
const TRANSPORT_EXIT_ROUGHNESS_MPS2: f64 = 1.2;
const TRANSPORT_ENTER_ROUGHNESS_MPS2: f64 = 0.6;
/// A vehicle that has been standing this long may have been left.
const TRANSPORT_EXIT_STILL_MS: i64 = 45_000;
/// How long Android's own activity recognition must agree before it is acted
/// on. It is a hint from a classifier we do not control, so it buys a faster
/// entry on flat ground — a city bus never climbs — but never a faster exit
/// than our own evidence would give.
const TRANSPORT_PLATFORM_HOLD_MS: i64 = 20_000;

#[derive(Debug, Clone, uniffi::Record)]
pub struct LiveSnapshot {
    pub timestamp_ms: i64,
    pub lat: f64,
    pub lon: f64,
    pub altitude_m: Option<f64>,
    pub speed_mps: f64,
    pub stationary: bool,
    pub accuracy_m: f64,
    /// Ride distance so far, metres. Provisional; canonical wins after Finish.
    pub distance_m: f64,
    /// Accumulated descent so far, metres, positive downhill.
    pub descent_m: f64,
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
    gps_motion_candidate: Option<HorizontalFix>,
    gps_stop_anchor: Option<HorizontalFix>,
    gps_stop_rearm_anchor: Option<HorizontalFix>,
    horizontal_reseat_pending: bool,
    distance_m: f64,
    descent_m: f64,
    /// Last accepted position in the EKF's local tangent frame. Cleared on a
    /// section restart so a reseat jump is never counted as ridden distance.
    distance_anchor: Option<[f64; 2]>,
    /// Hysteresis reference for descent, metres, on the filtered GPS series.
    altitude_reference: Option<f64>,
    /// Trailing median window over accepted GPS altitudes.
    altitude_window: VecDeque<f64>,
    /// Filtered altitude over the recent past, for the transport hint.
    climb_window: VecDeque<(i64, f64)>,
    /// Mean |accel| error over the stationary window — the roughness channel.
    recent_roughness: f64,
    motorized: bool,
    still_since_ms: Option<i64>,
    /// Android's activity recognition, when the app has it: `Some(true)` in a
    /// vehicle, `Some(false)` on a bike or on foot, `None` when unavailable.
    platform_vehicle: Option<bool>,
    platform_vehicle_since_ms: Option<i64>,
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
                gps_motion_candidate: None,
                gps_stop_anchor: None,
                gps_stop_rearm_anchor: None,
                horizontal_reseat_pending: false,
                distance_m: 0.0,
                descent_m: 0.0,
                distance_anchor: None,
                altitude_reference: None,
                altitude_window: VecDeque::new(),
                climb_window: VecDeque::new(),
                recent_roughness: 0.0,
                motorized: false,
                still_since_ms: None,
                platform_vehicle: None,
                platform_vehicle_since_ms: None,
            }),
        }
    }

    /// Whether the recorder should behave as if the rider is in a vehicle.
    ///
    /// Consumed by Android to lower GPS and IMU rates during a shuttle or bus
    /// leg. It is a power decision, never a recorded result: the ride's real
    /// transport spans come from the post-ride classifier over the raw file.
    pub fn motorized_hint(&self) -> bool {
        self.state
            .lock()
            .expect("live fusion mutex poisoned")
            .motorized
    }

    /// Feeds Android's activity recognition into the transport decision.
    ///
    /// The platform classifier answers the one question our own evidence
    /// cannot on flat ground: a city bus stuck in traffic neither climbs nor
    /// moves fast, so nothing in the ride itself says "vehicle". It is a hint
    /// and is treated as one — it can bring power saving on sooner, and it can
    /// end it, but it can never keep it on once our own evidence says the
    /// rider is riding. Pass `None` when the signal is unavailable or the
    /// permission was declined.
    pub fn set_platform_vehicle(&self, timestamp_ms: i64, in_vehicle: Option<bool>) {
        let mut s = self.state.lock().expect("live fusion mutex poisoned");
        if s.platform_vehicle != in_vehicle {
            s.platform_vehicle = in_vehicle;
            s.platform_vehicle_since_ms = Some(timestamp_ms);
        }
        if in_vehicle == Some(false) {
            s.motorized = false;
        }
    }

    /// Adds the totals of an interrupted recording that is being continued.
    ///
    /// The recorder keeps ride time across a process restart, so distance and
    /// descent must not silently restart at zero for the same ride. The caller
    /// derives the seed from the already-written raw fixes with
    /// [`live_totals_from_recording`], which applies these same rules.
    ///
    /// Additive rather than assigning: reading the raw file takes long enough
    /// that the rider can already be moving again, and those metres are as
    /// real as the recovered ones. Call it exactly once per resume.
    pub fn seed_totals(&self, distance_m: f64, descent_m: f64) {
        let mut s = self.state.lock().expect("live fusion mutex poisoned");
        s.distance_m += distance_m.max(0.0);
        s.descent_m += descent_m.max(0.0);
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
            s.gps_motion_candidate = None;
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
            // The same mean doubles as the roughness channel of the transport
            // hint: a vehicle floor is smooth, a trail is not.
            s.recent_roughness = accel_mean;
            if s.motorized && accel_mean > TRANSPORT_EXIT_ROUGHNESS_MPS2 {
                s.motorized = false;
            }
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
                    s.gps_motion_candidate = None;
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
        let gps_motion_evidence = !gps_stop_active
            && (stop_anchor_moved
                || rearm_anchor_moved
                || derived_velocity.is_some_and(|velocity| {
                    velocity[0].hypot(velocity[1]) >= GPS_DERIVED_MOVING_SPEED_MPS
                }));
        // A calm phone plus one displaced fix is still ambiguous: in field
        // tests that exact combination drew 10–20 m flowers while the phone
        // moved only centimetres. Require a second fix that continues away
        // from the STILL anchor before earth-relative GPS may release ZUPT.
        // Real motion is delayed by at most one GPS interval, and the bounded
        // post-pass restores the causally hidden departure anchors.
        let gps_motion_confirmed = if s.stationary && gps_motion_evidence {
            match (
                s.still_gps_anchor.or(s.last_gps_fix),
                s.gps_motion_candidate,
            ) {
                (Some(anchor), Some(candidate)) => {
                    confirms_continuing_gps_motion(anchor, candidate, current_fix)
                }
                _ => false,
            }
        } else {
            false
        };
        let gps_reports_motion = gps_motion_evidence && (!s.stationary || gps_motion_confirmed);
        if s.stationary {
            if gps_reports_motion || !gps_motion_evidence {
                s.gps_motion_candidate = None;
            } else {
                s.gps_motion_candidate = match s.gps_motion_candidate {
                    Some(candidate)
                        if gps_fix_distance(candidate, current_fix)
                            < candidate.accuracy_m.min(current_fix.accuracy_m).max(3.0) =>
                    {
                        Some(candidate)
                    }
                    _ => Some(current_fix),
                };
            }
        } else {
            s.gps_motion_candidate = None;
        }
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
        let altitude = anchor.2.map(|a| a + p[2]);
        accumulate_totals(&mut s, timestamp_ms, [p[0], p[1]], altitude_m);
        update_transport(&mut s, timestamp_ms, stationary);
        Some(LiveSnapshot {
            timestamp_ms,
            lat: out_lat,
            lon: out_lon,
            altitude_m: altitude,
            speed_mps: if stationary { 0.0 } else { fused_speed },
            stationary,
            accuracy_m: accuracy,
            distance_m: s.distance_m,
            descent_m: s.descent_m,
        })
    }

    /// Starts a new continuous recording section after a manual pause.
    ///
    /// The next GPS fix re-seats position and velocity instead of comparing
    /// against a fix from before the pause.
    pub fn start_new_section(&self) {
        self.reset_section_state();
    }
}

/// Ride totals recovered from an already-recorded raw file.
#[derive(Debug, Clone, uniffi::Record)]
pub struct LiveTotals {
    pub distance_m: f64,
    pub descent_m: f64,
}

/// Reads distance and descent already recorded in a raw `.jsonl.gz`.
///
/// Used when an interrupted recording is continued: ride time survives the
/// process restart, so the totals shown next to it must survive it too.
/// Blocking (file IO): call from a background thread.
#[uniffi::export]
pub fn live_totals_from_recording(path: String) -> Result<LiveTotals, FusionError> {
    let recording = parse_recording_file(Path::new(&path))?;
    let (distance_m, descent_m) = crate::analysis::distance_and_descent(&recording);
    Ok(LiveTotals {
        distance_m,
        descent_m,
    })
}

/// Adds one accepted fused fix to the ride totals.
///
/// Position arrives in the EKF's local tangent frame, so consecutive steps are
/// differential and free of the tangent-plane distortion a long ride would
/// otherwise accumulate.
/// `altitude_m` is the fix's own GPS altitude, not the fused vertical state:
/// the EKF's vertical channel is deliberately slow, and over a real descent it
/// reported barely a third of the drop while the rider was still on the trail.
/// Canonical descent is accumulated from the GPS altitude series too, so this
/// keeps the live number on the same quantity as the finalized one.
/// Updates the transport hint from the filtered altitude series.
///
/// Deliberately asymmetric. Entering needs a rate of climb held for most of a
/// minute — the same evidence the post-ride classifier calls a vehicle — while
/// any one of a descent, trail roughness or a long stop leaves immediately.
/// The hint only lowers sampling rates, so a missed vehicle costs battery and
/// a false vehicle costs resolution; leaving fast is what keeps the second one
/// from ever touching a real run.
fn update_transport(state: &mut State, timestamp_ms: i64, stationary: bool) {
    state.still_since_ms = if stationary {
        Some(state.still_since_ms.unwrap_or(timestamp_ms))
    } else {
        None
    };

    let platform_says_vehicle = state.platform_vehicle == Some(true)
        && state
            .platform_vehicle_since_ms
            .is_some_and(|since| timestamp_ms - since >= TRANSPORT_PLATFORM_HOLD_MS);

    // Rough descent: a rider, immediately. Smooth descent: only once it is
    // deeper than a serpentine dip could be.
    let descending_roughly = state.recent_roughness > TRANSPORT_VEHICLE_SMOOTH_MPS2
        && climb_rate_mps(state, TRANSPORT_EXIT_WINDOW_MS)
            .is_some_and(|rate| rate <= TRANSPORT_EXIT_DESCENT_MPS);
    let losing_the_mountain = drop_from_recent_peak(state) >= TRANSPORT_EXIT_DROP_M;
    let descending = descending_roughly || losing_the_mountain;

    if state.motorized {
        // A long red light is not the rider getting out while Android still
        // reports a vehicle around them.
        let standing_too_long = !platform_says_vehicle
            && state
                .still_since_ms
                .is_some_and(|since| timestamp_ms - since >= TRANSPORT_EXIT_STILL_MS);
        if standing_too_long || descending {
            state.motorized = false;
        }
        return;
    }

    // Our own evidence vetoes every entry, including the platform's. Without
    // this a descent would exit power saving and Android's still-stale vehicle
    // hint would switch it straight back on for the whole run.
    if descending || state.recent_roughness > TRANSPORT_ENTER_ROUGHNESS_MPS2 {
        return;
    }
    // The platform hint may enter from a standstill — congestion is mostly
    // standing — while our own climb evidence still requires real movement.
    if platform_says_vehicle {
        state.motorized = true;
        return;
    }
    if stationary {
        return;
    }
    if climb_rate_mps(state, TRANSPORT_ENTER_WINDOW_MS)
        .is_some_and(|rate| rate >= TRANSPORT_CLIMB_MPS)
    {
        state.motorized = true;
    }
}

/// Height given up since the highest point in the retained history.
fn drop_from_recent_peak(state: &State) -> f64 {
    let Some((_, current_m)) = state.climb_window.back() else {
        return 0.0;
    };
    let peak_m = state
        .climb_window
        .iter()
        .fold(f64::MIN, |high, (_, altitude)| high.max(*altitude));
    (peak_m - current_m).max(0.0)
}

/// Rate of climb over at least `window_ms` of filtered altitude, or None while
/// the history is too short to mean anything.
fn climb_rate_mps(state: &State, window_ms: i64) -> Option<f64> {
    let (newest_ms, newest_m) = *state.climb_window.back()?;
    // The newest sample that is already old enough: the tightest window that
    // still covers the required span, rather than the whole retained history.
    let (oldest_ms, oldest_m) = *state
        .climb_window
        .iter()
        .rev()
        .find(|(stamp, _)| newest_ms - stamp >= window_ms)?;
    Some((newest_m - oldest_m) / ((newest_ms - oldest_ms) as f64 / 1_000.0))
}

fn accumulate_totals(
    state: &mut State,
    timestamp_ms: i64,
    position: [f64; 2],
    altitude_m: Option<f64>,
) {
    match state.distance_anchor {
        Some(previous) => {
            let step = (position[0] - previous[0]).hypot(position[1] - previous[1]);
            if step >= MIN_MOVE_M {
                state.distance_m += step;
                state.distance_anchor = Some(position);
            }
        }
        None => state.distance_anchor = Some(position),
    }

    let Some(sample) = altitude_m.filter(|value| value.is_finite()) else {
        return;
    };
    state.altitude_window.push_back(sample);
    if state.altitude_window.len() > ALTITUDE_MEDIAN_WINDOW {
        state.altitude_window.pop_front();
    }
    if state.altitude_window.len() < ALTITUDE_MEDIAN_WINDOW {
        return;
    }
    let mut window: Vec<f64> = state.altitude_window.iter().copied().collect();
    window.sort_by(|a, b| a.total_cmp(b));
    let altitude = window[window.len() / 2];
    state.climb_window.push_back((timestamp_ms, altitude));
    while state
        .climb_window
        .front()
        .is_some_and(|(stamp, _)| timestamp_ms - stamp > TRANSPORT_CLIMB_HISTORY_MS)
    {
        state.climb_window.pop_front();
    }
    match state.altitude_reference {
        Some(reference) => {
            let delta = altitude - reference;
            if delta >= ALTITUDE_HYSTERESIS_M {
                state.altitude_reference = Some(altitude);
            } else if delta <= -ALTITUDE_HYSTERESIS_M {
                state.descent_m += -delta;
                state.altitude_reference = Some(altitude);
            }
        }
        // The reference starts at the oldest sample in the first full window,
        // not at its median: the metres ridden while the filter was warming up
        // are real, and a rider who has already dropped 20 m should see them.
        None => state.altitude_reference = state.altitude_window.front().copied(),
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
    fn reset_section_state(&self) {
        let mut state = self.state.lock().expect("live fusion mutex poisoned");
        // Sensors are stopped during a manual pause. No timing, attitude or
        // motion evidence from before it may be combined with resumed IMU,
        // even when the pause was shorter than the normal 500 ms gap guard.
        state.orientation = Mahony::new();
        state.last_imu_ms = None;
        state.motion.clear();
        state.stationary = false;
        state.calm_since_ms = None;
        state.motion_since_ms = None;
        state.gps_motion_hold_until_ms = i64::MIN;
        state.last_gps_fix = None;
        state.still_gps_anchor = None;
        state.gps_motion_candidate = None;
        state.gps_stop_anchor = None;
        state.gps_stop_rearm_anchor = None;
        state.horizontal_reseat_pending = state.ekf.is_some();
        // Totals survive the pause — the ride is the same ride — but the
        // anchors do not: the reseat jump across a pause is not ridden
        // distance, and altitude across it is not a continuous descent.
        state.distance_anchor = None;
        state.altitude_reference = None;
        state.altitude_window.clear();
        // A pause is a hard boundary for the transport hint too: full rates
        // resume with the ride, and the climb has to prove itself again.
        state.climb_window.clear();
        state.motorized = false;
        state.still_since_ms = None;
        state.platform_vehicle = None;
        state.platform_vehicle_since_ms = None;
        if let Some(ekf) = &mut state.ekf {
            ekf.reset_horizontal_velocity();
        }
    }
}

fn norm(v: [f64; 3]) -> f64 {
    (v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).sqrt()
}

fn gps_fix_distance(from: HorizontalFix, to: HorizontalFix) -> f64 {
    let en = project(to.lat, to.lon, from.lat, from.lon);
    en[0].hypot(en[1])
}

fn confirms_continuing_gps_motion(
    anchor: HorizontalFix,
    candidate: HorizontalFix,
    current: HorizontalFix,
) -> bool {
    let dt_s = (current.timestamp_ms - candidate.timestamp_ms) as f64 / 1_000.0;
    if !(0.2..=5.0).contains(&dt_s) {
        return false;
    }
    let candidate_en = project(candidate.lat, candidate.lon, anchor.lat, anchor.lon);
    let current_en = project(current.lat, current.lon, anchor.lat, anchor.lon);
    let continuation = [
        current_en[0] - candidate_en[0],
        current_en[1] - candidate_en[1],
    ];
    let continuation_m = continuation[0].hypot(continuation[1]);
    let minimum_progress_m = candidate
        .accuracy_m
        .min(current.accuracy_m)
        .mul_add(0.25, 1.0)
        .clamp(2.0, 4.0);
    let forward_progress = candidate_en[0] * continuation[0] + candidate_en[1] * continuation[1];
    continuation_m >= minimum_progress_m
        && forward_progress > 0.0
        && current_en[0].hypot(current_en[1])
            >= candidate_en[0].hypot(candidate_en[1]) + minimum_progress_m * 0.5
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
        // Reported speed and one displaced fix are both insufficient because
        // stationary phones have produced each. Two consecutive displaced
        // fixes in the same direction prove earth-relative motion.
        assert!(
            fusion
                .push_gps(1_000, 41.7, 44.8, None, Some(4.0), Some(10.0), Some(90.0))
                .is_some_and(|snapshot| snapshot.stationary)
        );
        for i in 101..200 {
            fusion.push_imu(i * 10, vec![0.0, 0.0, GRAVITY], vec![0.0; 3]);
        }
        let longitude_at = |east_m: f64| {
            44.8 + (east_m / (EARTH_RADIUS_M * 41.7_f64.to_radians().cos())).to_degrees()
        };
        let candidate = fusion
            .push_gps(
                2_000,
                41.7,
                longitude_at(12.0),
                None,
                Some(4.0),
                Some(10.0),
                Some(90.0),
            )
            .unwrap();
        assert!(candidate.stationary);
        for i in 201..300 {
            fusion.push_imu(i * 10, vec![0.0, 0.0, GRAVITY], vec![0.0; 3]);
        }
        let moving = fusion
            .push_gps(
                3_000,
                41.7,
                longitude_at(24.0),
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
    fn one_displaced_fix_cannot_release_a_calm_stationary_phone() {
        let fusion = LiveFusion::new();
        for timestamp_ms in (0..=1_500).step_by(20) {
            fusion.push_imu(timestamp_ms, vec![0.0, 0.0, GRAVITY], vec![0.0; 3]);
        }
        let first = fusion
            .push_gps(1_500, 41.7, 44.8, None, Some(8.0), Some(2.8), Some(90.0))
            .unwrap();

        for timestamp_ms in (1_520..=2_500).step_by(20) {
            fusion.push_imu(timestamp_ms, vec![0.0, 0.0, GRAVITY], vec![0.0; 3]);
        }
        let jumped_lon =
            44.8 + (12.0 / (EARTH_RADIUS_M * 41.7_f64.to_radians().cos())).to_degrees();
        let jumped = fusion
            .push_gps(
                2_500,
                41.7,
                jumped_lon,
                None,
                Some(8.0),
                Some(2.8),
                Some(90.0),
            )
            .unwrap();

        assert!(jumped.stationary);
        assert_eq!(jumped.speed_mps, 0.0);
        assert_eq!(jumped.lat, first.lat);
        assert_eq!(jumped.lon, first.lon);
    }

    #[test]
    fn alternating_large_gps_jumps_remain_at_the_still_anchor() {
        let fusion = LiveFusion::new();
        for timestamp_ms in (0..=1_500).step_by(20) {
            fusion.push_imu(timestamp_ms, vec![0.0, 0.0, GRAVITY], vec![0.0; 3]);
        }
        let first = fusion
            .push_gps(1_500, 41.7, 44.8, None, Some(8.0), Some(2.8), Some(90.0))
            .unwrap();
        let longitude_at = |east_m: f64| {
            44.8 + (east_m / (EARTH_RADIUS_M * 41.7_f64.to_radians().cos())).to_degrees()
        };

        for (index, east_m) in [12.0, 0.0, -12.0, 0.0, 12.0, 0.0].into_iter().enumerate() {
            let timestamp_ms = 2_500 + index as i64 * 1_000;
            let imu_start = timestamp_ms - 980;
            for imu_timestamp_ms in (imu_start..=timestamp_ms).step_by(20) {
                fusion.push_imu(imu_timestamp_ms, vec![0.0, 0.0, GRAVITY], vec![0.0; 3]);
            }
            let snapshot = fusion
                .push_gps(
                    timestamp_ms,
                    41.7,
                    longitude_at(east_m),
                    None,
                    Some(8.0),
                    Some(2.8),
                    Some(if east_m < 0.0 { 270.0 } else { 90.0 }),
                )
                .unwrap();
            assert!(snapshot.stationary, "jump {index} released STILL");
            assert_eq!(snapshot.lat, first.lat);
            assert_eq!(snapshot.lon, first.lon);
        }
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
    fn manual_section_reset_clears_state_even_after_a_short_pause() {
        let fusion = LiveFusion::new();
        for timestamp_ms in (10..=1_090).step_by(20) {
            fusion.push_imu(timestamp_ms, vec![0.0, 0.0, GRAVITY], vec![0.0; 3]);
        }
        let stopped = fusion
            .push_gps(1_090, 41.7, 44.8, None, Some(4.0), Some(2.0), Some(90.0))
            .unwrap();
        assert!(stopped.stationary);
        {
            let mut state = fusion.state.lock().unwrap();
            assert_eq!(state.last_imu_ms, Some(1_090));
            assert!(!state.motion.is_empty());
            assert!(state.orientation.is_initialized());
            state.gps_motion_hold_until_ms = 5_000;
        }

        fusion.start_new_section();

        {
            let state = fusion.state.lock().unwrap();
            assert_eq!(state.last_imu_ms, None);
            assert!(state.motion.is_empty());
            assert!(!state.stationary);
            assert_eq!(state.gps_motion_hold_until_ms, i64::MIN);
            assert!(!state.orientation.is_initialized());
            assert!(state.ekf.is_some());
            assert!(state.horizontal_reseat_pending);
        }
        // Only 10 ms after the last pre-pause IMU: this must be the new
        // section's first sample, not another sample in the old STILL window.
        assert!(!fusion.push_imu(1_100, vec![0.0, 0.0, GRAVITY], vec![0.0; 3]));

        let resumed_lon =
            44.8 + (10.0 / (EARTH_RADIUS_M * 41.7_f64.to_radians().cos())).to_degrees();
        let resumed = fusion
            .push_gps(
                1_100,
                41.7,
                resumed_lon,
                None,
                Some(4.0),
                Some(2.0),
                Some(90.0),
            )
            .unwrap();
        let offset = project(resumed.lat, resumed.lon, 41.7, resumed_lon);
        assert!(!resumed.stationary);
        assert!(
            offset[0].hypot(offset[1]) < 0.01,
            "section GPS did not reseat: {offset:?}"
        );
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

    /// Metres north of 41.7 N as a latitude, for readable total assertions.
    fn north_of(base_lat: f64, metres: f64) -> f64 {
        base_lat + (metres / EARTH_RADIUS_M).to_degrees()
    }

    #[test]
    fn ride_totals_accumulate_distance_and_descent() {
        let fusion = LiveFusion::new();
        let mut last = None;
        for step in 0..30 {
            last = fusion.push_gps(
                step * 1_000,
                north_of(41.7, step as f64 * 10.0),
                44.8,
                Some(1_000.0 - step as f64 * 10.0),
                Some(4.0),
                Some(10.0),
                Some(0.0),
            );
        }
        let snapshot = last.expect("moving fixes produce snapshots");
        assert!(
            (289.5..=290.5).contains(&snapshot.distance_m),
            "distance {} m is not the ridden 290 m",
            snapshot.distance_m,
        );
        // The median window costs the two newest fixes; everything the rider
        // has already descended must be there.
        assert!(
            (260.0..=290.0).contains(&snapshot.descent_m),
            "descent {} m does not match a 290 m drop",
            snapshot.descent_m,
        );
    }

    #[test]
    fn stationary_jitter_adds_no_distance_and_no_descent() {
        let fusion = LiveFusion::new();
        let mut last = None;
        for step in 0..10 {
            // Sub-metre horizontal jitter and sub-hysteresis altitude noise:
            // a phone lying on a bench must not ride anywhere.
            let jitter = if step % 2 == 0 { 0.4 } else { -0.4 };
            last = fusion.push_gps(
                step * 1_000,
                north_of(41.7, jitter),
                44.8,
                Some(1_000.0 + jitter),
                Some(4.0),
                Some(0.2),
                Some(0.0),
            );
        }
        let snapshot = last.expect("accepted fixes produce snapshots");
        assert_eq!(snapshot.distance_m, 0.0);
        assert_eq!(snapshot.descent_m, 0.0);
    }

    #[test]
    fn manual_pause_gap_is_not_ridden_distance() {
        let fusion = LiveFusion::new();
        for step in 0..5 {
            fusion.push_gps(
                step * 1_000,
                north_of(41.7, step as f64 * 10.0),
                44.8,
                Some(1_000.0),
                Some(4.0),
                Some(10.0),
                Some(0.0),
            );
        }
        let before = fusion
            .push_gps(
                5_000,
                north_of(41.7, 50.0),
                44.8,
                Some(1_000.0),
                Some(4.0),
                Some(10.0),
                Some(0.0),
            )
            .expect("moving fix")
            .distance_m;

        // Rider pauses, drives 2 km, resumes: the transit belongs to no ride.
        fusion.start_new_section();
        let after = fusion
            .push_gps(
                600_000,
                north_of(41.7, 2_000.0),
                44.8,
                Some(1_000.0),
                Some(4.0),
                Some(10.0),
                Some(0.0),
            )
            .expect("first fix of the new section")
            .distance_m;
        assert_eq!(before, after);
    }

    #[test]
    fn seeded_totals_continue_instead_of_restarting() {
        let fusion = LiveFusion::new();
        fusion.seed_totals(1_234.0, 210.0);
        for step in 0..3 {
            fusion.push_gps(
                step * 1_000,
                north_of(41.7, step as f64 * 10.0),
                44.8,
                Some(1_000.0),
                Some(4.0),
                Some(10.0),
                Some(0.0),
            );
        }
        let snapshot = fusion
            .push_gps(
                3_000,
                north_of(41.7, 30.0),
                44.8,
                Some(1_000.0),
                Some(4.0),
                Some(10.0),
                Some(0.0),
            )
            .expect("moving fix");
        assert!(
            snapshot.distance_m > 1_234.0,
            "seeded distance {} m restarted",
            snapshot.distance_m,
        );
        assert_eq!(snapshot.descent_m, 210.0);
    }

    /// Feeds `seconds` of GPS at 1 Hz climbing at `vertical_speed_mps`.
    fn climb(fusion: &LiveFusion, start_ms: i64, seconds: i64, vertical_speed_mps: f64) {
        for step in 0..seconds {
            fusion.push_gps(
                start_ms + step * 1_000,
                north_of(41.7, step as f64 * 6.0),
                44.8,
                Some(500.0 + vertical_speed_mps * step as f64),
                Some(4.0),
                Some(6.0),
                Some(0.0),
            );
        }
    }

    #[test]
    fn a_shuttle_climb_raises_the_transport_hint() {
        let fusion = LiveFusion::new();
        climb(&fusion, 0, 120, 1.8);
        assert!(fusion.motorized_hint(), "a 1.8 m/s climb is not a rider");
    }

    #[test]
    fn a_rider_climbing_hard_never_raises_the_hint() {
        let fusion = LiveFusion::new();
        climb(&fusion, 0, 300, 0.45);
        assert!(!fusion.motorized_hint(), "a human climb entered power save");
    }

    #[test]
    fn the_hint_drops_as_soon_as_the_descent_starts() {
        let fusion = LiveFusion::new();
        climb(&fusion, 0, 120, 1.8);
        assert!(fusion.motorized_hint());

        // Over the top and pointing down: full rates must come back.
        for step in 0..40 {
            fusion.push_gps(
                120_000 + step * 1_000,
                north_of(41.7, 720.0 + step as f64 * 8.0),
                44.8,
                Some(716.0 - step as f64 * 2.0),
                Some(4.0),
                Some(8.0),
                Some(180.0),
            );
        }
        assert!(!fusion.motorized_hint(), "power save survived the descent");
    }

    #[test]
    fn a_manual_pause_drops_the_hint() {
        let fusion = LiveFusion::new();
        climb(&fusion, 0, 120, 1.8);
        assert!(fusion.motorized_hint());
        fusion.start_new_section();
        assert!(!fusion.motorized_hint());
    }

    #[test]
    fn android_saying_vehicle_enters_power_save_without_a_climb() {
        let fusion = LiveFusion::new();
        fusion.set_platform_vehicle(0, Some(true));
        // Flat city traffic: no climb, barely any speed, plenty of standing.
        for step in 0..60 {
            fusion.push_gps(
                step * 1_000,
                north_of(41.7, step as f64 * 1.5),
                44.8,
                Some(400.0),
                Some(6.0),
                Some(1.5),
                Some(0.0),
            );
        }
        assert!(fusion.motorized_hint(), "flat congestion never entered");
    }

    #[test]
    fn android_saying_bicycle_ends_power_save_at_once() {
        let fusion = LiveFusion::new();
        climb(&fusion, 0, 120, 1.8);
        assert!(fusion.motorized_hint());
        fusion.set_platform_vehicle(120_000, Some(false));
        assert!(!fusion.motorized_hint(), "the bike hint was ignored");
    }

    #[test]
    fn android_saying_vehicle_cannot_hold_power_save_through_a_descent() {
        let fusion = LiveFusion::new();
        fusion.set_platform_vehicle(0, Some(true));
        climb(&fusion, 0, 120, 1.8);
        assert!(fusion.motorized_hint());

        for step in 0..40 {
            fusion.push_gps(
                120_000 + step * 1_000,
                north_of(41.7, 720.0 + step as f64 * 8.0),
                44.8,
                Some(716.0 - step as f64 * 2.0),
                Some(4.0),
                Some(8.0),
                Some(180.0),
            );
        }
        assert!(
            !fusion.motorized_hint(),
            "a platform hint outranked a real descent",
        );
    }

    /// One second of a vehicle floor: smooth enough that nothing reads as
    /// riding, delivered at the same 50 Hz Android feeds live fusion.
    fn smooth_second(fusion: &LiveFusion, start_ms: i64) {
        for sample in 0..50 {
            fusion.push_imu(
                start_ms + sample * 20,
                vec![0.02, -0.01, GRAVITY + 0.03],
                vec![0.004, 0.002, -0.003],
            );
        }
    }

    fn rough_second(fusion: &LiveFusion, start_ms: i64) {
        for sample in 0..50 {
            let shake = if sample % 2 == 0 { 2.6 } else { -2.2 };
            fusion.push_imu(
                start_ms + sample * 20,
                vec![shake, -1.4, GRAVITY + shake],
                vec![0.5, -0.4, 0.3],
            );
        }
    }

    /// Rides a profile at 6 m/s with a vehicle floor under the phone.
    fn smooth_leg(
        fusion: &LiveFusion,
        start_ms: i64,
        start_m: f64,
        start_altitude_m: f64,
        seconds: i64,
        vertical_speed_mps: f64,
        rough: bool,
    ) {
        for step in 0..seconds {
            let at = start_ms + step * 1_000;
            if rough {
                rough_second(fusion, at);
            } else {
                smooth_second(fusion, at);
            }
            fusion.push_gps(
                at,
                north_of(41.7, start_m + step as f64 * 6.0),
                44.8,
                Some(start_altitude_m + vertical_speed_mps * step as f64),
                Some(4.0),
                Some(6.0),
                Some(0.0),
            );
        }
    }

    #[test]
    fn a_dip_between_switchbacks_keeps_power_saving_on() {
        let fusion = LiveFusion::new();
        smooth_leg(&fusion, 0, 0.0, 400.0, 120, 1.8, false);
        assert!(fusion.motorized_hint(), "the shuttle climb never entered");

        // The serpentine gives back 25 m over half a minute, then climbs on.
        smooth_leg(&fusion, 120_000, 720.0, 616.0, 30, -0.85, false);

        assert!(
            fusion.motorized_hint(),
            "a road dip was mistaken for the start of a run",
        );
    }

    #[test]
    fn a_rough_descent_ends_power_saving_even_when_shallow() {
        let fusion = LiveFusion::new();
        smooth_leg(&fusion, 0, 0.0, 400.0, 120, 1.8, false);
        assert!(fusion.motorized_hint());

        // Same shallow profile, but the phone is being shaken by a trail.
        smooth_leg(&fusion, 120_000, 720.0, 616.0, 30, -0.85, true);

        assert!(
            !fusion.motorized_hint(),
            "trail roughness did not end power saving",
        );
    }
}
