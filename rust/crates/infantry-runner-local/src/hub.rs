// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! One process's event fan-out: buffered until the first subscriber (nothing is lost between spawn and `events`), then sent
//! to every live subscriber; [`ProcEvent::Exited`] is the last event and closes every channel.

use infantry_runner_api::ProcEvent;
use std::sync::Mutex;
use tokio::sync::mpsc;

pub struct Hub {
    st: Mutex<State>,
    backlog_max: usize,
}

struct State {
    subs: Vec<mpsc::UnboundedSender<ProcEvent>>,
    /// `Some` until the first subscriber.
    backlog: Option<Vec<ProcEvent>>,
    backlog_bytes: usize,
    exit: Option<i32>,
}

fn weight(e: &ProcEvent) -> usize {
    match e {
        ProcEvent::Output(b) | ProcEvent::Stderr(b) => b.len(),
        _ => 0,
    }
}

impl Hub {
    pub fn new(backlog_max: usize) -> Self {
        Hub { st: Mutex::new(State { subs: Vec::new(), backlog: Some(Vec::new()), backlog_bytes: 0, exit: None }), backlog_max }
    }

    pub fn exit(&self) -> Option<i32> {
        self.st.lock().unwrap().exit
    }

    /// Output after the exit (a grandchild still holding the pipe) is dropped: Exited is always last.
    pub fn publish(&self, ev: ProcEvent) {
        let mut st = self.st.lock().unwrap();
        if st.exit.is_some() {
            return;
        }
        let w = weight(&ev);
        let max = self.backlog_max;
        let State { backlog, backlog_bytes, subs, .. } = &mut *st;
        match backlog {
            Some(b) => {
                b.push(ev);
                *backlog_bytes += w;
                // Over the cap: the oldest output goes (a terminal's newest state matters most).
                while *backlog_bytes > max && b.len() > 1 {
                    *backlog_bytes -= weight(&b.remove(0));
                }
            }
            None => subs.retain(|s| s.send(ev.clone()).is_ok()),
        }
    }

    pub fn finish(&self, code: i32) {
        let mut st = self.st.lock().unwrap();
        if st.exit.is_some() {
            return;
        }
        st.exit = Some(code);
        if let Some(b) = st.backlog.as_mut() {
            b.push(ProcEvent::Exited(code));
        }
        for s in st.subs.drain(..) {
            let _ = s.send(ProcEvent::Exited(code));
        }
    }

    pub fn subscribe(&self) -> mpsc::UnboundedReceiver<ProcEvent> {
        let (tx, rx) = mpsc::unbounded_channel();
        let mut st = self.st.lock().unwrap();
        let mut sent_exit = false;
        if let Some(b) = st.backlog.take() {
            st.backlog_bytes = 0;
            for e in b {
                sent_exit |= matches!(e, ProcEvent::Exited(_));
                let _ = tx.send(e);
            }
        }
        match st.exit {
            Some(code) => {
                if !sent_exit {
                    let _ = tx.send(ProcEvent::Exited(code));
                }
            }
            None => st.subs.push(tx),
        }
        rx
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn backlog_then_live_then_exit_closes() {
        let h = Hub::new(1024);
        h.publish(ProcEvent::Output(b"a".to_vec()));
        let mut first = h.subscribe();
        h.publish(ProcEvent::Output(b"b".to_vec()));
        let mut second = h.subscribe();
        h.finish(3);
        h.publish(ProcEvent::Output(b"late".to_vec()));
        let mut late = h.subscribe();
        assert_eq!(first.try_recv().unwrap(), ProcEvent::Output(b"a".to_vec()));
        assert_eq!(first.try_recv().unwrap(), ProcEvent::Output(b"b".to_vec()));
        assert_eq!(first.try_recv().unwrap(), ProcEvent::Exited(3));
        assert!(first.try_recv().is_err());
        // A later subscriber gets events from its subscribe on: only the exit here.
        assert_eq!(second.try_recv().unwrap(), ProcEvent::Exited(3));
        assert_eq!(late.try_recv().unwrap(), ProcEvent::Exited(3));
    }

    #[test]
    fn exit_before_first_subscriber_is_delivered_once() {
        let h = Hub::new(1024);
        h.publish(ProcEvent::Output(b"x".to_vec()));
        h.finish(0);
        let mut r = h.subscribe();
        assert_eq!(r.try_recv().unwrap(), ProcEvent::Output(b"x".to_vec()));
        assert_eq!(r.try_recv().unwrap(), ProcEvent::Exited(0));
        assert!(r.try_recv().is_err());
    }
}
