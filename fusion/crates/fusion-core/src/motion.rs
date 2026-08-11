//! Shared IMU motion envelope.
//!
//! The raw IMU stream is reduced once, here, into the envelope callers actually
//! need: how hard the ground is working on the rider. Activity classification
//! uses it to recognize a vehicle by how unnaturally smooth its ride is.

use crate::ImuSample;

const MOTION_SAMPLE_INTERVAL_MS: i64 = 50;
pub(crate) const STANDARD_GRAVITY_MPS2: f64 = 9.806_65;

/// One downsampled, conservative summary of the IMU over ~50 ms.
#[derive(Debug, Clone, Copy)]
pub(crate) struct MotionSample {
    pub timestamp_ms: i64,
    /// How far the accelerometer magnitude sits from gravity. Zero while
    /// coasting on smooth tarmac, ~9.8 in free fall, large on an impact.
    pub accel_error_mps2: f64,
    pub gyro_rad_s: f64,
}

/// Reduces a raw IMU stream to a coarse motion envelope.
pub(crate) fn motion_samples(imu: &[ImuSample]) -> Vec<MotionSample> {
    let mut ordered: Vec<_> = imu.iter().collect();
    ordered.sort_by_key(|sample| sample.timestamp_ms);

    // Raw capture is around 200 Hz. Callers only need a coarse envelope, so
    // retain one conservative (max-error) bucket at 20 Hz. This bounds both
    // memory and rolling-window work on multi-hour recordings.
    let mut samples = Vec::with_capacity(ordered.len() / 20 + 1);
    let mut bucket: Option<MotionSample> = None;
    for sample in ordered {
        let accel = sample.accel.map(f64::from);
        let gyro = sample.gyro.map(f64::from);
        let accel_magnitude = (accel[0].powi(2) + accel[1].powi(2) + accel[2].powi(2)).sqrt();
        let gyro_magnitude = (gyro[0].powi(2) + gyro[1].powi(2) + gyro[2].powi(2)).sqrt();
        if !accel_magnitude.is_finite() || !gyro_magnitude.is_finite() {
            continue;
        }
        let candidate = MotionSample {
            timestamp_ms: sample.timestamp_ms,
            accel_error_mps2: (accel_magnitude - STANDARD_GRAVITY_MPS2).abs(),
            gyro_rad_s: gyro_magnitude,
        };
        match bucket.as_mut() {
            Some(current)
                if candidate.timestamp_ms - current.timestamp_ms < MOTION_SAMPLE_INTERVAL_MS =>
            {
                current.accel_error_mps2 = current.accel_error_mps2.max(candidate.accel_error_mps2);
                current.gyro_rad_s = current.gyro_rad_s.max(candidate.gyro_rad_s);
            }
            Some(_) => {
                samples.push(bucket.replace(candidate).expect("bucket exists"));
            }
            None => bucket = Some(candidate),
        }
    }
    samples.extend(bucket);
    samples
}
