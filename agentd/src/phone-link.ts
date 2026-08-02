import { createSocket, type Socket as UdpSocket } from "node:dgram";
import { networkInterfaces } from "node:os";
import { connect, type Socket as TcpSocket } from "node:net";
import type {
  ApprovalDecision,
  ApprovalOutcome,
  ApprovalRequest,
} from "./approval-manager";
import { parsePhoneTarget } from "./config";
import type { SessionStore } from "./session-store";
import type { AgentConfig, Logger, Session, SessionMessage } from "./types";

/**
 * Outbound link to the phone.
 *
 * The daemon is the CLIENT here even though it owns the data: Windows blocks
 * unsolicited inbound connections unless an administrator adds a firewall rule,
 * while outbound connections and the unicast replies to a broadcast we just sent
 * are allowed by default. Android has no such filter, so the phone listens and
 * the PC dials — no rule, no cable, nothing to configure.
 */
export const DISCOVERY_PORT = 8793;
const DISCOVERY_INTERVAL_MS = 2000;
const RECONNECT_DELAY_MS = 3000;
export const PHONE_HELLO_TIMEOUT_MS = 15_000;
export const REFUSAL_RECONNECT_DELAY_MS = 60_000;
const DETAIL_MESSAGE_LIMIT = 40;
const MAX_LINE_BYTES = 512 * 1024;
export const MAX_PHONE_SESSIONS_PER_PROVIDER = 200;

export interface PhoneLinkOptions {
  config: AgentConfig;
  store: SessionStore;
  logger: Logger;
  detailProvider: (sessionId: string, limit: number) => Promise<SessionMessage[]>;
  onDetailOpen?: (sessionId: string) => void;
  onApprovalDecision?: (requestId: string, decision: ApprovalDecision) => void;
  onConnected?: () => void;
  onDisconnected?: () => void;
  operatorMessage?: (message: string) => void;
  discoveryPort?: number;
  helloTimeoutMs?: number;
  reconnectDelayMs?: number;
  now?: () => number;
}

interface PhoneAnnouncement {
  host: string;
  port: number;
  name: string;
}

interface RejectedPhone {
  retryAt: number;
  reportedReason: string;
}

function parseAnnouncement(raw: Buffer, host: string): PhoneAnnouncement | undefined {
  try {
    const value: unknown = JSON.parse(raw.toString("utf8"));
    if (!value || typeof value !== "object") {
      return undefined;
    }
    const record = value as Record<string, unknown>;
    if (record.nexus !== "agents-phone") {
      return undefined;
    }
    const port = typeof record.port === "number" ? record.port : undefined;
    if (!port || port < 1 || port > 65535) {
      return undefined;
    }
    return {
      host,
      port,
      name: typeof record.name === "string" ? record.name.slice(0, 64) : "phone",
    };
  } catch {
    return undefined;
  }
}

/** Broadcast addresses of every IPv4 interface, plus the global fallback. */
function broadcastTargets(): string[] {
  const targets = new Set<string>(["255.255.255.255"]);
  for (const addresses of Object.values(networkInterfaces())) {
    for (const address of addresses ?? []) {
      if (address.family !== "IPv4" || address.internal || !address.netmask) {
        continue;
      }
      const ip = address.address.split(".").map(Number);
      const mask = address.netmask.split(".").map(Number);
      if (ip.length !== 4 || mask.length !== 4 || ip.some(isNaN) || mask.some(isNaN)) {
        continue;
      }
      targets.add(ip.map((part, index) => part | (~mask[index] & 0xff)).join("."));
    }
  }
  return [...targets];
}

export class PhoneLink {
  private discovery?: UdpSocket;
  private socket?: TcpSocket;
  private discoveryTimer?: NodeJS.Timeout;
  private reconnectTimer?: NodeJS.Timeout;
  private helloTimer?: NodeJS.Timeout;
  private buffer = "";
  private sequence = 0;
  private openSessionId?: string;
  private connectedTo?: string;
  private phoneName?: string;
  private authenticated = false;
  private stopped = false;
  private unsubscribe?: () => void;
  private readonly rejectedPhones = new Map<string, RejectedPhone>();
  private readonly retryAt = new Map<string, number>();
  private readonly now: () => number;
  private publishedSessions = new Map<string, Session>();

  constructor(private readonly options: PhoneLinkOptions) {
    this.now = options.now ?? Date.now;
  }

  start(): void {
    this.stopped = false;
    const socket = createSocket({ type: "udp4", reuseAddr: true });
    this.discovery = socket;
    socket.on("error", (error) => {
      this.options.logger.warn("discovery_socket_error", { reason: error.name });
    });
    socket.on("message", (message, remote) => {
      if (this.socket) {
        return;
      }
      const announcement = parseAnnouncement(message, remote.address);
      if (announcement) {
        this.connectToPhone(announcement);
      }
    });
    socket.bind(() => {
      socket.setBroadcast(true);
      this.announce();
      this.discoveryTimer = setInterval(() => this.announce(), DISCOVERY_INTERVAL_MS);
      this.discoveryTimer.unref();
    });
  }

  stop(): void {
    this.stopped = true;
    if (this.discoveryTimer) {
      clearInterval(this.discoveryTimer);
      this.discoveryTimer = undefined;
    }
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = undefined;
    }
    this.clearHelloTimer();
    this.unsubscribe?.();
    this.unsubscribe = undefined;
    this.socket?.destroy();
    this.socket = undefined;
    this.authenticated = false;
    this.publishedSessions.clear();
    this.discovery?.close();
    this.discovery = undefined;
  }

  get connected(): boolean {
    return this.authenticated && this.socket !== undefined && !this.socket.destroyed;
  }

  sendApprovalRequest(request: ApprovalRequest): boolean {
    return this.send({ ...request });
  }

  sendApprovalResolved(requestId: string, outcome: ApprovalOutcome): boolean {
    return this.send({ type: "approval_resolved", v: 1, requestId, outcome });
  }

  /** Streams an appended conversation message if the phone is reading that session. */
  sendDetailMessage(sessionId: string, message: SessionMessage): void {
    if (this.openSessionId === sessionId) {
      this.send({ type: "detail_append", sessionId, message });
    }
  }

  private announce(): void {
    if (this.socket || !this.discovery) {
      return;
    }
    const payload = Buffer.from(
      JSON.stringify({
        nexus: "agentd",
        v: 1,
        machineId: this.options.config.machineId,
        machineName: this.options.config.machineName,
      }),
    );
    const port = this.options.discoveryPort ?? DISCOVERY_PORT;
    for (const target of broadcastTargets()) {
      this.discovery.send(payload, port, target, (error) => {
        if (error) {
          this.options.logger.warn("discovery_send_failed", { target, reason: error.name });
        }
      });
    }
    for (const configuredTarget of this.options.config.phoneHosts) {
      const target = parsePhoneTarget(configuredTarget);
      if (target) {
        this.connectToPhone({ ...target, name: target.host });
      }
      if (this.socket) {
        break;
      }
    }
  }

  private connectToPhone(announcement: PhoneAnnouncement): void {
    if (this.socket || this.stopped) {
      return;
    }
    const target = `${announcement.host}:${announcement.port}`;
    const now = this.now();
    if ((this.rejectedPhones.get(target)?.retryAt ?? 0) > now) {
      return;
    }
    if ((this.retryAt.get(target) ?? 0) > now) {
      return;
    }
    const socket = connect({ host: announcement.host, port: announcement.port });
    this.socket = socket;
    this.connectedTo = target;
    this.phoneName = announcement.name;
    this.authenticated = false;
    socket.setNoDelay(true);
    socket.setKeepAlive(true, 30_000);
    socket.on("connect", () => {
      this.helloTimer = setTimeout(
        () => this.onHelloTimeout(socket),
        this.options.helloTimeoutMs ?? PHONE_HELLO_TIMEOUT_MS,
      );
      this.helloTimer.unref();
      this.write({
        type: "hello",
        v: 1,
        machineId: this.options.config.machineId,
        machineName: this.options.config.machineName,
        token: this.options.config.token,
      });
    });
    socket.on("data", (chunk) => this.onData(chunk));
    socket.on("error", (error) => {
      this.options.logger.warn("phone_link_error", { reason: error.name });
    });
    socket.on("close", () => this.onClose());
  }

  private subscribe(): () => void {
    const upsert = this.options.store.onUpsert((session) => this.reconcileSessions(session.id));
    const removed = this.options.store.onRemoved((sessionId) => this.reconcileSessions(sessionId));
    return () => {
      upsert();
      removed();
    };
  }

  private onClose(): void {
    const wasAuthenticated = this.authenticated;
    const target = this.connectedTo;
    this.clearHelloTimer();
    this.unsubscribe?.();
    this.unsubscribe = undefined;
    this.socket = undefined;
    this.authenticated = false;
    this.publishedSessions.clear();
    this.buffer = "";
    this.openSessionId = undefined;
    this.connectedTo = undefined;
    this.phoneName = undefined;
    if (target && !this.rejectedPhones.has(target)) {
      this.retryAt.set(target, this.now() + (this.options.reconnectDelayMs ?? RECONNECT_DELAY_MS));
    }
    if (wasAuthenticated && target) {
      this.options.logger.info("phone_link_closed", { target });
      this.options.onDisconnected?.();
    }
    if (!this.stopped) {
      // Discovery keeps running; give the phone a moment before dialling again.
      if (this.reconnectTimer) {
        clearTimeout(this.reconnectTimer);
      }
      this.reconnectTimer = setTimeout(
        () => this.announce(),
        this.options.reconnectDelayMs ?? RECONNECT_DELAY_MS,
      );
      this.reconnectTimer.unref();
    }
  }

  private onData(chunk: Buffer): void {
    this.buffer += chunk.toString("utf8");
    if (this.buffer.length > MAX_LINE_BYTES) {
      this.options.logger.warn("phone_link_flood");
      this.socket?.destroy();
      return;
    }
    let newline = this.buffer.indexOf("\n");
    while (newline >= 0) {
      const line = this.buffer.slice(0, newline).trim();
      this.buffer = this.buffer.slice(newline + 1);
      if (line) {
        this.handleLine(line);
      }
      newline = this.buffer.indexOf("\n");
    }
  }

  private handleLine(line: string): void {
    let message: Record<string, unknown>;
    try {
      const parsed: unknown = JSON.parse(line);
      if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
        return;
      }
      message = parsed as Record<string, unknown>;
    } catch {
      return;
    }
    switch (message.type) {
      case "hello_ack":
        this.onHelloAck();
        break;
      case "hello_reject":
        this.onHelloReject(message.reason);
        break;
      default:
        if (!this.authenticated) {
          return;
        }
        this.handleAuthenticatedMessage(message);
        break;
    }
  }

  private handleAuthenticatedMessage(message: Record<string, unknown>): void {
    switch (message.type) {
      case "refresh":
        this.sendSnapshot();
        break;
      case "detail_open": {
        const sessionId = typeof message.sessionId === "string" ? message.sessionId : undefined;
        if (!sessionId) {
          break;
        }
        this.openSessionId = sessionId;
        this.options.logger.info("phone_link_detail_open", { sessionId: sessionId.slice(0, 8) });
        this.options.onDetailOpen?.(sessionId);
        void this.sendDetail(sessionId);
        break;
      }
      case "detail_close":
        this.openSessionId = undefined;
        break;
      case "ping":
        this.send({ type: "pong", t: message.t });
        break;
      case "approval_decision": {
        const requestId =
          typeof message.requestId === "string" && message.requestId.length > 0
            ? message.requestId
            : undefined;
        const decision =
          message.decision === "allow" || message.decision === "deny"
            ? message.decision
            : undefined;
        if (requestId && decision) {
          this.options.onApprovalDecision?.(requestId, decision);
        }
        break;
      }
      default:
        break;
    }
  }

  private onHelloAck(): void {
    if (this.authenticated || !this.socket || this.socket.destroyed) {
      return;
    }
    this.authenticated = true;
    this.clearHelloTimer();
    if (this.connectedTo) {
      this.rejectedPhones.delete(this.connectedTo);
      this.retryAt.delete(this.connectedTo);
    }
    this.options.logger.info("phone_link_connected", {
      phone: this.phoneName ?? "phone",
      target: this.connectedTo ?? "unknown",
    });
    this.sendSnapshot();
    this.unsubscribe = this.subscribe();
    this.options.onConnected?.();
  }

  private onHelloReject(rawReason: unknown): void {
    if (this.authenticated || !this.connectedTo) {
      return;
    }
    const reason = rawReason === "bad_token" ? "bad_token" : "unknown_machine";
    const reportedReason = this.reasonKey(rawReason);
    const previous = this.rejectedPhones.get(this.connectedTo);
    this.rejectedPhones.set(this.connectedTo, {
      retryAt: this.now() + REFUSAL_RECONNECT_DELAY_MS,
      reportedReason,
    });

    if (!previous || previous.reportedReason !== reportedReason) {
      if (rawReason !== "unknown_machine" && rawReason !== "bad_token") {
        this.options.logger.warn("phone_link_reject_unknown_reason", {
          reason: rawReason,
          target: this.connectedTo,
        });
      }
      const message =
        reason === "bad_token"
          ? "The phone refused this computer because its saved token no longer matches, usually because the daemon identity was regenerated. On the phone, use Forget computers to clear it, then open Agents \u2192 Link a computer and start the daemon again."
          : "The phone refused this computer because it is not linked and the linking window is closed. On the phone, open Agents \u2192 Link a computer, then start the daemon again.";
      this.options.logger.warn("phone_link_rejected", {
        reason,
        rawReason,
        target: this.connectedTo,
        message,
      });
      this.tellOperator(message);
    }
    this.socket?.destroy();
  }

  private onHelloTimeout(socket: TcpSocket): void {
    if (this.socket !== socket || this.authenticated) {
      return;
    }
    this.options.logger.warn("phone_link_hello_timeout", {
      target: this.connectedTo ?? "unknown",
      timeoutMs: this.options.helloTimeoutMs ?? PHONE_HELLO_TIMEOUT_MS,
    });
    socket.destroy();
  }

  private clearHelloTimer(): void {
    if (this.helloTimer) {
      clearTimeout(this.helloTimer);
      this.helloTimer = undefined;
    }
  }

  private tellOperator(message: string): void {
    if (this.options.operatorMessage) {
      this.options.operatorMessage(message);
      return;
    }
    try {
      process.stderr.write(`nexus-agentd: ${message}\n`);
    } catch {
      // The durable log still contains the full operator guidance.
    }
  }

  private reasonKey(reason: unknown): string {
    try {
      return JSON.stringify(reason);
    } catch {
      return String(reason);
    }
  }

  private async sendDetail(sessionId: string): Promise<void> {
    const messages = await this.options.detailProvider(sessionId, DETAIL_MESSAGE_LIMIT);
    this.options.logger.info("phone_link_detail_read", {
      sessionId: sessionId.slice(0, 8),
      messages: messages.length,
    });
    if (this.openSessionId !== sessionId) {
      return;
    }
    this.send({
      type: "detail",
      sessionId,
      session: this.options.store.get(sessionId) ?? null,
      messages,
    });
  }

  private sendSnapshot(): void {
    const sessions = sessionsForPhone(this.options.store.list());
    this.publishedSessions = new Map(sessions.map((session) => [session.id, session]));
    this.send({
      type: "snapshot",
      seq: this.sequence,
      sessions,
    });
  }

  private reconcileSessions(changedSessionId: string): void {
    if (!this.authenticated) {
      return;
    }
    const sessions = sessionsForPhone(this.options.store.list());
    const next = new Map(sessions.map((session) => [session.id, session]));
    for (const sessionId of this.publishedSessions.keys()) {
      if (!next.has(sessionId)) {
        this.sendRemoved(sessionId);
      }
    }
    for (const session of sessions) {
      if (!this.publishedSessions.has(session.id) || session.id === changedSessionId) {
        this.sendUpsert(session);
      }
    }
    this.publishedSessions = next;
  }

  private sendUpsert(session: Session): void {
    this.sequence += 1;
    this.send({ type: "session_upsert", seq: this.sequence, session });
  }

  private sendRemoved(sessionId: string): void {
    this.sequence += 1;
    this.send({ type: "session_removed", seq: this.sequence, sessionId });
  }

  private send(message: Record<string, unknown>): boolean {
    if (!this.authenticated) {
      return false;
    }
    return this.write(message);
  }

  private write(message: Record<string, unknown>): boolean {
    const socket = this.socket;
    if (!socket || socket.destroyed) {
      return false;
    }
    try {
      socket.write(`${JSON.stringify(message)}\n`);
      return true;
    } catch {
      return false;
    }
  }
}

export function sessionsForPhone(sessions: Session[]): Session[] {
  const counts = new Map<Session["provider"], number>();
  return sessions.filter((session) => {
    const count = counts.get(session.provider) ?? 0;
    if (count >= MAX_PHONE_SESSIONS_PER_PROVIDER) {
      return false;
    }
    counts.set(session.provider, count + 1);
    return true;
  });
}
