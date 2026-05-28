use std::io::{self, IsTerminal, Write};

use crate::cli::OutputFormat;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProgressMode {
    Disabled,
    Plain,
    Tty,
}

impl ProgressMode {
    pub fn for_output(output: OutputFormat) -> Self {
        match output {
            OutputFormat::Json => Self::Disabled,
            OutputFormat::Text if io::stderr().is_terminal() => Self::Tty,
            OutputFormat::Text => Self::Plain,
        }
    }
}

pub struct ProgressReporter<W: Write> {
    mode: ProgressMode,
    writer: W,
    spinner_index: usize,
    last_tty_len: usize,
    plain_download_started: bool,
    plain_download_bucket: Option<u64>,
    plain_install_started: bool,
}

impl ProgressReporter<io::Stderr> {
    pub fn stderr(mode: ProgressMode) -> Self {
        Self::new(mode, io::stderr())
    }
}

impl<W: Write> ProgressReporter<W> {
    pub fn new(mode: ProgressMode, writer: W) -> Self {
        Self {
            mode,
            writer,
            spinner_index: 0,
            last_tty_len: 0,
            plain_download_started: false,
            plain_download_bucket: None,
            plain_install_started: false,
        }
    }

    pub fn download(&mut self, version: &str, downloaded: u64, total: Option<u64>) {
        match self.mode {
            ProgressMode::Disabled => {}
            ProgressMode::Tty => {
                let spinner = self.next_spinner();
                self.render_tty(&format!(
                    "Downloading APK {} {} {}",
                    version,
                    spinner,
                    format_download_progress(downloaded, total)
                ));
            }
            ProgressMode::Plain => {
                let bucket = download_plain_bucket(downloaded, total);
                if !self.plain_download_started || self.plain_download_bucket != Some(bucket) {
                    self.write_line(&format!(
                        "Downloading APK {}: {}",
                        version,
                        format_download_progress(downloaded, total)
                    ));
                    self.plain_download_started = true;
                    self.plain_download_bucket = Some(bucket);
                }
            }
        }
    }

    pub fn finish_download(&mut self, version: &str) {
        match self.mode {
            ProgressMode::Disabled => {}
            ProgressMode::Tty => self.render_tty_line(&format!("Downloaded APK {version}")),
            ProgressMode::Plain => self.write_line(&format!("Downloaded APK {version}")),
        }
    }

    pub fn install_tick(&mut self, version: &str) {
        match self.mode {
            ProgressMode::Disabled => {}
            ProgressMode::Tty => {
                let spinner = self.next_spinner();
                self.render_tty(&format!("Installing APK {version} {spinner}"));
            }
            ProgressMode::Plain if !self.plain_install_started => {
                self.write_line(&format!("Installing APK {version}..."));
                self.plain_install_started = true;
            }
            ProgressMode::Plain => {}
        }
    }

    pub fn finish_install(&mut self, version: &str) {
        match self.mode {
            ProgressMode::Disabled => {}
            ProgressMode::Tty => self.render_tty_line(&format!("Installed APK {version}")),
            ProgressMode::Plain => self.write_line(&format!("Installed APK {version}")),
        }
    }

    pub fn finish_with_error(&mut self) {
        if self.mode == ProgressMode::Tty && self.last_tty_len > 0 {
            self.write_line("");
            self.last_tty_len = 0;
        }
    }

    #[cfg(test)]
    fn into_inner(self) -> W {
        self.writer
    }

    fn next_spinner(&mut self) -> char {
        const FRAMES: &[u8] = b"-\\|/";
        let frame = FRAMES[self.spinner_index % FRAMES.len()] as char;
        self.spinner_index = self.spinner_index.wrapping_add(1);
        frame
    }

    fn render_tty(&mut self, message: &str) {
        let padding = self.last_tty_len.saturating_sub(message.len());
        let _ = write!(self.writer, "\r{message}{}", " ".repeat(padding));
        let _ = self.writer.flush();
        self.last_tty_len = message.len();
    }

    fn render_tty_line(&mut self, message: &str) {
        self.render_tty(message);
        self.write_line("");
        self.last_tty_len = 0;
    }

    fn write_line(&mut self, message: &str) {
        let _ = writeln!(self.writer, "{message}");
    }
}

fn download_plain_bucket(downloaded: u64, total: Option<u64>) -> u64 {
    match total {
        Some(total) if total > 0 => (downloaded.saturating_mul(4) / total).min(4),
        _ => downloaded / (5 * 1024 * 1024),
    }
}

fn format_download_progress(downloaded: u64, total: Option<u64>) -> String {
    match total {
        Some(total) if total > 0 => {
            let percent = (downloaded.saturating_mul(100) / total).min(100);
            format!(
                "{percent}% ({}/{})",
                format_bytes(downloaded),
                format_bytes(total)
            )
        }
        _ => format!("{} downloaded", format_bytes(downloaded)),
    }
}

fn format_bytes(bytes: u64) -> String {
    const KIB: u64 = 1024;
    const MIB: u64 = KIB * 1024;
    if bytes >= MIB {
        format!("{:.1} MiB", bytes as f64 / MIB as f64)
    } else if bytes >= KIB {
        format!("{:.1} KiB", bytes as f64 / KIB as f64)
    } else {
        format!("{bytes} B")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn formats_download_progress_with_content_length() {
        assert_eq!(
            format_download_progress(512 * 1024, Some(1024 * 1024)),
            "50% (512.0 KiB/1.0 MiB)"
        );
    }

    #[test]
    fn formats_download_progress_without_content_length() {
        assert_eq!(
            format_download_progress(2 * 1024 * 1024, None),
            "2.0 MiB downloaded"
        );
    }

    #[test]
    fn buckets_plain_download_progress_by_quarters_when_total_is_known() {
        assert_eq!(download_plain_bucket(0, Some(100)), 0);
        assert_eq!(download_plain_bucket(24, Some(100)), 0);
        assert_eq!(download_plain_bucket(25, Some(100)), 1);
        assert_eq!(download_plain_bucket(50, Some(100)), 2);
        assert_eq!(download_plain_bucket(75, Some(100)), 3);
        assert_eq!(download_plain_bucket(100, Some(100)), 4);
        assert_eq!(download_plain_bucket(200, Some(100)), 4);
    }

    #[test]
    fn buckets_plain_download_progress_by_size_when_total_is_unknown() {
        assert_eq!(download_plain_bucket(0, None), 0);
        assert_eq!(download_plain_bucket(5 * 1024 * 1024 - 1, None), 0);
        assert_eq!(download_plain_bucket(5 * 1024 * 1024, None), 1);
    }

    #[test]
    fn disabled_reporter_writes_nothing() {
        let mut reporter = ProgressReporter::new(ProgressMode::Disabled, Vec::new());
        reporter.download("0.5.1-rc.1", 1024, Some(2048));
        reporter.finish_download("0.5.1-rc.1");
        reporter.install_tick("0.5.1-rc.1");
        reporter.finish_install("0.5.1-rc.1");
        reporter.finish_with_error();
        assert!(reporter.into_inner().is_empty());
    }

    #[test]
    fn plain_reporter_writes_stage_lines() {
        let mut reporter = ProgressReporter::new(ProgressMode::Plain, Vec::new());
        reporter.download("0.5.1-rc.1", 0, Some(100));
        reporter.download("0.5.1-rc.1", 10, Some(100));
        reporter.download("0.5.1-rc.1", 25, Some(100));
        reporter.finish_download("0.5.1-rc.1");
        reporter.install_tick("0.5.1-rc.1");
        reporter.install_tick("0.5.1-rc.1");
        reporter.finish_install("0.5.1-rc.1");

        let output = String::from_utf8(reporter.into_inner()).expect("utf8 output");
        assert_eq!(
            output,
            concat!(
                "Downloading APK 0.5.1-rc.1: 0% (0 B/100 B)\n",
                "Downloading APK 0.5.1-rc.1: 25% (25 B/100 B)\n",
                "Downloaded APK 0.5.1-rc.1\n",
                "Installing APK 0.5.1-rc.1...\n",
                "Installed APK 0.5.1-rc.1\n",
            )
        );
    }

    #[test]
    fn tty_error_finishes_active_line() {
        let mut reporter = ProgressReporter::new(ProgressMode::Tty, Vec::new());
        reporter.install_tick("0.5.1-rc.1");
        reporter.finish_with_error();
        let output = String::from_utf8(reporter.into_inner()).expect("utf8 output");
        assert!(output.ends_with('\n'));
    }
}
