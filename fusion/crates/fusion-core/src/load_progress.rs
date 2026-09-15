//! Read-only progress and bounded GPS previews for canonical loading.
use crate::{CanonicalActivity, FusionError, GpsPoint};
use std::{cell::Cell, fs::File, io::Read, path::Path, rc::Rc, sync::Arc};

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum CanonicalStage {
    Reading,
    Motion,
    Track,
    Elevation,
    Transport,
    Finalizing,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct CanonicalPreviewPoint {
    pub timestamp_ms: i64,
    pub lat: f64,
    pub lon: f64,
    pub accuracy_m: Option<f64>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct CanonicalProgress {
    pub stage: CanonicalStage,
    pub read_bytes: u64,
    pub total_bytes: u64,
    pub gps_fixes: u64,
    pub preview: Vec<CanonicalPreviewPoint>,
}

#[uniffi::export(with_foreign)]
pub trait CanonicalObserver: Send + Sync {
    fn on_progress(&self, progress: CanonicalProgress);
}

struct CountingReader<R> {
    inner: R,
    count: Rc<Cell<u64>>,
}
impl<R: Read> Read for CountingReader<R> {
    fn read(&mut self, buffer: &mut [u8]) -> std::io::Result<usize> {
        let count = self.inner.read(buffer)?;
        self.count.set(self.count.get() + count as u64);
        Ok(count)
    }
}

/// Uses the same parser and math as finalize_recording; progress never feeds
/// back into analysis. Snapshot thinning is display-only, at most 2,000 points.
#[uniffi::export]
pub fn finalize_recording_with_progress(
    path: String,
    observer: Arc<dyn CanonicalObserver>,
) -> Result<CanonicalActivity, FusionError> {
    let file = File::open(Path::new(&path)).map_err(|e| FusionError::Io { msg: e.to_string() })?;
    let total = file
        .metadata()
        .map_err(|e| FusionError::Io { msg: e.to_string() })?
        .len();
    let count = Rc::new(Cell::new(0));
    observer.on_progress(CanonicalProgress {
        stage: CanonicalStage::Reading,
        read_bytes: 0,
        total_bytes: total,
        gps_fixes: 0,
        preview: vec![],
    });
    let recording = crate::recording::parse_recording_observed(
        CountingReader {
            inner: file,
            count: count.clone(),
        },
        |recording| {
            observer.on_progress(CanonicalProgress {
                stage: CanonicalStage::Reading,
                read_bytes: count.get().min(total),
                total_bytes: total,
                gps_fixes: recording.gps.len() as u64,
                preview: gps_preview(&recording.gps),
            });
        },
    )?;
    crate::canonical::finalize_observed(&recording, |stage| {
        observer.on_progress(CanonicalProgress {
            stage,
            read_bytes: count.get().min(total),
            total_bytes: total,
            gps_fixes: recording.gps.len() as u64,
            preview: vec![],
        })
    })
}

fn gps_preview(gps: &[GpsPoint]) -> Vec<CanonicalPreviewPoint> {
    if gps.is_empty() {
        return vec![];
    }
    let stride = (gps.len() - 1).div_ceil(1_999).max(1);
    let mut preview: Vec<_> = (0..gps.len())
        .step_by(stride)
        .chain(std::iter::once(gps.len() - 1))
        .map(|i| &gps[i])
        .filter(|p| {
            p.lat.is_finite()
                && p.lon.is_finite()
                && (-90.0..=90.0).contains(&p.lat)
                && (-180.0..=180.0).contains(&p.lon)
        })
        .map(|p| CanonicalPreviewPoint {
            timestamp_ms: p.timestamp_ms,
            lat: p.lat,
            lon: p.lon,
            accuracy_m: p.accuracy_m.map(f64::from),
        })
        .collect();
    preview.dedup();
    preview
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    #[derive(Default)]
    struct Observer(Mutex<Vec<CanonicalProgress>>);
    impl CanonicalObserver for Observer {
        fn on_progress(&self, progress: CanonicalProgress) {
            self.0.lock().unwrap().push(progress);
        }
    }

    #[test]
    fn observed_load_exposes_partial_gps_and_preserves_canonical_results() {
        let path = concat!(env!("CARGO_MANIFEST_DIR"), "/testdata/forest-30s.jsonl.gz").to_owned();
        let observer = Arc::new(Observer::default());
        let observed = finalize_recording_with_progress(path.clone(), observer.clone()).unwrap();
        let normal = crate::finalize_recording(path).unwrap();
        assert_eq!(observed.finalized_track, normal.finalized_track);
        assert_eq!(observed.raw_track, normal.raw_track);
        assert_eq!(observed.ride, normal.ride);
        assert_eq!(observed.transport_episodes, normal.transport_episodes);
        let events = observer.0.lock().unwrap();
        assert_eq!(events.first().unwrap().stage, CanonicalStage::Reading);
        assert_eq!(events.last().unwrap().stage, CanonicalStage::Finalizing);
        assert!(
            events
                .iter()
                .any(|p| !p.preview.is_empty() && p.gps_fixes < normal.raw_track.len() as u64)
        );
        assert!(
            events
                .windows(2)
                .all(|p| p[0].read_bytes <= p[1].read_bytes)
        );
        assert!(
            events
                .iter()
                .all(|p| p.read_bytes <= p.total_bytes && p.preview.len() <= 2_000)
        );
    }

    #[test]
    fn preview_is_bounded_and_keeps_both_ends() {
        let gps: Vec<_> = (0..50_000)
            .map(|i| GpsPoint {
                timestamp_ms: i,
                lat: 41.0,
                lon: 44.0,
                altitude_m: None,
                accuracy_m: Some(4.0),
                speed_mps: None,
                bearing_deg: None,
            })
            .collect();
        let preview = gps_preview(&gps);
        assert!(preview.len() <= 2_000);
        assert_eq!(preview.first().unwrap().timestamp_ms, 0);
        assert_eq!(preview.last().unwrap().timestamp_ms, 49_999);
    }
}
