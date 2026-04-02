use crate::api::request::ApiClient;
use crate::output::{CommandError, CommandResult};
use serde_json::json;

pub fn handle_back(api: &ApiClient<'_>, times: u32) -> CommandResult {
    if times == 0 {
        return Err(CommandError::invalid_params("times must be >= 1"));
    }
    for _ in 0..times {
        let _ = api.press_back().map_err(CommandError::from)?;
    }
    Ok(json!({"times": times}))
}

pub fn handle_home(api: &ApiClient<'_>) -> CommandResult {
    let _ = api.press_home().map_err(CommandError::from)?;
    Ok(json!({}))
}

pub fn handle_relaunch(api: &ApiClient<'_>, package_name: &str) -> CommandResult {
    let _ = api.press_home().map_err(CommandError::from)?;
    let launch = api.app_launch(package_name).map_err(CommandError::from)?;
    Ok(json!({"packageName": package_name, "launchResult": launch.message}))
}
