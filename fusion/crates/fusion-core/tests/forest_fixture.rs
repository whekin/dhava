//! Integration test against a REAL recording: the first ~30 s of an actual
//! OnePlus (LE2123) forest ride — ~15k IMU lines at ~500 Hz, ~30 GPS fixes
//! at 1 Hz, no baro. The rider does two bunny hops (around t ≈ +19.8 s and
//! +23.3 s) and rides down a short stair drop.

use fusion_core::{ALGORITHM_VERSION, ElevationSource, analyze_recording, finalize_recording};

fn fixture_path() -> String {
    concat!(env!("CARGO_MANIFEST_DIR"), "/testdata/forest-30s.jsonl.gz").to_owned()
}

#[test]
fn real_forest_recording_analyzes_plausibly() {
    let a = analyze_recording(fixture_path()).expect("fixture must parse and analyze");

    // Counts as captured on the device.
    assert!(
        (14_000..16_000).contains(&a.imu_count),
        "imu_count {}",
        a.imu_count
    );
    assert!((25..40).contains(&a.gps_count), "gps_count {}", a.gps_count);

    // Duration ~30 s.
    let duration_s = (a.ended_at_ms - a.started_at_ms) as f64 / 1000.0;
    assert!(
        (25.0..35.0).contains(&duration_s),
        "duration_s {duration_s}"
    );

    // Distance/speed sane: non-zero, below absurd for 30 s of forest riding.
    assert!(
        a.distance_m > 20.0 && a.distance_m < 500.0,
        "distance_m {}",
        a.distance_m
    );
    assert!(
        a.max_speed_mps > 1.0 && a.max_speed_mps < 25.0,
        "max_speed_mps {}",
        a.max_speed_mps
    );
    assert!(
        a.moving_time_s > 5.0 && a.moving_time_s <= duration_s + 1.0,
        "moving_time_s {}",
        a.moving_time_s
    );
    assert!(
        a.avg_moving_speed_mps > 0.5 && a.avg_moving_speed_mps <= a.max_speed_mps,
        "avg_moving_speed_mps {}",
        a.avg_moving_speed_mps
    );

    // The ride contains real bunny hops: at least one airtime window.
    assert!(
        !a.airtime_windows.is_empty(),
        "expected >=1 airtime window, got none"
    );
    assert_eq!(
        a.airtime_total_ms,
        a.airtime_windows.iter().map(|w| w.duration_ms).sum::<i64>()
    );
    for w in &a.airtime_windows {
        assert!(w.duration_ms >= 150, "window too short: {w:?}");
        assert!(w.duration_ms < 3_000, "window absurdly long: {w:?}");
        assert!(
            w.start_ms >= a.started_at_ms && w.start_ms <= a.ended_at_ms,
            "window outside ride: {w:?}"
        );
    }

    // Track decimated to ~1 Hz: about one point per second of ride.
    assert!(
        !a.track.is_empty() && a.track.len() as u32 <= a.gps_count,
        "track len {}",
        a.track.len()
    );

    assert_eq!(a.algorithm_version, ALGORITHM_VERSION);
    assert!(a.descent_m >= 0.0 && a.ascent_m >= 0.0);
}

#[test]
fn real_forest_recording_reports_gps_only_quality() {
    let canonical = finalize_recording(fixture_path()).expect("fixture must finalize");
    let quality = &canonical.quality;

    // The LE2123 recording has no barometer lines, so the vertical pass must
    // report GPS interpolation rather than barometric anchoring.
    assert_eq!(quality.baro_sample_count, 0);
    assert_eq!(quality.elevation_source, ElevationSource::GpsInterpolated);

    // Counts line up with the raw fixes the analyzer saw.
    assert_eq!(quality.gps_fix_count as usize, canonical.raw_track.len());
    assert_eq!(quality.gps_fix_count, canonical.analysis.gps_count);
    assert!(quality.gps_accepted_count <= quality.gps_fix_count);
    assert!(quality.gps_accepted_count > 0);

    // A phone fix always reports accuracy; the stats must exist and be sane.
    let median = quality.median_accuracy_m.expect("median accuracy");
    let p90 = quality.p90_accuracy_m.expect("p90 accuracy");
    assert!(median > 0.0 && median <= p90, "median {median}, p90 {p90}");

    // Continuous 1 Hz coverage: no >5 s holes in 30 s of recording.
    assert_eq!(quality.gps_gap_count, 0);
    assert_eq!(quality.longest_gap_s, 0.0);

    let uncertainty = quality.elevation_uncertainty_m.expect("uncertainty");
    assert!(
        uncertainty >= 5.0 && (uncertainty - (p90 * 1.5).max(5.0)).abs() < 1e-9,
        "uncertainty {uncertainty}"
    );
}
