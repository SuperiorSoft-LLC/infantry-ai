// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Kotlin `StatsConsistencyTest` (STATS-2), ported with its exact fixtures and expected values: one request sequence
//! written as a Claude transcript, a pi record and an opencode database must give the numbers the Kotlin app gave.

use infantry_harness::stats::{fold, StoreStats};
use infantry_harness::{Harness, Probe};
use std::path::{Path, PathBuf};

/// A machine whose home is a temp dir and whose environment is empty.
struct Home(PathBuf);

impl Probe for Home {
    fn env(&self, _name: &str) -> Option<String> {
        None
    }
    fn home(&self) -> Option<PathBuf> {
        Some(self.0.clone())
    }
    fn read_to_string(&self, path: &Path) -> Option<String> {
        infantry_harness::LocalProbe.read_to_string(path)
    }
    fn exists(&self, path: &Path) -> bool {
        path.exists()
    }
    fn list_dir(&self, path: &Path) -> Vec<PathBuf> {
        infantry_harness::LocalProbe.list_dir(path)
    }
    fn lines(&self, path: &Path) -> Option<Box<dyn Iterator<Item = String> + '_>> {
        infantry_harness::LocalProbe.lines(path)
    }
}

/// input, output, cacheRead, cacheWrite; the third request is all-zero (an aborted call) and must not count.
const REQS: [(u64, u64, u64, u64); 4] = [(100, 20, 900, 0), (50, 10, 1300, 200), (0, 0, 0, 0), (5, 7, 1700, 0)];
const T0: i64 = 1_792_576_800_000; // 2026-10-21T10:00:00Z
const MODEL: &str = "model-x";

fn at(i: usize) -> i64 {
    T0 + (i as i64 + 1) * 1000
}
fn iso(i: usize) -> String {
    format!("2026-10-21T10:00:{:02}Z", i + 1)
}

fn write_claude(home: &Path) {
    let lines: Vec<String> = REQS
        .iter()
        .enumerate()
        .map(|(i, (inp, out, read, write))| {
            format!(r#"{{"type":"assistant","timestamp":"{}","message":{{"id":"m{i}","role":"assistant","model":"{MODEL}","content":[{{"type":"tool_use","id":"t{i}","name":"Read","input":{{}}}}],"usage":{{"input_tokens":{inp},"cache_creation_input_tokens":{write},"cache_read_input_tokens":{read},"output_tokens":{out}}}}}}}"#, iso(i))
        })
        .collect();
    std::fs::create_dir_all(home.join(".claude/projects/p")).unwrap();
    std::fs::write(home.join(".claude/projects/p/c1.jsonl"), lines.join("\n") + "\n").unwrap();
}

fn write_pi(home: &Path) {
    let lines: Vec<String> = REQS
        .iter()
        .enumerate()
        .map(|(i, (inp, out, read, write))| {
            format!(
                r#"{{"type":"message","id":"a{i}","timestamp":"{}","message":{{"role":"assistant","content":[{{"type":"toolCall","id":"c{i}","name":"Read","arguments":{{}}}}],"provider":"x","model":"{MODEL}","usage":{{"input":{inp},"output":{out},"cacheRead":{read},"cacheWrite":{write},"totalTokens":{},"cost":{{"total":0.5}}}},"stopReason":"toolUse","timestamp":{}}}}}"#,
                iso(i),
                inp + out + read + write,
                at(i)
            )
        })
        .collect();
    std::fs::create_dir_all(home.join(".pi/agent/sessions/d")).unwrap();
    std::fs::write(home.join(".pi/agent/sessions/d/2026-10-21T10-00-00-000Z_p1.jsonl"), lines.join("\n") + "\n").unwrap();
    std::fs::write(home.join(".pi/agent/models-store.json"), format!(r#"{{"x":{{"models":[{{"id":"{MODEL}","contextWindow":200000}}]}}}}"#)).unwrap();
}

fn write_opencode(home: &Path) {
    let dir = home.join(".local/share/opencode");
    std::fs::create_dir_all(&dir).unwrap();
    let c = rusqlite::Connection::open(dir.join("opencode.db")).unwrap();
    c.execute_batch(
        "CREATE TABLE `session` (`id` text PRIMARY KEY, `time_created` integer NOT NULL, `time_updated` integer NOT NULL);
         CREATE TABLE `message` (`id` text PRIMARY KEY, `session_id` text NOT NULL, `time_created` integer NOT NULL, `time_updated` integer NOT NULL, `data` text NOT NULL);
         CREATE TABLE `part` (`id` text PRIMARY KEY, `message_id` text NOT NULL, `session_id` text NOT NULL, `time_created` integer NOT NULL, `time_updated` integer NOT NULL, `data` text NOT NULL);",
    )
    .unwrap();
    c.execute("INSERT INTO session VALUES ('o1',?1,?2)", [at(0), at(3)]).unwrap();
    for (i, (inp, out, read, write)) in REQS.iter().enumerate() {
        c.execute("INSERT INTO message VALUES (?1,'o1',?2,?2,?3)", rusqlite::params![format!("m{i}"), at(i), format!(r#"{{"role":"assistant","modelID":"{MODEL}","providerID":"x"}}"#)]).unwrap();
        c.execute("INSERT INTO part VALUES (?1,?2,'o1',?3,?3,?4)", rusqlite::params![format!("pa{i}"), format!("m{i}"), at(i) - 1, format!(r#"{{"type":"tool","callID":"c{i}","tool":"Read","state":{{"status":"completed"}}}}"#)]).unwrap();
        c.execute(
            "INSERT INTO part VALUES (?1,?2,'o1',?3,?3,?4)",
            rusqlite::params![format!("pf{i}"), format!("m{i}"), at(i), format!(r#"{{"type":"step-finish","reason":"tool-calls","cost":0.5,"tokens":{{"input":{inp},"output":{out},"reasoning":0,"cache":{{"read":{read},"write":{write}}}}}}}"#)],
        )
        .unwrap();
    }
}

fn read(h: &dyn Harness, probe: &Home, psid: &str) -> StoreStats {
    fold(&h.store_stats(probe, psid).unwrap_or_else(|| panic!("{}: no record", h.id())))
}

#[test]
fn the_same_request_sequence_gives_the_kotlin_numbers_on_every_harness() {
    let tmp = std::env::temp_dir().join(format!("infantry-stats-consistency-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&tmp);
    write_claude(&tmp);
    write_pi(&tmp);
    write_opencode(&tmp);
    let probe = Home(tmp.clone());
    let claude = infantry_harness_claude::ClaudeHarness::default();
    let pi = infantry_harness_pi::PiHarness::default();
    let opencode = infantry_harness_opencode::OpenCodeHarness::default();
    let results = [read(&claude, &probe, "c1"), read(&pi, &probe, "p1"), read(&opencode, &probe, "o1")];
    // Kotlin's expected points: (at, context, cumulative billed).
    let expected = vec![(Some(at(0)), 1000, 1020), (Some(at(1)), 1550, 2580), (Some(at(3)), 1705, 4292)];
    for (name, s) in ["claude", "pi", "opencode"].iter().zip(&results) {
        assert_eq!(s.series.iter().map(|p| (p.at, p.context_tokens, p.cumulative_billed_tokens)).collect::<Vec<_>>(), expected, "{name}");
        assert_eq!((s.series.last().map(|p| p.context_tokens), s.billed_tokens, s.tool_uses), (Some(1705), 4292, 4), "{name}");
        assert_eq!(s.last_model.as_deref(), Some(MODEL), "{name}");
    }
    assert_eq!(results[0].models, vec![MODEL.to_string()]);
    assert_eq!(results[1].models, vec![MODEL.to_string()]);
    assert_eq!(results[0].top_sources, vec![("Read".to_string(), 4, Some(550))]);
    assert_eq!(results[0].top_sources, results[1].top_sources);
    // opencode, as Kotlin `OpenCodeStats` reads it: the all-zero step is dropped BEFORE its tool is handed over, so that
    // tool joins the next step and the 155-token growth is credited, where Claude/pi leave it unattributed (Kotlin's
    // test never compared opencode's sources; this is the Kotlin reader's own result, kept as-is).
    assert_eq!(results[2].top_sources, vec![("Read".to_string(), 4, Some(705))]);
    assert_eq!((results[0].unattributed_tool_tokens, results[2].unattributed_tool_tokens), (155, 0));
    // Cost: only what the harness reports. Claude reports none; pi and opencode 0.5 per request.
    assert_eq!(results[0].cost_total, None);
    assert!(results[0].series.iter().all(|p| p.cumulative_cost.is_none()));
    assert_eq!(results[1].series.iter().map(|p| p.cumulative_cost).collect::<Vec<_>>(), vec![Some(0.5), Some(1.0), Some(1.5)]);
    assert_eq!(results[1].cost_total, Some(1.5));
    assert_eq!(results[2].cost_total, Some(1.5));
    // The limit comes from the model through a local catalog (claude states none of its own; the daemon asks pi's).
    assert_eq!(claude.context_window(&probe, MODEL), None);
    assert_eq!(pi.context_window(&probe, MODEL), Some(200_000));
    let _ = std::fs::remove_dir_all(&tmp);
}
