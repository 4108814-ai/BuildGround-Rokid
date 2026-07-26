import { createSocket, type Socket as UdpSocket } from "node:dgram";
import { networkInterfaces } from "node:os";
import { connect, type Socket as TcpSocket } from "node:net";
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
const DETAIL_MESSAGE_LIMIT = 40;
const MAX_LINE_BYTES = 512 * 1024;

export interface PhoneLinkOptions {
  config: AgentConfig;
  store: SessionStore;
  logger: Logger;
  detailProvider: (sessionId: string, limit: number) => Promise<SessionMessage[]>;
  onDetailOpen?: (sessionId: string) => void;
  discoveryPort?: number;
}

interface PhoneAnnouncement {
  host: string;
  port: number;
  name: string;
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
  private buffer = "";
  private sequence = 0;
  private openSessionId?: string;
  private connectedTo?: string;
  private stopped = false;
  private unsubscribe?: () => void;

  constructor(private readonly options: PhoneLinkOptions) {}

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
    this.unsubscribe?.();
    this.unsubscribe = undefined;
    this.socket?.destroy();
    this.socket = undefined;
    this.discovery?.close();
    this.discovery = undefined;
  }

  get connected(): boolean {
    return this.socket !== undefined;
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
  }

  private connectToPhone(announcement: PhoneAnnouncement): void {
    if (this.socket || this.stopped) {
      return;
    }
    const socket = connect({ host: announcement.host, port: announcement.port });
    this.socket = socket;
    socket.setNoDelay(true);
    socket.setKeepAlive(true, 30_000);
    socket.on("connect", () => {
      this.connectedTo = `${announcement.host}:${announcement.port}`;
      this.options.logger.info("phone_link_connected", {
        phone: announcement.name,
        target: this.connectedTo,
      });
      this.send({
        type: "hello",
        v: 1,
        machineId: this.options.config.machineId,
        machineName: this.options.config.machineName,
        token: this.options.config.token,
      });
      this.sendSnapshot();
      this.unsubscribe = this.subscribe();
    });
    socket.on("data", (chunk) => this.onData(chunk));
    socket.on("error", (error) => {
      this.options.logger.warn("phone_link_error", { reason: error.name });
    });
    socket.on("close", () => this.onClose());
  }

  private subscribe(): () => void {
    const upsert = this.options.store.onUpsert((session) => this.sendUpsert(session));
    const removed = this.options.store.onRemoved((sessionId) => this.sendRemoved(sessionId));
    return () => {
      upsert();
      removed();
    };
  }

  private onClose(): void {
    this.unsubscribe?.();
    this.unsubscribe = undefined;
    this.socket = undefined;
    this.buffer = "";
    this.openSessionId = undefined;
    if (this.connectedTo) {
      this.options.logger.info("phone_link_closed", { target: this.connectedTo });
      this.connectedTo = undefined;
    }
    if (!this.stopped) {
      // Discovery keeps running; give the phone a moment before dialling again.
      this.reconnectTimer = setTimeout(() => this.announce(), RECONNECT_DELAY_MS);
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
        break;
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
      default:
        break;
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
    this.send({
      type: "snapshot",
      seq: this.sequence,
      sessions: this.options.store.list(),
    });
  }

  private sendUpsert(session: Session): void {
    this.sequence += 1;
    this.send({ type: "session_upsert", seq: this.sequence, session });
  }

  private sendRemoved(sessionId: string): void {
    this.sequence += 1;
    this.send({ type: "session_removed", seq: this.sequence, sessionId });
  }

  private send(message: Record<string, unknown>): void {
    const socket = this.socket;
    if (!socket || socket.destroyed) {
      return;
    }
    socket.write(`${JSON.stringify(message)}\n`);
  }
}
