# AGENTS.md

## Project

Autofish is an Android device control service and CLI, targeting RhinoPi X1 (Qualcomm QCS8550 ARM64).

## Context

- Read `docs/ARCHITECTURE.md` before making architecture-impacting changes.
- Treat the current codebase and documented release workflow as the source of truth when docs drift.

## Current Baseline

- Service/API:
  - Android foreground service: `ServiceServerService`
  - HTTP surface: `/api/*`
- Execution path:
  - Preferred: Shizuku + system/shell path
  - Fallback: accessibility path
  - Routing entry: `ToolRouter`
- CLI:
  - Current CLI UX may evolve for AX when changes are documented and versioned.
- Skill packaging:
  - Canonical skill path: `cli/skills/autofish-control/SKILL.md`
  - CLI release workflow packages that exact path

## Rules

- Respond in Simplified Chinese.
- Use `just` instead of `make`.
- Do not add features that are not reflected in design or release docs.
- Hidden API reflection calls must have try-catch plus runnable fallback behavior.
- Preserve fallback execution paths when preferred capabilities are unavailable.

## Compatibility Policy

- Default to preserving external compatibility for service APIs, CLI commands, and packaged skill paths.
- Breaking changes are allowed only when they are intentional and governed.
- For breaking CLI or packaging changes, update docs in the same change set:
  - `docs/` when design or architecture intent changes
  - `CHANGELOG.md` with explicit `BREAKING` notes
  - command migration examples when flags, command shape, or workflow changes
- Breaking release cadence must follow semantic versioning discipline:
  - no silent breaking changes in patch releases
  - use prerelease tags such as `-rc` when validating release candidates

## Change Process

- If the requested change is not covered by current docs, update the relevant docs first or in the same change set.
- Internal refactors must keep behavior coherent and keep fallback paths runnable.
- CLI AX work may reshape command syntax when it materially improves usability, but the change must be documented and migration-ready.
- If the skill directory or skill filename changes, update `.github/workflows/cli-release.yml` in the same change.

## Release Notes

- CLI release tags: `cli-v*`
- App release tags: `app-v*`
- Tags containing `-rc` are prerelease channels.
- Before release, keep these aligned:
  - version files
  - README / CLI docs
  - skill packaging paths
  - changelog entries

## Quality Gates

- Before finishing a coding task, run at least:
  - `just build`
- For CLI Rust changes, also run:
  - `cargo check -q --manifest-path cli/Cargo.toml`
  - `cargo fmt --check --manifest-path cli/Cargo.toml`
  - `cargo clippy --manifest-path cli/Cargo.toml --all-targets --all-features -- -D warnings`
  - `cargo test -q --manifest-path cli/Cargo.toml`
- When logic changes are introduced, add or update minimal tests when feasible.
- Never merge changes that introduce obvious startup blocking, reliability regressions, or loss of observability.
