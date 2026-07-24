import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import type { HookPayload, Logger } from "./types";

export interface HookHttpServerOptions {
  port: number;
  sessionCount: () => number;
  onHook: (payload: HookPayload) => void | Promise<void>;
  logger: Logger;
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
          response.writeHead(200, {
            "content-type": "application/json",
            "content-length": "2",
          });
          response.end("{}");
          setImmediate(() => {
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
              return;
            }
            Promise.resolve(this.options.onHook(payload)).catch((error) => {
              this.options.logger.error("hook_processing_failed", {
                reason: error instanceof Error ? error.name : "unknown",
              });
            });
          });
        });
        request.on("error", () => {
          if (!response.headersSent) {
            response.writeHead(200, { "content-type": "application/json" });
            response.end("{}");
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
}
