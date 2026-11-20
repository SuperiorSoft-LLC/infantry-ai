// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Frame names (`Envelope.type` values), ported from the `Envelope` companion in `Protocol.kt`.
//! Generated once from the Kotlin source; Kotlin is frozen, so this list is the checklist.

pub const REQUEST_ID_CONTRACT: &str = "1.87.0";
pub const RUN_IN_FLIGHT: &str = "run_in_flight";
pub const READY: &str = "ready";
pub const PROMPT: &str = "prompt";
pub const STDOUT: &str = "stdout";
pub const STDERR: &str = "stderr";
pub const EXIT: &str = "exit";
pub const ERROR: &str = "error";
pub const SESSION: &str = "session";
pub const SESSIONS: &str = "sessions";
pub const KILL: &str = "kill";
pub const ASSISTANT: &str = "assistant";
pub const INTERRUPT: &str = "interrupt";
pub const QUEUE_ADD: &str = "queue_add";
pub const QUEUE_REMOVE: &str = "queue_remove";
pub const QUEUE_FORCE: &str = "queue_force";
pub const PROMPT_ACK: &str = "prompt_ack";
pub const CLIENT_SEND_PREFIX: &str = "send:";
pub const COMMAND_LIST: &str = "command_list";
pub const COMMANDS: &str = "commands";
pub const COMMAND_SAVE: &str = "command_save";
pub const COMMAND_DELETE: &str = "command_delete";
pub const RUN_START: &str = "run_start";
pub const RUN: &str = "run";
pub const RUN_OUTPUT: &str = "run_output";
pub const RUN_FINISHED: &str = "run_finished";
pub const RUN_STOP: &str = "run_stop";
pub const RUN_BYTES: &str = "run_bytes";
pub const RUN_LIST: &str = "run_list";
pub const RUNS: &str = "runs";
pub const RUN_ATTACH: &str = "run_attach";
pub const RUN_RESIZE: &str = "run_resize";
pub const RUN_INPUT: &str = "run_input";
pub const RESUME: &str = "resume";
pub const NEW: &str = "new";
pub const SESSION_DELETE: &str = "session_delete";
pub const SESSION_PROJECT: &str = "session_project";
pub const SESSION_RESTART: &str = "session_restart";
pub const SESSION_MCP_RECONNECT: &str = "session_mcp_reconnect";
pub const MCP_LIST: &str = "mcp_list";
pub const MCP_GET: &str = "mcp_get";
pub const MCP_ADD: &str = "mcp_add";
pub const MCP_UPDATE: &str = "mcp_update";
pub const MCP_REMOVE: &str = "mcp_remove";
pub const MCP_SET_ENABLED: &str = "mcp_set_enabled";
pub const MCP_TEST: &str = "mcp_test";
pub const MCP_ASSIGN: &str = "mcp_assign";
pub const MCP_DECLARE: &str = "mcp_declare";
pub const MCP_REVOKE: &str = "mcp_revoke";
pub const MCP_GRANTS: &str = "mcp_grants";
pub const MCP_PLAN: &str = "mcp_plan";
pub const MCP_SERVERS: &str = "mcp_servers";
pub const MCP_SERVER: &str = "mcp_server";
pub const MCP_STATUS: &str = "mcp_status";
pub const MCP_CHANGED: &str = "mcp_changed";
pub const WORKTREE: &str = "worktree";
pub const WORKTREES: &str = "worktrees";
pub const WORKTREE_REMOVE: &str = "worktree_remove";
pub const REPO: &str = "repo";
pub const MAIL_SEND: &str = "mail_send";
pub const MAIL_READ: &str = "mail_read";
pub const MAIL_INBOX: &str = "mail_inbox";
pub const MAIL_OUTBOX: &str = "mail_outbox";
pub const MAIL_THREAD: &str = "mail_thread";
pub const MAIL: &str = "mail";
pub const MAILS: &str = "mails";
pub const MAIL_THREADS: &str = "mail_threads";
pub const MAIL_WAITING: &str = "mail_waiting";
pub const BG_JOB: &str = "bg_job";
pub const PROGRESS: &str = "progress";
pub const WAITING: &str = "waiting";
pub const PROCESSES: &str = "processes";
pub const PROCESSES_REAP: &str = "processes_reap";
pub const HEALTH: &str = "health";
pub const PLUGIN_ADMIN: &str = "plugin_admin";
/// WEBUI-1: pushed to every client after a plugin is enabled or disabled, `{"id", "enabled"}`: re-read the plugin list.
pub const PLUGINS_CHANGED: &str = "plugins_changed";
pub const PROCESS_PRESSURE: &str = "process_pressure";
pub const MAIL_REFUSED: &str = "mail_refused";
pub const TEXT_DELTA: &str = "text_delta";
pub const STATUS: &str = "status";
pub const FINISHED: &str = "finished";
pub const REASONING: &str = "reasoning";
pub const TURN: &str = "turn";
pub const ROLE_LIST: &str = "role_list";
pub const ROLE_SAVE: &str = "role_save";
pub const ROLE: &str = "role";
pub const ROLES: &str = "roles";
pub const ROLE_DELETE: &str = "role_delete";
pub const ROLE_REMOVED: &str = "role_removed";
pub const ROLE_BINDING_LIST: &str = "role_binding_list";
pub const ROLE_BINDING_GET: &str = "role_binding_get";
pub const ROLE_BINDING_SET: &str = "role_binding_set";
pub const ROLE_BINDING: &str = "role_binding";
pub const ROLE_BINDINGS: &str = "role_bindings";
pub const PROMPT_SAVE: &str = "prompt_save";
pub const PROMPT_LIST: &str = "prompt_list";
pub const PROMPT_GET: &str = "prompt_get";
pub const PROMPT_DELETE: &str = "prompt_delete";
pub const PROMPT_ITEM: &str = "prompt_item";
pub const PROMPTS: &str = "prompts";
pub const SKILL_SAVE: &str = "skill_save";
pub const SKILL_LIST: &str = "skill_list";
pub const SKILL_GET: &str = "skill_get";
pub const SKILL_DELETE: &str = "skill_delete";
pub const SKILL_ITEM: &str = "skill_item";
pub const SKILLS: &str = "skills";
pub const LAYER_SKILL_SAVE: &str = "layer_skill_save";
pub const LAYER_SKILL_LIST: &str = "layer_skill_list";
pub const LAYER_SKILL_DELETE: &str = "layer_skill_delete";
pub const LAYER_SKILLS: &str = "layer_skills";
pub const TRAIT_SAVE: &str = "trait_save";
pub const TRAIT_LIST: &str = "trait_list";
pub const TRAIT_GET: &str = "trait_get";
pub const TRAIT_DELETE: &str = "trait_delete";
pub const TRAIT_ITEM: &str = "trait_item";
pub const TRAITS: &str = "traits";
pub const ROLE_TRAITS_SET: &str = "role_traits_set";
pub const ROLE_TRAITS_GET: &str = "role_traits_get";
pub const ROLE_TRAITS_LIST: &str = "role_traits_list";
pub const ROLE_TRAITS: &str = "role_traits";
pub const ROLE_TRAITS_ALL: &str = "role_traits_all";
pub const USAGE: &str = "usage";
pub const PROVIDER_STATE: &str = "provider_state";
pub const PROVIDER_STATES: &str = "provider_states";
pub const LIMITS: &str = "limits";
pub const USAGE_GET: &str = "usage_get";
pub const USAGE_REPORT: &str = "usage_report";
pub const USAGE_SET: &str = "usage_set";
pub const TICKET_FILES: &str = "ticket_files";
pub const TICKET_FILES_REPORT: &str = "ticket_files_report";
pub const ARCHIVE_QUERY: &str = "archive_query";
pub const ARCHIVE_APPLY: &str = "archive_apply";
pub const ARCHIVE_REPORT: &str = "archive_report";
pub const TRIGGER_SAVE: &str = "trigger_save";
pub const TRIGGER_LIST: &str = "trigger_list";
pub const TRIGGER_FIRE: &str = "trigger_fire";
pub const TRIGGER_ARM: &str = "trigger_arm";
pub const TRIGGER_DISABLE: &str = "trigger_disable";
pub const TRIGGER_DELETE: &str = "trigger_delete";
pub const TRIGGER_EVENTS: &str = "trigger_events";
pub const TRIGGER: &str = "trigger";
pub const TRIGGERS: &str = "triggers";
pub const POD_SUBMIT: &str = "pod_submit";
pub const POD_QUERY: &str = "pod_query";
pub const POD_CANCEL: &str = "pod_cancel";
pub const POD: &str = "pod";
pub const PROVIDERS: &str = "providers";
pub const MODELS: &str = "models";
pub const COMMAND_CATALOG: &str = "command_catalog";
pub const COMMAND_RESULT: &str = "command_result";
pub const AGENT_SAVE: &str = "agent_save";
pub const AGENT_LIST: &str = "agent_list";
pub const AGENT_DELETE: &str = "agent_delete";
pub const AGENT: &str = "agent";
pub const AGENTS: &str = "agents";
pub const AGENT_SWAP: &str = "agent_swap";
pub const AGENT_SWAPPED: &str = "agent_swapped";
pub const SESSION_SWAP: &str = "session_swap";
pub const SESSION_SWAPPED: &str = "session_swapped";
pub const AGENT_ROSTER: &str = "agent_roster";
pub const ROSTER: &str = "roster";
pub const SESSION_STATS_GET: &str = "session_stats_get";
pub const SESSION_STATS: &str = "session_stats";
pub const USAGE_METER_GET: &str = "usage_meter_get";
pub const USAGE_METER: &str = "usage_meter";
pub const TICKET_SAVE: &str = "ticket_save";
pub const TICKET_GET: &str = "ticket_get";
pub const TICKET_LIST: &str = "ticket_list";
pub const TICKET_MOVE: &str = "ticket_move";
pub const TICKET_UNASSIGN: &str = "ticket_unassign";
pub const AGENT_QUEUE: &str = "agent_queue";
pub const TICKET_ASSIGN: &str = "ticket_assign";
pub const AGENT_SWITCH: &str = "agent_switch";
pub const TICKET_LINK: &str = "ticket_link";
pub const TICKET_UNLINK: &str = "ticket_unlink";
pub const TICKET_REORDER: &str = "ticket_reorder";
pub const TICKET_DELETE: &str = "ticket_delete";
pub const TICKET: &str = "ticket";
pub const TICKETS: &str = "tickets";
pub const TICKET_START_NEXT: &str = "ticket_start_next";
pub const SETTINGS_GET: &str = "settings_get";
pub const SETTINGS_SET: &str = "settings_set";
pub const SETTINGS: &str = "settings";
pub const CONFIG_GET: &str = "config_get";
pub const CONFIG_SCHEMA: &str = "config_schema";
pub const CONFIG_VALUES: &str = "config_values";
pub const CONFIG_SET: &str = "config_set";
pub const CONFIG_CHANGED: &str = "config_changed";
pub const LIMIT_CATALOG: &str = "limit_catalog";
pub const CLI_LOCATIONS: &str = "cli_locations";
pub const CLI_LOCATION_SET: &str = "cli_location_set";
pub const HARNESS_INSTALL_STATUS: &str = "harness_install_status";
pub const HARNESS_INSTALL: &str = "harness_install";
pub const HARNESS_INSTALL_RESULT: &str = "harness_install_result";
pub const TOOL_APPROVAL: &str = "tool_approval";
pub const TOOL_APPROVAL_SET: &str = "tool_approval_set";
pub const PROMPT_REQUEST: &str = "prompt_request";
pub const PROMPT_RESPONSE: &str = "prompt_response";
pub const PROMPT_RESOLVED: &str = "prompt_resolved";
pub const APPROVAL_RULES: &str = "approval_rules";
pub const APPROVAL_RULE_DELETE: &str = "approval_rule_delete";
pub const PROMPT_ASK: &str = "prompt_ask";
pub const PROMPT_ANSWER: &str = "prompt_answer";
pub const QUESTION_ASK: &str = "question_ask";
pub const QUESTION_ASKED: &str = "question_asked";
pub const QUESTION: &str = "question";
pub const QUESTION_LIST: &str = "question_list";
pub const QUESTIONS: &str = "questions";
pub const QUESTION_ANSWER: &str = "question_answer";
pub const QUESTION_CANCEL: &str = "question_cancel";
pub const IMAGE_UPLOAD: &str = "image_upload";
pub const IMAGE: &str = "image";
pub const ATTENTION: &str = "attention";
pub const ATTENTION_LIST: &str = "attention_list";
pub const ATTENTION_ITEMS: &str = "attention_items";
pub const ATTENTION_ACK: &str = "attention_ack";
pub const TIMELINE_ITEM: &str = "timeline_item";
pub const TIMELINE_LIST: &str = "timeline_list";
pub const TIMELINE_ITEMS: &str = "timeline_items";
pub const PROJECT_SAVE: &str = "project_save";
pub const PROJECT_GET: &str = "project_get";
pub const PROJECT_LIST: &str = "project_list";
pub const PROJECT: &str = "project";
pub const PROJECTS: &str = "projects";
pub const PROJECT_BASE: &str = "project_base";
pub const PROJECT_CONFIG: &str = "project_config";
pub const PROJECT_DELETE: &str = "project_delete";
pub const PROJECT_DELETED: &str = "project_deleted";
pub const PROJECT_GIT_STATUS: &str = "project_git_status";
pub const PROJECT_GIT_DIFF: &str = "project_git_diff";
pub const PROJECT_GIT_LOG: &str = "project_git_log";
pub const PROJECT_GIT: &str = "project_git";
pub const PROJECT_GIT_PATCH: &str = "project_git_patch";
pub const PROJECT_GIT_HISTORY: &str = "project_git_history";
pub const PROJECT_GIT_BRANCHES: &str = "project_git_branches";
pub const PROJECT_GIT_BRANCH_CHANGES: &str = "project_git_branch_changes";
pub const PROJECT_GIT_BRANCH_DIFF: &str = "project_git_branch_diff";
pub const PROJECT_GIT_BRANCH_LIST: &str = "project_git_branch_list";
pub const PROJECT_GIT_BRANCH_DIFFSTAT: &str = "project_git_branch_diffstat";
pub const PROJECT_GIT_CHECKOUT: &str = "project_git_checkout";
pub const PROJECT_GIT_CHECKOUT_RESULT: &str = "project_git_checkout_result";
pub const PROJECT_GIT_WORKTREES: &str = "project_git_worktrees";
pub const PROJECT_GIT_WORKTREE_STATUS: &str = "project_git_worktree_status";
pub const PROJECT_GIT_WORKTREE_LIST: &str = "project_git_worktree_list";
pub const PROJECT_GIT_WORKTREE: &str = "project_git_worktree";
pub const WORKSPACE_SAVE: &str = "workspace_save";
pub const WORKSPACE_LIST: &str = "workspace_list";
pub const WORKSPACE: &str = "workspace";
pub const WORKSPACES: &str = "workspaces";
pub const EPIC_SAVE: &str = "epic_save";
pub const EPIC_LIST: &str = "epic_list";
pub const EPIC_BASE: &str = "epic_base";
pub const EPIC: &str = "epic";
pub const EPICS: &str = "epics";
pub const STORY_SAVE: &str = "story_save";
pub const STORY_GET: &str = "story_get";
pub const STORY_LIST: &str = "story_list";
pub const STORY_MOVE: &str = "story_move";
pub const STORY: &str = "story";
pub const STORIES: &str = "stories";
pub const MEMORY_SEARCH: &str = "memory_search";
pub const MEMORY_HITS: &str = "memory_hits";
pub const MEMORY_SAVE: &str = "memory_save";
pub const MEMORY_LIST: &str = "memory_list";
pub const MEMORY_NOTE: &str = "memory_note";
pub const MEMORY_NOTES: &str = "memory_notes";
pub const MEMORY_GET: &str = "memory_get";
pub const MEMORY_DELETE: &str = "memory_delete";
pub const MEMORY_PURGE_ROLE: &str = "memory_purge_role";
pub const DECISION_ADD: &str = "decision_add";
pub const DECISION_LIST: &str = "decision_list";
pub const DECISION_GET: &str = "decision_get";
pub const DECISION_ITEM: &str = "decision_item";
pub const DECISIONS: &str = "decisions";
pub const PIPELINE_CONFIG: &str = "pipeline_config";
pub const PIPELINE_SHOW: &str = "pipeline_show";
pub const PIPELINE_RECORD: &str = "pipeline_record";
pub const PIPELINE_CHECK: &str = "pipeline_check";
pub const PIPELINE_STATE: &str = "pipeline_state";
pub const NOTIFY: &str = "notify";
pub const SPOKEN: &str = "spoken";
pub const SECRET_SET: &str = "secret_set";
pub const SECRET_CLEAR: &str = "secret_clear";
pub const SECRET_IMPORT: &str = "secret_import";
pub const SECRET_STATUS: &str = "secret_status";
pub const SECRET_STATE: &str = "secret_state";
pub const SECRET_FIELD_SET: &str = "secret_field_set";
pub const SECRET_FIELD_DELETE: &str = "secret_field_delete";
pub const SECRET_DELETE: &str = "secret_delete";
pub const SECRET_MOVE: &str = "secret_move";
pub const SECRET_LOGIN: &str = "secret_login";
pub const PLUGIN_SECRET_GET: &str = "plugin_secret_get";
pub const PLUGIN_SECRET: &str = "plugin_secret";
pub const SECRET_REVEAL: &str = "secret_reveal";
pub const SECRET_BACKUP_EXPORT: &str = "secret_backup_export";
pub const SECRET_BACKUP_IMPORT: &str = "secret_backup_import";
pub const ISSUE_OPEN: &str = "issue_open";
pub const ISSUE_ATTEMPT: &str = "issue_attempt";
pub const ISSUE_FIX: &str = "issue_fix";
pub const ISSUE_LIST: &str = "issue_list";
pub const ISSUE_GET: &str = "issue_get";
pub const ISSUE_PRECHECK: &str = "issue_precheck";
pub const ISSUE_ITEM: &str = "issue_item";
pub const ISSUES: &str = "issues";

/// Requests whose answer type is also broadcast; with a `requestId` they are always answered directly.
pub const CORRELATED_REQUESTS: &[&str] = &[
    TICKET_SAVE,
    TICKET_GET,
    TICKET_LIST,
    TICKET_MOVE,
    TICKET_LINK,
    TICKET_UNLINK,
    TICKET_REORDER,
    TICKET_DELETE,
    TICKET_START_NEXT,
    TICKET_UNASSIGN,
    TICKET_ASSIGN,
    AGENT_SWITCH,
];

/// Every frame name, in source order (excludes [`CLIENT_SEND_PREFIX`], which is an id prefix).
pub const ALL: &[&str] = &[
    READY,
    PROMPT,
    STDOUT,
    STDERR,
    EXIT,
    ERROR,
    SESSION,
    SESSIONS,
    KILL,
    ASSISTANT,
    INTERRUPT,
    QUEUE_ADD,
    QUEUE_REMOVE,
    QUEUE_FORCE,
    PROMPT_ACK,
    COMMAND_LIST,
    COMMANDS,
    COMMAND_SAVE,
    COMMAND_DELETE,
    RUN_START,
    RUN,
    RUN_OUTPUT,
    RUN_FINISHED,
    RUN_STOP,
    RUN_BYTES,
    RUN_LIST,
    RUNS,
    RUN_ATTACH,
    RUN_RESIZE,
    RUN_INPUT,
    RESUME,
    NEW,
    SESSION_DELETE,
    SESSION_PROJECT,
    SESSION_RESTART,
    SESSION_MCP_RECONNECT,
    MCP_LIST,
    MCP_GET,
    MCP_ADD,
    MCP_UPDATE,
    MCP_REMOVE,
    MCP_SET_ENABLED,
    MCP_TEST,
    MCP_ASSIGN,
    MCP_DECLARE,
    MCP_REVOKE,
    MCP_GRANTS,
    MCP_PLAN,
    MCP_SERVERS,
    MCP_SERVER,
    MCP_STATUS,
    MCP_CHANGED,
    WORKTREE,
    WORKTREES,
    WORKTREE_REMOVE,
    REPO,
    MAIL_SEND,
    MAIL_READ,
    MAIL_INBOX,
    MAIL_OUTBOX,
    MAIL_THREAD,
    MAIL,
    MAILS,
    MAIL_THREADS,
    MAIL_WAITING,
    BG_JOB,
    PROGRESS,
    WAITING,
    PROCESSES,
    PROCESSES_REAP,
    HEALTH,
    PLUGIN_ADMIN,
    PLUGINS_CHANGED,
    PROCESS_PRESSURE,
    MAIL_REFUSED,
    TEXT_DELTA,
    STATUS,
    FINISHED,
    REASONING,
    TURN,
    ROLE_LIST,
    ROLE_SAVE,
    ROLE,
    ROLES,
    ROLE_DELETE,
    ROLE_REMOVED,
    ROLE_BINDING_LIST,
    ROLE_BINDING_GET,
    ROLE_BINDING_SET,
    ROLE_BINDING,
    ROLE_BINDINGS,
    PROMPT_SAVE,
    PROMPT_LIST,
    PROMPT_GET,
    PROMPT_DELETE,
    PROMPT_ITEM,
    PROMPTS,
    SKILL_SAVE,
    SKILL_LIST,
    SKILL_GET,
    SKILL_DELETE,
    SKILL_ITEM,
    SKILLS,
    LAYER_SKILL_SAVE,
    LAYER_SKILL_LIST,
    LAYER_SKILL_DELETE,
    LAYER_SKILLS,
    TRAIT_SAVE,
    TRAIT_LIST,
    TRAIT_GET,
    TRAIT_DELETE,
    TRAIT_ITEM,
    TRAITS,
    ROLE_TRAITS_SET,
    ROLE_TRAITS_GET,
    ROLE_TRAITS_LIST,
    ROLE_TRAITS,
    ROLE_TRAITS_ALL,
    USAGE,
    PROVIDER_STATE,
    PROVIDER_STATES,
    LIMITS,
    USAGE_GET,
    USAGE_REPORT,
    USAGE_SET,
    TICKET_FILES,
    TICKET_FILES_REPORT,
    ARCHIVE_QUERY,
    ARCHIVE_APPLY,
    ARCHIVE_REPORT,
    TRIGGER_SAVE,
    TRIGGER_LIST,
    TRIGGER_FIRE,
    TRIGGER_ARM,
    TRIGGER_DISABLE,
    TRIGGER_DELETE,
    TRIGGER_EVENTS,
    TRIGGER,
    TRIGGERS,
    POD_SUBMIT,
    POD_QUERY,
    POD_CANCEL,
    POD,
    PROVIDERS,
    MODELS,
    COMMAND_CATALOG,
    COMMAND_RESULT,
    AGENT_SAVE,
    AGENT_LIST,
    AGENT_DELETE,
    AGENT,
    AGENTS,
    AGENT_SWAP,
    AGENT_SWAPPED,
    SESSION_SWAP,
    SESSION_SWAPPED,
    AGENT_ROSTER,
    ROSTER,
    SESSION_STATS_GET,
    SESSION_STATS,
    USAGE_METER_GET,
    USAGE_METER,
    TICKET_SAVE,
    TICKET_GET,
    TICKET_LIST,
    TICKET_MOVE,
    TICKET_UNASSIGN,
    AGENT_QUEUE,
    TICKET_ASSIGN,
    AGENT_SWITCH,
    TICKET_LINK,
    TICKET_UNLINK,
    TICKET_REORDER,
    TICKET_DELETE,
    TICKET,
    TICKETS,
    TICKET_START_NEXT,
    SETTINGS_GET,
    SETTINGS_SET,
    SETTINGS,
    CONFIG_GET,
    CONFIG_SCHEMA,
    CONFIG_VALUES,
    CONFIG_SET,
    CONFIG_CHANGED,
    LIMIT_CATALOG,
    CLI_LOCATIONS,
    CLI_LOCATION_SET,
    HARNESS_INSTALL_STATUS,
    HARNESS_INSTALL,
    HARNESS_INSTALL_RESULT,
    TOOL_APPROVAL,
    TOOL_APPROVAL_SET,
    PROMPT_REQUEST,
    PROMPT_RESPONSE,
    PROMPT_RESOLVED,
    APPROVAL_RULES,
    APPROVAL_RULE_DELETE,
    PROMPT_ASK,
    PROMPT_ANSWER,
    QUESTION_ASK,
    QUESTION_ASKED,
    QUESTION,
    QUESTION_LIST,
    QUESTIONS,
    QUESTION_ANSWER,
    QUESTION_CANCEL,
    IMAGE_UPLOAD,
    IMAGE,
    ATTENTION,
    ATTENTION_LIST,
    ATTENTION_ITEMS,
    ATTENTION_ACK,
    TIMELINE_ITEM,
    TIMELINE_LIST,
    TIMELINE_ITEMS,
    PROJECT_SAVE,
    PROJECT_GET,
    PROJECT_LIST,
    PROJECT,
    PROJECTS,
    PROJECT_BASE,
    PROJECT_CONFIG,
    PROJECT_DELETE,
    PROJECT_DELETED,
    PROJECT_GIT_STATUS,
    PROJECT_GIT_DIFF,
    PROJECT_GIT_LOG,
    PROJECT_GIT,
    PROJECT_GIT_PATCH,
    PROJECT_GIT_HISTORY,
    PROJECT_GIT_BRANCHES,
    PROJECT_GIT_BRANCH_CHANGES,
    PROJECT_GIT_BRANCH_DIFF,
    PROJECT_GIT_BRANCH_LIST,
    PROJECT_GIT_BRANCH_DIFFSTAT,
    PROJECT_GIT_CHECKOUT,
    PROJECT_GIT_CHECKOUT_RESULT,
    PROJECT_GIT_WORKTREES,
    PROJECT_GIT_WORKTREE_STATUS,
    PROJECT_GIT_WORKTREE_LIST,
    PROJECT_GIT_WORKTREE,
    WORKSPACE_SAVE,
    WORKSPACE_LIST,
    WORKSPACE,
    WORKSPACES,
    EPIC_SAVE,
    EPIC_LIST,
    EPIC_BASE,
    EPIC,
    EPICS,
    STORY_SAVE,
    STORY_GET,
    STORY_LIST,
    STORY_MOVE,
    STORY,
    STORIES,
    MEMORY_SEARCH,
    MEMORY_HITS,
    MEMORY_SAVE,
    MEMORY_LIST,
    MEMORY_NOTE,
    MEMORY_NOTES,
    MEMORY_GET,
    MEMORY_DELETE,
    MEMORY_PURGE_ROLE,
    DECISION_ADD,
    DECISION_LIST,
    DECISION_GET,
    DECISION_ITEM,
    DECISIONS,
    PIPELINE_CONFIG,
    PIPELINE_SHOW,
    PIPELINE_RECORD,
    PIPELINE_CHECK,
    PIPELINE_STATE,
    NOTIFY,
    SPOKEN,
    SECRET_SET,
    SECRET_CLEAR,
    SECRET_IMPORT,
    SECRET_STATUS,
    SECRET_STATE,
    SECRET_FIELD_SET,
    SECRET_FIELD_DELETE,
    SECRET_DELETE,
    SECRET_MOVE,
    SECRET_LOGIN,
    PLUGIN_SECRET_GET,
    PLUGIN_SECRET,
    SECRET_REVEAL,
    SECRET_BACKUP_EXPORT,
    SECRET_BACKUP_IMPORT,
    ISSUE_OPEN,
    ISSUE_ATTEMPT,
    ISSUE_FIX,
    ISSUE_LIST,
    ISSUE_GET,
    ISSUE_PRECHECK,
    ISSUE_ITEM,
    ISSUES,
];
