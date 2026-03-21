import { mkdir, writeFile } from "node:fs/promises";
import { join } from "node:path";

export interface CommandSession {
  writeInput(command: string, payload: unknown): Promise<void>;
  writeOutput(command: string, payload: unknown): Promise<void>;
  writeError(command: string, payload: unknown): Promise<void>;
}

interface AgentFsLike {
  fs?: {
    writeFile?: (path: string, content: string) => Promise<void>;
  };
  kv?: {
    set?: (key: string, value: unknown) => Promise<void>;
  };
}

export async function openSession(sessionId: string): Promise<CommandSession> {
  const normalized = sessionId.trim() || "default";
  const agent = await tryOpenAgentFs(normalized);
  if (agent) {
    return {
      writeInput: (command, payload) => persistAgentFs(agent, normalized, command, "input", payload),
      writeOutput: (command, payload) => persistAgentFs(agent, normalized, command, "output", payload),
      writeError: (command, payload) => persistAgentFs(agent, normalized, command, "error", payload),
    };
  }

  const baseDir = join(process.cwd(), ".amctl-sessions", normalized, "commands");
  await mkdir(baseDir, { recursive: true });

  return {
    writeInput: (command, payload) => persistLocal(baseDir, command, "input", payload),
    writeOutput: (command, payload) => persistLocal(baseDir, command, "output", payload),
    writeError: (command, payload) => persistLocal(baseDir, command, "error", payload),
  };
}

async function tryOpenAgentFs(sessionId: string): Promise<AgentFsLike | null> {
  try {
    const mod = (await import("agentfs-sdk")) as {
      AgentFS?: { open: (opts: { id: string }) => Promise<AgentFsLike> };
      default?: { open: (opts: { id: string }) => Promise<AgentFsLike> };
    };
    const agentFs = mod.AgentFS ?? mod.default;
    if (!agentFs?.open) {
      return null;
    }
    return await agentFs.open({ id: sessionId });
  } catch {
    return null;
  }
}

async function persistAgentFs(
  agent: AgentFsLike,
  sessionId: string,
  command: string,
  phase: "input" | "output" | "error",
  payload: unknown,
): Promise<void> {
  const ts = new Date().toISOString().replace(/[:.]/g, "-");
  const safeCmd = command.replace(/[^a-zA-Z0-9:_-]/g, "_");
  const path = `/amctl/${sessionId}/commands/${ts}_${safeCmd}_${phase}.json`;
  const body = JSON.stringify(payload, null, 2);

  if (agent.fs?.writeFile) {
    await agent.fs.writeFile(path, body);
  }

  if (agent.kv?.set) {
    await agent.kv.set(`amctl/${sessionId}/latest/${safeCmd}/${phase}`, payload);
  }
}

async function persistLocal(
  baseDir: string,
  command: string,
  phase: "input" | "output" | "error",
  payload: unknown,
): Promise<void> {
  const ts = new Date().toISOString().replace(/[:.]/g, "-");
  const safeCmd = command.replace(/[^a-zA-Z0-9:_-]/g, "_");
  const file = join(baseDir, `${ts}_${safeCmd}_${phase}.json`);
  await writeFile(file, JSON.stringify(payload, null, 2), "utf8");
}
