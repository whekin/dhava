//! Debug helper: what did a glitching GPS do, and what caught it?
//!
//! Usage: cargo run -p fusion-core --release --example gps_probe -- rec.jsonl.gz

use fusion_core::{finalize_recording, parse_recording_file};
use std::path::Path;

/// The canonical accuracy cutoff, repeated here so the probe reports what the
/// pipeline would do rather than what it wishes it did.
const MAX_GPS_ACCURACY_M: f64 = 20.0;
/// `gps_quality::kinematically_plausible`, reimplemented because it is crate
/// private. Any change there has to be mirrored here or the probe lies.
const MAX_GROUND_SPEED_MPS: f64 = 50.0;
const MIN_GATE_INTERVAL_S: f64 = 0.2;
const MAX_GATE_INTERVAL_S: f64 = 5.0;
const MIN_CORROBORATING_SPEED_MPS: f64 = 1.5;
const SPEED_SLACK_MPS: f64 = 3.0;

const EARTH_RADIUS_M: f64 = 6_371_000.0;

fn distance_m(lat_a: f64, lon_a: f64, lat_b: f64, lon_b: f64) -> f64 {
    let (a, b) = (lat_a.to_radians(), lat_b.to_radians());
    let dlat = (lat_b - lat_a).to_radians();
    let dlon = (lon_b - lon_a).to_radians();
    let h = (dlat / 2.0).sin().powi(2) + a.cos() * b.cos() * (dlon / 2.0).sin().powi(2);
    2.0 * EARTH_RADIUS_M * h.sqrt().asin()
}

struct Fix {
    timestamp_ms: i64,
    lat: f64,
    lon: f64,
    accuracy_m: Option<f64>,
    speed_mps: Option<f64>,
}

/// True when the pair is one the kinematic gate would let through.
fn plausible(previous: &Fix, current: &Fix) -> bool {
    let dt_s = (current.timestamp_ms - previous.timestamp_ms) as f64 / 1_000.0;
    if dt_s <= 0.0 {
        return true;
    }
    let step_m = distance_m(previous.lat, previous.lon, current.lat, current.lon);
    let slack_m = previous.accuracy_m.unwrap_or(MAX_GPS_ACCURACY_M).max(0.0)
        + current.accuracy_m.unwrap_or(MAX_GPS_ACCURACY_M).max(0.0);
    if step_m > slack_m + MAX_GROUND_SPEED_MPS * dt_s {
        return false;
    }
    if !(MIN_GATE_INTERVAL_S..=MAX_GATE_INTERVAL_S).contains(&dt_s) {
        return true;
    }
    let corroborating = [previous.speed_mps, current.speed_mps]
        .into_iter()
        .flatten()
        .filter(|speed| *speed >= MIN_CORROBORATING_SPEED_MPS)
        .max_by(f64::total_cmp);
    let Some(speed_mps) = corroborating else {
        return true;
    };
    step_m <= slack_m + (speed_mps + SPEED_SLACK_MPS) * dt_s
}

fn percentile(sorted: &[f64], fraction: f64) -> f64 {
    if sorted.is_empty() {
        return f64::NAN;
    }
    sorted[((sorted.len() - 1) as f64 * fraction).round() as usize]
}

fn main() {
    let path = std::env::args().nth(1).expect("usage: gps_probe <recording>");
    let parsed = parse_recording_file(Path::new(&path)).expect("parse");
    let mut fixes: Vec<Fix> = parsed
        .gps
        .iter()
        .map(|point| Fix {
            timestamp_ms: point.timestamp_ms,
            lat: point.lat,
            lon: point.lon,
            accuracy_m: point.accuracy_m.map(f64::from).filter(|v| v.is_finite()),
            speed_mps: point.speed_mps.map(f64::from).filter(|v| v.is_finite()),
        })
        .collect();
    fixes.sort_by_key(|fix| fix.timestamp_ms);
    let started_at_ms = fixes.first().map(|fix| fix.timestamp_ms).unwrap_or(0);
    let minute = |timestamp_ms: i64| (timestamp_ms - started_at_ms) as f64 / 60_000.0;

    println!("== fixes ==");
    println!("count: {}", fixes.len());
    let mut accuracies: Vec<f64> = fixes.iter().filter_map(|fix| fix.accuracy_m).collect();
    accuracies.sort_by(f64::total_cmp);
    println!(
        "accuracy: p50 {:.1} m, p90 {:.1} m, p99 {:.1} m, max {:.1} m",
        percentile(&accuracies, 0.5),
        percentile(&accuracies, 0.9),
        percentile(&accuracies, 0.99),
        accuracies.last().copied().unwrap_or(f64::NAN),
    );
    let rejected = fixes
        .iter()
        .filter(|fix| fix.accuracy_m.is_some_and(|value| value > MAX_GPS_ACCURACY_M))
        .count();
    println!(
        "over the {MAX_GPS_ACCURACY_M:.0} m cutoff: {rejected} ({:.1}%)",
        rejected as f64 * 100.0 / fixes.len() as f64,
    );

    // Everything below judges the fixes the pipeline actually keeps.
    let accepted: Vec<&Fix> = fixes
        .iter()
        .filter(|fix| fix.accuracy_m.is_none_or(|value| value <= MAX_GPS_ACCURACY_M))
        .collect();
    println!("\n== steps between accepted fixes ==");
    let mut implied: Vec<f64> = Vec::new();
    let mut jumps: Vec<(f64, f64, f64, bool, i64, f64, f64)> = Vec::new();
    let mut total_m = 0.0;
    let mut caught_m = 0.0;
    let mut missed_m = 0.0;
    for pair in accepted.windows(2) {
        let (previous, current) = (pair[0], pair[1]);
        let dt_s = (current.timestamp_ms - previous.timestamp_ms) as f64 / 1_000.0;
        if dt_s <= 0.0 {
            continue;
        }
        let step_m = distance_m(previous.lat, previous.lon, current.lat, current.lon);
        let speed = step_m / dt_s;
        implied.push(speed);
        total_m += step_m;
        // Nothing on a bike does 25 m/s (90 km/h) on the trails this app is
        // for, so a step above it is the receiver moving, not the rider.
        if speed > 25.0 {
            let gated = !plausible(previous, current);
            if gated {
                caught_m += step_m;
            } else {
                missed_m += step_m;
            }
            jumps.push((
                minute(current.timestamp_ms),
                step_m,
                speed,
                gated,
                current.timestamp_ms,
                current.accuracy_m.unwrap_or(f64::NAN),
                current.speed_mps.unwrap_or(f64::NAN),
            ));
        }
    }
    implied.sort_by(f64::total_cmp);
    println!(
        "implied speed: p50 {:.1}, p90 {:.1}, p99 {:.1}, max {:.1} m/s",
        percentile(&implied, 0.5),
        percentile(&implied, 0.9),
        percentile(&implied, 0.99),
        implied.last().copied().unwrap_or(f64::NAN),
    );
    println!("raw path length over accepted fixes: {:.0} m", total_m);
    println!(
        "steps above 25 m/s: {} ({:.0} m of path)",
        jumps.len(),
        caught_m + missed_m,
    );
    println!(
        "  the kinematic gate refuses: {} ({:.0} m)",
        jumps.iter().filter(|jump| jump.3).count(),
        caught_m,
    );
    println!(
        "  it lets through:           {} ({:.0} m)",
        jumps.iter().filter(|jump| !jump.3).count(),
        missed_m,
    );

    println!("\n== the twelve biggest jumps ==");
    let mut biggest = jumps.clone();
    biggest.sort_by(|a, b| b.1.total_cmp(&a.1));
    for (minute, step_m, speed, gated, _, accuracy_m, reported_mps) in biggest.iter().take(12) {
        println!(
            "  t+{minute:>6.1} min  {step_m:>7.0} m at {speed:>6.0} m/s  \
             accuracy {accuracy_m:>5.1} m  reported {reported_mps:>5.1} m/s  \
             {}",
            if *gated { "REFUSED" } else { "accepted" },
        );
    }

    println!("\n== jumps per ten minutes ==");
    let last_minute = fixes.last().map(|fix| minute(fix.timestamp_ms)).unwrap_or(0.0);
    let mut bucket = 0.0;
    while bucket < last_minute {
        let in_bucket: Vec<_> = jumps
            .iter()
            .filter(|jump| jump.0 >= bucket && jump.0 < bucket + 10.0)
            .collect();
        if !in_bucket.is_empty() {
            println!(
                "  t+{:>5.0}-{:<5.0} min: {:>4} jumps, {:>8.0} m, worst {:.0} m",
                bucket,
                bucket + 10.0,
                in_bucket.len(),
                in_bucket.iter().map(|jump| jump.1).sum::<f64>(),
                in_bucket.iter().map(|jump| jump.1).fold(0.0, f64::max),
            );
        }
        bucket += 10.0;
    }

    println!("\n== what the rider is shown ==");
    let canonical = finalize_recording(path).expect("finalize");
    println!(
        "ride:     {:.0} m, ascent {:.0} m, descent {:.0} m, max speed {:.1} m/s",
        canonical.ride.distance_m,
        canonical.ride.ascent_m,
        canonical.ride.descent_m,
        canonical.ride.max_speed_mps,
    );
    println!(
        "transport: {:.0} m over {:.0} s",
        canonical.ride.transport_distance_m, canonical.ride.transport_time_s,
    );
    println!(
        "quality:  {:?}, median accuracy {:?}, p90 {:?}, gaps {} (longest {:.0} s)",
        canonical.quality.elevation_source,
        canonical.quality.median_accuracy_m,
        canonical.quality.p90_accuracy_m,
        canonical.quality.gps_gap_count,
        canonical.quality.longest_gap_s,
    );
}
