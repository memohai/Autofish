use crate::api::request::ApiClient;
use crate::commands::common::normalize_semantic_tap_by;
use crate::output::{CommandError, CommandResult};
use serde_json::json;

pub fn handle_tap(
    api: &ApiClient<'_>,
    xy: Option<[f32; 2]>,
    by: Option<&str>,
    value: Option<&str>,
    exact_match: bool,
) -> CommandResult {
    match (xy, by, value) {
        (Some([xv, yv]), None, None) => {
            let msg = api.tap(xv, yv).map_err(CommandError::from)?;
            Ok(json!({"mode": "coords", "x": xv, "y": yv, "result": msg.message}))
        }
        (None, Some(by_raw), Some(value_raw)) => {
            if value_raw.trim().is_empty() {
                return Err(CommandError::invalid_params("value must not be empty"));
            }
            let by_api = normalize_semantic_tap_by(by_raw)?;
            let msg = api
                .tap_node(&by_api, value_raw, exact_match)
                .map_err(CommandError::from)?;
            Ok(json!({
                "mode": "semantic",
                "by": by_raw,
                "byNormalized": by_api,
                "value": value_raw,
                "exactMatch": exact_match,
                "result": msg.message
            }))
        }
        _ => Err(CommandError::invalid_params(
            "tap requires either --xy or (--by and --value), and these modes cannot be mixed",
        )),
    }
}

pub fn handle_swipe(
    api: &ApiClient<'_>,
    x1: f32,
    y1: f32,
    x2: f32,
    y2: f32,
    duration: i64,
) -> CommandResult {
    let msg = api
        .swipe(x1, y1, x2, y2, duration)
        .map_err(CommandError::from)?;
    Ok(json!({"result": msg.message}))
}

pub fn handle_back(api: &ApiClient<'_>) -> CommandResult {
    let msg = api.press_back().map_err(CommandError::from)?;
    Ok(json!({"result": msg.message}))
}

pub fn handle_home(api: &ApiClient<'_>) -> CommandResult {
    let msg = api.press_home().map_err(CommandError::from)?;
    Ok(json!({"result": msg.message}))
}

pub fn handle_text(api: &ApiClient<'_>, text: &str) -> CommandResult {
    if text.is_empty() {
        return Err(CommandError::invalid_params("text must not be empty"));
    }
    let msg = api.input_text(text).map_err(CommandError::from)?;
    Ok(json!({"result": msg.message}))
}

pub fn handle_launch(api: &ApiClient<'_>, package_name: &str) -> CommandResult {
    let msg = api.app_launch(package_name).map_err(CommandError::from)?;
    Ok(json!({"result": msg.message}))
}

pub fn handle_stop(api: &ApiClient<'_>, package_name: &str) -> CommandResult {
    let msg = api.app_stop(package_name).map_err(CommandError::from)?;
    Ok(json!({"result": msg.message}))
}

pub fn handle_key(api: &ApiClient<'_>, key_code: i32) -> CommandResult {
    let msg = api.press_key(key_code).map_err(CommandError::from)?;
    Ok(json!({"keyCode": key_code, "result": msg.message}))
}
