import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import type { HookResponse } from "./approval-manager";
import type { HookPayload, Logger } from "./types";

export interface HookHttpServerOptions {
  port: number;
  sessionCount: () => number;
  onHook: (payload: HookPayload) => HookResponse | void | Promise<HookResponse | void>;
  logger: Logger;
  extraHealth?: () => Record<string, unknown>;
}

export class HookHttpServer {
  private server?: Server;
  private readonly startedAt = Date.now();

  constructor(private readonly options: HookHttpServerOptions) {}

  start(): Promise<void> {
    if (this.server) {
      return Promise.resolve();
    }
    const server = createServer((request, response) => {
      if (request.method === "GET" && request.url === "/health") {
        const body = JSON.stringify({
          ok: true,
          sessions: this.options.sessionCount(),
          uptimeMs: Date.now() - this.startedAt,
          ...this.options.extraHealth?.(),
        });
        response.writeHead(200, {
          "content-type": "application/json",
          "content-length": Buffer.byteLength(body),
        });
        response.end(body);
        return;
      }

      if (request.method === "POST" && request.url === "/hook") {
        let body = "";
        request.setEncoding("utf8");
        request.on("data", (chunk: string) => {
          body += chunk;
        });
        request.on("end", () => {
          let payload: HookPayload;
          try {
            const parsed: unknown = JSON.parse(body);
            if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
              throw new Error("payload is not a JSON object");
            }
            payload = parsed as HookPayload;
          } catch (error) {
            this.options.logger.warn("hook_parse_failed", {
              bodyBytes: Buffer.byteLength(body),
              reason: error instanceof Error ? error.name : "unknown",
            });
            this.respond(response, {});
            return;
          }
          Promise.resolve(this.options.onHook(payload))
            .then((result) => this.respond(response, result ?? {}))
            .catch((error) => {
              this.options.logger.error("hook_processing_failed", {
                reason: error instanceof Error ? error.name : "unknown",
              });
              this.respond(response, {});
            });
        });
        request.on("error", () => {
          if (!response.headersSent) {
            this.respond(response, {});
          }
        });
        return;
      }

      response.writeHead(404, { "content-type": "application/json" });
      response.end('{"error":"not_found"}');
    });
    this.server = server;
    return new Promise((resolve, reject) => {
      const onError = (error: Error) => {
        server.off("listening", onListening);
        this.server = undefined;
        reject(error);
      };
      const onListening = () => {
        server.off("error", onError);
        resolve();
      };
      server.once("error", onError);
      server.once("listening", onListening);
      server.listen(this.options.port, "127.0.0.1");
    });
  }

  port(): number | undefined {
    const address = this.server?.address();
    return address && typeof address !== "string" ? (address as AddressInfo).port : undefined;
  }

  stop(): Promise<void> {
    const server = this.server;
    this.server = undefined;
    if (!server) {
      return Promise.resolve();
    }
    return new Promise((resolve, reject) => {
      server.close((error) => error ? reject(error) : resolve());
    });
  }

  private respond(response: import("node:http").ServerResponse, value: HookResponse): void {
    if (response.headersSent || response.destroyed) {
      return;
    }
    const body = JSON.stringify(value);
    response.writeHead(200, {
      "content-type": "application/json",
      "content-length": Buffer.byteLength(body),
    });
    response.end(body);
  }
}
