#!/usr/bin/env node
import { Command } from "commander";
import { ZodError } from "zod";
import { CliError } from "./errors.js";
import { fail, ok } from "./output.js";
import type { GlobalOptions } from "./context.js";
import { runPreflight } from "./commands/preflight.js";
import { runAct } from "./commands/act.js";
import { runObserve } from "./commands/observe.js";
import { runVerify } from "./commands/verify.js";
import { runRecover } from "./commands/recover.js";
import { openSession } from "./session.js";

const program = new Command();

program
  .name("amc")
  .description("Deterministic executor for amctl REST API")
  .option("--base-url <url>", "amctl REST base URL", process.env.AMCTL_BASE_URL ?? "http://127.0.0.1:8081")
  .option("--token <token>", "Bearer token (or AMCTL_TOKEN env)", process.env.AMCTL_TOKEN)
  .option("--timeout-ms <ms>", "HTTP timeout in milliseconds", numberParser, 10000)
  .option("--session <id>", "agentfs session id", process.env.AMCTL_SESSION ?? "default");

program
  .command("preflight")
  .description("Check health and authentication readiness")
  .action(async () => {
    await executeCommand("preflight", {}, () => runPreflight(globalOptions()));
  });

program
  .command("act:tap")
  .requiredOption("--x <number>", "x", numberParser)
  .requiredOption("--y <number>", "y", numberParser)
  .action(async (opts: { x: number; y: number }) => {
    await executeCommand("act:tap", opts, () => runAct(globalOptions(), { type: "tap", x: opts.x, y: opts.y }));
  });

program
  .command("act:swipe")
  .requiredOption("--x1 <number>", "x1", numberParser)
  .requiredOption("--y1 <number>", "y1", numberParser)
  .requiredOption("--x2 <number>", "x2", numberParser)
  .requiredOption("--y2 <number>", "y2", numberParser)
  .option("--duration <ms>", "duration ms", numberParser, 300)
  .action(async (opts: { x1: number; y1: number; x2: number; y2: number; duration: number }) => {
    await executeCommand("act:swipe", opts, () => runAct(globalOptions(), { type: "swipe", ...opts }));
  });

program.command("act:back").action(async () => {
  await executeCommand("act:back", {}, () => runAct(globalOptions(), { type: "back" }));
});

program.command("act:home").action(async () => {
  await executeCommand("act:home", {}, () => runAct(globalOptions(), { type: "home" }));
});

program
  .command("act:text")
  .requiredOption("--text <text>", "text to input")
  .action(async (opts: { text: string }) => {
    await executeCommand("act:text", opts, () => runAct(globalOptions(), { type: "text", text: opts.text }));
  });

program
  .command("act:launch")
  .requiredOption("--package <name>", "package name")
  .action(async (opts: { package: string }) => {
    await executeCommand("act:launch", opts, () => runAct(globalOptions(), { type: "launch", packageName: opts.package }));
  });

program
  .command("act:stop")
  .requiredOption("--package <name>", "package name")
  .action(async (opts: { package: string }) => {
    await executeCommand("act:stop", opts, () => runAct(globalOptions(), { type: "stop", packageName: opts.package }));
  });

program.command("observe:screen").action(async () => {
  await executeCommand("observe:screen", {}, () => runObserve(globalOptions(), { type: "screen" }));
});

program
  .command("observe:screenshot")
  .option("--max-dim <n>", "max dimension", numberParser, 700)
  .option("--quality <n>", "jpeg quality", numberParser, 80)
  .action(async (opts: { maxDim: number; quality: number }) => {
    await executeCommand("observe:screenshot", opts, () =>
      runObserve(globalOptions(), { type: "screenshot", maxDim: opts.maxDim, quality: opts.quality }),
    );
  });

program.command("observe:top").action(async () => {
  await executeCommand("observe:top", {}, () => runObserve(globalOptions(), { type: "top" }));
});

program
  .command("verify:text-contains")
  .requiredOption("--text <text>", "text to search")
  .option("--ignore-case", "ignore case", true)
  .action(async (opts: { text: string; ignoreCase: boolean }) => {
    await executeCommand("verify:text-contains", opts, () =>
      runVerify(globalOptions(), { type: "text-contains", text: opts.text, ignoreCase: opts.ignoreCase }),
    );
  });

program
  .command("verify:top-activity")
  .requiredOption("--expected <activity>", "expected activity/package")
  .option("--mode <mode>", "contains|equals", "contains")
  .action(async (opts: { expected: string; mode: "contains" | "equals" }) => {
    await executeCommand("verify:top-activity", opts, () =>
      runVerify(globalOptions(), { type: "top-activity", expected: opts.expected, mode: opts.mode }),
    );
  });

program
  .command("verify:node-exists")
  .requiredOption("--by <by>", "id|text|desc|class|resource_id")
  .requiredOption("--value <value>", "match value")
  .option("--exact-match", "exact match", false)
  .action(async (opts: { by: "id" | "text" | "desc" | "class" | "resource_id"; value: string; exactMatch: boolean }) => {
    await executeCommand("verify:node-exists", opts, () =>
      runVerify(globalOptions(), { type: "node-exists", by: opts.by, value: opts.value, exactMatch: opts.exactMatch }),
    );
  });

program
  .command("recover:back")
  .option("--times <n>", "back times", numberParser, 1)
  .action(async (opts: { times: number }) => {
    await executeCommand("recover:back", opts, () => runRecover(globalOptions(), { type: "back", times: opts.times }));
  });

program.command("recover:home").action(async () => {
  await executeCommand("recover:home", {}, () => runRecover(globalOptions(), { type: "home" }));
});

program
  .command("recover:relaunch")
  .requiredOption("--package <name>", "package name")
  .action(async (opts: { package: string }) => {
    await executeCommand("recover:relaunch", opts, () =>
      runRecover(globalOptions(), { type: "relaunch", packageName: opts.package }),
    );
  });

program.parseAsync(process.argv).catch(async (err: unknown) => {
  const normalized = normalizeError(err);
  try {
    const opts = globalOptions();
    const session = await openSession(opts.sessionId);
    await session.writeError("parse", {
      command: "parse",
      error: normalized.message,
      code: normalized instanceof CliError ? normalized.code : "INTERNAL_ERROR",
      timestamp: new Date().toISOString(),
    });
  } catch {
    // ignore secondary session errors
  }
  fail("parse", normalized);
});

async function executeCommand(
  command: string,
  args: unknown,
  runner: () => Promise<Record<string, unknown>>,
): Promise<never> {
  const opts = globalOptions();
  const session = await openSession(opts.sessionId);
  const redacted = redactOptions(opts);

  await session.writeInput(command, {
    command,
    args,
    options: redacted,
    timestamp: new Date().toISOString(),
  });

  try {
    const result = await runner();
    await session.writeOutput(command, {
      command,
      result,
      timestamp: new Date().toISOString(),
    });
    ok(command, result);
  } catch (err) {
    const normalized = normalizeError(err);
    await session.writeError(command, {
      command,
      error: normalized.message,
      code: normalized instanceof CliError ? normalized.code : "INTERNAL_ERROR",
      timestamp: new Date().toISOString(),
    });
    fail(command, normalized);
  }
}

function globalOptions(): GlobalOptions {
  const opts = program.opts<{
    baseUrl: string;
    token?: string;
    timeoutMs: number;
    session: string;
  }>();

  return {
    baseUrl: opts.baseUrl,
    token: opts.token,
    timeoutMs: opts.timeoutMs,
    sessionId: opts.session,
  };
}

function numberParser(v: string): number {
  const n = Number(v);
  if (!Number.isFinite(n)) {
    throw new CliError("INVALID_PARAMS", `Invalid number: ${v}`);
  }
  return n;
}

function normalizeError(err: unknown): Error {
  if (err instanceof CliError) return err;
  if (err instanceof ZodError) {
    return new CliError("INVALID_PARAMS", err.issues.map((it) => `${it.path.join(".")}: ${it.message}`).join("; "));
  }
  if (err instanceof Error) return err;
  return new Error(String(err));
}

function redactOptions(options: GlobalOptions): Omit<GlobalOptions, "token"> & { token: string | undefined } {
  return {
    baseUrl: options.baseUrl,
    timeoutMs: options.timeoutMs,
    sessionId: options.sessionId,
    token: options.token ? "***" : undefined,
  };
}
