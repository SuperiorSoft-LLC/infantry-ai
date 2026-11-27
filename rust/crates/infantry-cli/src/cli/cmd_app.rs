// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! LOCAL-REPO-1: `infantry app publish <dir> [--repo <name>] [--channel <c>] [--notes <t>] [--platform <p>] [--os-home <dir>]`
//! and `infantry app repos [--os-home <dir>]`. Local only: no daemon, no network.

use super::{Getenv, Io, RawCommand};
use crate::localrepo;
use std::path::PathBuf;

pub const APP: RawCommand = RawCommand { matches: is_app, run };

fn is_app(a: &[String]) -> bool {
    a.first().is_some_and(|w| w == "app")
}

const USAGE: &str = "usage: infantry app publish <dir> [--repo <name>] [--channel stable|prerelease|...] [--notes <text>] [--os-home <dir>]\n       infantry app repos [--os-home <dir>]\nPublishes a built BattalionOS app (a folder with app.json) to a private local repo on this machine, signed with this device's local key. The Armory installs and updates it from there.";

fn run(argv: &[String], io: &mut dyn Io, getenv: Getenv) -> i32 {
    let mut pos = Vec::new();
    let (mut repo, mut channel, mut notes, mut platform, mut home) = (None, None, None, None, None);
    let mut it = argv.iter().skip(1);
    while let Some(w) = it.next() {
        let slot = match w.as_str() {
            "--repo" => &mut repo,
            "--channel" => &mut channel,
            "--notes" => &mut notes,
            "--platform" => &mut platform,
            "--os-home" => &mut home,
            "-h" | "--help" => {
                io.out(USAGE);
                return 0;
            }
            s if s.starts_with("--") => {
                io.err(&format!("error: unknown option {s}"));
                io.err(USAGE);
                return 2;
            }
            _ => {
                pos.push(w.clone());
                continue;
            }
        };
        match it.next() {
            Some(v) => *slot = Some(v.clone()),
            None => {
                io.err(&format!("error: {w} needs a value"));
                return 2;
            }
        }
    }
    let home = match localrepo::os_home(home.as_deref(), getenv) {
        Ok(h) => h,
        Err(e) => {
            io.err(&format!("error: {e}"));
            return 2;
        }
    };
    match pos.first().map(String::as_str) {
        Some("publish") if pos.len() == 2 => {
            let dir = PathBuf::from(&pos[1]);
            let a = localrepo::PublishArgs {
                dir: &dir,
                repo: repo.as_deref().unwrap_or(localrepo::DEFAULT_REPO),
                channel: channel.as_deref().unwrap_or(localrepo::DEFAULT_CHANNEL),
                platform: platform.as_deref(),
                notes: notes.as_deref(),
                home: &home,
            };
            match localrepo::publish(&a) {
                Ok(p) => {
                    io.out(&localrepo::summary(&p));
                    0
                }
                Err(e) => {
                    io.err(&format!("error: {e}"));
                    1
                }
            }
        }
        Some("repos") if pos.len() == 1 => {
            let repos = localrepo::list_repos(&home);
            if repos.is_empty() {
                io.out(&format!("no local repos under {} (the first publish makes \"{}\")", home.display(), localrepo::DEFAULT_REPO));
            }
            for (n, d) in repos {
                io.out(&format!("{n}\t{}", d.display()));
            }
            0
        }
        _ => {
            io.err(USAGE);
            2
        }
    }
}
