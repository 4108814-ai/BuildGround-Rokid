#!/usr/bin/env node
import { request } from "node:http";
import { approvalTimeoutFromEnv } from "./approval-manager";

const EXIT_AFTER_MS = approvalTimeoutFromEnv() + 5000;
const HTTP_PORT = (() => {
  const parsed = Number(process.env.NEXUS_AGENTD_HTTP_PORT);
  return Number.isSafeInteger(parsed) && parsed > 0 && parsed <= 65_535 ? parsed : 8791;
})();
const chunks: Buffer[] = [];
let finished = false;

function finish(output?: string): void {
  if (finished) {
    return;
  }
  finished = true;
  clearTimeout(deadline);
  if (output && output !== "{}") {
    process.stdout.write(output, () => process.exit(0));
    return;
  }
  process.exit(0);
}

const deadline = setTimeout(finish, EXIT_AFTER_MS);

process.stdin.on("data", (chunk: Buffer | string) => {
  chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
});
process.stdin.on("error", () => finish());
process.stdin.on("end", () => {
  if (finished) {
    return;
  }
  const body = Buffer.concat(chunks);
  try {
    const outgoing = request(
      {
        hostname: "127.0.0.1",
        port: HTTP_PORT,
        path: "/hook",
        method: "POST",
        headers: {
          "content-type": "application/json",
          "content-length": body.length,
        },
      },
      (response) => {
        let responseBody = "";
        response.setEncoding("utf8");
        response.on("data", (chunk: string) => {
          if (responseBody.length <= 64 * 1024) {
            responseBody += chunk;
          }
        });
        response.once("end", () => {
          try {
            const parsed: unknown = JSON.parse(responseBody);
            if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
              finish();
              return;
            }
            finish(JSON.stringify(parsed));
          } catch {
            finish();
          }
        });
      },
    );
    outgoing.once("error", () => finish());
    outgoing.end(body);
  } catch {
    finish();
  }
});
process.stdin.resume();

process.once("uncaughtException", () => finish());
process.once("unhandledRejection", () => finish());
process.once("exit", () => clearTimeout(deadline));
