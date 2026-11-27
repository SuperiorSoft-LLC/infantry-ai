// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Golden wire tests: each literal is what kotlinx.serialization (encodeDefaults = true) writes for the
//! DTO, derived from the Kotlin declarations (field order, NEVER-default omission). Round trip must be
//! byte-identical.
use infantry_protocol::agents::*;
use infantry_protocol::frames;
use infantry_protocol::prompts::*;
use infantry_protocol::session::*;
use infantry_protocol::usage::*;
use infantry_protocol::{Envelope, MessageOrigin};
use serde::{de::DeserializeOwned, Serialize};

fn golden<T: Serialize + DeserializeOwned>(json: &str) -> T {
    let v: T = serde_json::from_str(json).unwrap_or_else(|e| panic!("decode {json}: {e}"));
    assert_eq!(serde_json::to_string(&v).unwrap(), json);
    v
}

#[test]
fn envelope_writes_null_session_and_sequence_omits_request_id_and_origin() {
    let e: Envelope = golden(r#"{"type":"ready","data":"","sessionId":null,"sequence":null}"#);
    assert_eq!(e, Envelope::new("ready", ""));
    golden::<Envelope>(r#"{"type":"turn","data":"hi","sessionId":"s1","sequence":7,"requestId":"send:1","origin":{"type":"owner"}}"#);
}

#[test]
fn envelope_decodes_older_frames_without_optional_keys() {
    let e: Envelope = serde_json::from_str(r#"{"type":"kill"}"#).unwrap();
    assert_eq!(e.data, "");
    assert_eq!(e.session_id, None);
}

#[test]
fn message_origin_variants() {
    golden::<MessageOrigin>(r#"{"type":"owner"}"#);
    let a: MessageOrigin = golden(r#"{"type":"agent","sessionId":"abcdef123456","agentId":null,"role":"pm"}"#);
    assert_eq!(a.label(), "from pm (abcdef12)");
    golden::<MessageOrigin>(r#"{"type":"external","senderLabel":"mail","channel":null}"#);
    let s: MessageOrigin = golden(r#"{"type":"system","kind":"scheduler"}"#);
    assert_eq!(s.label(), "from system (scheduler)");
}

#[test]
fn session_record_golden() {
    let json = r#"{"id":"s1","provider":"claude","workingDir":"C:/w","spawnedAt":1,"lastHeartbeat":2,"state":"IDLE","providerSessionId":null,"childPid":null,"runCount":0,"resumeCount":0,"lastExitCode":null,"killOutcome":null,"lastError":null,"worktreePath":null,"worktreeBranch":null,"repoRoot":null,"worktreeBaseRef":null,"model":null,"effort":null,"agentId":null,"role":null,"ticketId":null,"contextSources":[],"projectId":null,"unattended":false,"report":null,"isProcessAlive":false,"dormant":false,"live":false,"turnActive":false}"#;
    let r: SessionRecord = golden(json);
    assert!(r.never_run());
}

#[test]
fn session_record_computed_fields_and_never_defaults() {
    let json = r#"{"id":"s2","provider":"claude","workingDir":"w","spawnedAt":1,"lastHeartbeat":2,"state":"BUSY","providerSessionId":"p","childPid":9,"runCount":1,"resumeCount":0,"lastExitCode":null,"killOutcome":null,"lastError":null,"worktreePath":null,"worktreeBranch":null,"repoRoot":null,"worktreeBaseRef":null,"model":"m","effort":null,"agentId":null,"role":"dev","ticketId":null,"contextSources":[{"source":"brief","chars":10}],"projectId":null,"unattended":true,"report":{"status":"pending","traitId":"t","reprompted":false,"checkedRun":0,"reportMessageId":null,"attentionId":null},"isProcessAlive":true,"dormant":false,"runner":"remote1","waiting":{"reason":"r","since":1,"untilAt":2,"expiresAt":3},"live":true,"turnActive":true}"#;
    golden::<SessionRecord>(json);
    // turnActive=false downgrades BUSY to IDLE on read.
    let stale = json.replace("\"turnActive\":true", "\"turnActive\":false");
    let r: SessionRecord = serde_json::from_str(&stale).unwrap();
    assert_eq!(r.state, SessionState::Idle);
}

#[test]
fn idle_session_with_background_work_stays_idle() {
    let json = r#"{"id":"s","provider":"p","workingDir":"w","spawnedAt":1,"lastHeartbeat":2,"state":"IDLE","backgroundWork":{"pid":5,"label":"x","startedAt":3,"count":1}}"#;
    let r: SessionRecord = serde_json::from_str(json).unwrap();
    let v: serde_json::Value = serde_json::to_value(&r).unwrap();
    assert_eq!(v["state"], "IDLE");
    assert_eq!(v["turnActive"], false);
    assert_eq!(v["backgroundWork"]["count"], 1);
}

#[test]
fn agent_dtos() {
    golden::<AgentProfile>(r#"{"id":null,"name":"QA","role":"qa","provider":null,"model":"opus","instructions":null,"projectId":null,"effort":null,"traitIds":["a"],"skillIds":null,"runner":null}"#);
    golden::<AgentSwap>(r#"{"agentId":"a1","provider":"claude","model":null,"effort":null,"session":"auto"}"#);
    let s: AgentSwap = serde_json::from_str(r#"{"agentId":"a","provider":"p"}"#).unwrap();
    assert_eq!(s.session, "auto");
    golden::<AgentSwapped>(r#"{"agent":{"id":"a1","name":"n","role":"r","provider":null,"model":null,"instructions":null,"projectId":null,"effort":null,"traitIds":null,"skillIds":null,"runner":null},"from":{"provider":"x","model":null,"effort":null},"session":{"outcome":"kept","sessionId":"s"}}"#);
    golden::<SessionSwap>(r#"{"sessionId":"s","provider":"p","model":null,"effort":null,"role":null}"#);
    golden::<SessionSwapped>(r#"{"sessionId":"s","provider":"p","model":null,"defaultModel":null,"from":{"provider":null,"model":null,"effort":null},"newConversation":true,"role":null,"fromRole":null}"#);
}

#[test]
fn usage_dtos() {
    golden::<RunUsage>(r#"{"runId":"r","provider":"opencode","model":"m","input":1,"output":2,"reasoning":3,"cacheRead":4,"cacheWrite":5,"costUsd":0.5}"#);
    golden::<UsageFrame>(r#"{"usage":{"runId":"r","provider":"p","model":"m","input":1,"output":2,"reasoning":0,"cacheRead":0,"cacheWrite":0,"costUsd":null},"type":"usage"}"#);
    golden::<ProviderState>(r#"{"provider":"claude","status":"exhausted","window":"5h","utilization":97.5,"resetsAt":10,"observedAt":5,"source":"live"}"#);
    golden::<ProviderStateFrame>(r#"{"state":{"provider":"c","status":"unknown","window":null,"utilization":null,"resetsAt":null,"observedAt":null,"source":"none"},"type":"provider_state"}"#);
    // From LimitsWireTest: the claude "unknown" entry after a 429, and the opencode-go ok entry.
    let l: ProviderLimits = golden(r#"{"provider":"claude","status":"unknown","windows":[],"reason":"rate_limited","observedAt":1800000000000,"stale":false,"retryAt":1800000300000}"#);
    assert_eq!(l.retry_at, Some(1_800_000_300_000));
    golden::<ProviderLimits>(r#"{"provider":"opencode-go","status":"ok","windows":[{"provider":"opencode-go","label":"5h limit","windowMinutes":300,"percent":55.0,"resetsAt":1800003600000,"observedAt":1800000000000,"source":"live","paceDelta":-25.0}],"reason":null,"observedAt":1800000000000,"stale":false,"retryAt":null}"#);
}

#[test]
fn pace_dtos() {
    golden::<PacingRule>(r#"{"enabled":true,"tiers":[{"below":60.0,"workers":3},{"below":80.0,"workers":2},{"below":null,"workers":1}],"mode":"pace","maxWorkers":5,"okProjected":100.0,"hardStopPercent":95.0}"#);
    assert_eq!(serde_json::from_str::<PacingRule>("{}").unwrap(), PacingRule::default());
    golden::<UsageOverride>(r#"{"workers":2,"until":99}"#);
    golden::<ManualUsage>(r#"{"percent":40.0,"resetsAt":null,"setAt":7}"#);
    golden::<UsageReport>(r#"{"meters":[{"meter":"claude","percent":12.5,"window":null,"resetsAt":null,"paceDelta":null,"source":"live","windows":[],"reason":null,"ruleAllowance":3,"override":null,"allowance":3,"running":1,"canStart":true,"warning":null,"updatedAt":null,"projectedPercent":null,"reasoning":null}],"rule":{"enabled":true,"tiers":[{"below":null,"workers":1}],"mode":"bands","maxWorkers":5,"okProjected":100.0,"hardStopPercent":95.0},"observedAt":1}"#);
    golden::<UsageSet>(r#"{"meter":"claude","manualPercent":50.0,"manualResetsAt":null,"clearManual":false,"overrideWorkers":null,"overrideUntil":null,"clearOverride":false,"rule":null}"#);
}

#[test]
fn prompt_and_question_dtos() {
    golden::<PromptRequest>(r#"{"promptId":"p1","sessionId":"s","kind":"tool_approval","createdAt":5,"tool":"Bash","input":"ls","question":null,"options":[],"multiSelect":false,"allowOther":false,"expiresAt":null,"onTimeout":null,"recommended":null}"#);
    golden::<PromptRequest>(r#"{"promptId":"p2","sessionId":"s","kind":"question","createdAt":5,"tool":null,"input":null,"question":"Which?","options":[{"label":"A","description":""},{"label":"B","description":"bee"}],"multiSelect":true,"allowOther":true,"expiresAt":9,"onTimeout":"deny","recommended":"A"}"#);
    golden::<PromptResponse>(r#"{"promptId":"p1","decision":"allow_once","answers":[],"otherText":null}"#);
    golden::<PromptResolved>(r#"{"promptId":"p1","resolvedBy":"timeout","outcome":"no_answer"}"#);
    golden::<PromptSave>(r#"{"name":"n","body":"b","tags":["x"]}"#);
    golden::<PromptView>(r#"{"id":"i","name":"n","body":"b","tags":[],"createdAt":1,"updatedAt":2}"#);
    golden::<ApprovalRule>(r#"{"ruleId":"r","projectId":"p","tool":"Bash","prefix":null,"createdAt":3}"#);
    golden::<ApprovalRulesList>(r#"{"projectId":null,"rules":[]}"#);
    golden::<ApprovalRuleDelete>(r#"{"ruleId":"r"}"#);
    golden::<PromptAsk>(r#"{"question":"q","options":[{"label":"a","description":""}],"multiSelect":false,"allowOther":false,"timeoutSec":30,"recommended":null}"#);
    golden::<PromptAnswer>(r#"{"answered":true,"answers":["a"],"otherText":null}"#);
    let ask: QuestionAsk = serde_json::from_str(r#"{"question":"q","options":[],"recommended":"a","reason":"r"}"#).unwrap();
    assert!(ask.allow_other);
    golden::<QuestionAsk>(r#"{"question":"q","options":[],"recommended":"a","reason":"r","allowOther":true,"defaultAfterMin":null}"#);
    golden::<QuestionAsked>(r#"{"questionId":"q1","note":"non-blocking: carry on with other work; the owner's answer arrives as mail in thread question:<id>"}"#);
    golden::<OwnerQuestion>(r#"{"id":"q","projectId":null,"sessionId":"s","agentId":null,"role":"dev","question":"?","options":[{"label":"A","description":""}],"recommended":"A","reason":"r","allowOther":false,"defaultAfterMin":10,"state":"open","answer":null,"otherText":null,"resolvedBy":null,"createdAt":1,"resolvedAt":null,"overridden":false}"#);
    golden::<QuestionAnswer>(r#"{"questionId":"q","answer":"A","otherText":null}"#);
    golden::<QuestionCancel>(r#"{"questionId":"q"}"#);
    golden::<ToolApprovalInfo>(r#"{"mode":null,"effective":"ask","source":"default","choices":["ask","auto"]}"#);
    golden::<ToolApprovalSet>(r#"{"mode":"auto"}"#);
}

#[test]
fn progress_omits_never_defaults() {
    golden::<SessionProgress>(r#"{"mode":"percent","state":"running","percent":40.0,"startedAt":1,"updatedAt":2}"#);
}

#[test]
fn frame_table() {
    let all = frames::ALL;
    assert_eq!(all.len(), 310, "310 frame names");
    let mut sorted = all.to_vec();
    sorted.sort();
    sorted.dedup();
    assert_eq!(sorted.len(), all.len(), "no duplicate frame names");
    assert_eq!(frames::READY, "ready");
    assert_eq!(frames::RUN_BYTES, "run_bytes");
    assert_eq!(frames::CLIENT_SEND_PREFIX, "send:");
    assert_eq!(frames::REQUEST_ID_CONTRACT, "1.87.0");
    for c in frames::CORRELATED_REQUESTS {
        assert!(all.contains(c));
    }
    assert_eq!(frames::CORRELATED_REQUESTS.len(), 12);
}

/// Every frame named in CONTRACT.md (this crate's wire contract text) has a constant, and every constant is documented there
/// (the Kotlin `ContractFrameDocTest` has the same short allow-list of undocumented frames).
#[test]
fn contract_md_and_constants_agree() {
    let doc = include_str!("../CONTRACT.md");
    const UNDOCUMENTED: [&str; 5] = ["session_mcp_reconnect", "mcp_declare", "waiting", "health", "secret_login"];
    for f in frames::ALL {
        if UNDOCUMENTED.contains(f) {
            continue;
        }
        assert!(doc.contains(&format!("`{f}`")), "frame `{f}` is not in CONTRACT.md");
    }
}
