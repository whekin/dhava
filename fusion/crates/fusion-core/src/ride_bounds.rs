//! Ride bounds and riding runs: the two ways a rider narrows what counts as the
//! ride, without the recording itself changing in any way.
//!
//! Both are pure views over an already-finalized track. Nothing here removes a
//! point or edits a label, so every index correspondence the app relies on —
//! profile positions, segment attempt indices, the odometer — survives untouched.
//! That is deliberate: a trim is what the rider says the ride was, not a claim
//! about what the sensors recorded.
use crate::activity::ActivityState;
use crate::canonical::{CanonicalTrackPoint, RideTotals, ride_breakdown};

/// The span the rider calls the ride. Absolute recording timestamps, inclusive
/// at both ends, so re-opening the editor round-trips the same boundary.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct RideBounds {
    pub started_at_ms: i64,
    pub ended_at_ms: i64,
}

impl RideBounds {
    pub(crate) fn contains(&self, point: &CanonicalTrackPoint) -> bool {
        (self.started_at_ms..=self.ended_at_ms).contains(&point.timestamp_ms)
    }
}

/// True when the point counts toward the ride under these bounds. No bounds
/// means the whole recording counts.
pub(crate) fn within(bounds: Option<RideBounds>, point: &CanonicalTrackPoint) -> bool {
    bounds.is_none_or(|bounds| bounds.contains(point))
}

#[derive(Debug, uniffi::Error)]
pub enum RideBoundsError {
    Invalid { msg: String },
}
impl std::fmt::Display for RideBoundsError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Invalid { msg } => f.write_str(msg),
        }
    }
}
impl std::error::Error for RideBoundsError {}

#[derive(Debug, uniffi::Record)]
pub struct BoundedRide {
    /// Totals over the kept span only, recomputed rather than subtracted —
    /// ascent and descent carry a reference altitude forward, so the parts of a
    /// ride never sum to the whole.
    pub ride: RideTotals,
    /// Running ride distance per input point, index-aligned with the input and
    /// flat wherever the rider is in a vehicle or outside the bounds.
    pub odometer_m: Vec<f64>,
    /// Half-open index range of the points the bounds keep.
    pub kept_from: u32,
    pub kept_to: u32,
}

/// Recompute the ride under the rider's bounds. `started_at_ms`/`ended_at_ms`
/// are the recording's own bounds, which never shrink — validating against the
/// trimmed span instead would make every edit one-way.
#[uniffi::export]
pub fn ride_within(
    track: Vec<CanonicalTrackPoint>,
    bounds: Option<RideBounds>,
    started_at_ms: i64,
    ended_at_ms: i64,
) -> Result<BoundedRide, RideBoundsError> {
    if let Some(bounds) = bounds {
        if bounds.started_at_ms >= bounds.ended_at_ms {
            return Err(RideBoundsError::Invalid {
                msg: "The ride must start before it finishes".into(),
            });
        }
        if bounds.started_at_ms < started_at_ms || bounds.ended_at_ms > ended_at_ms {
            return Err(RideBoundsError::Invalid {
                msg: "Trim boundaries must be inside the recording".into(),
            });
        }
        if track.iter().filter(|point| bounds.contains(point)).count() < 2 {
            return Err(RideBoundsError::Invalid {
                msg: "Trimming this far leaves no ride behind".into(),
            });
        }
    }
    let kept_from = track
        .iter()
        .position(|point| within(bounds, point))
        .unwrap_or(0) as u32;
    let kept_to = track
        .iter()
        .rposition(|point| within(bounds, point))
        .map_or(0, |last| last as u32 + 1);
    let (ride, odometer_m) = ride_breakdown(&track, bounds);
    Ok(BoundedRide {
        ride,
        odometer_m,
        kept_from,
        kept_to,
    })
}

/// One stretch of riding between shuttles or manual pauses — the unit a TCX lap
/// records and the unit a rider picks when exporting a single descent.
///
/// A dropped fix mid-descent does NOT end a run: the rider kept riding, and the
/// gap is a recording artifact. Only a vehicle or a manual pause ends one.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct RideRun {
    /// Position among the runs of this activity. Presentation only — it
    /// renumbers whenever an annotation changes, so never persist it as identity.
    pub index: u32,
    pub started_at_ms: i64,
    pub ended_at_ms: i64,
    /// Half-open index range into the track this was enumerated from.
    pub from_index: u32,
    pub to_index: u32,
    pub distance_m: f64,
    pub ascent_m: f64,
    pub descent_m: f64,
    pub moving_time_s: f64,
}

/// Enumerate the riding runs of a finalized track, honouring the rider's bounds.
///
/// This is the single definition of a run. Kotlin labels its export points from
/// what this returns rather than deciding boundaries again, so the run a rider
/// taps and the lap that gets written are always the same descent.
#[uniffi::export]
pub fn ride_runs(track: Vec<CanonicalTrackPoint>, bounds: Option<RideBounds>) -> Vec<RideRun> {
    let mut runs: Vec<RideRun> = Vec::new();
    let mut start: Option<usize> = None;
    for index in 0..=track.len() {
        let continues = track.get(index).is_some_and(|point| {
            point.activity_state != ActivityState::LikelyMotorized
                && within(bounds, point)
                && start.is_none_or(|first| track[first].section_id == point.section_id)
        });
        match (start, continues) {
            (None, true) => start = Some(index),
            (Some(first), false) => {
                push_run(&mut runs, &track, first, index);
                start = None;
            }
            _ => {}
        }
        // A section change ends the previous run and opens the next one on the
        // same point, which the plain continue/stop pair above cannot express.
        if start.is_none()
            && let Some(point) = track.get(index)
            && point.activity_state != ActivityState::LikelyMotorized
            && within(bounds, point)
        {
            start = Some(index);
        }
    }
    runs
}

fn push_run(runs: &mut Vec<RideRun>, track: &[CanonicalTrackPoint], from: usize, to: usize) {
    if to - from < 2 {
        return;
    }
    // Measured from the run's own start with the same accumulators the whole
    // ride uses, so a run's distance is never a subtraction of two odometers.
    let (totals, _) = ride_breakdown(&track[from..to], None);
    runs.push(RideRun {
        index: runs.len() as u32,
        started_at_ms: track[from].timestamp_ms,
        ended_at_ms: track[to - 1].timestamp_ms,
        from_index: from as u32,
        to_index: to as u32,
        distance_m: totals.distance_m,
        ascent_m: totals.ascent_m,
        descent_m: totals.descent_m,
        moving_time_s: totals.moving_time_s,
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::segment_editor::tests_support::point;

    /// Two descents with a shuttle between them, at 1 Hz and 1 m per sample.
    fn shuttle_day() -> Vec<CanonicalTrackPoint> {
        let mut track: Vec<_> = (0..200)
            .map(|i| point(i * 1_000, i as f64, 0, ActivityState::Downhill))
            .collect();
        track.extend(
            (200..400).map(|i| point(i * 1_000, i as f64, 0, ActivityState::LikelyMotorized)),
        );
        track.extend((400..600).map(|i| point(i * 1_000, i as f64, 0, ActivityState::Downhill)));
        track
    }

    #[test]
    fn runs_split_on_transport_and_pauses_but_never_on_a_dropped_fix() {
        let mut track = shuttle_day();
        // A ten-second hole inside the second descent: still one run.
        for point in &mut track[500..] {
            point.timestamp_ms += 10_000;
        }
        // A manual pause inside the first descent: two runs.
        for point in &mut track[100..200] {
            point.section_id = 1;
        }

        let runs = ride_runs(track, None);

        assert_eq!(runs.len(), 3, "{runs:#?}");
        assert_eq!(runs[0].from_index, 0);
        assert_eq!(runs[0].to_index, 100);
        assert_eq!(runs[1].from_index, 100);
        assert_eq!(runs[1].to_index, 200);
        assert_eq!(runs[2].from_index, 400, "the dropped fix split a descent");
        assert_eq!(runs[2].to_index, 600);
        assert_eq!(
            runs.iter().map(|run| run.index).collect::<Vec<_>>(),
            [0, 1, 2]
        );
        // A metre per sample, so each run measures about its own span. It lands
        // a metre or two under because the 1 m anchor holds the first step of
        // every stream, and the run carrying the hole also loses the single pair
        // that spans it — the gap is never bridged, but it ends nothing.
        for (run, expected_m) in runs.iter().zip([99.0, 99.0, 198.0]) {
            assert!(
                (run.distance_m - expected_m).abs() < 3.0,
                "run {} measured {} m, expected about {expected_m} m",
                run.index,
                run.distance_m,
            );
        }
    }

    #[test]
    fn bounds_drop_the_runs_they_exclude_and_shorten_the_one_they_cut() {
        let track = shuttle_day();
        let whole = ride_runs(track.clone(), None);
        assert_eq!(whole.len(), 2);

        // Keep only the second half of the closing descent.
        let bounds = Some(RideBounds {
            started_at_ms: 500_000,
            ended_at_ms: 599_000,
        });
        let trimmed = ride_runs(track, bounds);

        assert_eq!(trimmed.len(), 1);
        assert_eq!(trimmed[0].index, 0, "the kept run is renumbered from zero");
        assert_eq!(trimmed[0].from_index, 500);
        assert_eq!(trimmed[0].to_index, 600);
        assert!(trimmed[0].distance_m < whole[1].distance_m);
    }

    #[test]
    fn bounds_recompute_the_totals_and_leave_the_odometer_flat_outside() {
        let track = shuttle_day();
        let whole = ride_within(track.clone(), None, 0, 599_000).unwrap();
        let bounds = RideBounds {
            started_at_ms: 400_000,
            ended_at_ms: 599_000,
        };
        let trimmed = ride_within(track.clone(), Some(bounds), 0, 599_000).unwrap();

        assert_eq!(trimmed.kept_from, 400);
        assert_eq!(trimmed.kept_to, 600);
        assert!(
            trimmed.ride.distance_m < whole.ride.distance_m,
            "trimming away the first descent did not shorten the ride",
        );
        assert!(
            trimmed.odometer_m[..=400]
                .iter()
                .all(|metres| *metres == 0.0),
            "the trimmed head advanced the odometer",
        );
        assert_eq!(
            trimmed.odometer_m.last().copied(),
            Some(trimmed.ride.distance_m),
            "a TCX would state a distance the app never shows",
        );
        assert!(trimmed.odometer_m.windows(2).all(|pair| pair[1] >= pair[0]));
    }

    #[test]
    fn bounds_are_refused_when_they_are_backwards_outside_or_leave_nothing() {
        let track = shuttle_day();
        for bounds in [
            RideBounds {
                started_at_ms: 300_000,
                ended_at_ms: 100_000,
            },
            RideBounds {
                started_at_ms: -1,
                ended_at_ms: 100_000,
            },
            RideBounds {
                started_at_ms: 0,
                ended_at_ms: 600_000,
            },
            // Inside the recording, but between two samples.
            RideBounds {
                started_at_ms: 100_100,
                ended_at_ms: 100_900,
            },
        ] {
            assert!(
                ride_within(track.clone(), Some(bounds), 0, 599_000).is_err(),
                "{bounds:?} was accepted",
            );
        }
    }

    /// The whole reason bounds validate against the recording and not against
    /// themselves: the rider must be able to widen a trim back out again.
    #[test]
    fn a_trim_can_be_widened_after_it_is_applied() {
        let track = shuttle_day();
        let narrow = RideBounds {
            started_at_ms: 500_000,
            ended_at_ms: 550_000,
        };
        ride_within(track.clone(), Some(narrow), 0, 599_000).unwrap();
        let wider = RideBounds {
            started_at_ms: 10_000,
            ended_at_ms: 590_000,
        };
        ride_within(track, Some(wider), 0, 599_000).unwrap();
    }
}
