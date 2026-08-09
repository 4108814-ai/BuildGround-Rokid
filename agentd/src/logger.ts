import {
  appendFileSync,
  existsSync,
  mkdirSync,
  renameSync,
  statSync,
  unlinkSync,
} from "node:fs";
import path from "node:path";
import type { Logger, LogMeta } from "./types";

const MAX_LOG_BYTES = 5 * 1024 * 1024;

function safeMeta(meta: LogMeta | undefined): string {
  if (!meta || Object.keys(meta).length === 0) {
    return "";
  }
  try {
    return ` ${JSON.stringify(meta)}`;
  } catch {
    return " {\"meta\":\"unserializable\"}";
  }
}

export class FileLogger implements Logger {
  private readonly filePath: string;
  private readonly oldPath: string;

  constructor(stateDir: string) {
    mkdirSync(stateDir, { recursive: true });
    this.filePath = path.join(stateDir, "agentd.log");
    this.oldPath = `${this.filePath}.old`;
  }

  info(event: string, meta?: LogMeta): void {
    this.write("info", event, meta);
  }

  warn(event: string, meta?: LogMeta): void {
    this.write("warn", event, meta);
  }

  error(event: string, meta?: LogMeta): void {
    this.write("error", event, meta);
  }

  private write(level: string, event: string, meta?: LogMeta): void {
    try {
      if (existsSync(this.filePath) && statSync(this.filePath).size >= MAX_LOG_BYTES) {
        if (existsSync(this.oldPath)) {
          unlinkSync(this.oldPath);
        }
        renameSync(this.filePath, this.oldPath);
      }
      appendFileSync(
        this.filePath,
        `${new Date().toISOString()} ${level} ${event}${safeMeta(meta)}\n`,
        "utf8",
      );
    } catch {
      // Logging must never stop hook ingestion or the daemon.
    }
  }
}

export const silentLogger: Logger = {
  info() {},
  warn() {},
  error() {},
};
