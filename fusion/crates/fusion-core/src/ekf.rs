//! Position/velocity error-state Kalman filter fusing GPS with IMU-derived
//! world-frame linear acceleration.
//!
//! # Frame and state convention
//!
//! Local ENU frame anchored at the FIRST ACCEPTED GPS fix:
//! - x = east (m), y = north (m), z = up (m), origin at the anchor fix
//!   (horizontal) and at the anchor's GPS altitude (vertical, so `p_u = 0`
//!   means "anchor altitude").
//! - State `x = [p_e, p_n, p_u, v_e, v_n, v_u]` (meters, meters/second).
//!
//! # Why NO accel-bias states (deliberate, revisit if data disagrees)
//!
//! A 9-state variant (3 accel biases) was considered and skipped:
//! - The Mahony integral term already absorbs gyro bias; the dominant accel
//!   error is attitude error projecting gravity, which is NOT a constant
//!   body-frame bias — a bias state would chase it without converging.
//! - Horizontal accel is heading-ambiguous anyway (yaw unobservable), so a
//!   horizontal bias is unidentifiable; vertical drift is bounded by GPS
//!   altitude + ZUPT updates at ride timescales.
//! - 6 states keep every matrix op trivially cheap on-device.
//!
//! # Tuning constants (hand-tuned for MTB dynamics; see each constant)
//!
//! Process noise is the classic piecewise-white-acceleration model per axis:
//! `Q_axis = sigma^2 * [[dt^4/4, dt^3/2], [dt^3/2, dt^2]]` over (p, v).

use crate::linalg::{Mat, Vector};

/// Horizontal white-acceleration process noise, m/s^2 (1 sigma).
/// Large: the world-frame horizontal accel has an arbitrary heading (no
/// magnetometer), so the filter must trust GPS position/velocity for the
/// horizontal channel and treat IMU horizontal input as a weak hint.
pub const SIGMA_ACCEL_H: f64 = 2.5;
/// Vertical white-acceleration process noise, m/s^2 (1 sigma).
/// Small: the vertical linear accel is yaw-invariant and gravity-referenced,
/// so it is genuinely informative; residual error is attitude error
/// (~2 deg -> ~0.35 m/s^2) plus device vibration leakage.
pub const SIGMA_ACCEL_V: f64 = 0.8;

/// Floor on reported GPS horizontal accuracy, m. Android accuracy numbers
/// are optimistic (~68% circle) and occasionally absurdly small.
pub const GPS_H_ACC_FLOOR_M: f64 = 3.0;
/// GPS altitude error is empirically ~2-3x the horizontal accuracy.
pub const GPS_V_ACC_FACTOR: f64 = 2.5;
/// Floor on GPS altitude sigma, m ((5 m)^2 variance floor per the design).
pub const GPS_V_ACC_FLOOR_M: f64 = 5.0;
/// GPS-reported speed/bearing converted to a velocity measurement, 1 sigma.
pub const GPS_VEL_SIGMA_MPS: f64 = 0.7;
/// ZUPT pseudo-measurement noise, 1 sigma (m/s). Tight but not zero.
pub const ZUPT_SIGMA_MPS: f64 = 0.05;

/// Mahalanobis-squared gate for the 2-DOF horizontal position update.
/// Chi-square(2) at 99.9% is 13.8; slightly looser because GPS accuracy
/// estimates run optimistic. A 60 m outlier against a 4 m sigma scores in
/// the hundreds and dies here.
pub const GATE_POS: f64 = 18.0;
/// Gate for the 1-DOF altitude update (chi-square(1) 99.9% = 10.8).
pub const GATE_ALT: f64 = 12.0;
/// Gate for the 2-DOF velocity update.
pub const GATE_VEL: f64 = 18.0;
/// After this many CONSECUTIVE rejected updates on a channel, stop trusting
/// the state: re-seat the state on the measurement and reset that channel's
/// covariance. Prevents a filter that diverged (or a GPS reference shift)
/// from rejecting reality forever.
pub const MAX_CONSECUTIVE_REJECTS: u32 = 5;

/// Initial velocity sigma when the first fix has no usable speed, m/s.
const INIT_VEL_SIGMA: f64 = 3.0;

/// Result of one measurement update, for diagnostics.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UpdateOutcome {
    Accepted,
    /// Rejected by the Mahalanobis gate.
    Rejected,
    /// Rejected too many consecutive times: state/covariance re-seated on
    /// the measurement (counts as accepted afterwards).
    Reseated,
}

/// 6-state position/velocity EKF (linear measurement models, so technically
/// a Kalman filter; "EKF" kept for the eventual nonlinear extensions).
#[derive(Debug)]
pub struct Ekf {
    /// State [p_e, p_n, p_u, v_e, v_n, v_u].
    x: Vector<6>,
    p: Mat<6, 6>,
    pos_rejects: u32,
    alt_rejects: u32,
}

impl Ekf {
    /// Initializes at the anchor fix: position is the frame origin, altitude
    /// origin too. `vel_en` from GPS speed/bearing when available.
    pub fn new(h_acc_m: f64, vel_en: Option<[f64; 2]>) -> Self {
        let h_acc = h_acc_m.max(GPS_H_ACC_FLOOR_M);
        let v_acc = (h_acc * GPS_V_ACC_FACTOR).max(GPS_V_ACC_FLOOR_M);
        let (v0, vel_sigma) = match vel_en {
            Some([ve, vn]) => ([ve, vn], GPS_VEL_SIGMA_MPS),
            None => ([0.0, 0.0], INIT_VEL_SIGMA),
        };
        Ekf {
            x: Vector::from_array([0.0, 0.0, 0.0, v0[0], v0[1], 0.0]),
            p: Mat::diag([
                h_acc * h_acc,
                h_acc * h_acc,
                v_acc * v_acc,
                vel_sigma * vel_sigma,
                vel_sigma * vel_sigma,
                INIT_VEL_SIGMA * INIT_VEL_SIGMA,
            ]),
            pos_rejects: 0,
            alt_rejects: 0,
        }
    }

    pub fn position(&self) -> [f64; 3] {
        [self.x[(0, 0)], self.x[(1, 0)], self.x[(2, 0)]]
    }

    pub fn velocity(&self) -> [f64; 3] {
        [self.x[(3, 0)], self.x[(4, 0)], self.x[(5, 0)]]
    }

    /// Constant-acceleration predict over `dt` seconds with world-frame
    /// linear acceleration `a` (ENU, gravity already removed).
    ///
    /// During GPS gaps this simply keeps running; Q inflates the covariance
    /// (quartically in position), so when GPS returns the Mahalanobis gate
    /// naturally re-admits it — no special gap handling needed.
    pub fn predict(&mut self, a: [f64; 3], dt: f64) {
        if dt <= 0.0 || !dt.is_finite() {
            return;
        }
        let dt2 = dt * dt;

        // x = F x + B a  (position += v dt + a dt^2/2, velocity += a dt)
        for (i, acceleration) in a.iter().enumerate() {
            self.x[(i, 0)] += self.x[(i + 3, 0)] * dt + 0.5 * acceleration * dt2;
            self.x[(i + 3, 0)] += acceleration * dt;
        }

        // P = F P F^T + Q, with F = [[I, dt I], [0, I]] expanded in place:
        // exploiting the block structure is clearer AND cheaper than a full
        // 6x6 multiply. Blocks: Ppp += dt(Ppv + Pvp) + dt^2 Pvv;
        // Ppv += dt Pvv; Pvp symmetric; Pvv unchanged.
        for r in 0..3 {
            for c in 0..3 {
                self.p[(r, c)] +=
                    dt * (self.p[(r, c + 3)] + self.p[(r + 3, c)]) + dt2 * self.p[(r + 3, c + 3)];
            }
        }
        for r in 0..3 {
            for c in 0..3 {
                let d = dt * self.p[(r + 3, c + 3)];
                self.p[(r, c + 3)] += d;
                self.p[(c + 3, r)] += d;
            }
        }

        // Q: piecewise white acceleration, per axis.
        for i in 0..3 {
            let sigma = if i == 2 { SIGMA_ACCEL_V } else { SIGMA_ACCEL_H };
            let s2 = sigma * sigma;
            self.p[(i, i)] += s2 * dt2 * dt2 / 4.0;
            self.p[(i + 3, i + 3)] += s2 * dt2;
            let q_pv = s2 * dt * dt2 / 2.0;
            self.p[(i, i + 3)] += q_pv;
            self.p[(i + 3, i)] += q_pv;
        }
    }

    /// GPS horizontal position update. `z` in local ENU meters.
    pub fn update_gps_position(&mut self, z: [f64; 2], h_acc_m: f64) -> UpdateOutcome {
        let sigma = h_acc_m.max(GPS_H_ACC_FLOOR_M);
        let out = self.update::<2>([0, 1], z, [sigma * sigma; 2], Some(GATE_POS));
        match out {
            UpdateOutcome::Accepted => self.pos_rejects = 0,
            UpdateOutcome::Rejected => {
                self.pos_rejects += 1;
                if self.pos_rejects >= MAX_CONSECUTIVE_REJECTS {
                    // Recover: trust the measurement stream over the state.
                    self.reseat([0, 1], z, sigma * sigma * 9.0);
                    self.pos_rejects = 0;
                    return UpdateOutcome::Reseated;
                }
            }
            UpdateOutcome::Reseated => unreachable!(),
        }
        out
    }

    /// GPS altitude update, local frame ("up" meters above the anchor fix).
    /// Kept SEPARATE from the horizontal update: altitude error is 2-3x
    /// horizontal and poorly correlated with it, so it gets its own inflated
    /// R and its own gate instead of polluting a joint 3D update.
    pub fn update_gps_altitude(&mut self, z_up: f64, h_acc_m: f64) -> UpdateOutcome {
        let sigma = (h_acc_m.max(GPS_H_ACC_FLOOR_M) * GPS_V_ACC_FACTOR).max(GPS_V_ACC_FLOOR_M);
        let out = self.update::<1>([2], [z_up], [sigma * sigma], Some(GATE_ALT));
        match out {
            UpdateOutcome::Accepted => self.alt_rejects = 0,
            UpdateOutcome::Rejected => {
                self.alt_rejects += 1;
                if self.alt_rejects >= MAX_CONSECUTIVE_REJECTS {
                    self.reseat([2], [z_up], sigma * sigma * 9.0);
                    self.alt_rejects = 0;
                    return UpdateOutcome::Reseated;
                }
            }
            UpdateOutcome::Reseated => unreachable!(),
        }
        out
    }

    /// GPS speed+bearing as a horizontal velocity measurement (ENU m/s).
    pub fn update_gps_velocity(&mut self, vel_en: [f64; 2]) -> UpdateOutcome {
        self.update::<2>(
            [3, 4],
            vel_en,
            [GPS_VEL_SIGMA_MPS * GPS_VEL_SIGMA_MPS; 2],
            Some(GATE_VEL),
        )
    }

    /// Zero-velocity update: the IMU says the device is stationary.
    /// Ungated — the stationarity detector is the gate.
    pub fn update_zupt(&mut self) {
        self.update::<3>(
            [3, 4, 5],
            [0.0; 3],
            [ZUPT_SIGMA_MPS * ZUPT_SIGMA_MPS; 3],
            None,
        );
    }

    /// Generic Kalman update for measurements that OBSERVE STATE COMPONENTS
    /// DIRECTLY (H is a selector matrix picking `idx`), which covers every
    /// measurement this filter has. `gate` is a Mahalanobis-squared bound.
    fn update<const M: usize>(
        &mut self,
        idx: [usize; M],
        z: [f64; M],
        r_diag: [f64; M],
        gate: Option<f64>,
    ) -> UpdateOutcome {
        // Innovation y = z - H x, S = H P H^T + R.
        let mut y = Vector::<M>::zeros();
        let mut s = Mat::<M, M>::zeros();
        for i in 0..M {
            y[(i, 0)] = z[i] - self.x[(idx[i], 0)];
            for j in 0..M {
                s[(i, j)] = self.p[(idx[i], idx[j])];
            }
            s[(i, i)] += r_diag[i];
        }
        let Some(s_inv) = s.inverse() else {
            // Degenerate S (should not happen with positive R): skip.
            return UpdateOutcome::Rejected;
        };

        if let Some(g) = gate {
            let d2 = (y.transpose() * s_inv * y)[(0, 0)];
            if d2 > g {
                return UpdateOutcome::Rejected;
            }
        }

        // K = P H^T S^-1  (6xM); P H^T is just the picked columns of P.
        let mut pht = Mat::<6, M>::zeros();
        for r in 0..6 {
            for j in 0..M {
                pht[(r, j)] = self.p[(r, idx[j])];
            }
        }
        let k = pht * s_inv;

        // x += K y
        let dx = k * y;
        for r in 0..6 {
            self.x[(r, 0)] += dx[(r, 0)];
        }

        // Joseph-form covariance update for numerical robustness:
        // P = (I - K H) P (I - K H)^T + K R K^T.
        let mut ikh = Mat::<6, 6>::identity();
        for r in 0..6 {
            for j in 0..M {
                ikh[(r, idx[j])] -= k[(r, j)];
            }
        }
        let mut krk = Mat::<6, 6>::zeros();
        for r in 0..6 {
            for c in 0..6 {
                let mut acc = 0.0;
                for j in 0..M {
                    acc += k[(r, j)] * r_diag[j] * k[(c, j)];
                }
                krk[(r, c)] = acc;
            }
        }
        self.p = ikh * self.p * ikh.transpose() + krk;
        self.p.symmetrize();

        UpdateOutcome::Accepted
    }

    /// Re-seats the given state components on a measurement after prolonged
    /// rejection: overwrite the state, reset those variances (inflated), and
    /// clear their cross-covariances so stale correlations cannot drag the
    /// re-seated components away again.
    fn reseat<const M: usize>(&mut self, idx: [usize; M], z: [f64; M], var: f64) {
        for i in 0..M {
            self.x[(idx[i], 0)] = z[i];
            for c in 0..6 {
                self.p[(idx[i], c)] = 0.0;
                self.p[(c, idx[i])] = 0.0;
            }
            self.p[(idx[i], idx[i])] = var;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Deterministic LCG so tests need no rand dependency.
    pub struct Lcg(u64);
    impl Lcg {
        pub fn new(seed: u64) -> Self {
            Lcg(seed)
        }
        /// Uniform in [-1, 1).
        pub fn uniform(&mut self) -> f64 {
            self.0 = self
                .0
                .wrapping_mul(6364136223846793005)
                .wrapping_add(1442695040888963407);
            ((self.0 >> 11) as f64 / (1u64 << 53) as f64) * 2.0 - 1.0
        }
        /// Approximately normal via Irwin-Hall: sum of 6 uniforms in
        /// [-1, 1) has variance 6 * (1/3) = 2, so divide by sqrt(2).
        pub fn gauss(&mut self) -> f64 {
            let mut s = 0.0;
            for _ in 0..6 {
                s += self.uniform();
            }
            s / std::f64::consts::SQRT_2
        }
    }

    #[test]
    fn straight_constant_velocity_beats_gps_noise() {
        // Truth: eastward 5 m/s. GPS at 1 Hz with sigma = 4 m. IMU predicts
        // at 25 Hz with zero linear accel (true, since velocity is constant).
        let mut rng = Lcg::new(42);
        let mut ekf = Ekf::new(4.0, Some([5.0, 0.0]));
        let mut sum_sq_err = 0.0;
        let mut n = 0;
        let mut sum_sq_gps_err = 0.0;
        let dt = 0.04;
        let mut t = 0.0;
        let mut next_gps = 1.0;
        while t < 120.0 {
            ekf.predict([0.0, 0.0, 0.0], dt);
            t += dt;
            if t >= next_gps {
                let true_e = 5.0 * t;
                let ge = true_e + 4.0 * rng.gauss();
                let gn = 4.0 * rng.gauss();
                sum_sq_gps_err += (ge - true_e).powi(2) + gn.powi(2);
                ekf.update_gps_position([ge, gn], 4.0);
                let [pe, pn, _] = ekf.position();
                sum_sq_err += (pe - true_e).powi(2) + pn.powi(2);
                n += 1;
                next_gps += 1.0;
            }
        }
        let rms = (sum_sq_err / n as f64).sqrt();
        let gps_rms = (sum_sq_gps_err / n as f64).sqrt();
        assert!(rms < gps_rms, "fused RMS {rms} must beat GPS RMS {gps_rms}");
        let [ve, vn, _] = ekf.velocity();
        // A position-only filter's instantaneous terminal velocity still
        // follows the final noisy fix; the full-run RMS assertion above is
        // the important quality bound.
        assert!((ve - 5.0).abs() < 1.0, "fused v_e {ve}");
        assert!(vn.abs() < 0.5, "fused v_n {vn}");
    }

    #[test]
    fn sixty_meter_outliers_are_gated() {
        let mut ekf = Ekf::new(4.0, Some([5.0, 0.0]));
        let dt = 0.04;
        let mut t = 0.0;
        let mut next_gps = 1.0;
        let mut rejected = 0;
        while t < 30.0 {
            ekf.predict([0.0, 0.0, 0.0], dt);
            t += dt;
            if t >= next_gps {
                let true_e = 5.0 * t;
                // Every 5th fix jumps 60 m sideways.
                let outlier = (next_gps as i64) % 5 == 0;
                let z = if outlier {
                    [true_e, 60.0]
                } else {
                    [true_e, 0.0]
                };
                let out = ekf.update_gps_position(z, 4.0);
                if outlier {
                    assert_eq!(out, UpdateOutcome::Rejected, "outlier at t={t}");
                    rejected += 1;
                }
                let [_, pn, _] = ekf.position();
                assert!(pn.abs() < 10.0, "north drift {pn} after outliers");
                next_gps += 1.0;
            }
        }
        assert!(rejected >= 4);
    }

    #[test]
    fn prolonged_rejection_reseats_the_filter() {
        // A permanent 100 m reference shift: the gate must not lock the
        // filter out forever — after MAX_CONSECUTIVE_REJECTS it re-seats.
        let mut ekf = Ekf::new(4.0, Some([0.0, 0.0]));
        let mut saw_reseat = false;
        for i in 0..10 {
            ekf.predict([0.0, 0.0, 0.0], 1.0);
            let out = ekf.update_gps_position([100.0, 0.0], 4.0);
            if out == UpdateOutcome::Reseated {
                saw_reseat = true;
            }
            if i > 6 {
                assert_ne!(out, UpdateOutcome::Rejected, "still rejecting at fix {i}");
            }
        }
        assert!(saw_reseat);
        let [pe, _, _] = ekf.position();
        assert!((pe - 100.0).abs() < 5.0, "position not re-seated: {pe}");
    }

    #[test]
    fn covariance_inflates_through_gps_gap_and_readmits() {
        let mut ekf = Ekf::new(4.0, Some([5.0, 0.0]));
        // 30 s with no GPS at all.
        for _ in 0..(30 * 25) {
            ekf.predict([0.0, 0.0, 0.0], 0.04);
        }
        // GPS returns 40 m away from the propagated state; the inflated
        // covariance must let it in.
        let [pe, pn, _] = ekf.position();
        let out = ekf.update_gps_position([pe + 40.0, pn - 40.0], 4.0);
        assert_eq!(out, UpdateOutcome::Accepted);
    }

    #[test]
    fn zupt_pins_velocity() {
        let mut ekf = Ekf::new(4.0, Some([3.0, -2.0]));
        for _ in 0..25 {
            ekf.predict([0.0, 0.0, 0.0], 0.04);
            ekf.update_zupt();
        }
        let v = ekf.velocity();
        let speed = (v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).sqrt();
        assert!(speed < 0.05, "speed after ZUPTs: {speed}");
    }

    #[test]
    fn altitude_update_tracks_descent() {
        // Truth: descending at -1 m/s, honest vertical accel = 0 after the
        // initial transient (we just feed the true constant-velocity state).
        let mut ekf = Ekf::new(4.0, None);
        let mut t = 0.0;
        let dt = 0.04;
        let mut next_gps = 1.0;
        let mut rng = Lcg::new(7);
        while t < 60.0 {
            ekf.predict([0.0, 0.0, 0.0], dt);
            t += dt;
            if t >= next_gps {
                let true_up = -t;
                ekf.update_gps_altitude(true_up + 4.0 * rng.gauss(), 4.0);
                next_gps += 1.0;
            }
        }
        let [_, _, pu] = ekf.position();
        assert!((pu - (-60.0)).abs() < 6.0, "altitude estimate {pu}");
        let [_, _, vu] = ekf.velocity();
        assert!((vu - (-1.0)).abs() < 0.4, "vertical velocity {vu}");
    }
}
