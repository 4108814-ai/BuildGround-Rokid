const test = require("node:test");
const assert = require("node:assert/strict");
const fsp = require("node:fs/promises");
const os = require("node:os");
const path = require("node:path");
const {
  DEFAULT_CODEX_PORT,
  configPath,
  ensureConfig,
} = require("../dist/config.js");

test("new and upgraded configs keep optional integrations disabled by default", async () => {
  const tempDir = await fsp.mkdtemp(path.join(os.tmpdir(), "nexus-agentd-config-"));
  try {
    const fresh = ensureConfig(tempDir);
    assert.deepEqual(fresh.codex, { enabled: false, port: DEFAULT_CODEX_PORT });
    assert.deepEqual(fresh.phoneHosts, []);

    const legacy = {
      token: "legacy-token",
      wsPort: 8792,
      httpPort: 8791,
      machineId: "legacy-machine",
      machineName: "legacy-pc",
    };
    await fsp.writeFile(configPath(tempDir), `${JSON.stringify(legacy)}\n`);
    const upgraded = ensureConfig(tempDir);
    assert.deepEqual(upgraded.codex, { enabled: false, port: DEFAULT_CODEX_PORT });
    assert.deepEqual(upgraded.phoneHosts, []);
    const persisted = JSON.parse(await fsp.readFile(configPath(tempDir), "utf8"));
    assert.deepEqual(persisted.codex, { enabled: false, port: DEFAULT_CODEX_PORT });
    assert.deepEqual(persisted.phoneHosts, []);
  } finally {
    await fsp.rm(tempDir, { recursive: true, force: true });
  }
});

test("phone targets accept hosts with optional valid ports and reject invalid shapes", async () => {
  const tempDir = await fsp.mkdtemp(path.join(os.tmpdir(), "nexus-agentd-config-"));
  try {
    const config = ensureConfig(tempDir);
    config.phoneHosts = ["phone.example", "100.64.0.2:18792"];
    await fsp.writeFile(configPath(tempDir), `${JSON.stringify(config)}\n`);
    assert.deepEqual(ensureConfig(tempDir).phoneHosts, config.phoneHosts);

    for (const invalid of [
      "phone.example",
      [""],
      ["phone.example:0"],
      ["phone.example:65536"],
      ["phone.example:not-a-port"],
      [42],
    ]) {
      config.phoneHosts = invalid;
      await fsp.writeFile(configPath(tempDir), `${JSON.stringify(config)}\n`);
      assert.throws(() => ensureConfig(tempDir), /Invalid nexus-agentd config/);
    }
  } finally {
    await fsp.rm(tempDir, { recursive: true, force: true });
  }
});

test("Codex configuration uses the existing config file and validates its loopback port", async () => {
  const tempDir = await fsp.mkdtemp(path.join(os.tmpdir(), "nexus-agentd-config-"));
  try {
    const config = ensureConfig(tempDir);
    config.codex = { enabled: true, port: 18490 };
    await fsp.writeFile(configPath(tempDir), `${JSON.stringify(config)}\n`);
    assert.deepEqual(ensureConfig(tempDir).codex, { enabled: true, port: 18490 });

    config.codex.port = 0;
    await fsp.writeFile(configPath(tempDir), `${JSON.stringify(config)}\n`);
    assert.throws(() => ensureConfig(tempDir), /Invalid nexus-agentd config/);

    config.codex = "yes";
    await fsp.writeFile(configPath(tempDir), `${JSON.stringify(config)}\n`);
    assert.throws(() => ensureConfig(tempDir), /Invalid nexus-agentd config/);
  } finally {
    await fsp.rm(tempDir, { recursive: true, force: true });
  }
});
