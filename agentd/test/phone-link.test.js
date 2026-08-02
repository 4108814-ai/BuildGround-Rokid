const test = require("node:test");
const assert = require("node:assert/strict");
const net = require("node:net");
const { SessionStore } = require("../dist/session-store.js");
const {
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

function createLink(overrides = {}) {
  const logs = loggerHarness();
  const store = new SessionStore(config, logs.logger);
  const link = new PhoneLink({
    config,
    store,
    logger: logs.logger,
    detailProvider: async () => [],
    ...overrides,
  });
  return { link, store, logs };
}

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
