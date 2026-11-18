// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Re-reads every live plan meter on its own every `usage.refreshMinutes` (default 5, 0 = off) and tells the caller
//! so it can push the new report. One read at a time; a failed read waits until the source's `retryAt`, else doubles
//! the wait (up to [`MAX_BACKOFF_MS`]); a good read resets it. Wakes at most once a minute.
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::Duration;

use infantry_protocol::usage::ProviderLimits;

use crate::limits::is_failure;
use crate::util::Clock;

pub const IDLE_RECHECK_MS: i64 = 60_000;
pub const MAX_BACKOFF_MS: i64 = 60 * 60_000;

/// `minutes` doubled once per consecutive failure: at least a minute, at most 60 minutes.
pub fn delay_for(minutes: i64, failures: u32) -> i64 {
    let base = minutes.max(1) * 60_000;
    let cap = MAX_BACKOFF_MS.max(base);
    let mut d = base;
    for _ in 0..failures.min(12) {
        d = (d * 2).min(cap);
    }
    d
}

pub struct Refresher {
    read: Box<dyn Fn() -> Vec<ProviderLimits> + Send + Sync>,
    minutes: Box<dyn Fn() -> i64 + Send + Sync>,
    on_refreshed: Box<dyn Fn() + Send + Sync>,
    clock: Clock,
    running: AtomicBool,
    failures: AtomicU32,
}

impl Refresher {
    pub fn new(
        read: Box<dyn Fn() -> Vec<ProviderLimits> + Send + Sync>,
        minutes: Box<dyn Fn() -> i64 + Send + Sync>,
        on_refreshed: Box<dyn Fn() + Send + Sync>,
        clock: Clock,
    ) -> Self {
        Refresher { read, minutes, on_refreshed, clock, running: AtomicBool::new(false), failures: AtomicU32::new(0) }
    }

    /// Consecutive failed refreshes (0 after a good one).
    pub fn failure_count(&self) -> u32 {
        self.failures.load(Ordering::SeqCst)
    }

    /// Runs one refresh if enabled and none is in flight; returns the delay in ms until the next one.
    pub fn tick(&self) -> i64 {
        let every = (self.minutes)();
        if every <= 0 {
            self.failures.store(0, Ordering::SeqCst);
            return IDLE_RECHECK_MS;
        }
        if self.running.swap(true, Ordering::SeqCst) {
            return delay_for(every, self.failure_count());
        }
        let results = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| (self.read)()));
        self.running.store(false, Ordering::SeqCst);
        let results = match results {
            Ok(r) => r,
            Err(_) => {
                self.failures.fetch_add(1, Ordering::SeqCst);
                return delay_for(every, self.failure_count());
            }
        };
        if results.iter().any(is_failure) {
            self.failures.fetch_add(1, Ordering::SeqCst);
        } else {
            self.failures.store(0, Ordering::SeqCst);
        }
        let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| (self.on_refreshed)()));
        if self.failure_count() > 0 {
            // A source backing off says when it may be read again: wake then rather than on a guess.
            let now = (self.clock)();
            if let Some(wait) = results.iter().filter(|r| is_failure(r)).filter_map(|r| r.retry_at.map(|a| a - now)).filter(|d| *d > 0).min() {
                return wait.clamp(IDLE_RECHECK_MS, MAX_BACKOFF_MS.max(wait));
            }
        }
        delay_for(every, self.failure_count())
    }
}

/// A background loop that can be stopped at once (its sleep is a condvar wait).
pub struct Ticker {
    stop: Arc<(Mutex<bool>, Condvar)>,
    handle: Option<std::thread::JoinHandle<()>>,
}

impl Ticker {
    /// Runs `tick` after `first_delay_ms`, then again after the delay each tick returns, until stopped.
    pub fn spawn(name: &str, first_delay_ms: i64, tick: impl Fn() -> i64 + Send + 'static) -> Ticker {
        let stop = Arc::new((Mutex::new(false), Condvar::new()));
        let s = stop.clone();
        let handle = std::thread::Builder::new()
            .name(name.into())
            .spawn(move || {
                let mut delay = first_delay_ms;
                loop {
                    let (lock, cv) = &*s;
                    let guard = lock.lock().unwrap_or_else(|p| p.into_inner());
                    let (guard, _) = cv.wait_timeout_while(guard, Duration::from_millis(delay.max(1) as u64), |stopped| !*stopped).unwrap_or_else(|p| p.into_inner());
                    if *guard {
                        return;
                    }
                    drop(guard);
                    delay = std::panic::catch_unwind(std::panic::AssertUnwindSafe(&tick)).unwrap_or(IDLE_RECHECK_MS);
                }
            })
            .ok();
        Ticker { stop, handle }
    }

    pub fn stop(&mut self) {
        let (lock, cv) = &*self.stop;
        *lock.lock().unwrap_or_else(|p| p.into_inner()) = true;
        cv.notify_all();
        if let Some(h) = self.handle.take() {
            let _ = h.join();
        }
    }
}

impl Drop for Ticker {
    fn drop(&mut self) {
        self.stop();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn delay_doubles_per_failure_and_caps_at_an_hour() {
        assert_eq!(delay_for(5, 0), 300_000);
        assert_eq!(delay_for(5, 1), 600_000);
        assert_eq!(delay_for(5, 9), MAX_BACKOFF_MS);
        assert_eq!(delay_for(0, 0), 60_000);
    }

    #[test]
    fn a_failed_read_counts_and_a_retry_at_wins() {
        let fail = ProviderLimits { retry_at: Some(10 * 60_000), ..ProviderLimits::unknown("claude", 0, "rate_limited") };
        let r = Refresher::new(Box::new(move || vec![fail.clone()]), Box::new(|| 5), Box::new(|| {}), Arc::new(|| 0));
        assert_eq!(r.tick(), 10 * 60_000);
        assert_eq!(r.failure_count(), 1);
        let ok = Refresher::new(Box::new(|| vec![ProviderLimits::unknown("x", 0, "no_credentials")]), Box::new(|| 5), Box::new(|| {}), Arc::new(|| 0));
        assert_eq!(ok.tick(), 300_000);
        assert_eq!(ok.failure_count(), 0, "no credentials is not a transient failure");
    }
}
