const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const {
  listDirectory,
  listRoots,
  MAX_FS_ENTRIES,
} = require("../dist/fs-browse.js");

function directoryEntry(name, kind = "directory") {
  return {
    name,
    isDirectory: () => kind === "directory" || kind === "symlink",
    isSymbolicLink: () => kind === "symlink",
  };
}

test("Windows roots start with Home and include only existing drives", () => {
  const checked = [];
  const listing = listRoots("win32", "C:\\Users\\tester", (drivePath) => {
    checked.push(drivePath);
    return drivePath === "C:\\" || drivePath === "E:\\";
  });

  assert.deepEqual(listing, {
    path: null,
    parent: null,
    entries: [
      { name: "Home", path: "C:\\Users\\tester" },
      { name: "C:\\", path: "C:\\" },
      { name: "E:\\", path: "E:\\" },
    ],
    truncated: false,
    error: null,
  });
  assert.equal(checked.length, 26);
  assert.equal(checked[0], "A:\\");
  assert.equal(checked.at(-1), "Z:\\");
});

test("non-Windows roots contain Home followed by the filesystem root", () => {
  const listing = listRoots("linux", "/home/tester", () => {
    throw new Error("drive checks are Windows-only");
  });

  assert.deepEqual(listing, {
    path: null,
    parent: null,
    entries: [
      { name: "Home", path: "/home/tester" },
      { name: "/", path: "/" },
    ],
    truncated: false,
    error: null,
  });
});

test("directory listings keep visible real directories and sort case-insensitively", async () => {
  const requestedPath = path.resolve(path.parse(process.cwd()).root, "BrowseTarget");
  let readRequest;
  const listing = await listDirectory(requestedPath, async (directoryPath, options) => {
    readRequest = { directoryPath, options };
    return [
      directoryEntry("zeta"),
      directoryEntry(".hidden"),
      directoryEntry("notes.txt", "file"),
      directoryEntry("linked-directory", "symlink"),
      directoryEntry("alpha"),
      directoryEntry("Beta"),
    ];
  });

  assert.deepEqual(readRequest, { directoryPath: requestedPath, options: { withFileTypes: true } });
  assert.deepEqual(listing, {
    path: requestedPath,
    parent: path.dirname(requestedPath),
    entries: ["alpha", "Beta", "zeta"].map((name) => ({
      name,
      path: path.join(requestedPath, name),
    })),
    truncated: false,
    error: null,
  });
});

test("directory listings cap visible directories at 300 and report truncation", async () => {
  const requestedPath = path.resolve(path.parse(process.cwd()).root, "ManyDirectories");
  const directoryEntries = Array.from({ length: MAX_FS_ENTRIES + 1 }, (_, index) =>
    directoryEntry(`Dir${String(index).padStart(3, "0")}`),
  );
  const listing = await listDirectory(requestedPath, async () => directoryEntries);

  assert.equal(MAX_FS_ENTRIES, 300);
  assert.equal(listing.entries.length, MAX_FS_ENTRIES);
  assert.equal(listing.entries[0].name, "Dir000");
  assert.equal(listing.entries.at(-1).name, "Dir299");
  assert.equal(listing.truncated, true);
  assert.equal(listing.error, null);
});

test("filesystem roots have no parent", async () => {
  const root = path.parse(process.cwd()).root;
  const listing = await listDirectory(root, async () => []);

  assert.equal(listing.path, root);
  assert.equal(listing.parent, null);
  assert.deepEqual(listing.entries, []);
  assert.equal(listing.error, null);
});

test("invalid and unreadable paths return empty error listings", async () => {
  const relative = await listDirectory("relative/path", async () => {
    throw new Error("must not read a relative path");
  });
  assert.equal(relative.error, "Path must be a local absolute path");
  assert.deepEqual(relative.entries, []);

  const unc = await listDirectory("\\\\server\\share", async () => {
    throw new Error("must not read a UNC path");
  });
  assert.equal(unc.error, "Path must be a local absolute path");
  assert.deepEqual(unc.entries, []);

  const overlongPath = path.resolve(`${"x".repeat(4097)}`);
  const overlong = await listDirectory(overlongPath, async () => {
    throw new Error("must not read an over-long path");
  });
  assert.equal(overlong.error, "Path is too long");
  assert.deepEqual(overlong.entries, []);

  const missingPath = path.resolve(path.parse(process.cwd()).root, "DefinitelyMissing");
  const missing = await listDirectory(missingPath, async () => {
    const error = new Error("missing");
    error.code = "ENOENT";
    throw error;
  });
  assert.equal(missing.path, missingPath);
  assert.equal(missing.error, "Path does not exist");
  assert.deepEqual(missing.entries, []);
  assert.equal(missing.truncated, false);
});
