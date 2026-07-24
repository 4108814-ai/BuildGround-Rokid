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

test("loopback HTTP server reports health and acknowledges hooks before processing", async () => {
  let received;
  const warnings = [];
  const logger = {
    info() {},
    warn(event) { warnings.push(event); },
    error() {},
  };
  const never = new Promise(() => {});
  const server = new HookHttpServer({
    port: 0,
    sessionCount: () => 3,
    onHook(payload) {
      received = payload;
      return never;
    },
    logger,
  });
  await server.start();
  try {
    const health = await call(server.port(), "GET", "/health");
    assert.equal(health.status, 200);
    assert.deepEqual(JSON.parse(health.body), {
      ok: true,
      sessions: 3,
      uptimeMs: JSON.parse(health.body).uptimeMs,
    });

    const payload = { session_id: "http-session", hook_event_name: "SessionStart" };
    const accepted = await call(server.port(), "POST", "/hook", JSON.stringify(payload));
    assert.deepEqual(accepted, { status: 200, body: "{}" });
    await new Promise((resolve) => setImmediate(resolve));
    assert.deepEqual(received, payload);

    const malformed = await call(server.port(), "POST", "/hook", "{");
    assert.deepEqual(malformed, { status: 200, body: "{}" });
    await new Promise((resolve) => setImmediate(resolve));
    assert.deepEqual(warnings, ["hook_parse_failed"]);
  } finally {
    await server.stop();
  }
});
