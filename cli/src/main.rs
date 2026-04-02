mod api;
mod builder;
mod cli;
mod commands;
mod core;
mod memory;
mod output;
mod runner;

use crate::builder::ReqClientBuilder;
use crate::cli::Cli;
use crate::memory::{TraceRecord, TraceStore};
use crate::runner::run_command;
use clap::Parser;
use crossbeam_channel::{Receiver, bounded, select};
use std::process;
use std::time::Instant;

fn main() -> anyhow::Result<()> {
    let ctrl_c_events = ctrl_channel()?;
    let cli = Cli::parse();
    let trace_store = if cli.no_trace {
        None
    } else {
        Some(TraceStore::new(cli.trace_db.clone())?)
    };

    let runtime = ReqClientBuilder::new(
        cli.url.trim_end_matches('/').to_string(),
        cli.timeout_ms,
        cli.proxy,
    )
    .with_token(cli.token.clone());

    let client = runtime.build()?;
    let started = Instant::now();
    let result = run_command(&client, &runtime, &ctrl_c_events, &cli.command);
    persist_trace(
        &trace_store,
        &cli,
        &runtime.session_id,
        &result,
        started.elapsed().as_millis(),
    );
    println!("{}", serde_json::to_string(&result)?);

    let exit_code = match result.get("status").and_then(|value| value.as_str()) {
        Some("ok") => 0,
        Some("interrupted") => 130,
        _ => 1,
    };
    if exit_code != 0 {
        process::exit(exit_code);
    }
    Ok(())
}

fn persist_trace(
    trace_store: &Option<TraceStore>,
    cli: &Cli,
    trace_id: &str,
    result: &serde_json::Value,
    duration_ms: u128,
) {
    let Some(store) = trace_store else {
        return;
    };

    let status = result
        .get("status")
        .and_then(|value| value.as_str())
        .unwrap_or("unknown")
        .to_string();
    let output_json = match serde_json::to_string(result) {
        Ok(value) => value,
        Err(error) => format!(r#"{{"traceSerializeError":"{error}"}}"#),
    };
    let command = format!("{:?}", cli.command);
    let record = TraceRecord {
        created_at: chrono::Utc::now().to_rfc3339(),
        session: cli.session.clone(),
        trace_id: trace_id.to_string(),
        command,
        status,
        output_json,
        duration_ms,
    };
    if let Err(error) = store.record(&record) {
        eprintln!("warn: failed to persist trace: {error}");
    }
}

pub(crate) fn run_with_interrupt<T, F>(ctrl_c_events: &Receiver<()>, work: F) -> anyhow::Result<T>
where
    T: Send + 'static,
    F: FnOnce() -> anyhow::Result<T> + Send + 'static,
{
    let (done_tx, done_rx) = bounded::<anyhow::Result<T>>(1);
    std::thread::spawn(move || {
        let _ = done_tx.send(work());
    });

    select! {
        recv(ctrl_c_events) -> _ => Err(anyhow::anyhow!("Interrupted by SIGINT (Ctrl+C)")),
        recv(done_rx) -> msg => {
            match msg {
                Ok(res) => res,
                Err(_) => Err(anyhow::anyhow!("worker channel closed unexpectedly")),
            }
        }
    }
}

fn ctrl_channel() -> Result<Receiver<()>, ctrlc::Error> {
    let (sender, receiver) = bounded(100);
    ctrlc::set_handler(move || {
        let _ = sender.send(());
    })?;
    Ok(receiver)
}
