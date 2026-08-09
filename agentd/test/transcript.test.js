const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const fsp = require("node:fs/promises");
const os = require("node:os");
const path = require("node:path");
const { SessionStore } = require("../dist/session-store.js");
const { TranscriptTailer } = require("../dist/transcript.js");
const { silentLogger } = require("../dist/logger.js");

test("tailer reads appends, buffers partial lines, and applies tool and error entries", async () => {
  const tempDir = await fsp.mkdtemp(path.join(os.tmpdir(), "nexus-agentd-transcript-"));
  const transcriptPath = path.join(tempDir, "session.jsonl");
  const fixturePath = path.join(__dirname, "fixtures", "transcript-appends.jsonl");
  const fixtureLines = fs.readFileSync(fixturePath, "utf8").trimEnd().split(/\r?\n/);
  await fsp.writeFile(transcriptPath, '{"type":"system","timestamp":1753380000000}\n');

  const store = new SessionStore(
    { machineId: "machine-test", machineName: "test-pc" },
    silentLogger,
  );
  store.handleHook({
    session_id: "tail-session",
    transcript_path: transcriptPath,
    cwd: "E:\\work\\tail",
    hook_event_name: "UserPromptSubmit",
    prompt: "Tail this",
  });
  const tailer = new TranscriptTailer(
    transcriptPath,
    (update) => store.applyTranscriptUpdate("tail-session", update),
    silentLogger,
    { pollIntervalMs: 60_000 },
  );

  try {
    await tailer.start();
    const assistantLine = fixtureLines[0];
    const splitAt = Math.floor(assistantLine.length / 2);
    await fsp.appendFile(transcriptPath, assistantLine.slice(0, splitAt));
    await tailer.pollNow();
    assert.equal(store.get("tail-session").lastAssistantText, undefined);

    await fsp.appendFile(transcriptPath, `${assistantLine.slice(splitAt)}\n`);
    await tailer.pollNow();
    let session = store.get("tail-session");
    assert.equal(session.lastAssistantText, "I am checking the build output now.");
    assert.equal(session.turn.lastTool, "Bash");
    assert.equal(session.status, "working");

    await fsp.appendFile(transcriptPath, `${fixtureLines[1]}\n`);
    await tailer.pollNow();
    session = store.get("tail-session");
    assert.equal(session.status, "error");
    assert.equal(session.statusDetail, "API request failed while reading the response");
  } finally {
    tailer.stop();
    store.dispose();
    await fsp.rm(tempDir, { recursive: true, force: true });
  }
});
