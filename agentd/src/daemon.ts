import { homedir } from "node:os";
import path from "node:path";
import { ApprovalManager, approvalTimeoutFromEnv, type HookResponse } from "./approval-manager";
import { CodexMonitor } from "./codex/monitor";
import { configPath, defaultStateDir, ensureConfig } from "./config";
import { discoverRecentSessions } from "./discovery";
import { HookHttpServer } from "./http-server";
import { FileLogger } from "./logger";
import { SessionStore } from "./session-store";
import { PhoneLink } from "./phone-link";
import { TranscriptTailManager } from "./transcript";
import { readRecentMessages } from "./transcript-messages";
import type { HookPayload } from "./types";
import { WsHub } from "./ws-server";

export interface RunningDaemon {
  config: ReturnType<typeof ensureConfig>;
  sessions: SessionStore;
  stop(): Promise<void>;
}

export async function startDaemon(): Promise<RunningDaemon> {
  const stateDir = defaultStateDir();
  const config = ensureConfig(stateDir);
  const logger = new FileLogger(stateDir);
  const sessions = new SessionStore(config, logger);
  const claudeDir = process.env.NEXUS_AGENTD_CLAUDE_DIR || path.join(homedir(), ".claude");
  const discovered = await discoverRecentSessions(path.join(claudeDir, "projects"), logger);
  for (const session of discovered) {
    sessions.addDiscovered(session);
  }

  let wsHub: WsHub | undefined;
  let phoneLink: PhoneLink | undefined;
  let approvals: ApprovalManager | undefined;
  const tailManager = new TranscriptTailManager(
    (sessionId, update) => sessions.applyTranscriptUpdate(sessionId, update),
    logger,
    (sessionId, message) => {
      wsHub?.broadcastDetailMessage(sessionId, message);
      phoneLink?.sendDetailMessage(sessionId, message);
    },
  );

  const detailProvider = async (sessionId: string, limit: number) => {
    const transcriptPath = sessions.transcriptPath(sessionId);
    return transcriptPath ? readRecentMessages(transcriptPath, limit) : [];
  };
  // Reading a conversation also starts tailing it, so the view stays live
  // even for a session that had been quiet since the daemon started.
  const onDetailOpen = (sessionId: string) => {
    const transcriptPath = sessions.transcriptPath(sessionId);
    if (transcriptPath && !tailManager.isTailing(sessionId)) {
      tailManager.start(sessionId, transcriptPath);
    }
  };
  const processHook = (payload: HookPayload): HookResponse | Promise<HookResponse> => {
    sessions.handleHook(payload);
    const sessionId = typeof payload.session_id === "string" ? payload.session_id : undefined;
    const eventName = typeof payload.hook_event_name === "string" ? payload.hook_event_name : undefined;
    const transcriptPath =
      typeof payload.transcript_path === "string"
        ? payload.transcript_path
        : sessionId
          ? sessions.transcriptPath(sessionId)
          : undefined;
    if (sessionId && eventName === "SessionEnd") {
      tailManager.stop(sessionId);
      approvals?.resolveSession(sessionId);
    } else if (sessionId && transcriptPath && sessions.get(sessionId)?.stale === false) {
      tailManager.start(sessionId, transcriptPath);
    }
    if (eventName === "PreToolUse") {
      return approvals?.request(payload) ?? {};
    }
    return {};
  };

  const hub = new WsHub(config, sessions, logger, { detailProvider, onDetailOpen });
  wsHub = hub;
  const link = new PhoneLink({
    config,
    configFilePath: configPath(stateDir),
    store: sessions,
    logger,
    detailProvider,
    onDetailOpen,
    onApprovalDecision: (requestId, decision) => approvals?.handleDecision(requestId, decision),
    onConnected: () => approvals?.onLinkConnected(),
    onDisconnected: () => approvals?.onLinkDisconnected(),
  });
  phoneLink = link;
  approvals = new ApprovalManager({
    transport: link,
    logger,
    timeoutMs: approvalTimeoutFromEnv(),
  });
  const codex = new CodexMonitor({
    config,
    store: sessions,
    approvals,
    logger,
  });
  const httpServer = new HookHttpServer({
    port: config.httpPort,
    sessionCount: () => sessions.size,
    onHook: processHook,
    logger,
    extraHealth: () => ({ codex: codex.availability() }),
  });
  const heartbeatTimer = setInterval(() => sessions.sweepStalled(), 60_000);
  heartbeatTimer.unref();

  try {
    await httpServer.start();
    await hub.start();
    link.start();
    codex.start();
  } catch (error) {
    clearInterval(heartbeatTimer);
    link.stop();
    await codex.stop();
    await httpServer.stop().catch(() => undefined);
    await hub.stop().catch(() => undefined);
    tailManager.stopAll();
    sessions.dispose();
    throw error;
  }

  logger.info("daemon_started", {
    httpHost: "127.0.0.1",
    httpPort: config.httpPort,
    wsHost: "0.0.0.0",
    wsPort: config.wsPort,
    discoveredSessions: discovered.length,
  });

  let stopped = false;
  return {
    config,
    sessions,
    async stop() {
      if (stopped) {
        return;
      }
      stopped = true;
      clearInterval(heartbeatTimer);
      await codex.stop();
      approvals?.dispose();
      link.stop();
      tailManager.stopAll();
      await Promise.all([httpServer.stop(), hub.stop()]);
      sessions.dispose();
      logger.info("daemon_stopped");
    },
  };
}
