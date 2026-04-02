use crate::api::request::ScreenRow;
use crate::cli::{MarkScope, RefreshMode, ScreenFieldArg};
use crate::output::CommandError;
use serde_json::{Value, json};

pub struct OverlaySetOptions {
    pub enabled: bool,
    pub max_marks: usize,
    pub mark_scope: MarkScope,
    pub refresh: RefreshMode,
    pub refresh_interval_ms: Option<u64>,
    pub offset_x: Option<i32>,
    pub offset_y: Option<i32>,
}

pub fn normalize_semantic_tap_by(by: &str) -> Result<String, CommandError> {
    let by_norm = by.to_lowercase();
    match by_norm.as_str() {
        "text" => Ok("text".to_string()),
        "desc" | "content_desc" => Ok("content_desc".to_string()),
        "resid" | "resource_id" | "res_id" => Ok("resource_id".to_string()),
        "ref" => Ok("ref".to_string()),
        _ => Err(CommandError::invalid_params(
            "tap --by must be one of: text,desc,resid,ref (aliases: content_desc,resource_id,res_id)",
        )),
    }
}

pub fn matches_text(value: Option<&str>, expected: &str, ignore_case: bool) -> bool {
    let Some(v) = value else {
        return false;
    };
    if ignore_case {
        v.to_lowercase().contains(&expected.to_lowercase())
    } else {
        v.contains(expected)
    }
}

pub fn parse_screen_fields(fields: &[ScreenFieldArg]) -> Vec<String> {
    let default_fields = vec![
        "id".to_string(),
        "class".to_string(),
        "text".to_string(),
        "desc".to_string(),
        "resId".to_string(),
        "flags".to_string(),
    ];
    if fields.is_empty() {
        return default_fields;
    }
    let mut out = Vec::<String>::new();
    for field in fields {
        let name = match field {
            ScreenFieldArg::Id => "id",
            ScreenFieldArg::Class => "class",
            ScreenFieldArg::Text => "text",
            ScreenFieldArg::Desc => "desc",
            ScreenFieldArg::ResId => "resId",
            ScreenFieldArg::Flags => "flags",
            ScreenFieldArg::Bounds => "bounds",
        }
        .to_string();
        if !out.contains(&name) {
            out.push(name);
        }
    }
    out
}

pub fn compact_row_json(row: ScreenRow, fields: &[String]) -> Value {
    let mut obj = serde_json::Map::new();
    for field in fields {
        match field.as_str() {
            "id" => {
                obj.insert("id".to_string(), json!(row.node_id));
            }
            "class" => {
                obj.insert("class".to_string(), json!(row.class_name));
            }
            "text" => {
                obj.insert("text".to_string(), json!(row.text));
            }
            "desc" => {
                obj.insert("desc".to_string(), json!(row.desc));
            }
            "resId" => {
                obj.insert("resId".to_string(), json!(row.res_id));
            }
            "flags" => {
                obj.insert("flags".to_string(), json!(row.flags));
            }
            "bounds" => {
                obj.insert("bounds".to_string(), json!(row.bounds));
            }
            _ => {}
        }
    }
    Value::Object(obj)
}
