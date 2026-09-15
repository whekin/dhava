//! Transport episodes and explicit rider corrections. All classification and
//! derived totals stay in Rust; Android persists only the authored intervals.
use crate::activity::{ActivityClassification, ActivityState, non_motorized_classifications};
use crate::canonical::{CanonicalTrackPoint, RideTotals, ride_totals};
use crate::gps_quality::geographic_distance_m;

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct TransportEpisode {
    pub started_at_ms: i64,
    pub ended_at_ms: i64,
}

#[derive(Debug, uniffi::Error)]
pub enum TransportError {
    InvalidInterval { msg: String },
}
impl std::fmt::Display for TransportError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::InvalidInterval { msg } => f.write_str(msg),
        }
    }
}
impl std::error::Error for TransportError {}

#[derive(Debug, uniffi::Record)]
pub struct TransportCorrection {
    pub track: Vec<CanonicalTrackPoint>,
    pub ride: RideTotals,
    pub episodes: Vec<TransportEpisode>,
}

/// Replace automatic transport labels, restoring ordinary downhill/transit/still
/// outside the user's intervals. Empty means explicitly no transport.
#[uniffi::export]
pub fn correct_transport(
    mut track: Vec<CanonicalTrackPoint>,
    mut episodes: Vec<TransportEpisode>,
    started_at_ms: i64,
    ended_at_ms: i64,
) -> Result<TransportCorrection, TransportError> {
    episodes.sort_by_key(|e| e.started_at_ms);
    for (i, episode) in episodes.iter().enumerate() {
        if episode.started_at_ms < started_at_ms
            || episode.ended_at_ms > ended_at_ms
            || episode.started_at_ms >= episode.ended_at_ms
        {
            return Err(TransportError::InvalidInterval {
                msg: "Transport boundaries must be inside the recording, with start before finish"
                    .into(),
            });
        }
        if i > 0 && episodes[i - 1].ended_at_ms >= episode.started_at_ms {
            return Err(TransportError::InvalidInterval {
                msg: "Transport episodes must not overlap".into(),
            });
        }
        if !track
            .iter()
            .any(|p| (episode.started_at_ms..=episode.ended_at_ms).contains(&p.timestamp_ms))
        {
            return Err(TransportError::InvalidInterval {
                msg: "No recorded positions in this interval".into(),
            });
        }
    }
    let base = non_motorized_classifications(&track);
    let mut episode = 0;
    for (point, label) in track.iter_mut().zip(base) {
        while episode < episodes.len() && point.timestamp_ms > episodes[episode].ended_at_ms {
            episode += 1;
        }
        let motorized =
            episode < episodes.len() && point.timestamp_ms >= episodes[episode].started_at_ms;
        point.activity_state = if motorized {
            ActivityState::LikelyMotorized
        } else {
            label.state
        };
        point.activity_confidence = if motorized { 1.0 } else { label.confidence };
    }
    let ride = ride_totals(&track);
    Ok(TransportCorrection {
        track,
        ride,
        episodes,
    })
}

const UNLOADING_STOP_MS: i64 = 30_000;
const MAX_DEPARTURE_BACKFILL_MS: i64 = 120_000;
const LONG_STOP_MS: i64 = 180_000;
const EXIT_LOOKAHEAD_MS: i64 = 300_000;
const RIDING_TAIL_MS: i64 = 45_000;
const RIDING_TAIL_DROP_M: f64 = 30.0;
/// A riding gap this brief and this short is label flicker around boarding or
/// unloading, not a run. Splitting on it scatters one descent across several
/// exported segments, so the episodes around it belong together.
const MIN_RIDING_GAP_MS: i64 = 60_000;
const MIN_RIDING_GAP_M: f64 = 100.0;
/// Likewise, an interval that goes nowhere in almost no time is not a vehicle.
const MIN_EPISODE_MS: i64 = 60_000;
const MIN_EPISODE_M: f64 = 150.0;

/// A stateful episode pass. Evidence establishes entry; changing slope does
/// not change vehicle identity. Stops and subsequent motion resolve exit.
pub(crate) fn label_episodes(
    track: &[CanonicalTrackPoint],
    labels: &mut [ActivityClassification],
) -> Vec<TransportEpisode> {
    if track.is_empty() {
        return Vec::new();
    }
    let mut ranges: Vec<(usize, usize)> = Vec::new();
    let evidence = labels.to_vec();
    let mut active: Option<usize> = None;
    let mut last_vehicle = 0;
    let mut departure = 0;
    let mut i = 0;
    while i < track.len() {
        if track[i].stationary == Some(true) {
            let stop_start = i;
            while i + 1 < track.len() && track[i + 1].stationary == Some(true) {
                i += 1;
            }
            let duration =
                track.get(i + 1).unwrap_or(&track[i]).timestamp_ms - track[stop_start].timestamp_ms;
            if duration >= UNLOADING_STOP_MS {
                let next_vehicle = evidence[i + 1..]
                    .iter()
                    .position(|e| e.state == ActivityState::LikelyMotorized)
                    .map(|offset| i + 1 + offset);
                let resumes_vehicle = next_vehicle.is_some_and(|next| {
                    track[next].timestamp_ms - track[i].timestamp_ms <= EXIT_LOOKAHEAD_MS
                        && riding_tail(track, &evidence, i + 1, next).is_none()
                });
                if (duration >= LONG_STOP_MS || !resumes_vehicle)
                    && let Some(start) = active.take()
                {
                    let end = riding_tail(track, &evidence, last_vehicle + 1, stop_start)
                        .unwrap_or(stop_start);
                    push_range(&mut ranges, start, end);
                }
                departure = i + 1;
            }
        } else if evidence[i].state == ActivityState::LikelyMotorized {
            if active.is_none() {
                // Backfill the boarding/departure part only, never an earlier run.
                let mut start = i;
                while start > departure
                    && evidence[start - 1].state != ActivityState::Downhill
                    && track[i].timestamp_ms - track[start - 1].timestamp_ms
                        <= MAX_DEPARTURE_BACKFILL_MS
                {
                    start -= 1;
                }
                active = Some(start);
            }
            last_vehicle = i;
        }
        i += 1;
    }
    if let Some(start) = active {
        let end =
            riding_tail(track, &evidence, last_vehicle + 1, track.len()).unwrap_or(track.len());
        push_range(&mut ranges, start, end);
    }
    let (ranges, discarded) = coalesce(track, ranges);
    for &(start, end) in &ranges {
        for label in &mut labels[start..end] {
            label.state = ActivityState::LikelyMotorized;
            label.confidence = 0.8;
        }
    }
    if !discarded.is_empty() {
        // The evidence that opened these ranges still reads as a vehicle, so
        // rejecting the episode means re-reading those points as human motion.
        let on_foot = non_motorized_classifications(track);
        for (start, end) in discarded {
            labels[start..end].clone_from_slice(&on_foot[start..end]);
        }
    }
    ranges
        .iter()
        .map(|&(start, end)| TransportEpisode {
            started_at_ms: track[start].timestamp_ms,
            ended_at_ms: track[end - 1].timestamp_ms,
        })
        .collect()
}

/// Half-open `start..end` spans of track indices.
type Ranges = Vec<(usize, usize)>;

fn push_range(ranges: &mut Ranges, start: usize, end: usize) {
    if start < end {
        ranges.push((start, end));
    }
}

/// Glue episodes that flicker apart, then discard whatever is left that no
/// vehicle could have produced, returning the kept and the discarded. Merging
/// comes first: a real uplift chopped by a stray riding blip must become one
/// episode before anything is judged too small to keep, or its fragments would
/// each be dismissed as noise.
fn coalesce(track: &[CanonicalTrackPoint], ranges: Ranges) -> (Ranges, Ranges) {
    let mut merged: Ranges = Vec::with_capacity(ranges.len());
    for (start, end) in ranges {
        match merged.last_mut() {
            Some(previous)
                if insubstantial(
                    track,
                    previous.1 - 1,
                    start,
                    MIN_RIDING_GAP_MS,
                    MIN_RIDING_GAP_M,
                ) =>
            {
                previous.1 = end;
            }
            _ => merged.push((start, end)),
        }
    }
    merged.into_iter().partition(|&(start, end)| {
        !insubstantial(track, start, end - 1, MIN_EPISODE_MS, MIN_EPISODE_M)
    })
}

/// Neither long enough nor far enough to be a genuine interval.
fn insubstantial(
    track: &[CanonicalTrackPoint],
    from: usize,
    to: usize,
    max_ms: i64,
    max_m: f64,
) -> bool {
    if track[to].timestamp_ms - track[from].timestamp_ms >= max_ms {
        return false;
    }
    let travelled: f64 = track[from..=to]
        .windows(2)
        .map(|pair| geographic_distance_m(pair[0].lat, pair[0].lon, pair[1].lat, pair[1].lon))
        .sum();
    travelled < max_m
}

/// A substantial descending tail without renewed vehicle evidence can be a
/// missed unloading transition. Retain it as riding; users can correct the
/// ambiguous case of a vehicle whose journey itself ends downhill.
fn riding_tail(
    track: &[CanonicalTrackPoint],
    labels: &[ActivityClassification],
    start: usize,
    end: usize,
) -> Option<usize> {
    if start >= end {
        return None;
    }
    let first = (start..end).find(|&i| labels[i].state == ActivityState::Downhill)?;
    let last = end - 1;
    if track[last].timestamp_ms - track[first].timestamp_ms < RIDING_TAIL_MS {
        return None;
    }
    let drop = track[first].altitude_m? - track[last].altitude_m?;
    (drop >= RIDING_TAIL_DROP_M).then_some(first)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::segment_editor::tests_support::point;

    #[test]
    fn manual_intervals_change_labels_and_totals_but_never_geometry() {
        let track: Vec<_> = (0..100)
            .map(|i| point(i * 1_000, i as f64 * 5.0, 0, ActivityState::LikelyMotorized))
            .collect();
        let original = track.clone();
        let without = correct_transport(track.clone(), vec![], 0, 99_000).unwrap();
        assert!(
            without
                .track
                .iter()
                .all(|p| p.activity_state == ActivityState::Downhill)
        );
        assert_eq!(without.ride.transport_distance_m, 0.0);
        let corrected = correct_transport(
            track.clone(),
            vec![TransportEpisode {
                started_at_ms: 20_000,
                ended_at_ms: 50_000,
            }],
            0,
            99_000,
        )
        .unwrap();
        for (i, (before, after)) in track.iter().zip(&corrected.track).enumerate() {
            assert_eq!(
                (
                    before.timestamp_ms,
                    before.lat,
                    before.lon,
                    before.altitude_m,
                    before.section_id
                ),
                (
                    after.timestamp_ms,
                    after.lat,
                    after.lon,
                    after.altitude_m,
                    after.section_id
                )
            );
            assert_eq!(
                after.activity_state == ActivityState::LikelyMotorized,
                (20..=50).contains(&i)
            );
        }
        assert!(corrected.ride.distance_m < without.ride.distance_m);
        assert_eq!(track, original);
        assert_eq!(
            corrected.episodes,
            vec![TransportEpisode {
                started_at_ms: 20_000,
                ended_at_ms: 50_000
            }]
        );
    }

    #[test]
    fn corrections_reject_invalid_boundaries_and_overlap() {
        let track: Vec<_> = (0..100)
            .map(|i| point(i * 1_000, i as f64, 0, ActivityState::Downhill))
            .collect();
        for episodes in [
            vec![TransportEpisode {
                started_at_ms: -1,
                ended_at_ms: 10_000,
            }],
            vec![TransportEpisode {
                started_at_ms: 0,
                ended_at_ms: 100_000,
            }],
            vec![TransportEpisode {
                started_at_ms: 30_000,
                ended_at_ms: 10_000,
            }],
            vec![
                TransportEpisode {
                    started_at_ms: 0,
                    ended_at_ms: 30_000,
                },
                TransportEpisode {
                    started_at_ms: 20_000,
                    ended_at_ms: 40_000,
                },
            ],
        ] {
            assert!(correct_transport(track.clone(), episodes, 0, 99_000).is_err());
        }
    }

    #[test]
    fn one_episode_keeps_long_road_descents_and_ends_at_unloading() {
        let mut track: Vec<_> = (0..800)
            .map(|i| point(i * 1_000, i as f64, 0, ActivityState::Transit))
            .collect();
        let mut labels: Vec<_> = track
            .iter()
            .map(|_| ActivityClassification {
                state: ActivityState::Transit,
                confidence: 0.8,
            })
            .collect();
        for label in &mut labels[20..80] {
            label.state = ActivityState::LikelyMotorized;
        }
        for label in &mut labels[100..400] {
            label.state = ActivityState::Downhill;
        }
        for label in &mut labels[400..460] {
            label.state = ActivityState::LikelyMotorized;
        }
        for (point, label) in track[470..530].iter_mut().zip(&mut labels[470..530]) {
            point.stationary = Some(true);
            label.state = ActivityState::Still;
        }
        for label in &mut labels[530..] {
            label.state = ActivityState::Downhill;
        }
        label_episodes(&track, &mut labels);
        assert!(
            labels[..470]
                .iter()
                .all(|p| p.state == ActivityState::LikelyMotorized)
        );
        assert!(
            labels[470..530]
                .iter()
                .all(|p| p.state == ActivityState::Still)
        );
        assert!(
            labels[530..]
                .iter()
                .all(|p| p.state == ActivityState::Downhill)
        );
    }

    /// Reproduces a real uplift whose unloading tail alternated vehicle and
    /// still every few sparse fixes, which used to leave seven single-point
    /// riding segments stranded between seven throwaway episodes.
    #[test]
    fn flicker_at_unloading_yields_one_episode_not_a_dozen() {
        let mut track = Vec::new();
        let mut labels = Vec::new();
        let mut push = |north_m: f64, state: ActivityState| {
            let timestamp_ms = track.len() as i64 * 5_000;
            track.push(point(timestamp_ms, north_m, 0, state));
            labels.push(ActivityClassification {
                state,
                confidence: 0.8,
            });
        };
        // Two kilometres of uplift, then the flicker, then a real descent.
        for i in 0..120 {
            push(i as f64 * -20.0, ActivityState::LikelyMotorized);
        }
        for i in 0..14 {
            push(-2_400.0, ActivityState::Still);
            push(-2_400.0, ActivityState::Downhill);
            let _ = i;
        }
        for i in 0..200 {
            push(-2_400.0 + i as f64 * 15.0, ActivityState::Downhill);
        }
        for point in &mut track[120..148] {
            point.stationary = Some(true);
        }
        let episodes = label_episodes(&track, &mut labels);
        assert_eq!(episodes.len(), 1, "flicker must not split the uplift");
        assert!(
            labels[..120]
                .iter()
                .all(|l| l.state == ActivityState::LikelyMotorized)
        );
        assert!(
            labels[148..]
                .iter()
                .all(|l| l.state == ActivityState::Downhill),
            "the descent after unloading stays riding"
        );
    }

    #[test]
    fn an_episode_going_nowhere_in_no_time_is_discarded() {
        let mut track: Vec<_> = (0..400)
            .map(|i| point(i * 1_000, i as f64 * 3.0, 0, ActivityState::Downhill))
            .collect();
        let mut labels: Vec<_> = track
            .iter()
            .map(|_| ActivityClassification {
                state: ActivityState::Downhill,
                confidence: 0.9,
            })
            .collect();
        // Twenty seconds and sixty metres of spurious vehicle evidence.
        for (point, label) in track[200..220].iter_mut().zip(&mut labels[200..220]) {
            point.speed_mps = Some(3.0);
            label.state = ActivityState::LikelyMotorized;
        }
        assert!(label_episodes(&track, &mut labels).is_empty());
        assert!(
            labels
                .iter()
                .all(|l| l.state != ActivityState::LikelyMotorized),
            "a discarded episode must not leave motorized labels behind"
        );
    }
}
