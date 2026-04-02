use crate::api::request::ApiClient;
use crate::builder::ReqClientBuilder;
use crate::cli::{
    ActCommands, Commands, ObserveCommands, OverlayCommands, RecoverCommands, VerifyCommands,
};
use crate::commands::{act, common::OverlaySetOptions, observe, recover, verify};
use crate::output::into_output;
use crossbeam_channel::Receiver;
use reqwest::blocking::Client;
use serde_json::Value;

pub fn run_command(
    client: &Client,
    runtime: &ReqClientBuilder,
    ctrl_c_events: &Receiver<()>,
    command: &Commands,
) -> Value {
    let api = ApiClient::new(
        client,
        runtime.base_url.as_str(),
        runtime.token.as_deref(),
        ctrl_c_events,
    );

    match command {
        Commands::Health => into_output(
            &runtime.session_id,
            "health",
            "health",
            observe_health(&api),
        ),
        Commands::Act { command } => match command {
            ActCommands::Tap {
                xy,
                by,
                value,
                exact_match,
            } => into_output(
                &runtime.session_id,
                "act",
                "tap",
                act::handle_tap(
                    &api,
                    *xy,
                    by.as_ref().map(|value| value.as_str()),
                    value.as_ref().map(|value| value.as_str()),
                    *exact_match,
                ),
            ),
            ActCommands::Swipe { from, to, duration } => into_output(
                &runtime.session_id,
                "act",
                "swipe",
                act::handle_swipe(&api, from[0], from[1], to[0], to[1], *duration),
            ),
            ActCommands::Back => {
                into_output(&runtime.session_id, "act", "back", act::handle_back(&api))
            }
            ActCommands::Home => {
                into_output(&runtime.session_id, "act", "home", act::handle_home(&api))
            }
            ActCommands::Text { text } => into_output(
                &runtime.session_id,
                "act",
                "text",
                act::handle_text(&api, text),
            ),
            ActCommands::Launch { package_name } => into_output(
                &runtime.session_id,
                "act",
                "launch",
                act::handle_launch(&api, package_name),
            ),
            ActCommands::Stop { package_name } => into_output(
                &runtime.session_id,
                "act",
                "stop",
                act::handle_stop(&api, package_name),
            ),
            ActCommands::Key { key_code } => into_output(
                &runtime.session_id,
                "act",
                "key",
                act::handle_key(&api, *key_code),
            ),
        },
        Commands::Observe { command } => match command {
            ObserveCommands::Screen {
                full,
                max_rows,
                fields,
            } => into_output(
                &runtime.session_id,
                "observe",
                "screen",
                observe::handle_screen(&api, *full, *max_rows, fields),
            ),
            ObserveCommands::Overlay { command } => match command {
                OverlayCommands::Get => into_output(
                    &runtime.session_id,
                    "observe",
                    "overlay",
                    observe::handle_overlay_get(&api),
                ),
                OverlayCommands::Set {
                    enable,
                    disable,
                    max_marks,
                    mark_scope,
                    refresh,
                    refresh_interval_ms,
                    offset_x,
                    offset_y,
                } => into_output(
                    &runtime.session_id,
                    "observe",
                    "overlay",
                    observe::handle_overlay_set(
                        &api,
                        OverlaySetOptions {
                            enabled: if *enable {
                                true
                            } else if *disable {
                                false
                            } else {
                                unreachable!("clap requires exactly one of --enable or --disable")
                            },
                            max_marks: *max_marks,
                            mark_scope: *mark_scope,
                            refresh: *refresh,
                            refresh_interval_ms: *refresh_interval_ms,
                            offset_x: *offset_x,
                            offset_y: *offset_y,
                        },
                    ),
                ),
            },
            ObserveCommands::Screenshot {
                max_dim,
                quality,
                annotate,
                hide_overlay,
                max_marks,
                mark_scope,
            } => into_output(
                &runtime.session_id,
                "observe",
                "screenshot",
                observe::handle_screenshot(
                    &api,
                    *max_dim,
                    *quality,
                    *annotate,
                    *hide_overlay,
                    *max_marks,
                    *mark_scope,
                ),
            ),
            ObserveCommands::Top => into_output(
                &runtime.session_id,
                "observe",
                "top",
                observe::handle_top(&api),
            ),
            ObserveCommands::Refs { max_rows } => into_output(
                &runtime.session_id,
                "observe",
                "refs",
                observe::handle_refs(&api, *max_rows),
            ),
        },
        Commands::Verify { command } => match command {
            VerifyCommands::TextContains { text, ignore_case } => into_output(
                &runtime.session_id,
                "verify",
                "text-contains",
                verify::handle_text_contains(&api, text, *ignore_case),
            ),
            VerifyCommands::TopActivity { expected, mode } => into_output(
                &runtime.session_id,
                "verify",
                "top-activity",
                verify::handle_top_activity(&api, expected, mode),
            ),
            VerifyCommands::NodeExists {
                by,
                value,
                exact_match,
            } => into_output(
                &runtime.session_id,
                "verify",
                "node-exists",
                verify::handle_node_exists(&api, by, value, *exact_match),
            ),
        },
        Commands::Recover { command } => match command {
            RecoverCommands::Back { times } => into_output(
                &runtime.session_id,
                "recover",
                "back",
                recover::handle_back(&api, *times),
            ),
            RecoverCommands::Home => into_output(
                &runtime.session_id,
                "recover",
                "home",
                recover::handle_home(&api),
            ),
            RecoverCommands::Relaunch { package_name } => into_output(
                &runtime.session_id,
                "recover",
                "relaunch",
                recover::handle_relaunch(&api, package_name),
            ),
        },
    }
}

fn observe_health(api: &ApiClient<'_>) -> crate::output::CommandResult {
    let health = api.health().map_err(crate::output::CommandError::from)?;
    Ok(serde_json::json!({"health": health.payload}))
}
