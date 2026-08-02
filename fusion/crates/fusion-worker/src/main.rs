//! Nakvali fusion worker (skeleton).
//!
//! Future design: an async worker (likely tokio + sqlx) that
//! 1. `LISTEN`s on a Postgres channel (or polls) for newly uploaded raw
//!    recordings (GPS + IMU + baro streams),
//! 2. runs them through `fusion-core` (Kalman smoothing, gate crossings,
//!    airtime detection),
//! 3. writes the derived results (segment times, jumps, smoothed track)
//!    back to the database.
//!
//! Kept dependency-light on purpose until the database layer lands.

fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
        )
        .init();

    let database_url = std::env::var("DATABASE_URL").ok();
    match &database_url {
        Some(_) => tracing::info!("fusion-worker starting (DATABASE_URL configured)"),
        None => {
            tracing::warn!("fusion-worker starting without DATABASE_URL; set it before processing")
        }
    }

    // TODO: main processing loop
    // loop {
    //     1. LISTEN/poll Postgres for a new raw recording id;
    //     2. load GpsPoint / ImuSample / BaroSample streams;
    //     3. run fusion-core: Kalman filter, detect_gate_crossing per gate,
    //        airtime detection;
    //     4. write segment times / jumps / smoothed track back to the DB;
    //     5. mark the recording as processed.
    // }
}
