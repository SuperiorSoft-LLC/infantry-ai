# The AgentHQ evidence bar

What a worker must produce before a ticket can be called done, and what the PM checks before
he or she merges anything.

This file exists because workers repeatedly shipped work that was **green but proved nothing**, and
because the rule used to live only in per-agent briefings that some workers never received. It is
reachable with `read`, which every worker has.

**A green suite is not evidence. It is the absence of disproof.**

---

## 1. RED before the implementation

The test must fail *for the reason the ticket describes*, before the production code exists.

- A test that fails to compile is **not** a RED. It proves the symbol is missing, nothing more.
- The best RED is an empty stub that **compiles** and fails on a missing assertion:

```kotlin
fun RosterSection(state: AppState) {
    // RED: nothing rendered yet; the roster rows arrive with the fix.
}
```

- If you wrote the test and the implementation in the same sitting, say so plainly. Do not
  manufacture a RED commit after the fact. An invented RED is worse than no RED, and it is
  immediately visible in the commit order.

## 2. The mutation check — the one that actually matters

Green tests prove nothing until you break the code and watch them go red.

Pick the specific line your ticket is about, change it so the code is wrong, run the suite, and
confirm **RED at a named `file:line`**. Then restore it and confirm `git diff` is empty.

Three mutations that have caught real bugs on this board:

| Mutation | Why it matters |
|---|---|
| Make the `--project` filter ignore its argument | The filter was never load-bearing |
| Match the *first* plausible frame instead of the correct answer frame | The actual bug most of these tickets exist to prevent |
| Neuter your own new assertion and re-run | Proves the assertion is load-bearing, not dead code |

**A test that cannot fail is worse than no test.** See §3 for the specific shape of this failure.

## 3. No test may pass vacuously

The recurring defect on this board: a test that sets up a condition, then asserts only the
*outcome* — so when the condition never occurs, the test still passes.

```kotlin
// VACUOUS. If the publisher thread delivered nothing, this passes identically.
val hammer = Thread { while (!interrupted) { publish(); Thread.sleep(15) } }
hammer.start()
val out = cli("epics", "--project", p)
assertEquals(expected, out)   // passes even if ZERO pushes landed
```

The fix is to **prove the condition occurred**, inside the window the test is about:

```kotlin
val before = client.of(Envelope.EPIC).size
// ... run the thing under test ...
val during = client.of(Envelope.EPIC).size   // sampled BEFORE stopping the producer
assertTrue(during > before, "no push landed in the window, so this proved nothing")
```

Sample the counter **before** the `finally` that stops the producer, or a push that arrived too
late can satisfy the assertion and prove nothing about the window.

Complementary coverage is good and is not a substitute: a deterministic twin (buffered frame, no
thread) plus an end-to-end version (real binary, real socket) covers two different failure modes.
Keep both, and say in a comment why each exists.

## 4. Durability — commit early, commit often

Uncommitted work is **one disk**. A restart, a failed `git worktree remove`, or a re-cut destroys
it permanently. This has already destroyed `DaemonMain.kt` and `Roster.kt` on this board.

- `git add <explicit path>` — **never** `git add -A`.
- WIP commit mid-thought is the correct action, not a compromise.
- Commit *before* you run the long test, not after.
- Ignore tool-local directories such as `.claude/` and `.pi/` when assessing what is uncommitted.

## 5. Report honestly, and to the PM

- Report to the **PM**, not the owner. Assume a report to the owner is lost.
- **Claimed test counts are claims until reproduced.** "GREEN 693/0/0/0 x5" when one run happened is
  the exact failure this board keeps hitting. Quote real totals from the JUnit XML.
- If you did not run it, say you did not run it.
- If you are stuck, say you are stuck on pass 1, not pass 4.

## 6. The base gate

Before writing anything:

```
git merge-base --is-ancestor <current-mvp-sha> <branch>
```

A fresh worktree is **not** evidence of a good base — a stale branch cuts cleanly into a brand-new
worktree and the runner still reports `reused: false`. Read `cwd` in the runner's response and
compare it to your worktree path; `useWorktree: false` makes the runner ignore your branch
entirely and launch you in the main checkout.

## 7. Two traps that look symmetrical and are not

Read these before assuming; they have both shipped silently.

- `epic_list` with an **empty** payload returns `[]` (exact project match).
- `story_list` with an **empty** payload returns **every** project.

One client cannot infer the other's behaviour. Pass an explicit project id.

And an `epic` frame can arrive **unsolicited** on the same socket after a `story_save` carrying an
`epicId`. Match your reply on the `epics` frame type specifically.

---

## 8. Never park on a question

Ending your turn with a question marks the session **needs input** and it stops. Nobody may be watching, so a
parked session is a ticket that will not finish tonight.

When you hit an ambiguity: **choose the most reasonable option, do the work, and log the decision** as an
internal comment on the ticket or a project note, so the owner can review it later. Do not stop to ask.

Stop only when the task is genuinely blocked with no safe default — and then say exactly what you tried, what
each option would cost, and which you would pick if pushed.

The same applies to your reports. A report that ends in "shall I...?" or "which do you want?" has ended in the
wrong place. Report what you found, what you did, and what you assumed — then keep going.

An unblocking decision you made and logged is never held against you. A parked ticket always is.

---

## Quick checklist before you say DONE

> Extended, tickable version plus a PM brief template: [`EVIDENCE-BAR-CHECKLIST.md`](EVIDENCE-BAR-CHECKLIST.md).
> This file remains the authority.

- [ ] RED existed first, or I have said plainly that it did not
- [ ] Mutation: broke the relevant line, got RED at a named `file:line`, restored, `git diff` empty
- [ ] No assertion is vacuous — the condition it depends on is proven to have occurred
- [ ] Nothing uncommitted; committed by explicit path
- [ ] No question left parked at the end of my turn
- [ ] Base gate passed; `cwd` was the worktree, not the main checkout
- [ ] Suite run with `--no-build-cache`, real totals quoted from the XML
- [ ] Report sent to the PM, not the owner

**A PM will hold a merge over a missing mutation even when the code is obviously right.** That is
the job. If you are certain your work is correct, say so in the report and name the specific
mutation you believe would fail — that conversation is much faster than a stalled ticket.