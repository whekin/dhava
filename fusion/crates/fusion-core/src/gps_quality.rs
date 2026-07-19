//! Shared horizontal GPS quality gates.
//!
//! Horizontal accuracy is a position radius, not a guarantee that every fix
//! inside the canonical 20 m cutoff is mutually consistent. Android's Doppler
//! ground speed supplies an independent upper bound for short inter-fix moves.

use crate::GpsPoint;

const EARTH_RADIUS_M: f64 = 6_371_000.0;
const MIN_GATE_INTERVAL_S: f64 = 0.2;
const MAX_GATE_INTERVAL_S: f64 = 5.0;
/// Below this, Android speed has proven too noisy to veto earth-relative
/// movement (including exact zero reports on a smoothly moving vehicle).
const MIN_CORROBORATING_SPEED_MPS: f64 = 1.5;
/// Covers real acceleration between ~1 Hz fixes without allowing a low-speed
/// report to excuse a large coordinate teleport.
const SPEED_SLACK_MPS: f64 = 3.0;

#[derive(Debug, Clone, Copy)]
pub(crate) struct HorizontalFix {
    pub timestamp_ms: i64,
    pub lat: f64,
    pub lon: f64,
    pub accuracy_m: f64,
    pub speed_mps: Option<f64>,
}

impl HorizontalFix {
    pub(crate) fn from_gps(point: &GpsPoint, default_accuracy_m: f64) -> Self {
        Self {
            timestamp_ms: point.timestamp_ms,
            lat: point.lat,
            lon: point.lon,
            accuracy_m: point
                .accuracy_m
                .map(f64::from)
                .filter(|value| value.is_finite() && *value >= 0.0)
                .unwrap_or(default_accuracy_m),
            speed_mps: valid_speed(point.speed_mps.map(f64::from)),
        }
    }
}

/// Rejects only short moves that exceed both fixes' position radii plus what
/// their reported ground speed could plausibly cover.
///
/// Missing/near-zero speed never vetoes displacement: field data showed exact
/// zero on moving vehicles, and earth-relative motion must still release STILL.
pub(crate) fn kinematically_plausible(previous: HorizontalFix, current: HorizontalFix) -> bool {
    let dt_s = (current.timestamp_ms - previous.timestamp_ms) as f64 / 1_000.0;
    if !(MIN_GATE_INTERVAL_S..=MAX_GATE_INTERVAL_S).contains(&dt_s) {
        return true;
    }

    let corroborating_speed_mps = [previous.speed_mps, current.speed_mps]
        .into_iter()
        .flatten()
        .filter(|speed| *speed >= MIN_CORROBORATING_SPEED_MPS)
        .max_by(f64::total_cmp);
    let Some(speed_mps) = corroborating_speed_mps else {
        return true;
    };

    let position_slack_m = previous.accuracy_m.max(0.0) + current.accuracy_m.max(0.0);
    let travel_limit_m = (speed_mps + SPEED_SLACK_MPS) * dt_s;
    geographic_distance_m(previous.lat, previous.lon, current.lat, current.lon)
        <= position_slack_m + travel_limit_m
}

pub(crate) fn geographic_distance_m(lat_a: f64, lon_a: f64, lat_b: f64, lon_b: f64) -> f64 {
    let (lat_a_rad, lat_b_rad) = (lat_a.to_radians(), lat_b.to_radians());
    let dlat = (lat_b - lat_a).to_radians();
    let dlon = (lon_b - lon_a).to_radians();
    let h =
        (dlat / 2.0).sin().powi(2) + lat_a_rad.cos() * lat_b_rad.cos() * (dlon / 2.0).sin().powi(2);
    2.0 * EARTH_RADIUS_M * h.sqrt().asin()
}

fn valid_speed(speed_mps: Option<f64>) -> Option<f64> {
    speed_mps.filter(|speed| speed.is_finite() && *speed >= 0.0)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fix(timestamp_ms: i64, east_m: f64, accuracy_m: f64, speed_mps: f64) -> HorizontalFix {
        let lat = 41.7;
        HorizontalFix {
            timestamp_ms,
            lat,
            lon: 44.8 + (east_m / (EARTH_RADIUS_M * lat.to_radians().cos())).to_degrees(),
            accuracy_m,
            speed_mps: Some(speed_mps),
        }
    }

    #[test]
    fn rejects_kojoring_style_low_speed_teleport() {
        assert!(!kinematically_plausible(
            fix(0, 0.0, 3.8, 4.31),
            fix(1_000, 16.8, 3.9, 2.91),
        ));
    }

    #[test]
    fn accuracy_radii_allow_position_correction() {
        assert!(kinematically_plausible(
            fix(0, 0.0, 10.0, 2.0),
            fix(1_000, 22.0, 10.0, 2.0),
        ));
    }

    #[test]
    fn zero_speed_cannot_veto_vehicle_displacement() {
        assert!(kinematically_plausible(
            fix(0, 0.0, 4.0, 0.0),
            fix(1_000, 12.0, 4.0, 0.0),
        ));
    }
}
