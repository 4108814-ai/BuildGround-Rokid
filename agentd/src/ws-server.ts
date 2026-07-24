import { timingSafeEqual } from "node:crypto";
import { readFileSync } from "node:fs";
import type { AddressInfo } from "node:net";
import path from "node:path";
import WebSocket, { WebSocketServer, type RawData } from "ws";
import { SessionStore } from "./session-store";
import type { AgentConfig, Logger, Session } from "./types";

const PROTOCOL_VERSION = 1;
const SERVER_VERSION = (() => {
  const packageJson = JSON.parse(
    readFileSync(path.resolve(__dirname, "..", "package.json"), "utf8"),
  ) as { version?: unknown };
  if (typeof packageJson.version !== "string") {
    throw new Error("nexus-agentd package version is missing");
  }
  return packageJson.version;
})();

interface ClientState {
  authenticated: boolean;
  lastPongAt: number;
  helloTimer: NodeJS.Timeout;
  pongTimer?: NodeJS.Timeout;
}

export interface WsHubOptions {
  host?: string;
  helloTimeoutMs?: number;
  keepaliveIntervalMs?: number;
  pongTimeoutMs?: number;
}

function tokenMatches(received: unknown, expected: string): boolean {
  if (typeof received !== "string") {
    return false;
  }
  const left = Buffer.from(received);
  const right = Buffer.from(expected);
  return left.length === right.length && timingSafeEqual(left, right);
}

export class WsHub {
  private server?: WebSocketServer;
  private keepaliveTimer?: NodeJS.Timeout;
  private readonly clients = new Map<WebSocket, ClientState>();
  private sequence = 0;
  private readonly unsubscribeUpsert: () => void;
  private readonly unsubscribeRemoved: () => void;

  constructor(
    private readonly config: AgentConfig,
    private readonly store: SessionStore,
    private readonly logger: Logger,
    private readonly options: WsHubOptions = {},
  ) {
    this.unsubscribeUpsert = store.onUpsert((session) => this.broadcastUpsert(session));
    this.unsubscribeRemoved = store.onRemoved((sessionId) => this.broadcastRemoved(sessionId));
  }

  get seq(): number {
    return this.sequence;
  }

  start(): Promise<void> {
    if (this.server) {
      return Promise.resolve();
    }
    const server = new WebSocketServer({
      host: this.options.host ?? "0.0.0.0",
      port: this.config.wsPort,
    });
    this.server = server;
    server.on("connection", (socket) => this.accept(socket));
    server.on("error", (error) => {
      this.logger.error("ws_server_error", { reason: error.name });
    });

    return new Promise((resolve, reject) => {
      const onError = (error: Error) => {
        server.off("listening", onListening);
        this.server = undefined;
        reject(error);
      };
      const onListening = () => {
        server.off("error", onError);
        const interval = this.options.keepaliveIntervalMs ?? 30_000;
        this.keepaliveTimer = setInterval(() => this.keepalive(), interval);
        this.keepaliveTimer.unref();
        resolve();
      };
      server.once("error", onError);
      server.once("listening", onListening);
    });
  }

  port(): number | undefined {
    const address = this.server?.address();
    return address && typeof address !== "string" ? (address as AddressInfo).port : undefined;
  }

  async stop(): Promise<void> {
    if (this.keepaliveTimer) {
      clearInterval(this.keepaliveTimer);
      this.keepaliveTimer = undefined;
    }
    for (const [socket, state] of this.clients) {
      clearTimeout(state.helloTimer);
      if (state.pongTimer) {
        clearTimeout(state.pongTimer);
      }
      socket.terminate();
    }
    this.clients.clear();
    const server = this.server;
    this.server = undefined;
    if (server) {
      await new Promise<void>((resolve) => server.close(() => resolve()));
    }
    this.unsubscribeUpsert();
    this.unsubscribeRemoved();
  }

  private accept(socket: WebSocket): void {
    const helloTimer = setTimeout(() => {
      if (!this.clients.get(socket)?.authenticated) {
        socket.close(4408, "hello timeout");
      }
    }, this.options.helloTimeoutMs ?? 5000);
    helloTimer.unref();
    this.clients.set(socket, {
      authenticated: false,
      lastPongAt: Date.now(),
      helloTimer,
    });

    socket.on("message", (data, isBinary) => this.onMessage(socket, data, isBinary));
    socket.on("error", (error) => {
      this.logger.warn("ws_client_error", { reason: error.name });
    });
    socket.on("close", () => {
      const state = this.clients.get(socket);
      if (state) {
        clearTimeout(state.helloTimer);
        if (state.pongTimer) {
          clearTimeout(state.pongTimer);
        }
      }
      this.clients.delete(socket);
    });
  }

  private onMessage(socket: WebSocket, data: RawData, isBinary: boolean): void {
    const state = this.clients.get(socket);
    if (!state) {
      return;
    }
    if (isBinary) {
      if (!state.authenticated) {
        socket.close(4401, "authentication required");
      } else {
        this.logger.info("ws_binary_ignored");
      }
      return;
    }

    let message: Record<string, unknown>;
    try {
      const parsed: unknown = JSON.parse(data.toString());
      if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
        throw new Error("not an object");
      }
      message = parsed as Record<string, unknown>;
    } catch {
      if (!state.authenticated) {
        socket.close(4401, "authentication required");
      } else {
        this.logger.info("ws_invalid_json_ignored");
      }
      return;
    }

    if (!state.authenticated) {
      if (
        message.type !== "hello" ||
        message.v !== PROTOCOL_VERSION ||
        !tokenMatches(message.token, this.config.token)
      ) {
        socket.close(4401, "authentication failed");
        return;
      }
      state.authenticated = true;
      state.lastPongAt = Date.now();
      clearTimeout(state.helloTimer);
      this.armPongDeadline(socket, state);
      this.send(socket, {
        type: "hello_ack",
        v: PROTOCOL_VERSION,
        server: {
          name: "nexus-agentd",
          version: SERVER_VERSION,
          machineId: this.config.machineId,
          machineName: this.config.machineName,
        },
      });
      this.sendSnapshot(socket);
      return;
    }

    switch (message.type) {
      case "refresh":
        this.sendSnapshot(socket);
        break;
      case "pong":
        state.lastPongAt = Date.now();
        this.armPongDeadline(socket, state);
        break;
      default:
        this.logger.info("ws_unknown_message", {
          type: typeof message.type === "string" ? message.type.slice(0, 80) : "missing",
        });
        break;
    }
  }

  private sendSnapshot(socket: WebSocket): void {
    this.send(socket, {
      type: "snapshot",
      seq: this.sequence,
      sessions: this.store.list(),
    });
  }

  private broadcastUpsert(session: Session): void {
    this.sequence += 1;
    this.broadcast({
      type: "session_upsert",
      seq: this.sequence,
      session,
    });
  }

  private broadcastRemoved(sessionId: string): void {
    this.sequence += 1;
    this.broadcast({
      type: "session_removed",
      seq: this.sequence,
      sessionId,
    });
  }

  private keepalive(): void {
    const now = Date.now();
    const pongTimeout = this.options.pongTimeoutMs ?? 90_000;
    for (const [socket, state] of this.clients) {
      if (!state.authenticated || socket.readyState !== WebSocket.OPEN) {
        continue;
      }
      if (now - state.lastPongAt >= pongTimeout) {
        socket.close(4409, "pong timeout");
        continue;
      }
      this.send(socket, { type: "ping", t: now });
      socket.ping();
    }
  }

  private broadcast(message: Record<string, unknown>): void {
    for (const [socket, state] of this.clients) {
      if (state.authenticated && socket.readyState === WebSocket.OPEN) {
        this.send(socket, message);
      }
    }
  }

  private send(socket: WebSocket, message: Record<string, unknown>): void {
    if (socket.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify(message));
    }
  }

  private armPongDeadline(socket: WebSocket, state: ClientState): void {
    if (state.pongTimer) {
      clearTimeout(state.pongTimer);
    }
    state.pongTimer = setTimeout(() => {
      if (socket.readyState === WebSocket.OPEN) {
        socket.close(4409, "pong timeout");
      }
    }, this.options.pongTimeoutMs ?? 90_000);
    state.pongTimer.unref();
  }
}
