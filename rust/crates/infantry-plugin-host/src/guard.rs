// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The guard around every call into one plugin (the behaviour of the Kotlin `PluginGuard`, rebuilt for WASM and
//! sidecars):
//!
//! - a **runaway** (the call used its CPU budget: fuel or the wall deadline ran out) fails that call and is a strike;
//!   [`GuardLimits::strikes`] runaways in a row DISABLE the plugin;
//! - a **trap** (panic, unreachable, bad memory access, a sidecar that died or said `trap`) disables it at once;
//! - a **hang** (no answer by the hang ceiling, e.g. a silent sidecar) is a breaker strike; [`GuardLimits::strikes`]
//!   in a row OPEN the breaker: calls are refused for [`GuardLimits::cooldown_ms`], then one cheap health probe runs
//!   (half-open) and its answer closes the breaker, its failure re-opens it;
//! - the plugin's own `err` result is the caller's business: no strike, the plugin stays loaded;
//! - more than [`GuardLimits::queue_limit`] calls waiting is refused at once as "busy" (no strike);
//! - [`Guard::retire`] (unload, hot swap) refuses new calls and waits for the running ones to drain.
//!
//! Whatever the plugin does, the host keeps running: a fault only ever changes this guard's state.
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant};

/// How a runtime call failed (the plugin's own `err` is not a fault).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Fault {
    /// The call exhausted its CPU budget (fuel or the epoch deadline).
    Runaway(String),
    /// The plugin crashed: a trap, a panic, a dead or lying sidecar.
    Trap(String),
    /// No answer by the hang ceiling (the runtime could not interrupt it).
    Hang(String),
    /// The call was cut off on purpose (the plugin is being unloaded); never a strike.
    Cancelled(String),
}

impl std::fmt::Display for Fault {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Fault::Runaway(s) | Fault::Trap(s) | Fault::Hang(s) | Fault::Cancelled(s) => f.write_str(s),
        }
    }
}

/// Why a guarded call has no value.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CallError {
    /// The plugin answered with its own error (`result::err`).
    Plugin(String),
    /// The host refused the call without running it (disabled, cooling down, busy, unloaded, undeclared).
    Refused(String),
    /// The call ran and faulted; the guard has recorded it.
    Failed(String),
}

impl CallError {
    pub fn message(&self) -> &str {
        match self {
            CallError::Plugin(s) | CallError::Refused(s) | CallError::Failed(s) => s,
        }
    }
}

impl std::fmt::Display for CallError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.message())
    }
}

#[derive(Debug, Clone)]
pub struct GuardLimits {
    /// Consecutive runaways before the plugin is disabled, and consecutive hangs before the breaker opens.
    pub strikes: u32,
    /// How long the breaker stays open before a health probe.
    pub cooldown_ms: u64,
    /// Calls allowed in flight (running + waiting); one more is refused as busy.
    pub queue_limit: usize,
}

#[derive(Debug, Default)]
struct State {
    disabled: Option<String>,
    runaways: u32,
    hangs: u32,
    tripped: bool,
    open_until: Option<Instant>,
    open_strike: u32,
}

pub struct Guard {
    pub module_id: String,
    limits: GuardLimits,
    state: Mutex<State>,
    inflight: AtomicUsize,
    retired: AtomicBool,
    probing: AtomicBool,
    log: Box<dyn Fn(&str) + Send + Sync>,
    on_disabled: Box<dyn Fn(&str) + Send + Sync>,
}

impl Guard {
    pub fn new(module_id: &str, limits: GuardLimits, log: impl Fn(&str) + Send + Sync + 'static, on_disabled: impl Fn(&str) + Send + Sync + 'static) -> Self {
        Guard {
            module_id: module_id.into(),
            limits,
            state: Mutex::default(),
            inflight: AtomicUsize::new(0),
            retired: AtomicBool::new(false),
            probing: AtomicBool::new(false),
            log: Box::new(log),
            on_disabled: Box::new(on_disabled),
        }
    }

    pub fn disabled_reason(&self) -> Option<String> {
        self.state.lock().unwrap().disabled.clone()
    }
    pub fn is_disabled(&self) -> bool {
        self.disabled_reason().is_some()
    }
    pub fn is_cooling(&self) -> bool {
        self.state.lock().unwrap().tripped
    }
    pub fn is_retired(&self) -> bool {
        self.retired.load(Ordering::SeqCst)
    }
    pub fn inflight(&self) -> usize {
        self.inflight.load(Ordering::SeqCst)
    }

    /// Runs `call` under the guard. `probe` is the cheap health call the half-open breaker runs first.
    pub fn run<T>(&self, what: &str, probe: &dyn Fn() -> Result<(), Fault>, call: impl FnOnce() -> Result<Result<T, String>, Fault>) -> Result<T, CallError> {
        let id = &self.module_id;
        if let Some(why) = self.disabled_reason() {
            return Err(CallError::Refused(format!("plugin {id} is disabled: {why}")));
        }
        if self.is_retired() {
            return Err(CallError::Refused(format!("plugin {id} is unloaded; {what} refused")));
        }
        let open = {
            let s = self.state.lock().unwrap();
            if s.tripped { Some((s.open_until, s.open_strike)) } else { None }
        };
        if let Some((until, strike)) = open {
            if let Some(left) = until.and_then(|u| u.checked_duration_since(Instant::now())) {
                let secs = left.as_millis().div_ceil(1000);
                return Err(CallError::Refused(format!("plugin {id} is cooling down (strike {strike}), retry in {secs}s; {what} refused")));
            }
            if self.probing.swap(true, Ordering::SeqCst) {
                return Err(CallError::Refused(format!("plugin {id} is cooling down (probe in flight), retry in 1s; {what} refused")));
            }
            (self.log)(&format!("[plugins] daemon plugin {id} breaker open -> half-open: health probe"));
            let health = probe();
            self.probing.store(false, Ordering::SeqCst);
            match health {
                Ok(()) => self.recover("health probe"),
                Err(Fault::Trap(why)) => {
                    self.disable(&format!("health probe trapped: {why}"));
                    return Err(CallError::Refused(format!("plugin {id} is disabled: health probe trapped: {why}")));
                }
                Err(f) => {
                    self.reopen(&format!("health probe failed: {f}"));
                    return Err(CallError::Refused(format!("plugin {id} is cooling down, health probe failed ({f}); {what} refused")));
                }
            }
        }
        if self.inflight.fetch_add(1, Ordering::SeqCst) >= self.limits.queue_limit {
            self.inflight.fetch_sub(1, Ordering::SeqCst);
            return Err(CallError::Refused(format!("plugin {id} is busy ({} calls in flight); {what} refused", self.limits.queue_limit)));
        }
        let out = call();
        self.inflight.fetch_sub(1, Ordering::SeqCst);
        match out {
            Ok(r) => {
                {
                    let mut s = self.state.lock().unwrap();
                    s.runaways = 0;
                    s.hangs = 0;
                }
                r.map_err(CallError::Plugin)
            }
            Err(Fault::Cancelled(why)) => Err(CallError::Failed(format!("plugin {id}: {what} was cut off: {why}"))),
            Err(_) if self.is_retired() => Err(CallError::Failed(format!("plugin {id} is unloaded; {what} did not finish"))),
            Err(Fault::Trap(why)) => {
                self.disable(&format!("{what} trapped: {why}"));
                Err(CallError::Failed(format!("plugin {id}: {what} trapped: {why}")))
            }
            Err(Fault::Runaway(why)) => {
                let n = {
                    let mut s = self.state.lock().unwrap();
                    s.runaways += 1;
                    s.runaways
                };
                let max = self.limits.strikes.max(1);
                if n >= max {
                    self.disable(&format!("{what} ran away {n} times in a row ({why})"));
                } else {
                    (self.log)(&format!("[plugins] daemon plugin {id}: {what} ran away: {why} (strike {n} of {max}; plugin stays loaded)"));
                }
                Err(CallError::Failed(format!("plugin {id}: {what} was stopped: {why}")))
            }
            Err(Fault::Hang(why)) => {
                let (n, tripped) = {
                    let mut s = self.state.lock().unwrap();
                    s.hangs += 1;
                    (s.hangs, s.tripped)
                };
                let max = self.limits.strikes.max(1);
                if tripped {
                    (self.log)(&format!("[plugins] daemon plugin {id}: {what} hung while the breaker is open (window not extended)"));
                } else if n >= max {
                    self.trip(&format!("{what} hung ({n} in a row): {why}"));
                } else {
                    (self.log)(&format!("[plugins] daemon plugin {id}: {what} hung: {why} (strike {n} of {max}; plugin stays loaded)"));
                }
                Err(CallError::Failed(format!("plugin {id}: {what} hung: {why}")))
            }
        }
    }

    fn trip(&self, reason: &str) {
        let mut s = self.state.lock().unwrap();
        if s.tripped {
            return;
        }
        s.tripped = true;
        s.open_strike = 1;
        s.open_until = Some(Instant::now() + Duration::from_millis(self.limits.cooldown_ms));
        drop(s);
        (self.log)(&format!("[plugins] daemon plugin {} breaker closed -> open (strike 1, cooling down {}s, then a health probe): {reason}", self.module_id, self.limits.cooldown_ms / 1000));
    }

    fn reopen(&self, reason: &str) {
        let mut s = self.state.lock().unwrap();
        if !s.tripped {
            return;
        }
        s.open_strike += 1;
        s.open_until = Some(Instant::now() + Duration::from_millis(self.limits.cooldown_ms));
        let strike = s.open_strike;
        drop(s);
        (self.log)(&format!("[plugins] daemon plugin {} breaker half-open -> open (strike {strike}): {reason}", self.module_id));
    }

    fn recover(&self, what: &str) {
        let mut s = self.state.lock().unwrap();
        if !s.tripped {
            return;
        }
        s.tripped = false;
        s.open_until = None;
        s.hangs = 0;
        drop(s);
        (self.log)(&format!("[plugins] daemon plugin {} breaker half-open -> closed: {what} answered", self.module_id));
    }

    /// Disables the plugin for good (until it is loaded again). Told once.
    pub fn disable(&self, reason: &str) {
        if self.is_retired() {
            return;
        }
        let mut s = self.state.lock().unwrap();
        if s.disabled.is_some() {
            return;
        }
        s.disabled = Some(reason.into());
        drop(s);
        (self.log)(&format!("[plugins] daemon plugin {} DISABLED: {reason}", self.module_id));
        (self.on_disabled)(reason);
    }

    /// Planned unload: new calls are refused; waits up to `drain_ms` for the running ones. True = drained.
    pub fn retire(&self, drain_ms: u64) -> bool {
        self.retired.store(true, Ordering::SeqCst);
        let until = Instant::now() + Duration::from_millis(drain_ms);
        while self.inflight() > 0 {
            if Instant::now() >= until {
                (self.log)(&format!("[plugins] daemon plugin {}: a call was still running {drain_ms} ms after unload began; cutting it off", self.module_id));
                return false;
            }
            std::thread::sleep(Duration::from_millis(2));
        }
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn guard(cooldown_ms: u64) -> Guard {
        Guard::new("Acme_X", GuardLimits { strikes: 3, cooldown_ms, queue_limit: 4 }, |_| {}, |_| {})
    }
    fn ok() -> Result<(), Fault> {
        Ok(())
    }

    #[test]
    fn three_runaways_in_a_row_disable_a_success_resets() {
        let g = guard(0);
        let run = |g: &Guard| g.run::<()>("call-tool(x)", &ok, || Err(Fault::Runaway("fuel".into())));
        run(&g).unwrap_err();
        run(&g).unwrap_err();
        g.run("call-tool(x)", &ok, || Ok(Ok(()))).unwrap();
        run(&g).unwrap_err();
        run(&g).unwrap_err();
        assert!(!g.is_disabled());
        run(&g).unwrap_err();
        assert!(g.is_disabled());
        let e = g.run("call-tool(x)", &ok, || Ok(Ok(()))).unwrap_err();
        assert!(matches!(e, CallError::Refused(ref m) if m.contains("disabled")), "{e:?}");
    }

    #[test]
    fn a_trap_disables_at_once_a_plugin_error_never_does() {
        let g = guard(0);
        assert_eq!(g.run::<()>("t", &ok, || Ok(Err("bad args".into()))), Err(CallError::Plugin("bad args".into())));
        assert!(!g.is_disabled());
        g.run::<()>("t", &ok, || Err(Fault::Trap("unreachable".into()))).unwrap_err();
        assert!(g.is_disabled());
    }

    #[test]
    fn hangs_open_the_breaker_then_a_probe_closes_it() {
        let g = guard(30);
        for _ in 0..3 {
            g.run::<()>("t", &ok, || Err(Fault::Hang("silent".into()))).unwrap_err();
        }
        assert!(g.is_cooling() && !g.is_disabled());
        let e = g.run("t", &ok, || Ok(Ok(()))).unwrap_err();
        assert!(e.message().contains("cooling down"), "{e}");
        std::thread::sleep(Duration::from_millis(40));
        let bad = || Err(Fault::Hang("still silent".into()));
        assert!(g.run("t", &bad, || Ok(Ok(()))).unwrap_err().message().contains("health probe failed"));
        std::thread::sleep(Duration::from_millis(40));
        g.run("t", &ok, || Ok(Ok(()))).unwrap();
        assert!(!g.is_cooling());
    }

    #[test]
    fn retire_refuses_new_calls() {
        let g = guard(0);
        assert!(g.retire(10));
        assert!(g.run("t", &ok, || Ok(Ok(()))).unwrap_err().message().contains("unloaded"));
    }
}
