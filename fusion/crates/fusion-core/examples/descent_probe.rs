//! Debug helper: where does the reported descent come from?
//!
//! Usage: cargo run -p fusion-core --release --example descent_probe -- rec.jsonl.gz

use fusion_core::canonical::CanonicalTrackPoint;
use fusion_core::{ActivityState, finalize_recording, parse_recording_file};
use std::path::Path;

/// Same accumulator as `canonical::ascent_descent`, with a tunable band and an
/// optional "skip stationary" rule, so each variant is comparable.
fn accumulate(track: &[CanonicalTrackPoint], band: f64, skip_stationary: bool) -> (f64, f64) {
    let mut ascent = 0.0;
    let mut descent = 0.0;
    let mut reference: Option<(i32, f64)> = None;
    for point in track {
        if skip_stationary && point.stationary == Some(true) {
            continue;
        }
        let Some(altitude) = point.altitude_m else {
            continue;
        };
        let Some((section_id, previous)) = reference else {
            reference = Some((point.section_id, altitude));
            continue;
        };
        if section_id != point.section_id {
            reference = Some((point.section_id, altitude));
            continue;
        }
        let delta = altitude - previous;
        if delta >= band {
            ascent += delta;
            reference = Some((point.section_id, altitude));
        } else if delta <= -band {
            descent += -delta;
            reference = Some((point.section_id, altitude));
        }
    }
    (ascent, descent)
}

/// Net endpoint change per section, robust to the edges (the GPS-only rule).
fn net_by_section(track: &[CanonicalTrackPoint], band: f64) -> (f64, f64) {
    let mut ascent = 0.0;
    let mut descent = 0.0;
    let mut start = 0usize;
    let points: Vec<&CanonicalTrackPoint> =
        track.iter().filter(|p| p.altitude_m.is_some()).collect();
    while start < points.len() {
        let section_id = points[start].section_id;
        let mut end = start + 1;
        while end < points.len() && points[end].section_id == section_id {
            end += 1;
        }
        let section = &points[start..end];
        if section.len() >= 2 {
            let edge = 5.min(section.len() / 2).max(1);
            let head = median(section[..edge].iter().filter_map(|p| p.altitude_m));
            let tail = median(section[section.len() - edge..].iter().filter_map(|p| p.altitude_m));
            let delta = tail - head;
            if delta >= band {
                ascent += delta;
            } else if delta <= -band {
                descent -= delta;
            }
        }
        start = end;
    }
    (ascent, descent)
}

fn median(values: impl Iterator<Item = f64>) -> f64 {
    let mut v: Vec<f64> = values.collect();
    v.sort_by(f64::total_cmp);
    if v.is_empty() {
        return 0.0;
    }
    let mid = v.len() / 2;
    if v.len().is_multiple_of(2) {
        (v[mid - 1] + v[mid]) / 2.0
    } else {
        v[mid]
    }
}

/// Relative barometric altitude, no GPS anchoring at all: pure sensor shape.
fn baro_only_descent(path: &str, band: f64) -> Option<(f64, f64, usize, f64)> {
    let parsed = parse_recording_file(Path::new(path)).ok()?;
    if parsed.baro.is_empty() {
        return None;
    }
    let mut samples: Vec<(i64, f64)> = parsed
        .baro
        .iter()
        .filter_map(|s| {
            let p = f64::from(s.pressure_hpa);
            (p.is_finite() && p > 0.0).then_some((s.timestamp_ms, p))
        })
        .collect();
    samples.sort_by_key(|s| s.0);
    let reference_pressure = samples.first()?.1;
    let series: Vec<f64> = samples
        .iter()
        .map(|(_, p)| 44_330.0 * (1.0 - (p / reference_pressure).powf(0.190_284)))
        .collect();
    let duration_h = (samples.last()?.0 - samples[0].0) as f64 / 3_600_000.0;

    let mut ascent = 0.0;
    let mut descent = 0.0;
    let mut reference = series[0];
    for &value in &series[1..] {
        let delta = value - reference;
        if delta >= band {
            ascent += delta;
            reference = value;
        } else if delta <= -band {
            descent += -delta;
            reference = value;
        }
    }
    Some((ascent, descent, series.len(), duration_h))
}

/// Relative barometric altitude series, sorted, same formula as the pipeline.
fn baro_series(parsed: &fusion_core::ParsedRecording) -> Vec<(i64, f64)> {
    let mut samples: Vec<(i64, f64)> = parsed
        .baro
        .iter()
        .filter_map(|s| {
            let p = f64::from(s.pressure_hpa);
            (p.is_finite() && p > 0.0).then_some((s.timestamp_ms, p))
        })
        .collect();
    samples.sort_by_key(|s| s.0);
    let Some(reference_pressure) = samples.first().map(|s| s.1) else {
        return Vec::new();
    };
    samples
        .into_iter()
        .map(|(t, p)| (t, 44_330.0 * (1.0 - (p / reference_pressure).powf(0.190_284))))
        .collect()
}

/// Linear interpolation over a sorted (timestamp, value) series.
fn interp(series: &[(i64, f64)], timestamp_ms: i64) -> Option<f64> {
    let index = series.partition_point(|s| s.0 < timestamp_ms);
    if index == 0 {
        return (series.first()?.0 == timestamp_ms).then(|| series[0].1);
    }
    if index == series.len() {
        return None;
    }
    let (t0, v0) = series[index - 1];
    let (t1, v1) = series[index];
    if t1 == t0 {
        return Some(v0);
    }
    let weight = (timestamp_ms - t0) as f64 / (t1 - t0) as f64;
    Some(v0 + (v1 - v0) * weight)
}

/// Centred median over a time window, like the pipeline's offset smoothing.
fn timed_median(series: &[(i64, f64)], half_window_ms: i64) -> Vec<(i64, f64)> {
    let mut lo = 0usize;
    let mut hi = 0usize;
    let mut out = Vec::with_capacity(series.len());
    for (index, &(timestamp_ms, _)) in series.iter().enumerate() {
        while series[lo].0 < timestamp_ms - half_window_ms {
            lo += 1;
        }
        hi = hi.max(index);
        while hi + 1 < series.len() && series[hi + 1].0 <= timestamp_ms + half_window_ms {
            hi += 1;
        }
        out.push((timestamp_ms, median(series[lo..=hi].iter().map(|s| s.1))));
    }
    out
}

fn accumulate_series(series: &[(i64, f64)], band: f64) -> (f64, f64) {
    let mut ascent = 0.0;
    let mut descent = 0.0;
    let Some(&(_, first)) = series.first() else {
        return (0.0, 0.0);
    };
    let mut reference = first;
    for &(_, value) in &series[1..] {
        let delta = value - reference;
        if delta >= band {
            ascent += delta;
            reference = value;
        } else if delta <= -band {
            descent += -delta;
            reference = value;
        }
    }
    (ascent, descent)
}

fn main() {
    let path = std::env::args().nth(1).expect("usage: descent_probe <rec>");
    let canonical = finalize_recording(path.clone()).expect("finalize");
    let q = &canonical.quality;

    println!("== recording ==");
    println!("algorithm:       {}", canonical.algorithm_version);
    println!(
        "duration:        {:.1} min",
        (canonical.analysis.ended_at_ms - canonical.analysis.started_at_ms) as f64 / 60_000.0
    );
    println!("distance (ride): {:.0} m", canonical.ride.distance_m);
    println!("transport:       {:.0} m", canonical.ride.transport_distance_m);
    println!("elevation src:   {:?}", q.elevation_source);
    println!("baro samples:    {}", q.baro_sample_count);
    println!(
        "gps fixes:       {} ({} accepted), median acc {:?}, p90 {:?}",
        q.gps_fix_count, q.gps_accepted_count, q.median_accuracy_m, q.p90_accuracy_m
    );
    println!(
        "gaps:            {} (longest {:.0} s)",
        q.gps_gap_count, q.longest_gap_s
    );
    println!("uncertainty:     {:?} m", q.elevation_uncertainty_m);

    println!("\n== what the app reports ==");
    println!(
        "ride  (UI tile): ascent {:.1} m / descent {:.1} m",
        canonical.ride.ascent_m, canonical.ride.descent_m
    );
    println!(
        "analysis:        ascent {:.1} m / descent {:.1} m",
        canonical.analysis.ascent_m, canonical.analysis.descent_m
    );

    let ride_only: Vec<CanonicalTrackPoint> = canonical
        .finalized_track
        .iter()
        .filter(|p| p.activity_state != ActivityState::LikelyMotorized)
        .cloned()
        .collect();
    let stationary = ride_only
        .iter()
        .filter(|p| p.stationary == Some(true))
        .count();
    println!(
        "\n== finalized track ({} pts, {} ride-only, {} stationary) ==",
        canonical.finalized_track.len(),
        ride_only.len(),
        stationary
    );
    for band in [2.0, 3.0, 5.0, 10.0] {
        let (a, d) = accumulate(&ride_only, band, false);
        let (as_, ds) = accumulate(&ride_only, band, true);
        println!(
            "band {band:>4.1} m: accum {a:>7.1} / {d:>7.1}   no-stationary {as_:>7.1} / {ds:>7.1}"
        );
    }
    let (net_a, net_d) = net_by_section(&ride_only, 2.0);
    println!("net by section: ascent {net_a:.1} m / descent {net_d:.1} m");

    println!("\n== raw GPS altitude (no baro, no smoothing) ==");
    let raw_ride: Vec<CanonicalTrackPoint> = canonical.raw_track.clone();
    for band in [2.0, 5.0, 10.0] {
        let (a, d) = accumulate(&raw_ride, band, false);
        println!("band {band:>4.1} m: accum {a:>7.1} / {d:>7.1}");
    }

    println!("\n== barometer only (relative, never anchored to GPS) ==");
    match baro_only_descent(&path, 2.0) {
        Some((a, d, n, hours)) => {
            println!("samples {n}, duration {hours:.2} h");
            println!("band  2.0 m: accum {a:>7.1} / {d:>7.1}");
            let (a5, d5, _, _) = baro_only_descent(&path, 5.0).unwrap();
            println!("band  5.0 m: accum {a5:>7.1} / {d5:>7.1}");
        }
        None => println!("no barometer samples"),
    }

    println!("\n== where the descent accumulates (one walk, attributed by state) ==");
    // One continuous accumulation over the ride-only track; each accepted step
    // is charged to the state of the point that closed it, so the parts sum to
    // the total instead of each subset re-walking the whole profile.
    {
        let mut by_state: std::collections::BTreeMap<String, (f64, f64, usize)> =
            std::collections::BTreeMap::new();
        let mut reference: Option<(i32, f64)> = None;
        for point in &ride_only {
            let Some(altitude) = point.altitude_m else {
                continue;
            };
            let entry = by_state
                .entry(format!("{:?}", point.activity_state))
                .or_default();
            entry.2 += 1;
            let Some((section_id, previous)) = reference else {
                reference = Some((point.section_id, altitude));
                continue;
            };
            if section_id != point.section_id {
                reference = Some((point.section_id, altitude));
                continue;
            }
            let delta = altitude - previous;
            if delta >= 2.0 {
                entry.0 += delta;
                reference = Some((point.section_id, altitude));
            } else if delta <= -2.0 {
                entry.1 += -delta;
                reference = Some((point.section_id, altitude));
            }
        }
        for (state, (ascent, descent, count)) in &by_state {
            println!("{state:>16}: {count:>6} pts, ascent {ascent:>7.1} / descent {descent:>7.1}");
        }
    }

    println!("\n== biggest single accumulation steps (ride-only walk) ==");
    {
        let mut steps: Vec<(f64, i64, i64, String)> = Vec::new();
        let mut reference: Option<(i32, f64, i64)> = None;
        for point in &ride_only {
            let Some(altitude) = point.altitude_m else {
                continue;
            };
            let Some((section_id, previous, previous_ms)) = reference else {
                reference = Some((point.section_id, altitude, point.timestamp_ms));
                continue;
            };
            if section_id != point.section_id {
                reference = Some((point.section_id, altitude, point.timestamp_ms));
                continue;
            }
            let delta = altitude - previous;
            if delta.abs() >= 2.0 {
                steps.push((
                    delta,
                    point.timestamp_ms - previous_ms,
                    point.timestamp_ms,
                    format!("{:?}", point.activity_state),
                ));
                reference = Some((point.section_id, altitude, point.timestamp_ms));
            }
        }
        steps.sort_by(|a, b| b.0.abs().total_cmp(&a.0.abs()));
        for (delta, gap_ms, timestamp_ms, state) in steps.iter().take(12) {
            println!(
                "  t+{:>6.1} min  step {:>+8.1} m  after a {:>6.1} s gap  into {state}",
                (timestamp_ms - canonical.analysis.started_at_ms) as f64 / 60_000.0,
                delta,
                *gap_ms as f64 / 1000.0,
            );
        }
        let across_holes: f64 = steps
            .iter()
            .filter(|(_, gap_ms, _, _)| *gap_ms > 10_000)
            .map(|(delta, _, _, _)| delta.abs())
            .sum();
        println!("  steps that closed a gap longer than 10 s: {across_holes:.1} m of vertical");
    }

    println!("\n== high-frequency noise in the finalized altitude ==");
    {
        let series: Vec<(i64, f64)> = canonical
            .finalized_track
            .iter()
            .filter_map(|p| p.altitude_m.map(|alt| (p.timestamp_ms, alt)))
            .collect();
        let baseline = timed_median(&series, 5_000);
        let mut residuals: Vec<f64> = series
            .iter()
            .zip(&baseline)
            .map(|((_, value), (_, smooth))| (value - smooth).abs())
            .collect();
        residuals.sort_by(f64::total_cmp);
        let rms = (residuals.iter().map(|r| r * r).sum::<f64>() / residuals.len() as f64).sqrt();
        println!(
            "residual vs +/-5 s median: rms {rms:.2} m, p50 {:.2}, p95 {:.2}, max {:.2}",
            residuals[residuals.len() / 2],
            residuals[residuals.len() * 95 / 100],
            residuals.last().copied().unwrap_or(0.0)
        );

        println!("\n== descent after smoothing the finalized altitude ==");
        for half_window_ms in [0i64, 5_000, 15_000, 30_000] {
            let smoothed = if half_window_ms == 0 {
                series.clone()
            } else {
                timed_median(&series, half_window_ms)
            };
            let line: Vec<String> = [2.0f64, 3.0, 5.0]
                .iter()
                .map(|band| {
                    let (a, d) = accumulate_series(&smoothed, *band);
                    format!("band {band:.0} m: {a:>7.1} / {d:>7.1}")
                })
                .collect();
            println!(
                "median +/-{:>2} s   {}",
                half_window_ms / 1000,
                line.join("   ")
            );
        }
    }

    println!("\n== GPS anchor offset: how much fake vertical it injects ==");
    let parsed = parse_recording_file(Path::new(&path)).expect("parse");
    let baro = baro_series(&parsed);
    if !baro.is_empty() {
        // The offset the pipeline actually rides on: GPS altitude minus the
        // relative barometric altitude at the same instant.
        let mut offsets: Vec<(i64, f64)> = Vec::new();
        for point in &canonical.raw_track {
            let (Some(gps_alt), Some(relative)) =
                (point.altitude_m, interp(&baro, point.timestamp_ms))
            else {
                continue;
            };
            offsets.push((point.timestamp_ms, gps_alt - relative));
        }
        let spread = {
            let mut values: Vec<f64> = offsets.iter().map(|o| o.1).collect();
            values.sort_by(f64::total_cmp);
            (values.first().copied().unwrap_or(0.0), values.last().copied().unwrap_or(0.0))
        };
        println!(
            "offset anchors: {}, range {:.1} .. {:.1} m (spread {:.1} m)",
            offsets.len(),
            spread.0,
            spread.1,
            spread.1 - spread.0
        );
        // What the pipeline smooths with: a +/-30 s median. Accumulating its own
        // wander says how many metres of climb/drop the anchor invents by itself.
        for half_window_ms in [30_000i64, 300_000, 900_000] {
            let smoothed = timed_median(&offsets, half_window_ms);
            let (a, d) = accumulate_series(&smoothed, 2.0);
            println!(
                "median +/-{:>3} s: offset wander accumulates ascent {a:>7.1} / descent {d:>7.1}",
                half_window_ms / 1000
            );
        }
    }

    println!("\n== the opening climb, where nothing went downhill ==");
    {
        let started_at_ms = canonical.analysis.started_at_ms;
        for minutes in [10.0f64, 20.0, 24.0] {
            let until = started_at_ms + (minutes * 60_000.0) as i64;
            let window: Vec<CanonicalTrackPoint> = canonical
                .finalized_track
                .iter()
                .filter(|p| p.timestamp_ms <= until)
                .cloned()
                .collect();
            let raw_window: Vec<CanonicalTrackPoint> = canonical
                .raw_track
                .iter()
                .filter(|p| p.timestamp_ms <= until)
                .cloned()
                .collect();
            let first = window.iter().find_map(|p| p.altitude_m).unwrap_or(f64::NAN);
            let last = window
                .iter()
                .rev()
                .find_map(|p| p.altitude_m)
                .unwrap_or(f64::NAN);
            let (_, baro_descent) = accumulate(&window, 2.0, false);
            let (_, gps_descent) = accumulate(&raw_window, 2.0, false);
            println!(
                "first {minutes:>4.0} min: net {:+.1} m | baro-anchored descent {baro_descent:>6.1} m | raw GPS descent {gps_descent:>6.1} m",
                last - first
            );
        }
    }

    println!("\n== live screen replay (what the rider saw while recording) ==");
    {
        let live = fusion_core::LiveFusion::new();
        let mut imu = parsed.imu.clone();
        imu.sort_by_key(|s| s.timestamp_ms);
        let mut gps = parsed.gps.clone();
        gps.sort_by_key(|p| p.timestamp_ms);
        let started_at_ms = canonical.analysis.started_at_ms;

        let mut baro = parsed.baro.clone();
        baro.sort_by_key(|s| s.timestamp_ms);

        let mut imu_index = 0usize;
        let mut baro_index = 0usize;
        let mut last_report = started_at_ms;
        let mut last_descent = 0.0;
        for point in &gps {
            while baro_index < baro.len() && baro[baro_index].timestamp_ms <= point.timestamp_ms {
                live.push_baro(
                    baro[baro_index].timestamp_ms,
                    f64::from(baro[baro_index].pressure_hpa),
                );
                baro_index += 1;
            }
            while imu_index < imu.len() && imu[imu_index].timestamp_ms <= point.timestamp_ms {
                let sample = &imu[imu_index];
                live.push_imu(
                    sample.timestamp_ms,
                    sample.accel.iter().map(|v| f64::from(*v)).collect(),
                    sample.gyro.iter().map(|v| f64::from(*v)).collect(),
                );
                imu_index += 1;
            }
            let snapshot = live.push_gps(
                point.timestamp_ms,
                point.lat,
                point.lon,
                point.altitude_m,
                point.accuracy_m.map(f64::from),
                point.speed_mps.map(f64::from),
                point.bearing_deg.map(f64::from),
            );
            let Some(snapshot) = snapshot else { continue };
            if point.timestamp_ms - last_report >= 600_000 {
                println!(
                    "  t+{:>5.1} min  live descent {:>8.1} m (+{:>6.1})  distance {:>7.0} m  alt {:>8.1}",
                    (point.timestamp_ms - started_at_ms) as f64 / 60_000.0,
                    snapshot.descent_m,
                    snapshot.descent_m - last_descent,
                    snapshot.distance_m,
                    snapshot.altitude_m.unwrap_or(f64::NAN),
                );
                last_report = point.timestamp_ms;
                last_descent = snapshot.descent_m;
            }
        }
    }

    println!("\n== altitude profile, 1 point per minute ==");
    let mut last_printed: Option<i64> = None;
    for point in &canonical.finalized_track {
        if last_printed.is_some_and(|last| point.timestamp_ms - last < 60_000) {
            continue;
        }
        last_printed = Some(point.timestamp_ms);
        let minute = (point.timestamp_ms - canonical.analysis.started_at_ms) as f64 / 60_000.0;
        println!(
            "  t+{:>5.1} min  alt {:>8.2}  acc {:>6.1}  {}{:?}",
            minute,
            point.altitude_m.unwrap_or(f64::NAN),
            point.accuracy_m.unwrap_or(f64::NAN),
            if point.stationary == Some(true) { "stationary " } else { "" },
            point.activity_state
        );
    }
}
