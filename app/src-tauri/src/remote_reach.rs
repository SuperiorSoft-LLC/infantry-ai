// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! PHONE-1: does the PC a phone is paired with answer, and does it take the phone's saved login? Built everywhere so the
//! rule is tested on the desktop; only the phone shell (`mobile.rs`, `remote_check`) calls it.
#![cfg_attr(not(mobile), allow(dead_code))]

/// PHONE-1: how long a saved PC has to answer before the phone opens the connect screen instead of the app.
const CHECK_SECS: u64 = 5;

/// What the phone found out about its saved PC (`@infantry/client` RemoteCheck).
#[derive(Debug, PartialEq, Eq)]
pub enum Reach {
    Ok,
    Unreachable,
    Refused,
}

impl Reach {
    pub fn word(&self) -> &'static str {
        match self {
            Reach::Ok => "ok",
            Reach::Unreachable => "unreachable",
            Reach::Refused => "refused",
        }
    }
}

/// `/health` answers without a token, so a failure there means the PC cannot be reached; `/ws` (a plain GET, no upgrade) is
/// checked against the saved token, and only a 401 or 403 there means the token is refused (any other answer: it was accepted).
pub fn classify(health: Result<u16, String>, ws: Result<u16, String>) -> Reach {
    match health {
        Ok(c) if c < 500 => {}
        _ => return Reach::Unreachable,
    }
    match ws {
        Ok(401) | Ok(403) => Reach::Refused,
        Err(_) => Reach::Unreachable,
        Ok(_) => Reach::Ok,
    }
}

fn status_of(r: Result<ureq::Response, ureq::Error>) -> Result<u16, String> {
    match r {
        Ok(resp) => Ok(resp.status()),
        Err(ureq::Error::Status(code, _)) => Ok(code),
        Err(e) => Err(e.to_string()),
    }
}

pub fn reach(url: &str, token: Option<&str>) -> Reach {
    let Ok(base) = crate::daemon_http::http_base(url) else { return Reach::Unreachable };
    let agent = {
        let d = std::time::Duration::from_secs(CHECK_SECS);
        ureq::AgentBuilder::new().timeout(d).timeout_connect(d).timeout_read(d).timeout_write(d).build()
    };
    let health = status_of(agent.get(&format!("{base}/health")).call());
    if !matches!(health, Ok(c) if c < 500) {
        return classify(health, Err("skipped".into()));
    }
    let mut req = agent.get(&format!("{base}/ws"));
    if let Some(t) = token.filter(|t| !t.is_empty()) {
        req = req.set("Authorization", &format!("Bearer {t}"));
    }
    classify(health, status_of(req.call()))
}


#[cfg(test)]
mod tests {
    use super::{classify, Reach};

    #[test]
    fn a_saved_pc_is_judged_by_health_then_by_its_token() {
        assert_eq!(classify(Ok(200), Ok(400)), Reach::Ok);
        assert_eq!(classify(Ok(200), Ok(426)), Reach::Ok);
        assert_eq!(classify(Ok(200), Ok(401)), Reach::Refused);
        assert_eq!(classify(Ok(200), Ok(403)), Reach::Refused);
        assert_eq!(classify(Err("timed out".into()), Err("skipped".into())), Reach::Unreachable);
        assert_eq!(classify(Ok(502), Ok(200)), Reach::Unreachable);
        assert_eq!(classify(Ok(200), Err("reset".into())), Reach::Unreachable);
    }
}
