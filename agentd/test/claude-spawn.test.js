const test = require("node:test");
const assert = require("node:assert/strict");
const { EventEmitter } = require("node:events");
const { Writable } = require("node:stream");
const { createClaudeSpawner } = require("../dist/claude-spawn.js");

function fakeChild() {
  const child = new EventEmitter();
  child.input = "";
  child.stdin = new Writable({
    write(chunk, _encoding, callback) {
      child.input += chunk.toString();
      callback();
    },
  });
  child.unreferenced = false;
  child.unref = () => {
    child.unreferenced = true;
  };
  return child;
}

test("Claude spawn keeps the prompt on stdin and caches CLI location", async () => {
  const calls = [];
  let locateCalls = 0;
  const spawnProcess = (command, args, options) => {
    const child = fakeChild();
    calls.push({ command, args, options, child });
    queueMicrotask(() => child.emit("spawn"));
    return child;
  };
  const startClaude = createClaudeSpawner(spawnProcess, "win32", () => {
    locateCalls += 1;
    return true;
  });
  const prompt = 'fix "quotes" & never put this in argv';

  assert.deepEqual(await startClaude("E:\\work\\project", prompt), { ok: true });
  assert.deepEqual(await startClaude("E:\\work\\other", "second prompt"), { ok: true });

  assert.equal(locateCalls, 1);
  assert.equal(calls[0].command, "cmd.exe");
  assert.deepEqual(calls[0].args, ["/d", "/s", "/c", "claude -p"]);
  assert.equal(calls[0].args.some((arg) => arg.includes(prompt)), false);
  assert.equal(calls[0].options.cwd, "E:\\work\\project");
  assert.equal(calls[0].options.detached, true);
  assert.equal(calls[0].options.shell, false);
  assert.deepEqual(calls[0].options.stdio, ["pipe", "ignore", "ignore"]);
  assert.equal(calls[0].child.input, prompt);
  assert.equal(calls[0].child.unreferenced, true);
});

test("Claude spawn maps ENOENT to the stable CLI-not-found error", async () => {
  let spawnCalls = 0;
  const startClaude = createClaudeSpawner(() => {
    spawnCalls += 1;
    const child = fakeChild();
    queueMicrotask(() => {
      const error = new Error("spawn failed");
      error.code = "ENOENT";
      child.emit("error", error);
    });
    return child;
  }, "linux", () => true);

  assert.deepEqual(await startClaude("/work/project", "do the thing"), {
    ok: false,
    error: "Claude Code CLI not found on this computer",
  });
  assert.deepEqual(await startClaude("/work/project", "try again"), {
    ok: false,
    error: "Claude Code CLI not found on this computer",
  });
  assert.equal(spawnCalls, 1, "ENOENT availability should be cached");
});
