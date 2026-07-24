#!/usr/bin/env node
import { request } from "node:http";
import path from "node:path";
import qrcode from "qrcode-terminal";
import { defaultStateDir, ensureConfig } from "./config";
import { startDaemon } from "./daemon";
import { pairingPayload } from "./pairing";
import { installHooks, uninstallHooks } from "./settings";

interface Health {
  ok: boolean;
  sessions: number;
}

function getHealth(port: number): Promise<Health | undefined> {
  return new Promise((resolve) => {
    const outgoing = request(
      {
        hostname: "127.0.0.1",
        port,
        path: "/health",
        method: "GET",
        timeout: 1000,
      },
      (response) => {
        let body = "";
        response.setEncoding("utf8");
        response.on("data", (chunk: string) => {
          body += chunk;
        });
        response.on("end", () => {
          try {
            const parsed = JSON.parse(body) as Health;
            resolve(response.statusCode === 200 && parsed.ok === true ? parsed : undefined);
          } catch {
            resolve(undefined);
          }
        });
      },
    );
    outgoing.once("timeout", () => {
      outgoing.destroy();
      resolve(undefined);
    });
    outgoing.once("error", () => resolve(undefined));
    outgoing.end();
  });
}

async function run(): Promise<void> {
  const daemon = await startDaemon();
  process.stdout.write(
    `nexus-agentd listening on 127.0.0.1:${daemon.config.httpPort} and 0.0.0.0:${daemon.config.wsPort}\n`,
  );
  const shutdown = () => {
    void daemon.stop().finally(() => process.exit(0));
  };
  process.once("SIGINT", shutdown);
  process.once("SIGTERM", shutdown);
}

async function main(): Promise<void> {
  const command = process.argv[2] || "run";
  const agentRoot = path.resolve(__dirname, "..");
  switch (command) {
    case "run":
      await run();
      break;
    case "install-hooks": {
      const result = installHooks(agentRoot);
      process.stdout.write(
        result.changed
          ? `Installed Claude Code hooks in ${result.settingsPath}${
              result.backupPath ? ` (backup: ${result.backupPath})` : ""
            }\n`
          : `Claude Code hooks are already installed in ${result.settingsPath}\n`,
      );
      break;
    }
    case "uninstall-hooks": {
      const result = uninstallHooks();
      process.stdout.write(
        result.changed
          ? `Removed nexus-agentd hooks from ${result.settingsPath}${
              result.backupPath ? ` (backup: ${result.backupPath})` : ""
            }\n`
          : `No nexus-agentd hooks found in ${result.settingsPath}\n`,
      );
      break;
    }
    case "pair": {
      const config = ensureConfig(defaultStateDir());
      const payload = pairingPayload(config);
      process.stdout.write(`${payload}\n`);
      qrcode.generate(payload, { small: true }, (code) => process.stdout.write(`${code}\n`));
      break;
    }
    case "status": {
      const config = ensureConfig(defaultStateDir());
      const health = await getHealth(config.httpPort);
      process.stdout.write(
        health
          ? `running: yes, sessions: ${health.sessions}\n`
          : "running: no, sessions: 0\n",
      );
      break;
    }
    default:
      throw new Error(
        `Unknown command '${command}'. Use run, install-hooks, uninstall-hooks, pair, or status.`,
      );
  }
}

void main().catch((error) => {
  process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
  process.exitCode = 1;
});
