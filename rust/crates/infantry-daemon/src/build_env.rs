// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The build environment (prod | staging | dev) that picks a plugin's endpoints (`module.json` `daemon.endpoints`).
//!
//! Order: a build baked with `INFANTRY_BUILD_ENV=prod` is ALWAYS prod (a release build cannot be pointed at a test
//! stack by a stray variable). Any other build reads `INFANTRY_ENV` at start, else the baked value, else prod.
//! To bake: `INFANTRY_BUILD_ENV=prod cargo build --release ...` (the release workflow does this). A local or test
//! build leaves it unset (or sets `staging`/`dev`) and starts with `INFANTRY_ENV=staging`.

/// The environments a plugin may declare endpoints for.
pub const ENVIRONMENTS: &[&str] = &["prod", "staging", "dev"];
/// The run-time variable.
pub const ENV_VAR: &str = "INFANTRY_ENV";

fn known(v: Option<&str>) -> Option<String> {
    v.map(|s| s.trim().to_ascii_lowercase()).filter(|s| ENVIRONMENTS.contains(&s.as_str()))
}

/// Pure resolution: `baked` is the build-time value, `runtime` the `INFANTRY_ENV` value. An unknown value is ignored.
pub fn resolve(baked: Option<&str>, runtime: Option<&str>) -> String {
    let baked = known(baked);
    if baked.as_deref() == Some("prod") {
        return "prod".into();
    }
    known(runtime).or(baked).unwrap_or_else(|| "prod".into())
}

/// The running build's environment (read once per call; the daemon reads it at start).
pub fn current() -> String {
    resolve(option_env!("INFANTRY_BUILD_ENV"), std::env::var(ENV_VAR).ok().as_deref())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_is_prod() {
        assert_eq!(resolve(None, None), "prod");
        assert_eq!(resolve(None, Some("nonsense")), "prod");
    }
    #[test]
    fn the_run_time_variable_picks_staging_and_dev() {
        assert_eq!(resolve(None, Some("staging")), "staging");
        assert_eq!(resolve(None, Some(" DEV ")), "dev");
    }
    #[test]
    fn a_build_baked_prod_cannot_be_changed() {
        assert_eq!(resolve(Some("prod"), Some("staging")), "prod");
    }
    #[test]
    fn a_staging_build_defaults_to_staging_and_may_be_overridden() {
        assert_eq!(resolve(Some("staging"), None), "staging");
        assert_eq!(resolve(Some("staging"), Some("dev")), "dev");
    }
}
