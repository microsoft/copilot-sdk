// Copyright (c) Microsoft Corporation. All rights reserved.

pub(super) use super::support;

mod abort;
mod ask_user;
mod auto_tier;
mod byok_bearer_token_provider;
mod canvas;
mod client;
mod client_api;
mod client_lifecycle;
mod client_options;
mod commands;
mod copilot_request_handler;
mod elicitation;
mod error_resilience;
mod event_fidelity;
mod external_tool_cancellation;
mod github_telemetry;
mod hooks;
mod hooks_extended;
#[cfg(feature = "in-process")]
mod host_callbacks;
#[cfg(feature = "in-process")]
mod host_transports;
#[cfg(feature = "in-process")]
mod inprocess;
#[cfg(all(feature = "in-process", any(target_os = "linux", windows)))]
mod inprocess_child_processes;
mod mcp_and_agents;
mod mcp_oauth;
mod mode_empty;
mod mode_handlers;
mod multi_client;
mod multi_client_commands_elicitation;
mod multi_provider_registry;
mod multi_turn;
mod pending_work_resume;
mod per_session_auth;
mod permissions;
mod pre_mcp_tool_call_hook;
mod rpc_smoke;
mod session;
mod session_config;
mod session_fs;
mod session_fs_sqlite;
mod session_lifecycle;
mod session_todos_changed;
mod set_tools;
mod skill_provider;
mod skills;
mod streaming_fidelity;
#[cfg(feature = "derive")]
mod structured_output;
mod subagent_hooks;
mod suspend;
mod system_message_sections;
mod system_message_transform;
mod telemetry;
mod tool_results;
mod tools;
