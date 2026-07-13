//! Attitude estimation: Mahony-style complementary filter on gyro + accel.
//!
//! Purpose in the pipeline: track the GRAVITY direction so device-frame
//! specific force can be rotated into the world frame and gravity removed,
//! yielding world-frame linear acceleration for the EKF.
//!
//! Frames and conventions:
//! - World frame is local ENU (x = east, y = north, z = up).
//! - The quaternion rotates BODY vectors into WORLD vectors.
//! - Yaw is UNOBSERVABLE without a magnetometer (we start at an arbitrary
//!   yaw and it drifts with gyro bias). That is accepted: roll/pitch — i.e.
//!   the gravity direction — is what matters. The z (vertical) component of
//!   the world-frame linear acceleration is invariant under yaw error, so
//!   the EKF altitude channel gets a clean signal; horizontal components
//!   point in an arbitrary heading and are currently disabled for live
//!   position prediction after real trail vibration caused severe drift.
//! - No magnetometer aiding for now: forest + bike frame + motor-less but
//!   magnet-rich environment makes phone mag data untrustworthy, and we do
//!   not need yaw.

/// Standard gravity, m/s^2.
pub const GRAVITY: f64 = 9.80665;

/// Proportional feedback gain (rad/s per rad of gravity error). Hand-tuned:
/// high enough to track slow attitude drift, low enough that sustained
/// linear accelerations (braking, berms) do not tilt the estimate much.
const KP: f64 = 1.5;
/// Integral feedback gain — absorbs slow gyro bias.
const KI: f64 = 0.05;
/// For the first seconds after start, use an aggressive proportional gain so
/// the filter converges before the ride starts moving.
const KP_STARTUP: f64 = 10.0;
const STARTUP_S: f64 = 2.0;
/// Accel correction is only applied when |accel| is within this band around
/// 1 g — outside it the accelerometer is dominated by linear acceleration
/// (jumps, landings, braking) and says nothing about gravity direction.
const ACCEL_TRUST_MIN: f64 = 0.6 * GRAVITY;
const ACCEL_TRUST_MAX: f64 = 1.4 * GRAVITY;

/// Unit quaternion (w, x, y, z) rotating body-frame vectors to world frame.
#[derive(Debug, Clone, Copy)]
pub struct Quat {
    pub w: f64,
    pub x: f64,
    pub y: f64,
    pub z: f64,
}

impl Quat {
    const IDENTITY: Quat = Quat {
        w: 1.0,
        x: 0.0,
        y: 0.0,
        z: 0.0,
    };

    fn normalize(&mut self) {
        let n = (self.w * self.w + self.x * self.x + self.y * self.y + self.z * self.z).sqrt();
        if n > 0.0 {
            self.w /= n;
            self.x /= n;
            self.y /= n;
            self.z /= n;
        } else {
            *self = Quat::IDENTITY;
        }
    }

    /// Rotates a body-frame vector into the world frame (plain rotation
    /// matrix expansion — clarity over cycles, this runs at ~100 Hz).
    pub fn rotate(&self, v: [f64; 3]) -> [f64; 3] {
        let (w, x, y, z) = (self.w, self.x, self.y, self.z);
        [
            (1.0 - 2.0 * (y * y + z * z)) * v[0]
                + 2.0 * (x * y - w * z) * v[1]
                + 2.0 * (x * z + w * y) * v[2],
            2.0 * (x * y + w * z) * v[0]
                + (1.0 - 2.0 * (x * x + z * z)) * v[1]
                + 2.0 * (y * z - w * x) * v[2],
            2.0 * (x * z - w * y) * v[0]
                + 2.0 * (y * z + w * x) * v[1]
                + (1.0 - 2.0 * (x * x + y * y)) * v[2],
        ]
    }

    /// Rotates a world-frame vector into the body frame (inverse rotation).
    pub fn rotate_inv(&self, v: [f64; 3]) -> [f64; 3] {
        let conj = Quat {
            w: self.w,
            x: -self.x,
            y: -self.y,
            z: -self.z,
        };
        conj.rotate(v)
    }

    /// Shortest-arc quaternion rotating unit vector `from` onto unit `to`.
    fn from_two_vectors(from: [f64; 3], to: [f64; 3]) -> Quat {
        let d = dot(from, to);
        if d < -0.999_999 {
            // 180 degrees: pick any orthogonal axis.
            let axis = if from[0].abs() < 0.9 {
                normalize(cross([1.0, 0.0, 0.0], from))
            } else {
                normalize(cross([0.0, 1.0, 0.0], from))
            };
            return Quat {
                w: 0.0,
                x: axis[0],
                y: axis[1],
                z: axis[2],
            };
        }
        let c = cross(from, to);
        let mut q = Quat {
            w: 1.0 + d,
            x: c[0],
            y: c[1],
            z: c[2],
        };
        q.normalize();
        q
    }
}

fn cross(a: [f64; 3], b: [f64; 3]) -> [f64; 3] {
    [
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    ]
}

fn dot(a: [f64; 3], b: [f64; 3]) -> f64 {
    a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}

fn norm(a: [f64; 3]) -> f64 {
    dot(a, a).sqrt()
}

fn normalize(a: [f64; 3]) -> [f64; 3] {
    let n = norm(a);
    if n > 0.0 {
        [a[0] / n, a[1] / n, a[2] / n]
    } else {
        a
    }
}

/// Mahony complementary attitude filter.
///
/// Feed it bucket-averaged IMU samples (~100 Hz); read back world-frame
/// linear acceleration via [`Mahony::world_linear_accel`].
#[derive(Debug)]
pub struct Mahony {
    q: Quat,
    /// Integral feedback term (gyro bias estimate), body frame, rad/s.
    integral: [f64; 3],
    /// Seconds of samples processed so far (drives the startup gain).
    elapsed_s: f64,
    initialized: bool,
}

impl Default for Mahony {
    fn default() -> Self {
        Self::new()
    }
}

impl Mahony {
    pub fn new() -> Self {
        Mahony {
            q: Quat::IDENTITY,
            integral: [0.0; 3],
            elapsed_s: 0.0,
            initialized: false,
        }
    }

    /// Advances the filter by `dt` seconds using body-frame `gyro` (rad/s)
    /// and `accel` (m/s^2, specific force).
    pub fn update(&mut self, accel: [f64; 3], gyro: [f64; 3], dt: f64) {
        if dt <= 0.0 || !dt.is_finite() || dt > 0.5 {
            // Nonsense or huge gap: re-anchor from accel on the next sample
            // rather than integrating garbage.
            if dt > 0.5 {
                self.initialized = false;
            }
            return;
        }

        let a_norm = norm(accel);
        if !self.initialized {
            if a_norm > 1e-3 {
                // Align the measured accel direction (up, in body frame at
                // rest) with world +z. Yaw starts arbitrary (unobservable).
                self.q = Quat::from_two_vectors(normalize(accel), [0.0, 0.0, 1.0]);
                self.initialized = true;
            }
            return;
        }

        let kp = if self.elapsed_s < STARTUP_S {
            KP_STARTUP
        } else {
            KP
        };

        // Gravity-direction error: measured "up" (normalized accel) vs the
        // filter's "up" expressed in the body frame.
        let mut err = [0.0; 3];
        if (ACCEL_TRUST_MIN..=ACCEL_TRUST_MAX).contains(&a_norm) {
            let a_hat = [accel[0] / a_norm, accel[1] / a_norm, accel[2] / a_norm];
            let v_hat = self.q.rotate_inv([0.0, 0.0, 1.0]);
            err = cross(a_hat, v_hat);
        }

        for (i, error) in err.iter().enumerate() {
            self.integral[i] += KI * error * dt;
        }
        let omega = [
            gyro[0] + kp * err[0] + self.integral[0],
            gyro[1] + kp * err[1] + self.integral[1],
            gyro[2] + kp * err[2] + self.integral[2],
        ];

        // Quaternion kinematics: q_dot = 0.5 * q * (0, omega).
        let (w, x, y, z) = (self.q.w, self.q.x, self.q.y, self.q.z);
        let (gx, gy, gz) = (omega[0], omega[1], omega[2]);
        self.q.w += 0.5 * dt * (-x * gx - y * gy - z * gz);
        self.q.x += 0.5 * dt * (w * gx + y * gz - z * gy);
        self.q.y += 0.5 * dt * (w * gy - x * gz + z * gx);
        self.q.z += 0.5 * dt * (w * gz + x * gy - y * gx);
        self.q.normalize();

        self.elapsed_s += dt;
    }

    /// World-frame linear acceleration (m/s^2): attitude-rotated specific
    /// force with gravity removed. ENU, z up. During a free fall this reads
    /// approximately (0, 0, -g).
    pub fn world_linear_accel(&self, accel: [f64; 3]) -> [f64; 3] {
        let a_world = self.q.rotate(accel);
        [a_world[0], a_world[1], a_world[2] - GRAVITY]
    }

    /// Angular rate around the gravity axis, in rad/s. Unlike horizontal
    /// heading, this component is invariant to the filter's arbitrary yaw and
    /// can locate a turn inside a GPS interval even when the phone is mounted
    /// at an arbitrary roll/pitch orientation.
    pub fn vertical_angular_rate(&self, gyro: [f64; 3]) -> f64 {
        self.q.rotate(gyro)[2]
    }

    /// True once the filter has been anchored to an accel sample.
    pub fn is_initialized(&self) -> bool {
        self.initialized
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Feeds static IMU data for a device tilted 30 degrees about x and
    /// checks the filter converges: world linear accel goes to ~0.
    #[test]
    fn converges_on_tilted_static_data() {
        // Device tilted: gravity reads on both y and z body axes.
        let tilt = 30.0f64.to_radians();
        let accel = [0.0, GRAVITY * tilt.sin(), GRAVITY * tilt.cos()];
        let gyro = [0.0, 0.0, 0.0];

        let mut f = Mahony::new();
        for _ in 0..300 {
            // 3 s at 100 Hz.
            f.update(accel, gyro, 0.01);
        }
        let lin = f.world_linear_accel(accel);
        let mag = (lin[0] * lin[0] + lin[1] * lin[1] + lin[2] * lin[2]).sqrt();
        assert!(mag < 0.05, "residual linear accel {mag} m/s^2");
    }

    /// Starts the filter deliberately mis-aligned (identity attitude, tilted
    /// device) and verifies the accel feedback pulls it back.
    #[test]
    fn recovers_from_wrong_initial_attitude() {
        let tilt = 45.0f64.to_radians();
        let accel = [GRAVITY * tilt.sin(), 0.0, GRAVITY * tilt.cos()];
        let mut f = Mahony::new();
        // First sample initializes exactly; rotate the device afterwards by
        // feeding a different gravity direction with zero gyro (as if the
        // gyro missed the rotation entirely) — the worst case for feedback.
        f.update([0.0, 0.0, GRAVITY], [0.0, 0.0, 0.0], 0.01);
        for _ in 0..1000 {
            f.update(accel, [0.0, 0.0, 0.0], 0.01);
        }
        let lin = f.world_linear_accel(accel);
        let mag = (lin[0] * lin[0] + lin[1] * lin[1] + lin[2] * lin[2]).sqrt();
        assert!(mag < 0.15, "residual linear accel {mag} m/s^2");
    }

    /// Gravity direction must survive a slow rotation tracked by the gyro.
    #[test]
    fn tracks_gyro_rotation() {
        let mut f = Mahony::new();
        f.update([0.0, 0.0, GRAVITY], [0.0; 3], 0.01);
        // Rotate 90 degrees about body x over 1 s; accel follows gravity.
        let rate = std::f64::consts::FRAC_PI_2;
        let steps = 100;
        for i in 0..steps {
            let angle = rate * (i as f64 + 0.5) / steps as f64;
            let accel = [0.0, GRAVITY * angle.sin(), GRAVITY * angle.cos()];
            f.update(accel, [rate, 0.0, 0.0], 0.01);
        }
        let accel_end = [0.0, GRAVITY, 0.0];
        let lin = f.world_linear_accel(accel_end);
        let mag = (lin[0] * lin[0] + lin[1] * lin[1] + lin[2] * lin[2]).sqrt();
        assert!(mag < 0.3, "residual linear accel {mag} m/s^2");
    }

    #[test]
    fn quat_rotation_roundtrip() {
        let q = Quat::from_two_vectors(normalize([1.0, 2.0, 3.0]), [0.0, 0.0, 1.0]);
        let v = [0.3, -0.4, 0.5];
        let back = q.rotate_inv(q.rotate(v));
        for i in 0..3 {
            assert!((back[i] - v[i]).abs() < 1e-12);
        }
    }
}
