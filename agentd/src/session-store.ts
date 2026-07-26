import path from "node:path";
import type {
  AgentConfig,
  HookPayload,
  Logger,
  Session,
  TranscriptUpdate,
} from "./types";

const DONE_RETENTION_MS = 30 * 60 * 1000;
const STALLED_AFTER_MS = 30 * 60 * 1000;

interface SessionRecord {
  session: Session;
  lastUserPrompt?: string;
  transcriptPath?: string;
}

export interface DiscoveredSession {
  id: string;
  cwd?: string;
  projectDir: string;
  title?: string;
  lastActivityAt: number;
  transcriptPath?: string;
}

type UpsertListener = (session: Session) => void;
type RemovedListener = (sessionId: string) => void;

function asString(value: unknown): string | undefined {
  return typeof value === "string" && value.length > 0 ? value : undefined;
}

export function truncateText(value: string, maxLength: number): string {
  return value.replace(/\s+/g, " ").trim().slice(0, maxLength);
}

export function normalizeCwd(value: string | undefined): string {
  return value ? value.replace(/\\/g, "/") : "";
}

function basename(value: string): string {
  if (!value) {
    return "";
  }
  return path.win32.basename(value.replace(/\//g, "\\"));
}

function cloneSession(session: Session): Session {
  return {
    ...session,
    turn: session.turn ? { ...session.turn } : undefined,
    pendingRequest: session.pendingRequest ? { ...session.pendingRequest } : undefined,
  };
}

export class SessionStore {
  private readonly records = new Map<string, SessionRecord>();
  private readonly doneTimers = new Map<string, NodeJS.Timeout>();
  private readonly upsertListeners = new Set<UpsertListener>();
  private readonly removedListeners = new Set<RemovedListener>();
  private readonly now: () => number;

  constructor(
    private readonly config: Pick<AgentConfig, "machineId" | "machineName">,
    private readonly logger: Logger,
    now: () => number = Date.now,
  ) {
    this.now = now;
  }

  get size(): number {
    return this.records.size;
  }

  list(): Session[] {
    return [...this.records.values()]
      .map((record) => cloneSession(record.session))
      .sort((left, right) => right.lastActivityAt - left.lastActivityAt);
  }

  get(sessionId: string): Session | undefined {
    const record = this.records.get(sessionId);
    return record ? cloneSession(record.session) : undefined;
  }

  transcriptPath(sessionId: string): string | undefined {
    return this.records.get(sessionId)?.transcriptPath;
  }

  onUpsert(listener: UpsertListener): () => void {
    this.upsertListeners.add(listener);
    return () => this.upsertListeners.delete(listener);
  }

  onRemoved(listener: RemovedListener): () => void {
    this.removedListeners.add(listener);
    return () => this.removedListeners.delete(listener);
  }

  addDiscovered(discovered: DiscoveredSession): void {
    if (this.records.has(discovered.id)) {
      return;
    }
    const cwd = normalizeCwd(discovered.cwd);
    const project = basename(cwd) || basename(discovered.projectDir);
    const session: Session = {
      id: discovered.id,
      provider: "claude",
      machineId: this.config.machineId,
      machineName: this.config.machineName,
      title: truncateText(discovered.title || project || discovered.id.slice(0, 8), 120),
      cwd,
      project,
      status: "idle",
      stale: true,
      lastActivityAt: discovered.lastActivityAt,
    };
    // Keep the transcript path even while stale: the wearer can open the
    // conversation of a session that has not emitted a hook event yet.
    this.records.set(discovered.id, { session, transcriptPath: discovered.transcriptPath });
    this.emitUpsert(session);
  }

  handleHook(payload: HookPayload): void {
    const eventName = asString(payload.hook_event_name);
    const sessionId = asString(payload.session_id);
    if (!eventName) {
      this.logger.warn("hook_missing_event");
      return;
    }
    if (!sessionId) {
      this.logger.warn("hook_missing_session", { eventName });
      return;
    }

    const existing = this.records.get(sessionId);
    const canCreate = new Set([
      "SessionStart",
      "UserPromptSubmit",
      "Stop",
      "Notification",
      "SessionEnd",
    ]).has(eventName);
    if (!existing && !canCreate) {
      this.logger.info("hook_ignored_unknown_session", { eventName });
      return;
    }

    const now = this.now();
    const cwdFromHook = asString(payload.cwd);
    const transcriptPath = asString(payload.transcript_path);
    const record = existing ?? this.createRecord(sessionId, cwdFromHook, now);
    this.cancelDoneRemoval(sessionId);
    record.session.stale = false;
    record.session.lastActivityAt = now;
    if (cwdFromHook) {
      record.session.cwd = normalizeCwd(cwdFromHook);
      record.session.project = basename(record.session.cwd);
    }
    if (transcriptPath) {
      record.transcriptPath = transcriptPath;
    }

    switch (eventName) {
      case "SessionStart":
        record.session.status = "idle";
        record.session.statusDetail = undefined;
        record.session.pendingRequest = undefined;
        break;
      case "UserPromptSubmit": {
        const prompt = asString(payload.prompt);
        if (prompt) {
          record.lastUserPrompt = truncateText(prompt, 120);
          record.session.title = record.lastUserPrompt || record.session.title;
        }
        record.session.status = "working";
        record.session.statusDetail = undefined;
        record.session.pendingRequest = undefined;
        record.session.turn = { activeSince: now };
        break;
      }
      case "Stop":
        if (record.session.pendingRequest) {
          record.session.status = "needs_you";
        } else {
          record.session.status = "idle";
          record.session.statusDetail = undefined;
        }
        break;
      case "SubagentStop":
        break;
      case "PreCompact":
      case "PostToolUse":
        break;
      case "Notification":
        this.applyNotification(record, asString(payload.message), now);
        break;
      case "SessionEnd":
        record.session.status = "done";
        record.session.statusDetail = undefined;
        record.session.pendingRequest = undefined;
        this.scheduleDoneRemoval(sessionId);
        break;
      default:
        this.logger.info("hook_unknown_event", { eventName });
        break;
    }

    this.refreshDerivedTitle(record);
    this.emitUpsert(record.session);
  }

  applyTranscriptUpdate(sessionId: string, update: TranscriptUpdate): void {
    const record = this.records.get(sessionId);
    if (!record || record.session.status === "done" || record.session.stale) {
      return;
    }

    record.session.lastActivityAt = Math.max(record.session.lastActivityAt, update.activityAt);
    if (update.lastAssistantText) {
      record.session.lastAssistantText = truncateText(update.lastAssistantText, 140);
    }
    if (update.lastTool) {
      record.session.turn = {
        ...record.session.turn,
        lastTool: truncateText(update.lastTool, 140),
        activeSince: record.session.turn?.activeSince ?? update.activityAt,
      };
    }
    if (update.error) {
      record.session.status = "error";
      record.session.statusDetail = truncateText(update.error, 140);
    }
    this.emitUpsert(record.session);
  }

  sweepStalled(now = this.now()): void {
    for (const record of this.records.values()) {
      if (
        record.session.status === "working" &&
        now - record.session.lastActivityAt > STALLED_AFTER_MS
      ) {
        record.session.status = "error";
        record.session.statusDetail = "stalled?";
        this.emitUpsert(record.session);
      }
    }
  }

  removeSession(sessionId: string): void {
    if (!this.records.delete(sessionId)) {
      return;
    }
    this.cancelDoneRemoval(sessionId);
    for (const listener of this.removedListeners) {
      listener(sessionId);
    }
  }

  dispose(): void {
    for (const timer of this.doneTimers.values()) {
      clearTimeout(timer);
    }
    this.doneTimers.clear();
    this.upsertListeners.clear();
    this.removedListeners.clear();
  }

  private createRecord(sessionId: string, cwdValue: string | undefined, now: number): SessionRecord {
    const cwd = normalizeCwd(cwdValue);
    const project = basename(cwd);
    const record: SessionRecord = {
      session: {
        id: sessionId,
        provider: "claude",
        machineId: this.config.machineId,
        machineName: this.config.machineName,
        title: project || sessionId.slice(0, 8),
        cwd,
        project,
        status: "idle",
        stale: false,
        lastActivityAt: now,
      },
    };
    this.records.set(sessionId, record);
    return record;
  }

  private applyNotification(record: SessionRecord, message: string | undefined, now: number): void {
    if (!message) {
      this.logger.info("notification_unclassified", { reason: "missing_message" });
      return;
    }
    const lower = message.toLowerCase();
    const kind = lower.includes("permission")
      ? "permission"
      : lower.includes("waiting for your input") || lower.includes("idle")
        ? "idle_prompt"
        : undefined;
    if (!kind) {
      this.logger.info("notification_unclassified", { messageLength: message.length });
      return;
    }
    record.session.status = "needs_you";
    record.session.statusDetail = undefined;
    record.session.pendingRequest = {
      kind,
      summary: message,
      createdAt: now,
    };
  }

  private refreshDerivedTitle(record: SessionRecord): void {
    record.session.title =
      record.lastUserPrompt ||
      basename(record.session.cwd) ||
      record.session.id.slice(0, 8);
  }

  private emitUpsert(session: Session): void {
    const snapshot = cloneSession(session);
    for (const listener of this.upsertListeners) {
      listener(snapshot);
    }
  }

  private scheduleDoneRemoval(sessionId: string): void {
    this.cancelDoneRemoval(sessionId);
    const timer = setTimeout(() => {
      this.doneTimers.delete(sessionId);
      this.removeSession(sessionId);
    }, DONE_RETENTION_MS);
    timer.unref();
    this.doneTimers.set(sessionId, timer);
  }

  private cancelDoneRemoval(sessionId: string): void {
    const timer = this.doneTimers.get(sessionId);
    if (timer) {
      clearTimeout(timer);
      this.doneTimers.delete(sessionId);
    }
  }
}
