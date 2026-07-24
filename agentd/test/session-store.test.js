const test = require("node:test");
const assert = require("node:assert/strict");
const { SessionStore } = require("../dist/session-store.js");
const { silentLogger } = require("../dist/logger.js");

const identity = {
  machineId: "machine-test",
  machineName: "test-pc",
};

test("scripted hooks drive normalized status and pending-request transitions", () => {
  let now = 1_753_380_000_000;
  const store = new SessionStore(identity, silentLogger, () => now);
  const base = {
    session_id: "session-123456789",
    transcript_path: "C:\\tmp\\session.jsonl",
    cwd: "E:\\work\\sample",
  };

  store.handleHook({ ...base, hook_event_name: "SessionStart", source: "startup" });
  let session = store.get(base.session_id);
  assert.equal(session.status, "idle");
  assert.equal(session.stale, false);
  assert.equal(session.cwd, "E:/work/sample");
  assert.equal(session.project, "sample");

  now += 10;
  store.handleHook({
    ...base,
    hook_event_name: "UserPromptSubmit",
    prompt: "Implement the monitoring protocol",
  });
  session = store.get(base.session_id);
  assert.equal(session.status, "working");
  assert.equal(session.title, "Implement the monitoring protocol");
  assert.equal(session.turn.activeSince, now);

  now += 10;
  store.handleHook({
    ...base,
    hook_event_name: "Notification",
    message: "Permission required to run a command",
  });
  session = store.get(base.session_id);
  assert.equal(session.status, "needs_you");
  assert.deepEqual(session.pendingRequest, {
    kind: "permission",
    summary: "Permission required to run a command",
    createdAt: now,
  });

  now += 10;
  store.handleHook({ ...base, hook_event_name: "Stop" });
  assert.equal(store.get(base.session_id).status, "needs_you");

  now += 10;
  store.handleHook({
    ...base,
    hook_event_name: "UserPromptSubmit",
    prompt: "Continue",
  });
  session = store.get(base.session_id);
  assert.equal(session.status, "working");
  assert.equal(session.pendingRequest, undefined);

  now += 10;
  store.handleHook({ ...base, hook_event_name: "Stop" });
  assert.equal(store.get(base.session_id).status, "idle");

  now += 10;
  store.handleHook({
    ...base,
    hook_event_name: "Notification",
    message: "Claude is waiting for your input",
  });
  session = store.get(base.session_id);
  assert.equal(session.status, "needs_you");
  assert.equal(session.pendingRequest.kind, "idle_prompt");

  now += 10;
  store.handleHook({
    ...base,
    hook_event_name: "UserPromptSubmit",
    prompt: "Resolve the question",
  });
  now += 10;
  store.handleHook({ ...base, hook_event_name: "Stop" });
  assert.equal(store.get(base.session_id).status, "idle");
  assert.equal(store.get(base.session_id).pendingRequest, undefined);

  store.dispose();
});

test("working sessions become stalled errors and session end becomes done", () => {
  let now = 1_753_380_000_000;
  const store = new SessionStore(identity, silentLogger, () => now);
  const base = {
    session_id: "stalled-session",
    cwd: "E:\\work\\sample",
  };
  store.handleHook({ ...base, hook_event_name: "UserPromptSubmit", prompt: "Wait" });
  now += 30 * 60 * 1000 + 1;
  store.sweepStalled();
  assert.equal(store.get(base.session_id).status, "error");
  assert.equal(store.get(base.session_id).statusDetail, "stalled?");

  store.handleHook({ ...base, hook_event_name: "SessionEnd", reason: "completed" });
  assert.equal(store.get(base.session_id).status, "done");
  store.dispose();
});
