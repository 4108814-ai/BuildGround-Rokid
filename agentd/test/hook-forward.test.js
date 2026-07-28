const test = require("node:test");
const assert = require("node:assert/strict");
const http = require("node:http");
const path = require("node:path");
const { spawn } = require("node:child_process");

const forwarderPath = path.resolve(__dirname, "..", "dist", "hook-forward.js");

function listen(server) {
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => resolve(server.address().port));
  });
}

function close(server) {
  return new Promise((resolve) => server.close(resolve));
}

function runForwarder(port, payload, timeoutMs = "500") {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [forwarderPath], {
      env: {
        ...process.env,
        NEXUS_AGENTD_HTTP_PORT: String(port),
        NEXUS_AGENTD_APPROVAL_TIMEOUT_MS: timeoutMs,
      },
      stdio: ["pipe", "pipe", "pipe"],
    });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (chunk) => { stdout += chunk.toString(); });
    child.stderr.on("data", (chunk) => { stderr += chunk.toString(); });
    child.once("error", reject);
    child.once("exit", (code) => resolve({ code, stdout, stderr }));
    child.stdin.end(JSON.stringify(payload));
  });
}

test("hook forwarder waits for and prints a decision, but stays silent for local fallback", async () => {
  let responseBody = {
    hookSpecificOutput: {
      hookEventName: "PreToolUse",
      permissionDecision: "allow",
    },
  };
  const server = http.createServer((request, response) => {
    request.resume();
    request.once("end", () => {
      setTimeout(() => {
        const body = JSON.stringify(responseBody);
        response.writeHead(200, { "content-type": "application/json" });
        response.end(body);
      }, 40);
    });
  });
  const port = await listen(server);
  try {
    const startedAt = Date.now();
    const decided = await runForwarder(port, bashPayload());
    assert.equal(decided.code, 0);
    assert.equal(decided.stderr, "");
    assert.ok(Date.now() - startedAt >= 30);
    assert.deepEqual(JSON.parse(decided.stdout), responseBody);

    responseBody = {};
    const local = await runForwarder(port, bashPayload());
    assert.deepEqual(local, { code: 0, stdout: "", stderr: "" });
  } finally {
    await close(server);
  }
});

test("hook forwarder falls through silently when the daemon is unavailable", async () => {
  const server = http.createServer();
  const port = await listen(server);
  await close(server);
  assert.deepEqual(
    await runForwarder(port, bashPayload()),
    { code: 0, stdout: "", stderr: "" },
  );
});

function bashPayload() {
  return {
    hook_event_name: "PreToolUse",
    session_id: "forwarder-session",
    tool_name: "Bash",
    tool_input: { command: "npm test" },
  };
}
