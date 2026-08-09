import { spawn, type ChildProcess, type SpawnOptions } from "node:child_process";
import { accessSync, constants, statSync } from "node:fs";
import path from "node:path";
import type { ThreadStartResult } from "./types";

export type SpawnProcess = (
  command: string,
  args: string[],
  options: SpawnOptions,
) => ChildProcess;

export type LocateClaude = () => boolean;

export function claudeOnPath(
  platform: NodeJS.Platform = process.platform,
  environment: NodeJS.ProcessEnv = process.env,
): boolean {
  const pathValue = environment.PATH ?? environment.Path ?? "";
  const candidates = platform === "win32" ? ["claude.exe", "claude.cmd"] : ["claude"];
  const accessMode = platform === "win32" ? constants.F_OK : constants.X_OK;
  const directories = pathValue.split(platform === "win32" ? ";" : path.delimiter);
  for (const candidate of candidates) {
    for (const rawDirectory of directories) {
      const directory = rawDirectory.trim().replace(/^"|"$/g, "");
      if (!directory) {
        continue;
      }
      try {
        const candidatePath = path.join(directory, candidate);
        accessSync(candidatePath, accessMode);
        if (statSync(candidatePath).isFile()) {
          return true;
        }
      } catch {
        // Match PATH lookup: inaccessible and absent entries are skipped.
      }
    }
  }
  return false;
}

export function createClaudeSpawner(
  spawnProcess: SpawnProcess = spawn,
  platform: NodeJS.Platform = process.platform,
  locateClaude: LocateClaude = () => claudeOnPath(platform),
): (cwd: string, prompt: string) => Promise<ThreadStartResult> {
  let cliAvailable: boolean | undefined;

  return async (cwd, prompt) => {
    if (cliAvailable === undefined) {
      cliAvailable = locateClaude();
    }
    if (!cliAvailable) {
      return cliNotFound();
    }

    const command = platform === "win32" ? "cmd.exe" : "claude";
    const args = platform === "win32" ? ["/d", "/s", "/c", "claude -p"] : ["-p"];
    let child: ChildProcess;
    try {
      child = spawnProcess(command, args, {
        cwd,
        stdio: ["pipe", "ignore", "ignore"],
        detached: true,
        windowsHide: platform === "win32",
        shell: false,
      });
    } catch (error) {
      if (errorCode(error) === "ENOENT") {
        cliAvailable = false;
        return cliNotFound();
      }
      return { ok: false, error: "Unable to start Claude Code" };
    }

    return new Promise<ThreadStartResult>((resolve) => {
      let settled = false;
      const finish = (result: ThreadStartResult) => {
        if (!settled) {
          settled = true;
          resolve(result);
        }
      };
      child.once("error", (error) => {
        if (errorCode(error) === "ENOENT") {
          cliAvailable = false;
          finish(cliNotFound());
        } else {
          finish({ ok: false, error: "Unable to start Claude Code" });
        }
      });
      child.once("spawn", () => {
        child.unref();
        finish({ ok: true });
      });
      child.stdin?.on("error", () => undefined);
      child.stdin?.end(prompt);
    });
  };
}

function errorCode(error: unknown): unknown {
  return error && typeof error === "object" && "code" in error
    ? (error as { code?: unknown }).code
    : undefined;
}

function cliNotFound(): ThreadStartResult {
  return { ok: false, error: "Claude Code CLI not found on this computer" };
}

export const spawnClaudeThread = createClaudeSpawner();
