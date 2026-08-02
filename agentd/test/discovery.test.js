const test = require("node:test");
const assert = require("node:assert/strict");
const fsp = require("node:fs/promises");
const os = require("node:os");
const path = require("node:path");
const { discoverRecentSessions } = require("../dist/discovery.js");
const { silentLogger } = require("../dist/logger.js");

test("discovery titles use the last human user text and skip synthetic wrappers", async () => {
  const tempDir = await fsp.mkdtemp(path.join(os.tmpdir(), "nexus-agentd-discovery-"));
  const projectsDir = path.join(tempDir, "projects");
  const projectDir = path.join(projectsDir, "sample-project");
  const transcriptPath = path.join(projectDir, "session-title.jsonl");
  try {
    await fsp.mkdir(projectDir, { recursive: true });
    const entries = [
      { type: "user", message: { role: "user", content: "Earlier human title" } },
      { type: "user", message: { role: "user", content: "Latest human title" } },
      {
        type: "user",
        message: {
          role: "user",
          content: "  <task-notification>synthetic wrapper</task-notification>",
        },
      },
    ];
    await fsp.writeFile(
      transcriptPath,
      `${entries.map((entry) => JSON.stringify(entry)).join("\n")}\n`,
    );

    const sessions = await discoverRecentSessions(projectsDir, silentLogger);
    assert.equal(sessions.length, 1);
    assert.equal(sessions[0].title, "Latest human title");
  } finally {
    await fsp.rm(tempDir, { recursive: true, force: true });
  }
});
