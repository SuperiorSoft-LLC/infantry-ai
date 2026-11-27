// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! STATUS-LINE-1: the `host_stats` frame behind the footer line "RAM X GB free · agents CPU Y%". Each tick the daemon
//! reads the machine's free RAM and the CPU time of its agent session processes; CPU % is the CPU time used since the
//! last tick over the wall time and the logical cores (as Task Manager shows it).

use serde_json::{json, Value};
use std::collections::HashMap;

/// Seconds between two `host_stats` frames (0 = off).
pub const INTERVAL_SETTING: &str = "limits.processes.hostStatsSeconds";
pub const INTERVAL_DEFAULT_SECS: u64 = 5;

/// The CPU times of the last tick, to turn the next one into a percentage.
#[derive(Default)]
pub struct CpuSampler {
    last: Option<(u64, HashMap<u32, u64>)>,
}

impl CpuSampler {
    /// `now_ms`: wall clock; `times`: (pid, CPU ms so far) of each agent process now. None on the first sample.
    /// A process new since the last tick counts from this tick on.
    pub fn sample(&mut self, now_ms: u64, times: &[(u32, u64)], cores: u32) -> Option<f64> {
        let cur: HashMap<u32, u64> = times.iter().copied().collect();
        let out = self.last.as_ref().and_then(|(at, prev)| {
            let wall = now_ms.checked_sub(*at).filter(|w| *w > 0)?;
            let used: u64 = cur.iter().filter_map(|(pid, t)| prev.get(pid).map(|p| t.saturating_sub(*p))).sum();
            Some((used as f64 * 100.0 / (wall as f64 * cores.max(1) as f64)).min(100.0))
        });
        self.last = Some((now_ms, cur));
        out
    }
}

/// The frame body: `{freeMb, totalMb, agents, agentsCpuPercent}` (null where the OS does not answer, or on the first tick).
pub fn body(free_mb: Option<u64>, total_mb: Option<u64>, agents: usize, cpu: Option<f64>) -> Value {
    json!({ "freeMb": free_mb, "totalMb": total_mb, "agents": agents, "agentsCpuPercent": cpu.map(|c| (c * 10.0).round() / 10.0) })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn first_tick_has_no_cpu_then_the_delta_over_wall_and_cores() {
        let mut s = CpuSampler::default();
        assert_eq!(s.sample(1_000, &[(1, 500), (2, 100)], 4), None);
        // 5 s wall, 4 cores = 20 000 core-ms; pid 1 used 1 000 ms, pid 2 used 1 000 ms -> 10 %.
        assert_eq!(s.sample(6_000, &[(1, 1_500), (2, 1_100)], 4), Some(10.0));
    }

    #[test]
    fn a_new_process_counts_from_its_first_tick_and_a_gone_one_drops() {
        let mut s = CpuSampler::default();
        s.sample(0, &[(1, 100)], 1);
        assert_eq!(s.sample(1_000, &[(3, 9_000)], 1), Some(0.0));
        assert_eq!(s.sample(2_000, &[(3, 9_500)], 1), Some(50.0));
    }

    #[test]
    fn body_rounds_to_one_decimal_and_keeps_nulls() {
        assert_eq!(body(Some(10_854), Some(32_000), 2, Some(0.1234)), json!({"freeMb": 10_854, "totalMb": 32_000, "agents": 2, "agentsCpuPercent": 0.1}));
        assert_eq!(body(None, None, 0, None)["agentsCpuPercent"], Value::Null);
    }
}
