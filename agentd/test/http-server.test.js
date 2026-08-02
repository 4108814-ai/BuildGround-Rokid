const test = require("node:test");
const assert = require("node:assert/strict");
const http = require("node:http");
const { HookHttpServer } = require("../dist/http-server.js");

function call(port, method, requestPath, body = "") {
  return new Promise((resolve, reject) => {
    const outgoing = http.request(
      {
        hostname: "127.0.0.1",
        port,
        method,
        path: requestPath,
        headers: body ? { "content-length": Buffer.byteLength(body) } : undefined,
      },
      (response) => {
        let responseBody = "";
        response.setEncoding("utf8");
        response.on("data", (chunk) => { responseBody += chunk; });
        response.on("end", () => resolve({
          status: response.statusCode,
          body: responseBody,
        }));
      },
    );
    outgoing.once("error", reject);
    outgoing.end(body);
  });
}

test("loopback HTTP server reports health and returns the completed hook decision", async () => {
  let received;
  let markReceived;
  const receivedHook = new Promise((resolve) => { markReceived = resolve; });
  const warnings = [];
  const logger = {
    info() {},
    warn(event) { warnings.push(event); },
    error() {},
  };
  let release;
  const held = new Promise((resolve) => { release = resolve; });
  const server = new HookHttpServer({
    port: 0,
    sessionCount: () => 3,
    async onHook(payload) {
      received = payload;
      markReceived();
      await held;
      return {
        hookSpecificOutput: {
          hookEventName: "PreToolUse",
          permissionDecision: "deny",
        },
      };
    },
    logger,
    extraHealth: () => ({
      codex: { enabled: true, available: false, reason: "codex not installed" },
    }),
  });
  await server.start();
  try {
    const health = await call(server.port(), "GET", "/health");
    assert.equal(health.status, 200);
    assert.deepEqual(JSON.parse(health.body), {
      ok: true,
      sessions: 3,
      uptimeMs: JSON.parse(health.body).uptimeMs,
      codex: { enabled: true, available: false, reason: "codex not installed" },
    });

    const payload = { session_id: "http-session", hook_event_name: "SessionStart" };
    let completed = false;
    const acceptedPromise = call(server.port(), "POST", "/hook", JSON.stringify(payload))
      .then((value) => {
        completed = true;
        return value;
      });
    await receivedHook;
    assert.deepEqual(received, payload);
    assert.equal(completed, false);
    release();
    const accepted = await acceptedPromise;
    assert.equal(accepted.status, 200);
    assert.deepEqual(JSON.parse(accepted.body), {
      hookSpecificOutput: {
        hookEventName: "PreToolUse",
        permissionDecision: "deny",
      },
    });

    const malformed = await call(server.port(), "POST", "/hook", "{");
    assert.deepEqual(malformed, { status: 200, body: "{}" });
    await new Promise((resolve) => setImmediate(resolve));
    assert.deepEqual(warnings, ["hook_parse_failed"]);
  } finally {
    await server.stop();
  }
});
