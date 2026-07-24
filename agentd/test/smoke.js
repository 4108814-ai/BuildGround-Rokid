const assert = require("node:assert/strict");
const fs = require("node:fs");
const fsp = require("node:fs/promises");
const http = require("node:http");
const os = require("node:os");
const path = require("node:path");
const { spawn, spawnSync } = require("node:child_process");
const WebSocket = require("ws");

const projectRoot = path.resolve(__dirname, "..");

function health() {
  return new Promise((resolve) => {
    const request = http.get("http://127.0.0.1:8791/health", (response) => {
      let body = "";
      response.setEncoding("utf8");
      response.on("data", (chunk) => { body += chunk; });
      response.on("end", () => {
        try {
          resolve(response.statusCode === 200 ? JSON.parse(body) : undefined);
        } catch {
          resolve(undefined);
        }
      });
    });
    request.setTimeout(250, () => {
      request.destroy();
      resolve(undefined);
    });
    request.on("error", () => resolve(undefined));
  });
}

async function waitForDaemon(child) {
  const deadline = Date.now() + 8000;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) {
      throw new Error(`daemon exited early with code ${child.exitCode}`);
    }
    const response = await health();
    if (response?.ok) return response;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error("daemon did not become healthy");
}

function postWithCurl(payload) {
  const curl = process.platform === "win32" ? "curl.exe" : "curl";
  const result = spawnSync(
    curl,
    [
      "--silent",
      "--show-error",
      "--fail",
      "--header",
      "content-type: application/json",
      "--data-binary",
      JSON.stringify(payload),
      "http://127.0.0.1:8791/hook",
    ],
    { encoding: "utf8" },
  );
  assert.equal(result.status, 0, result.stderr || "curl failed");
  assert.equal(result.stdout, "{}");
}

function waitForFrame(frames, predicate, timeoutMs = 5000) {
  const existing = frames.find(predicate);
  if (existing) return Promise.resolve(existing);
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("timed out waiting for smoke WS frame")), timeoutMs);
    frames.waiters.push((frame) => {
      if (predicate(frame)) {
        clearTimeout(timer);
        resolve(frame);
        return true;
      }
      return false;
    });
  });
}

async function stopChild(child) {
  if (child.exitCode !== null) return;
  const exited = new Promise((resolve) => child.once("exit", resolve));
  child.kill("SIGTERM");
  await Promise.race([
    exited,
    new Promise((resolve) => setTimeout(resolve, 2000)),
  ]);
  if (child.exitCode === null) {
    child.kill("SIGKILL");
    await exited;
  }
}

async function main() {
  const tempDir = await fsp.mkdtemp(path.join(os.tmpdir(), "nexus-agentd-smoke-"));
  const stateDir = path.join(tempDir, "state");
  const claudeDir = path.join(tempDir, "claude");
  const transcriptPath = path.join(tempDir, "smoke-session.jsonl");
  await fsp.mkdir(path.join(claudeDir, "projects"), { recursive: true });
  await fsp.writeFile(
    transcriptPath,
    '{"type":"system","timestamp":"2026-07-24T10:00:00.000Z","cwd":"E:/smoke/project"}\n',
  );

  const child = spawn(process.execPath, [path.join(projectRoot, "dist", "cli.js"), "run"], {
    cwd: projectRoot,
    env: {
      ...process.env,
      NEXUS_AGENTD_STATE_DIR: stateDir,
      NEXUS_AGENTD_CLAUDE_DIR: claudeDir,
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  let childOutput = "";
  child.stdout.on("data", (chunk) => { childOutput += chunk.toString(); });
  child.stderr.on("data", (chunk) => { childOutput += chunk.toString(); });
  let socket;

  try {
    await waitForDaemon(child);
    const config = JSON.parse(
      fs.readFileSync(path.join(stateDir, "config.json"), "utf8"),
    );
    const base = {
      session_id: "smoke-session",
      transcript_path: transcriptPath,
      cwd: "E:/smoke/project",
    };
    postWithCurl({ ...base, hook_event_name: "SessionStart", source: "startup" });

    socket = new WebSocket("ws://127.0.0.1:8792");
    const frames = [];
    frames.waiters = [];
    socket.on("message", (data) => {
      const frame = JSON.parse(data.toString());
      frames.push(frame);
      frames.waiters = frames.waiters.filter((waiter) => !waiter(frame));
      if (frame.type === "ping") {
        socket.send(JSON.stringify({ type: "pong", t: frame.t }));
      }
    });
    await new Promise((resolve, reject) => {
      socket.once("open", resolve);
      socket.once("error", reject);
    });
    socket.send(JSON.stringify({
      type: "hello",
      v: 1,
      token: config.token,
      client: { name: "plugin-agents", version: "smoke" },
    }));
    const helloAck = await waitForFrame(frames, (frame) => frame.type === "hello_ack");
    const snapshot = await waitForFrame(frames, (frame) => frame.type === "snapshot");

    postWithCurl({
      ...base,
      hook_event_name: "UserPromptSubmit",
      prompt: "Smoke-test the monitor",
    });
    const upsert = await waitForFrame(frames, (frame) => frame.type === "session_upsert");

    assert.equal(helloAck.v, 1);
    assert.equal(snapshot.sessions.some((session) => session.id === "smoke-session"), true);
    assert.equal(upsert.session.status, "working");
    assert.equal(upsert.seq, snapshot.seq + 1);
    for (const frame of [helloAck, snapshot, upsert]) {
      process.stdout.write(`${JSON.stringify(frame)}\n`);
    }
    process.stdout.write("Smoke test passed\n");
  } catch (error) {
    if (childOutput) process.stderr.write(childOutput);
    throw error;
  } finally {
    socket?.terminate();
    await stopChild(child);
    await fsp.rm(tempDir, { recursive: true, force: true });
  }
}

main().catch((error) => {
  process.stderr.write(`${error.stack || error}\n`);
  process.exitCode = 1;
});
