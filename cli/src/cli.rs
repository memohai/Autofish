use std::path::PathBuf;

use clap::{Parser, ValueEnum};

fn parse_non_negative_f32(v: &str) -> Result<f32, String> {
    let parsed = v
        .parse::<f32>()
        .map_err(|_| format!("invalid float value: {v}"))?;
    if parsed < 0.0 {
        return Err(format!("value must be >= 0, got {parsed}"));
    }
    Ok(parsed)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, ValueEnum)]
pub enum ProxyMode {
    System,
    Direct,
    Auto,
}

#[derive(Parser, Debug)]
#[command(name = "amc", about = "Deterministic executor for amctl REST API")]
pub struct Cli {
    #[arg(long, env = "AMC_URL")]
    pub url: String,

    #[arg(long, env = "AMC_TOKEN")]
    pub token: Option<String>,

    #[arg(long, default_value_t = 10000)]
    pub timeout_ms: u64,

    #[arg(long, value_enum, default_value_t = ProxyMode::Auto)]
    pub proxy: ProxyMode,

    #[arg(long = "no-trace", default_value_t = false)]
    pub no_trace: bool,

    #[arg(
        long,
        env = "AMC_DB",
        default_value = "amc.db",
        value_hint = clap::ValueHint::FilePath
    )]
    pub trace_db: PathBuf,

    #[arg(long, default_value = "default")]
    pub session: String,

    #[command(subcommand)]
    pub command: Commands,
}

#[derive(clap::Subcommand, Debug)]
pub enum Commands {
    #[command(name = "health", about = "Check service health")]
    Health,
    #[command(name = "act", about = "Run one device action")]
    Act {
        #[command(subcommand)]
        command: ActCommands,
    },
    #[command(name = "observe", about = "Observe device state")]
    Observe {
        #[command(subcommand)]
        command: ObserveCommands,
    },
    #[command(name = "verify", about = "Verify expected state")]
    Verify {
        #[command(subcommand)]
        command: VerifyCommands,
    },
    #[command(name = "recover", about = "Run simple recovery actions")]
    Recover {
        #[command(subcommand)]
        command: RecoverCommands,
    },
}

#[derive(clap::Subcommand, Debug)]
pub enum ActCommands {
    #[command(
        name = "tap",
        about = "Tap at screen coordinates",
        long_about = "Tap at screen coordinates.\n\nCoordinates must be non-negative."
    )]
    Tap {
        #[arg(long, allow_hyphen_values = true, value_parser = parse_non_negative_f32)]
        x: f32,
        #[arg(long, allow_hyphen_values = true, value_parser = parse_non_negative_f32)]
        y: f32,
    },
    #[command(
        name = "swipe",
        about = "Swipe from (x1,y1) to (x2,y2)",
        long_about = "Swipe from (x1,y1) to (x2,y2).\n\nCoordinates must be non-negative."
    )]
    Swipe {
        #[arg(long, allow_hyphen_values = true, value_parser = parse_non_negative_f32)]
        x1: f32,
        #[arg(long, allow_hyphen_values = true, value_parser = parse_non_negative_f32)]
        y1: f32,
        #[arg(long, allow_hyphen_values = true, value_parser = parse_non_negative_f32)]
        x2: f32,
        #[arg(long, allow_hyphen_values = true, value_parser = parse_non_negative_f32)]
        y2: f32,
        #[arg(long, default_value_t = 300)]
        duration: i64,
    },
    #[command(name = "back")]
    Back,
    #[command(name = "home")]
    Home,
    #[command(name = "text")]
    Text {
        #[arg(long)]
        text: String,
    },
    #[command(name = "launch")]
    Launch {
        #[arg(long = "package")]
        package_name: String,
    },
    #[command(
        name = "stop",
        about = "Stop an app by package name",
        long_about = "Stop (force-stop) an app by package name."
    )]
    Stop {
        #[arg(long = "package")]
        package_name: String,
    },
    #[command(name = "key")]
    Key {
        #[arg(long = "key-code")]
        key_code: i32,
    },
}

#[derive(clap::Subcommand, Debug)]
pub enum ObserveCommands {
    #[command(
        name = "screen",
        about = "Observe current UI tree snapshot",
        long_about = "Observe current UI tree snapshot.\n\nOutput includes `hasWebView` and `nodeReliability`.\nWhen `hasWebView=true` or `nodeReliability=low`, node-based verification may be less reliable."
    )]
    Screen {
        #[arg(long, default_value = "compact")]
        mode: String,
        #[arg(long = "max-rows", default_value_t = 120)]
        max_rows: usize,
        #[arg(long, default_value = "id,class,text,desc,resId,flags")]
        fields: String,
    },
    #[command(
        name = "overlay",
        about = "Get or set server-side accessibility overlay state",
        long_about = "Manage server-side accessibility overlay used by annotate features.\n\nIf `--enabled` is omitted, this command returns current overlay state.\n\nColor legend:\n- Green: generic interactive nodes\n- Orange: buttons\n- Cyan: input fields\n- Yellow: selection controls (checkbox/switch/radio)\n- Pink: scroll/list containers\n- Blue: text-bearing non-interactive nodes"
    )]
    Overlay {
        #[arg(long)]
        enabled: Option<bool>,
        #[arg(long = "max-marks", default_value_t = 300)]
        max_marks: usize,
        /// default false: full marks, not only interactive nodes
        #[arg(long)]
        interactive_only: Option<bool>,
        #[arg(long)]
        auto_refresh: Option<bool>,
        #[arg(long = "refresh-interval-ms", default_value_t = 800)]
        refresh_interval_ms: u64,
        #[arg(long = "offset-x")]
        offset_x: Option<i32>,
        #[arg(long = "offset-y")]
        offset_y: Option<i32>,
    },
    #[command(
        name = "screenshot",
        about = "Capture a compressed screenshot",
        long_about = "Capture a compressed screenshot.\n\n`max-dim` limits the long edge of the image.\n`quality` is JPEG quality from 1 to 100 (higher = bigger file).\nUse `--annotate` to include server-side overlay marks in screenshot."
    )]
    Screenshot {
        #[arg(long = "max-dim", default_value_t = 700)]
        max_dim: i64,
        #[arg(long, default_value_t = 80)]
        quality: i64,
        #[arg(long, default_value_t = false)]
        annotate: bool,
        #[arg(long)]
        hide_overlay: Option<bool>,
        #[arg(long = "max-marks", default_value_t = 120)]
        max_marks: usize,
        /// default false: include both interactive and text-bearing nodes
        #[arg(long)]
        interactive_only: Option<bool>,
    },
    #[command(name = "top")]
    Top,
}

#[derive(clap::Subcommand, Debug)]
pub enum VerifyCommands {
    #[command(name = "text-contains")]
    TextContains {
        #[arg(long)]
        text: String,
        #[arg(long, default_value_t = true)]
        ignore_case: bool,
    },
    #[command(name = "top-activity")]
    TopActivity {
        #[arg(long)]
        expected: String,
        #[arg(long, default_value = "contains")]
        mode: String,
    },
    #[command(
        name = "node-exists",
        about = "Verify a node exists by text/content_desc/resource_id/class_name",
        long_about = "Verify a node exists by text/content_desc/resource_id/class_name.\n\nAliases: `desc` -> `content_desc`, `class` -> `class_name`.\nIf the screen is WebView-heavy (`hasWebView=true` or `nodeReliability=low`), prefer `verify text-contains` first."
    )]
    NodeExists {
        #[arg(long)]
        by: String,
        #[arg(long)]
        value: String,
        #[arg(long, default_value_t = false)]
        exact_match: bool,
    },
}

#[derive(clap::Subcommand, Debug)]
pub enum RecoverCommands {
    #[command(name = "back")]
    Back {
        #[arg(long, default_value_t = 1)]
        times: u32,
    },
    #[command(name = "home")]
    Home,
    #[command(name = "relaunch")]
    Relaunch {
        #[arg(long = "package")]
        package_name: String,
    },
}
