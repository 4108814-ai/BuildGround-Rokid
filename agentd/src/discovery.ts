import { open, readdir, stat } from "node:fs/promises";
import path from "node:path";
import type { Logger } from "./types";
import type { DiscoveredSession } from "./session-store";
import { contentText } from "./transcript";

const RECENT_WINDOW_MS = 24 * 60 * 60 * 1000;
const MAX_FILES = 200;
const TAIL_BYTES = 64 * 1024;

interface Candidate {
  filePath: string;
  projectDir: string;
  mtimeMs: number;
  size: number;
}

function objectValue(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown>
    : undefined;
}

async function inspectTail(candidate: Candidate): Promise<{ cwd?: string; title?: string }> {
  const start = Math.max(0, candidate.size - TAIL_BYTES);
  const length = candidate.size - start;
  if (length <= 0) {
    return {};
  }
  const handle = await open(candidate.filePath, "r");
  let text: string;
  try {
    const buffer = Buffer.allocUnsafe(length);
    const { bytesRead } = await handle.read(buffer, 0, length, start);
    text = buffer.subarray(0, bytesRead).toString("utf8");
  } finally {
    await handle.close();
  }

  let lines = text.split(/\r?\n/);
  if (start > 0) {
    lines = lines.slice(1);
  }
  let cwd: string | undefined;
  let title: string | undefined;
  for (const line of lines) {
    if (!line) {
      continue;
    }
    try {
      const entry = objectValue(JSON.parse(line));
      if (!entry) {
        continue;
      }
      if (typeof entry.cwd === "string" && entry.cwd) {
        cwd = entry.cwd;
      }
      const message = objectValue(entry.message);
      const type = typeof entry.type === "string" ? entry.type.toLowerCase() : "";
      const role = typeof message?.role === "string" ? message.role.toLowerCase() : "";
      if (type === "user" || role === "user") {
        const textContent = contentText(message?.content ?? entry.content);
        if (textContent) {
          title = textContent.slice(0, 120);
        }
      }
    } catch {
      // A tail chunk may start or end in the middle of a JSONL entry.
    }
  }
  return { cwd, title };
}

export async function discoverRecentSessions(
  projectsDir: string,
  logger: Logger,
  now: number = Date.now(),
): Promise<DiscoveredSession[]> {
  const candidates: Candidate[] = [];
  let projectEntries;
  try {
    projectEntries = await readdir(projectsDir, { withFileTypes: true });
  } catch {
    return [];
  }

  for (const projectEntry of projectEntries) {
    if (!projectEntry.isDirectory()) {
      continue;
    }
    const projectDir = path.join(projectsDir, projectEntry.name);
    let files;
    try {
      files = await readdir(projectDir, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const file of files) {
      if (!file.isFile() || !file.name.endsWith(".jsonl")) {
        continue;
      }
      const filePath = path.join(projectDir, file.name);
      try {
        const fileStat = await stat(filePath);
        if (now - fileStat.mtimeMs <= RECENT_WINDOW_MS) {
          candidates.push({
            filePath,
            projectDir,
            mtimeMs: fileStat.mtimeMs,
            size: fileStat.size,
          });
        }
      } catch {
        // A transcript can disappear while discovery is running.
      }
    }
  }

  candidates.sort((left, right) => right.mtimeMs - left.mtimeMs);
  const sessions: DiscoveredSession[] = [];
  for (const candidate of candidates.slice(0, MAX_FILES)) {
    try {
      const details = await inspectTail(candidate);
      sessions.push({
        id: path.basename(candidate.filePath, ".jsonl"),
        cwd: details.cwd,
        projectDir: candidate.projectDir,
        title: details.title,
        lastActivityAt: candidate.mtimeMs,
        transcriptPath: candidate.filePath,
      });
    } catch (error) {
      logger.warn("discovery_transcript_failed", {
        reason: error instanceof Error ? error.name : "unknown",
      });
    }
  }
  return sessions;
}
