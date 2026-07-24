#!/usr/bin/env node
import { request } from "node:http";

const EXIT_AFTER_MS = 2000;
const chunks: Buffer[] = [];
let finished = false;

function finish(): never {
  if (!finished) {
    finished = true;
  }
  process.exit(0);
}

const deadline = setTimeout(finish, EXIT_AFTER_MS);

process.stdin.on("data", (chunk: Buffer | string) => {
  chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
});
process.stdin.on("error", finish);
process.stdin.on("end", () => {
  if (finished) {
    return;
  }
  const body = Buffer.concat(chunks);
  try {
    const outgoing = request(
      {
        hostname: "127.0.0.1",
        port: 8791,
        path: "/hook",
        method: "POST",
        headers: {
          "content-type": "application/json",
          "content-length": body.length,
        },
      },
      (response) => {
        response.resume();
        response.once("end", finish);
      },
    );
    outgoing.once("error", finish);
    outgoing.end(body);
  } catch {
    finish();
  }
});
process.stdin.resume();

process.once("uncaughtException", finish);
process.once("unhandledRejection", finish);
process.once("exit", () => clearTimeout(deadline));
