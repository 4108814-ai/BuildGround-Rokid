const test = require("node:test");
const assert = require("node:assert/strict");
const fsp = require("node:fs/promises");
const net = require("node:net");
const os = require("node:os");
const path = require("node:path");
const { configPath, ensureConfig, saveConfig } = require("../dist/config.js");
const { SessionStore } = require("../dist/session-store.js");
const {
  buildPhoneDialTargets,
  MAX_PHONE_SESSIONS_PER_PROVIDER,
  PhoneLink,
  REFUSAL_RECONNECT_DELAY_MS,
} = require("../dist/phone-link.js");

const config = {
  token: "phone-link-token",
  wsPort: 8792,
  httpPort: 8791,
  machineId: "machine-phone-link",
  machineName: "phone-link-pc",
  phoneHosts: [],
  tailnetDiscovery: false,
};

function loggerHarness() {
  const entries = [];
  return {
    entries,
    logger: {
      info(event, meta) { entries.push({ level: "info", event, meta }); },
      warn(event, meta) { entries.push({ level: "warn", event, meta }); },
      error(event, meta) { entries.push({ level: "error", event, meta }); },
    },
  };
}

function collectLines(socket) {
  const lines = [];
  const waiters = [];
  let buffer = "";
  socket.on("data", (chunk) => {
    buffer += chunk.toString("utf8");
    let newline = buffer.indexOf("\n");
    while (newline >= 0) {
      const line = buffer.slice(0, newline).trim();
      buffer = buffer.slice(newline + 1);
      if (line) {
        const message = JSON.parse(line);
        lines.push(message);
        for (let index = waiters.length - 1; index >= 0; index -= 1) {
          if (waiters[index].predicate(message)) {
            const [{ resolve, timer }] = waiters.splice(index, 1);
            clearTimeout(timer);
            resolve(message);
          }
        }
      }
      newline = buffer.indexOf("\n");
    }
  });
  lines.waitFor = (predicate, timeoutMs = 1000) => {
    const existing = lines.find(predicate);
    if (existing) return Promise.resolve(existing);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(
        () => reject(new Error("timed out waiting for phone-link frame")),
        timeoutMs,
      );
      waiters.push({ predicate, resolve, timer });
    });
  };
  return lines;
}

function listen(server) {
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => resolve(server.address().port));
  });
}

async function waitUntil(predicate, message, timeoutMs = 1000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
  throw new Error(message);
}

async function closeServer(server, sockets) {
  for (const socket of sockets) socket.destroy();
  await new Promise((resolve) => server.close(resolve));
}

// Teardown of the fake phone servers can race a in-flight frame promise; a
// stray rejection then fails the whole file with no diagnostic at all. Keep
// the noise visible in the output without letting it kill the process.
process.on("unhandledRejection", (reason) => {
  console.error("phone-link test unhandled rejection:", reason);
});

function createLink(overrides = {}) {
  const logs = loggerHarness();
  const store = new SessionStore(config, logs.logger);
  const link = new PhoneLink({
    config,
    store,
    logger: logs.logger,
    detailProvider: async () => [],
    // Never the real discovery port: announcing on 8793 makes any actual
    // phone on the LAN answer mid-test and dial into the assertions.
    discoveryPort: 48793,
    ...overrides,
  });
  return { link, store, logs };
}

test("discovered targets are deduplicated by host against static and backing-off targets", () => {
  const discovered = ["100.64.1.2:8792", "100.64.1.2:8792"];

  assert.deepEqual(
    buildPhoneDialTargets(["100.64.1.2"], discovered),
    [{ host: "100.64.1.2", port: 8792, name: "100.64.1.2" }],
  );
  assert.deepEqual(
    buildPhoneDialTargets(["100.64.1.2:18792"], discovered),
    [{ host: "100.64.1.2", port: 18792, name: "100.64.1.2" }],
  );
  assert.deepEqual(buildPhoneDialTargets([], discovered), [
    { host: "100.64.1.2", port: 8792, name: "100.64.1.2" },
  ]);
  assert.deepEqual(buildPhoneDialTargets([], discovered, new Set(["100.64.1.2"])), []);
});

test("disabled tailnet discovery does not invoke the peer scanner", async () => {
  let scans = 0;
  const { link, store } = createLink({
    config: { ...config, tailnetDiscovery: false },
    tailnetDiscovery: {
      async discover() {
        scans += 1;
        return ["100.64.1.2:8792"];
      },
    },
  });
  try {
    await link.refreshTargets();
    assert.equal(scans, 0);
  } finally {
    link.stop();
    store.dispose();
  }
});

test("phone target refresh picks up disk changes and keeps the last good list on corruption", async () => {
  const tempDir = await fsp.mkdtemp(path.join(os.tmpdir(), "nexus-agentd-phone-link-"));
  const sockets = [];
  let connections = 0;
  const server = net.createServer((socket) => {
    sockets.push(socket);
    connections += 1;
    collectLines(socket);
  });
  const port = await listen(server);
  const diskConfig = ensureConfig(tempDir);
  diskConfig.tailnetDiscovery = false;
  saveConfig(diskConfig, tempDir);
  let now = 30_000;
  let scans = 0;
  const { link, store } = createLink({
    config: diskConfig,
    configFilePath: configPath(tempDir),
    now: () => now,
    reconnectDelayMs: 10_000,
    tailnetDiscovery: {
      async discover() {
        scans += 1;
        return [];
      },
    },
  });

  try {
    link.start();
    await link.refreshTargets();
    diskConfig.phoneHosts = [`127.0.0.1:${port}`];
    saveConfig(diskConfig, tempDir);

    await link.refreshTargets();
    await waitUntil(() => connections === 1, "hot-reloaded target was not dialed");
    sockets[0].destroy();
    await waitUntil(() => !link.socket, "first hot-reload connection did not close");

    now += 10_000;
    await fsp.writeFile(configPath(tempDir), "{partial");
    await link.refreshTargets();
    await waitUntil(() => connections === 2, "last good target was not retained");
    assert.equal(scans, 0);
  } finally {
    link.stop();
    store.dispose();
    await closeServer(server, sockets);
    await fsp.rm(tempDir, { recursive: true, force: true });
  }
});

test("static phone targets dial and complete the existing handshake", async () => {
  const sockets = [];
  let acceptConnection;
  const accepted = new Promise((resolve) => { acceptConnection = resolve; });
  const server = net.createServer((socket) => {
    sockets.push(socket);
    acceptConnection({ socket, lines: collectLines(socket) });
  });
  const port = await listen(server);
  const { link, store } = createLink({
    config: { ...config, phoneHosts: [`127.0.0.1:${port}`] },
  });

  try {
    link.start();
    const { socket, lines } = await accepted;
    const hello = await lines.waitFor((frame) => frame.type === "hello");
    assert.equal(hello.machineId, config.machineId);
    assert.equal(hello.token, config.token);
    socket.write('{"type":"hello_ack","v":1}\n');
    await waitUntil(() => link.connected, "static target did not authenticate");
    await lines.waitFor((frame) => frame.type === "snapshot");
  } finally {
    link.stop();
    store.dispose();
    await closeServer(server, sockets);
  }
});

test("static phone refusal backoff prevents an immediate redial", async () => {
  const sockets = [];
  let connections = 0;
  const server = net.createServer((socket) => {
    sockets.push(socket);
    connections += 1;
    const lines = collectLines(socket);
    void lines.waitFor((frame) => frame.type === "hello").then(() => {
      socket.write('{"type":"hello_reject","v":1,"reason":"unknown_machine"}\n');
    }).catch(() => undefined);
  });
  const port = await listen(server);
  let now = 20_000;
  const { link, store } = createLink({
    config: { ...config, phoneHosts: [`127.0.0.1:${port}`] },
    now: () => now,
    reconnectDelayMs: 0,
    operatorMessage() {},
  });

  try {
    link.start();
    await waitUntil(() => connections === 1 && !link.socket, "static target did not refuse");
    link.announce();
    await new Promise((resolve) => setTimeout(resolve, 30));
    assert.equal(connections, 1, "static refusal backoff allowed an immediate redial");

    now += REFUSAL_RECONNECT_DELAY_MS;
    link.announce();
    await waitUntil(() => connections === 2, "static target did not retry after backoff");
  } finally {
    link.stop();
    store.dispose();
    await closeServer(server, sockets);
  }
});

test("TCP stays dialling until hello_ack, then sends data and accepts decisions", async () => {
  const sockets = [];
  let acceptConnection;
  const accepted = new Promise((resolve) => { acceptConnection = resolve; });
  const server = net.createServer((socket) => {
    sockets.push(socket);
    const lines = collectLines(socket);
    acceptConnection({ socket, lines });
  });
  const port = await listen(server);
  const decisions = [];
  let connectedCallbacks = 0;
  let disconnectedCallbacks = 0;
  const { link, store, logs } = createLink({
    onApprovalDecision: (requestId, decision) => decisions.push({ requestId, decision }),
    onConnected: () => { connectedCallbacks += 1; },
    onDisconnected: () => { disconnectedCallbacks += 1; },
  });

  try {
    link.connectToPhone({ host: "127.0.0.1", port, name: "test phone" });
    const { socket, lines } = await accepted;
    const hello = await lines.waitFor((frame) => frame.type === "hello");
    assert.equal(hello.token, config.token);
    assert.equal(link.connected, false);
    assert.equal(
      logs.entries.some((entry) => entry.event === "phone_link_connected"),
      false,
    );
    assert.equal(lines.some((frame) => frame.type === "snapshot"), false);

    socket.write('{"type":"hello_ack","v":1}\n');
    await waitUntil(() => link.connected, "link did not authenticate");
    assert.equal(connectedCallbacks, 1);
    assert.equal(
      logs.entries.filter((entry) => entry.event === "phone_link_connected").length,
      1,
    );
    await lines.waitFor((frame) => frame.type === "snapshot");

    const request = {
      type: "approval_request",
      v: 1,
      requestId: "phone-request",
      sessionId: "phone-session",
      tool: "Bash",
      summary: "npm test",
      detail: "cd /repo && npm test",
      createdAt: 123,
    };
    assert.equal(link.sendApprovalRequest(request), true);
    assert.deepEqual(
      await lines.waitFor((frame) => frame.type === "approval_request"),
      request,
    );
    socket.write(
      '{"type":"approval_decision","v":1,"requestId":"phone-request","decision":"allow"}\n',
    );
    await waitUntil(() => decisions.length === 1, "decision was not delivered");
    assert.deepEqual(decisions, [{ requestId: "phone-request", decision: "allow" }]);

    socket.destroy();
    await waitUntil(() => disconnectedCallbacks === 1, "disconnect callback was not delivered");
    assert.equal(link.connected, false);
  } finally {
    link.stop();
    store.dispose();
    await closeServer(server, sockets);
  }
});

test("authenticated fs_list frames validate ids and return filesystem listings", async () => {
  const tempDir = await fsp.mkdtemp(path.join(os.tmpdir(), "nexus-agentd-fs-list-"));
  const missingPath = path.join(tempDir, "missing");
  const sockets = [];
  let acceptConnection;
  const accepted = new Promise((resolve) => { acceptConnection = resolve; });
  const server = net.createServer((socket) => {
    sockets.push(socket);
    acceptConnection({ socket, lines: collectLines(socket) });
  });
  const port = await listen(server);
  const { link, store } = createLink();

  try {
    link.connectToPhone({ host: "127.0.0.1", port, name: "test phone" });
    const { socket, lines } = await accepted;
    await lines.waitFor((frame) => frame.type === "hello");
    socket.write('{"type":"hello_ack","v":1}\n');
    await lines.waitFor((frame) => frame.type === "snapshot");

    socket.write(`${JSON.stringify({ type: "fs_list" })}\n`);
    socket.write(`${JSON.stringify({ type: "fs_list", id: "" })}\n`);
    socket.write(`${JSON.stringify({ type: "fs_list", id: "x".repeat(65) })}\n`);
    await new Promise((resolve) => setTimeout(resolve, 30));
    assert.equal(lines.some((frame) => frame.type === "fs_listing"), false);

    const rootId = "roots-request-opaque";
    socket.write(`${JSON.stringify({ type: "fs_list", id: rootId })}\n`);
    const roots = await lines.waitFor(
      (frame) => frame.type === "fs_listing" && frame.id === rootId,
    );
    assert.equal(roots.path, null);
    assert.equal(roots.parent, null);
    assert.deepEqual(roots.entries[0], { name: "Home", path: os.homedir() });
    assert.equal(roots.truncated, false);
    assert.equal(roots.error, null);

    const requests = [
      { id: "relative", path: "relative/path", error: "Path must be a local absolute path" },
      { id: "unc", path: "\\\\server\\share", error: "Path must be a local absolute path" },
      { id: "overlong", path: path.resolve("x".repeat(4097)), error: "Path is too long" },
      { id: "missing", path: missingPath, error: "Path does not exist" },
    ];
    for (const request of requests) {
      socket.write(`${JSON.stringify({ type: "fs_list", id: request.id, path: request.path })}\n`);
    }
    for (const request of requests) {
      const listing = await lines.waitFor(
        (frame) => frame.type === "fs_listing" && frame.id === request.id,
      );
      assert.equal(listing.id, request.id);
      assert.equal(listing.path, request.path);
      assert.deepEqual(listing.entries, []);
      assert.equal(listing.truncated, false);
      assert.equal(listing.error, request.error);
    }
  } finally {
    link.stop();
    store.dispose();
    await closeServer(server, sockets);
    await fsp.rm(tempDir, { recursive: true, force: true });
  }
});

test("hello rejection prints actionable guidance once and backs off for 60 seconds", async () => {
  assert.equal(REFUSAL_RECONNECT_DELAY_MS, 60_000);
  const sockets = [];
  let connections = 0;
  const server = net.createServer((socket) => {
    sockets.push(socket);
    connections += 1;
    const attempt = connections;
    const lines = collectLines(socket);
    void lines.waitFor((frame) => frame.type === "hello").then(() => {
      if (attempt === 3) {
        socket.write('{"type":"hello_ack","v":1}\n');
      } else {
        socket.write('{"type":"hello_reject","v":1,"reason":"future_reason"}\n');
      }
    });
  });
  const port = await listen(server);
  let now = 10_000;
  const operatorMessages = [];
  const { link, store, logs } = createLink({
    now: () => now,
    reconnectDelayMs: 0,
    operatorMessage: (message) => operatorMessages.push(message),
  });
  const announcement = { host: "127.0.0.1", port, name: "rejecting phone" };

  try {
    link.connectToPhone(announcement);
    await waitUntil(() => connections === 1 && !link.socket, "first rejection did not close");
    assert.equal(operatorMessages.length, 1);
    assert.match(operatorMessages[0], /Agents \u2192 Link a computer/);
    assert.match(operatorMessages[0], /start the daemon again/);
    const unknownLog = logs.entries.find(
      (entry) => entry.event === "phone_link_reject_unknown_reason",
    );
    assert.equal(unknownLog.meta.reason, "future_reason");

    link.connectToPhone(announcement);
    await new Promise((resolve) => setTimeout(resolve, 30));
    assert.equal(connections, 1, "rejection backoff allowed an early redial");

    now += REFUSAL_RECONNECT_DELAY_MS;
    link.connectToPhone(announcement);
    await waitUntil(() => connections === 2 && !link.socket, "second rejection did not close");
    assert.equal(operatorMessages.length, 1, "same refusal guidance was repeated");

    now += REFUSAL_RECONNECT_DELAY_MS;
    link.connectToPhone(announcement);
    await waitUntil(() => connections === 3 && link.connected, "third attempt did not authenticate");
    sockets[2].destroy();
    await waitUntil(() => !link.socket, "authenticated attempt did not close");

    link.connectToPhone(announcement);
    await waitUntil(() => connections === 4 && !link.socket, "post-success rejection did not close");
    assert.equal(operatorMessages.length, 2, "successful hello_ack did not reset refusal reporting");
  } finally {
    link.stop();
    store.dispose();
    await closeServer(server, sockets);
  }
});

test("bad_token rejection explains regenerated identity and Forget computers", async () => {
  const sockets = [];
  const server = net.createServer((socket) => {
    sockets.push(socket);
    const lines = collectLines(socket);
    void lines.waitFor((frame) => frame.type === "hello").then(() => {
      socket.write('{"type":"hello_reject","v":1,"reason":"bad_token"}\n');
    });
  });
  const port = await listen(server);
  const operatorMessages = [];
  const { link, store } = createLink({
    operatorMessage: (message) => operatorMessages.push(message),
  });
  try {
    link.connectToPhone({ host: "127.0.0.1", port, name: "phone" });
    await waitUntil(() => operatorMessages.length === 1, "bad-token guidance was not printed");
    assert.match(operatorMessages[0], /identity was regenerated/);
    assert.match(operatorMessages[0], /Forget computers/);
    assert.equal(link.connected, false);
  } finally {
    link.stop();
    store.dispose();
    await closeServer(server, sockets);
  }
});

test("missing hello_ack closes after the handshake deadline and retries normally", async () => {
  const sockets = [];
  let connections = 0;
  const server = net.createServer((socket) => {
    sockets.push(socket);
    connections += 1;
    collectLines(socket);
  });
  const port = await listen(server);
  let now = 50_000;
  const { link, store, logs } = createLink({
    helloTimeoutMs: 25,
    reconnectDelayMs: 20,
    now: () => now,
    operatorMessage() {},
  });
  const announcement = { host: "127.0.0.1", port, name: "silent phone" };
  try {
    link.connectToPhone(announcement);
    await waitUntil(
      () => connections === 1 && !link.socket,
      "handshake timeout did not close the first socket",
    );
    assert.equal(link.connected, false);
    assert.equal(
      logs.entries.filter((entry) => entry.event === "phone_link_hello_timeout").length,
      1,
    );

    link.connectToPhone(announcement);
    await new Promise((resolve) => setTimeout(resolve, 10));
    assert.equal(connections, 1, "normal reconnect delay was skipped");
    now += 20;
    link.connectToPhone(announcement);
    await waitUntil(() => connections === 2, "normal retry did not dial again");
  } finally {
    link.stop();
    store.dispose();
    await closeServer(server, sockets);
  }
});

test("snapshots and deltas publish at most 200 sessions per provider", async () => {
  const sockets = [];
  let acceptConnection;
  const accepted = new Promise((resolve) => { acceptConnection = resolve; });
  const server = net.createServer((socket) => {
    sockets.push(socket);
    const lines = collectLines(socket);
    acceptConnection({ socket, lines });
  });
  const port = await listen(server);
  const { link, store } = createLink();
  for (let index = 0; index < 201; index += 1) {
    store.upsertProviderSession({
      id: `codex-${index}`,
      provider: "codex",
      machineId: config.machineId,
      machineName: config.machineName,
      title: `Codex ${index}`,
      cwd: "E:/repo",
      project: "repo",
      status: "idle",
      stale: false,
      lastActivityAt: index,
    });
  }

  try {
    link.connectToPhone({ host: "127.0.0.1", port, name: "test phone" });
    const { socket, lines } = await accepted;
    await lines.waitFor((frame) => frame.type === "hello");
    socket.write('{"type":"hello_ack","v":1}\n');
    const snapshot = await lines.waitFor((frame) => frame.type === "snapshot");
    assert.equal(MAX_PHONE_SESSIONS_PER_PROVIDER, 200);
    assert.equal(
      snapshot.sessions.filter((session) => session.provider === "codex").length,
      200,
    );
    assert.equal(snapshot.sessions.some((session) => session.id === "codex-0"), false);

    store.upsertProviderSession({
      id: "codex-0",
      provider: "codex",
      machineId: config.machineId,
      machineName: config.machineName,
      title: "Codex 0 now active",
      cwd: "E:/repo",
      project: "repo",
      status: "working",
      stale: false,
      lastActivityAt: 1000,
    });
    const removed = await lines.waitFor(
      (frame) => frame.type === "session_removed" && frame.sessionId === "codex-1",
    );
    const upsert = await lines.waitFor(
      (frame) => frame.type === "session_upsert" && frame.session.id === "codex-0",
    );
    assert.equal(removed.seq, snapshot.seq + 1);
    assert.equal(upsert.seq, snapshot.seq + 2);
  } finally {
    link.stop();
    store.dispose();
    await closeServer(server, sockets);
  }
});
