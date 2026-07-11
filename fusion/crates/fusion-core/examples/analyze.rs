//! Debug helper: analyze a raw recording and dump the result.
//!
//! Usage: cargo run -p fusion-core --example analyze -- path/to/recording.jsonl.gz

fn main() {
    let path = std::env::args()
        .nth(1)
        .expect("usage: analyze <recording.jsonl.gz>");
    match fusion_core::analyze_recording(path) {
        Ok(a) => {
            let duration_s = (a.ended_at_ms - a.started_at_ms) as f64 / 1000.0;
            println!("algorithm:     {}", a.algorithm_version);
            println!("duration:      {duration_s:.1} s");
            println!("moving time:   {:.1} s", a.moving_time_s);
            println!("distance:      {:.1} m", a.distance_m);
            println!("ascent:        {:.1} m", a.ascent_m);
            println!("descent:       {:.1} m", a.descent_m);
            println!("max speed:     {:.2} m/s", a.max_speed_mps);
            println!("avg mov speed: {:.2} m/s", a.avg_moving_speed_mps);
            println!("gps/imu count: {} / {}", a.gps_count, a.imu_count);
            println!("track points:  {}", a.track.len());
            println!("airtime total: {} ms", a.airtime_total_ms);
            for (i, w) in a.airtime_windows.iter().enumerate() {
                println!(
                    "  window {i}: t=+{:.1}s dur={} ms peak={:.2} g",
                    (w.start_ms - a.started_at_ms) as f64 / 1000.0,
                    w.duration_ms,
                    w.landing_peak_g
                );
            }
        }
        Err(e) => {
            eprintln!("analysis failed: {e}");
            std::process::exit(1);
        }
    }
}
