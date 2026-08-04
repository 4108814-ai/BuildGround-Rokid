const test = require("node:test");
const assert = require("node:assert/strict");
const { EventEmitter } = require("node:events");
const { PassThrough } = require("node:stream");
const { WebSocketServer } = require("ws");
const { ApprovalManager } = require("../dist/approval-manager.js");
const {
  CodexMonitor,
  MAX_CODEX_SESSIONS,
  codexApprovalRequestId,
  codexApprovalResponse,
  codexSpawnSpec,
  codexTerminateSpec,
  describeCodexApproval,
  normalizeCodexThread,
} = require("../dist/codex/monitor.js");
const { silentLogger } = require("../dist/logger.js");
const { SessionStore } = require("../dist/session-store.js");
const {
  CODEX_APP_SERVER_BINDINGS_VERSION,
} = require("../dist/codex/protocol.js");

const identity = {
  machineId: "codex-machine",
  machineName: "codex-pc",
};

function makeThread(overrides = {}) {
  return {
    id: "codex-thread-1",
    sessionId: "codex-session-tree",
    forkedFromId: null,
    parentThreadId: null,
    preview: "Implement the Codex monitor",
    ephemeral: false,
    modelProvider: "openai",
    createdAt: 1_753_000_000,
    updatedAt: 1_753_000_010,
    recencyAt: 1_753_000_020,
    status: { type: "idle" },
    path: "C:\\Users\\test\\.codex\\sessions\\thread.jsonl",
    cwd: "E:\\work\\sample",
    cliVersion: "0.145.0",
    source: "cli",
    threadSource: null,
    agentNickname: null,
    agentRole: null,
    gitInfo: null,
    name: null,
    turns: [],
    ...overrides,
  };
}

function turn(status, overrides = {}) {
  return {
    id: "turn-1",
    items: [],
    itemsView: "all",
    status,
    error: status === "failed"
      ? { message: "model failed", codexErrorInfo: null, additionalDetails: null }
      : null,
    startedAt: 1_753_000_030,
    completedAt: status === "inProgress" ? null : 1_753_000_040,
    durationMs: status === "inProgress" ? null : 10_000,
    ...overrides,
  };
}

class FakeApprovalTransport {
  connected = true;
  frames = [];

  sendApprovalRequest(request) {
    if (!this.connected) return false;
    this.frames.push({ ...request });
    return true;
  }

  sendApprovalResolved(requestId, outcome) {
    if (!this.connected) return false;
    this.frames.push({ type: "approval_resolved", v: 1, requestId, outcome });
    return true;
  }
}

async function waitUntil(predicate, message, timeoutMs = 2500) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const result = predicate();
    if (result) return result;
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
  throw new Error(message);
}

function listen(server, port = 0) {
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(port, "127.0.0.1", () => resolve(server.address().port));
  });
}

async function closeServer(server) {
  for (const client of server.clients) client.terminate();
  await new Promise((resolve) => server.close(resolve));
}

function makeHarness(port, overrides = {}) {
  const store = new SessionStore(identity, silentLogger);
  const transport = new FakeApprovalTransport();
  const approvals = new ApprovalManager({
    transport,
    logger: silentLogger,
    timeoutMs: overrides.approvalTimeoutMs ?? 1000,
  });
  const monitor = new CodexMonitor({
    config: {
      ...identity,
      codex: { enabled: true, port },
    },
    store,
    approvals,
    logger: silentLogger,
    connectTimeoutMs: 80,
    requestTimeoutMs: 1000,
    startTimeoutMs: 500,
    reconnectDelayMs: 20,
    sweepIntervalMs: overrides.sweepIntervalMs,
    launch: overrides.launch ?? (() => {
      throw new Error("codex executable not found");
    }),
    terminate: overrides.terminate,
  });
  return { store, transport, approvals, monitor };
}

function attachProtocol(server, threadFactory = () => makeThread()) {
  const connections = [];
  server.on("connection", (socket) => {
    const record = { socket, messages: [] };
    connections.push(record);
    socket.on("message", (raw) => {
      const message = JSON.parse(raw.toString());
      record.messages.push(message);
      if (message.id === undefined) return;
      let result;
      switch (message.method) {
        case "initialize":
          result = { userAgent: "fake-codex" };
          break;
        case "thread/list":
          result = { data: [threadFactory()], nextCursor: null, backwardsCursor: null };
          break;
        case "thread/resume":
          result = {
            thread: threadFactory(),
            model: "gpt-5",
            modelProvider: "openai",
            serviceTier: null,
            cwd: threadFactory().cwd,
          };
          break;
        case "thread/read":
          result = { thread: threadFactory() };
          break;
        default:
          return;
      }
      socket.send(JSON.stringify({ id: message.id, result }));
    });
  });
  return connections;
}

test("Codex normalization has conservative explicit status, title, and path mappings", () => {
  const cases = [
    [{ status: { type: "active", activeFlags: [] }, turns: [turn("inProgress")] }, "working"],
    [{ status: { type: "active", activeFlags: ["waitingOnApproval"] } }, "needs_you"],
    [{ status: { type: "idle" }, turns: [turn("failed")] }, "error"],
    [{ status: { type: "systemError" } }, "error"],
    [{ status: { type: "notLoaded" }, turns: [turn("completed")] }, "idle"],
  ];
  for (const [overrides, expected] of cases) {
    assert.equal(normalizeCodexThread(makeThread(overrides), identity).status, expected);
  }

  const named = normalizeCodexThread(
    makeThread({ name: "  Friendly name  " }),
    identity,
  );
  assert.equal(named.provider, "codex");
  assert.equal(named.title, "Friendly name");
  assert.equal(named.cwd, "E:/work/sample");
  assert.equal(named.project, "sample");

  const pending = normalizeCodexThread(
    makeThread({ status: { type: "idle" }, turns: [turn("failed")] }),
    identity,
    { summary: "Approve npm test", createdAt: 1_753_000_050_000 },
  );
  assert.equal(pending.status, "needs_you", "an active approval takes precedence");
  assert.equal(pending.pendingRequest.kind, "permission");
});

test("approval projections and responses use the JSON-RPC id and exact stable contracts", () => {
  assert.notEqual(codexApprovalRequestId("rpc-7"), codexApprovalRequestId(7));
  assert.equal(codexApprovalRequestId("rpc-7"), "codex:s:cnBjLTc");

  const command = {
    id: "rpc-7",
    method: "item/commandExecution/requestApproval",
    params: {
      threadId: "thread",
      turnId: "turn",
      itemId: "reused-item-id",
      startedAtMs: 123,
      environmentId: null,
      reason: "Run tests",
      command: "npm test",
      cwd: "E:\\repo",
    },
  };
  assert.deepEqual(describeCodexApproval(command), {
    tool: "Command",
    summary: "Run tests",
    detail: "npm test",
    createdAt: 123,
  });
  assert.deepEqual(codexApprovalResponse(command, "allow"), { decision: "accept" });
  assert.deepEqual(codexApprovalResponse(command, "deny"), { decision: "decline" });

  const permissions = {
    id: 9,
    method: "item/permissions/requestApproval",
    params: {
      threadId: "thread",
      turnId: "turn",
      itemId: "item",
      environmentId: null,
      startedAtMs: 456,
      cwd: "E:\\repo",
      reason: null,
      permissions: {
        network: { enabled: true },
        fileSystem: { read: ["E:\\input"], write: ["E:\\output"] },
      },
    },
  };
  assert.deepEqual(codexApprovalResponse(permissions, "allow"), {
    permissions: permissions.params.permissions,
    scope: "turn",
  });
  assert.deepEqual(codexApprovalResponse(permissions, "deny"), {
    permissions: {},
    scope: "turn",
  });
});

test("spawn command is loopback-only and honors the Windows .cmd shim", () => {
  assert.equal(CODEX_APP_SERVER_BINDINGS_VERSION, "0.145.0");
  const windows = codexSpawnSpec(8390, "win32");
  assert.equal(windows.command, "codex");
  assert.deepEqual(windows.args, [
    "app-server",
    "--listen",
    "ws://127.0.0.1:8390",
  ]);
  assert.equal(windows.options.shell, true);
  assert.equal(windows.options.windowsHide, true);
  assert.equal(windows.args.some((arg) => arg.includes("0.0.0.0")), false);
  assert.deepEqual(codexTerminateSpec(4321, "win32"), {
    command: "taskkill.exe",
    args: ["/pid", "4321", "/t", "/f"],
    options: {
      shell: false,
      windowsHide: true,
      stdio: "ignore",
    },
  });

  assert.equal(codexSpawnSpec(8390, "linux").options.shell, false);
  assert.equal(codexTerminateSpec(4321, "linux"), undefined);
});

test("monitor attaches, resumes, refreshes, streams statuses, and routes approval responses", async () => {
  const server = new WebSocketServer({ noServer: true });
  const port = await new Promise((resolve, reject) => {
    const http = require("node:http").createServer();
    http.once("error", reject);
    http.listen(0, "127.0.0.1", () => {
      http.on("upgrade", (request, socket, head) => {
        server.handleUpgrade(request, socket, head, (ws) => server.emit("connection", ws, request));
      });
      server._httpServer = http;
      resolve(http.address().port);
    });
  });
  let currentThread = makeThread();
  const connections = attachProtocol(server, () => structuredClone(currentThread));
  const { store, transport, approvals, monitor } = makeHarness(port);

  try {
    monitor.start();
    await waitUntil(() => monitor.availability().available, "monitor did not attach");
    assert.equal(store.get(currentThread.id).provider, "codex");
    assert.deepEqual(
      connections[0].messages.map((message) => message.method),
      ["initialize", "initialized", "thread/list", "thread/resume", "thread/read"],
    );

    currentThread = makeThread({
      status: { type: "active", activeFlags: [] },
      turns: [turn("inProgress")],
    });
    connections[0].socket.send(JSON.stringify({
      method: "turn/started",
      params: { threadId: currentThread.id, turn: currentThread.turns[0] },
    }));
    await waitUntil(() => store.get(currentThread.id)?.status === "working", "turn did not go working");

    const approvalRpcId = "approval-request-44";
    connections[0].socket.send(JSON.stringify({
      id: approvalRpcId,
      method: "item/commandExecution/requestApproval",
      params: {
        threadId: currentThread.id,
        turnId: "turn-1",
        itemId: "not-unique",
        startedAtMs: Date.now(),
        environmentId: null,
        reason: "Run the test suite",
        command: "npm test",
        cwd: "E:\\work\\sample",
      },
    }));
    const frame = await waitUntil(
      () => transport.frames.find((entry) => entry.type === "approval_request"),
      "approval was not sent to the phone transport",
    );
    assert.equal(frame.requestId, codexApprovalRequestId(approvalRpcId));
    assert.equal(store.get(currentThread.id).status, "needs_you");

    approvals.handleDecision(frame.requestId, "allow");
    const response = await waitUntil(
      () => connections[0].messages.find(
        (message) => message.id === approvalRpcId && message.result,
      ),
      "approval response was not returned to Codex",
    );
    assert.deepEqual(response.result, { decision: "accept" });

    const droppedRpcId = 45;
    connections[0].socket.send(JSON.stringify({
      id: droppedRpcId,
      method: "item/fileChange/requestApproval",
      params: {
        threadId: currentThread.id,
        turnId: "turn-1",
        itemId: "also-not-a-routing-key",
        startedAtMs: Date.now(),
        reason: "Write the generated report",
        grantRoot: "E:\\work\\sample",
      },
    }));
    await waitUntil(
      () => transport.frames.find(
        (entry) => entry.requestId === codexApprovalRequestId(droppedRpcId),
      ),
      "second approval was not sent to the phone transport",
    );
    transport.connected = false;
    approvals.onLinkDisconnected();
    await new Promise((resolve) => setTimeout(resolve, 20));
    assert.equal(
      connections[0].messages.some((message) => message.id === droppedRpcId && message.result),
      false,
      "phone link loss must not answer the Codex request",
    );
    assert.equal(store.get(currentThread.id).status, "needs_you");
    transport.connected = true;

    connections[0].socket.terminate();
    await waitUntil(() => connections.length === 2, "monitor did not reconnect");
    await waitUntil(
      () => connections[1].messages.some((message) => message.method === "thread/read"),
      "reconnect did not refresh",
    );
    const methods = connections[1].messages.map((message) => message.method);
    assert.ok(
      methods.indexOf("thread/resume") < methods.indexOf("thread/list"),
      "known threads must be re-resumed before the authoritative list refresh",
    );
    assert.ok(methods.indexOf("thread/list") < methods.indexOf("thread/read"));
  } finally {
    await monitor.stop();
    approvals.dispose();
    store.dispose();
    for (const client of server.clients) client.terminate();
    await new Promise((resolve) => server._httpServer.close(resolve));
    server.close();
  }
});

test("monitor caps discovery at the session cap and never requests an extra page", async () => {
  const server = new WebSocketServer({ port: 0, host: "127.0.0.1" });
  await new Promise((resolve) => server.once("listening", resolve));
  const port = server.address().port;
  const fakePageSize = 40;
  const listRequests = [];
  server.on("connection", (socket) => {
    socket.on("message", (raw) => {
      const message = JSON.parse(raw.toString());
      if (message.id === undefined) return;
      if (message.method === "initialize") {
        socket.send(JSON.stringify({ id: message.id, result: {} }));
        return;
      }
      if (message.method === "thread/list") {
        listRequests.push(message.params);
        const offset = (listRequests.length - 1) * fakePageSize;
        const data = Array.from({ length: fakePageSize }, (_, index) =>
          makeThread({
            id: `thread-${offset + index}`,
            updatedAt: 1_753_001_000 - offset - index,
            recencyAt: 1_753_001_000 - offset - index,
          }),
        );
        socket.send(JSON.stringify({
          id: message.id,
          result: {
            data,
            nextCursor: `page-${listRequests.length + 1}-must-not-be-read-past-the-cap`,
            backwardsCursor: null,
          },
        }));
        return;
      }
      const threadId = message.params.threadId;
      const thread = makeThread({ id: threadId });
      const result = message.method === "thread/resume"
        ? {
            thread,
            model: "gpt-5",
            modelProvider: "openai",
            serviceTier: null,
            cwd: thread.cwd,
          }
        : { thread };
      socket.send(JSON.stringify({ id: message.id, result }));
    });
  });
  const { store, approvals, monitor } = makeHarness(port);
  try {
    monitor.start();
    await waitUntil(() => monitor.availability().available, "monitor did not finish discovery", 5000);
    assert.equal(
      store.list().filter((session) => session.provider === "codex").length,
      MAX_CODEX_SESSIONS,
    );
    assert.equal(listRequests.length, Math.ceil(MAX_CODEX_SESSIONS / fakePageSize));
    assert.equal(listRequests[0].limit, MAX_CODEX_SESSIONS);
    assert.equal(listRequests.at(-1).limit, MAX_CODEX_SESSIONS - fakePageSize);
    assert.ok(listRequests.every((params) => params.sortKey === "recency_at"));
    assert.ok(listRequests.every((params) => params.sourceKinds.includes("exec")));
  } finally {
    await monitor.stop();
    approvals.dispose();
    store.dispose();
    await closeServer(server);
  }
});

test("when attach fails the monitor starts and owns a loopback app-server lifecycle", async () => {
  const probe = new WebSocketServer({ port: 0, host: "127.0.0.1" });
  await new Promise((resolve) => probe.once("listening", resolve));
  const port = probe.address().port;
  await closeServer(probe);

  let ownedServer;
  let killed = false;
  class FakeChild extends EventEmitter {
    pid = 4242;
    stderr = new PassThrough();
    exitCode = null;
    signalCode = null;
    kill() {
      killed = true;
      this.exitCode = 0;
      for (const client of ownedServer.clients) client.terminate();
      ownedServer.close();
      this.emit("exit", 0, null);
      return true;
    }
  }
  const child = new FakeChild();
  const launch = (requestedPort) => {
    assert.equal(requestedPort, port);
    ownedServer = new WebSocketServer({ port: requestedPort, host: "127.0.0.1" });
    attachProtocol(ownedServer);
    return child;
  };
  const { store, approvals, monitor } = makeHarness(port, {
    launch,
    terminate: async (owned) => {
      owned.kill();
    },
  });
  try {
    monitor.start();
    await waitUntil(() => monitor.availability().available, "owned server was not reached");
    assert.equal(store.get("codex-thread-1").provider, "codex");
  } finally {
    await monitor.stop();
    assert.equal(killed, true, "monitor did not terminate the app-server it started");
    approvals.dispose();
    store.dispose();
  }
});

test("missing Codex is an availability reason, not a monitor startup error", async () => {
  const probe = new WebSocketServer({ port: 0, host: "127.0.0.1" });
  await new Promise((resolve) => probe.once("listening", resolve));
  const port = probe.address().port;
  await closeServer(probe);
  const { store, approvals, monitor } = makeHarness(port);
  try {
    assert.doesNotThrow(() => monitor.start());
    const state = await waitUntil(
      () => {
        const availability = monitor.availability();
        return availability.reason?.includes("could not start") ? availability : undefined;
      },
      "missing Codex reason was not reported",
    );
    assert.equal(state.enabled, true);
    assert.equal(state.available, false);
    assert.match(state.reason, /codex executable not found/);
    assert.equal(store.size, 0);
  } finally {
    await monitor.stop();
    approvals.dispose();
    store.dispose();
  }
});

test("disabled Codex monitoring neither connects nor launches a process", async () => {
  const store = new SessionStore(identity, silentLogger);
  const transport = new FakeApprovalTransport();
  const approvals = new ApprovalManager({ transport, logger: silentLogger });
  let launches = 0;
  const monitor = new CodexMonitor({
    config: { ...identity, codex: { enabled: false, port: 8390 } },
    store,
    approvals,
    logger: silentLogger,
    launch() {
      launches += 1;
      throw new Error("must not launch");
    },
  });
  try {
    monitor.start();
    await new Promise((resolve) => setTimeout(resolve, 20));
    assert.deepEqual(monitor.availability(), {
      enabled: false,
      available: false,
      reason: "disabled",
    });
    assert.equal(launches, 0);
  } finally {
    await monitor.stop();
    approvals.dispose();
    store.dispose();
  }
});

test("the periodic sweep adopts threads born outside the app-server connection", async () => {
  const server = new WebSocketServer({ port: 0, host: "127.0.0.1" });
  await new Promise((resolve) => server.once("listening", resolve));
  const port = server.address().port;
  const threads = new Map([["thread-a", makeThread({ id: "thread-a" })]]);
  server.on("connection", (socket) => {
    socket.on("message", (raw) => {
      const message = JSON.parse(raw.toString());
      if (message.id === undefined) return;
      let result;
      switch (message.method) {
        case "initialize":
          result = { userAgent: "fake-codex" };
          break;
        case "thread/list":
          result = { data: [...threads.values()], nextCursor: null, backwardsCursor: null };
          break;
        case "thread/resume":
          result = {
            thread: threads.get(message.params.threadId) ?? makeThread(),
            model: "gpt-5",
            modelProvider: "openai",
            serviceTier: null,
            cwd: "E:\work\sample",
          };
          break;
        case "thread/read":
          result = { thread: threads.get(message.params.threadId) ?? makeThread() };
          break;
        default:
          return;
      }
      socket.send(JSON.stringify({ id: message.id, result }));
    });
  });
  const { store, approvals, monitor } = makeHarness(port, { sweepIntervalMs: 40 });
  try {
    monitor.start();
    await waitUntil(() => monitor.availability().available, "monitor did not connect");
    assert.equal(store.get("thread-b"), undefined);

    // A terminal-only run appears in storage without any server notification.
    threads.set("thread-b", makeThread({ id: "thread-b", preview: "CLI run" }));
    const adopted = await waitUntil(
      () => store.get("thread-b"),
      "sweep did not adopt the CLI thread",
    );
    assert.equal(adopted.provider, "codex");
    assert.equal(adopted.title, "CLI run");
  } finally {
    await monitor.stop();
    approvals.dispose();
    store.dispose();
    await closeServer(server);
  }
});
