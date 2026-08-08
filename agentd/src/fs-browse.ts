import path from "node:path";

export const MAX_FS_ENTRIES = 300;
const MAX_PATH_CHARS = 4096;

export interface FsEntry {
  name: string;
  path: string;
}

export interface FsListing {
  path: string | null;
  parent: string | null;
  entries: FsEntry[];
  truncated: boolean;
  error: string | null;
}

export interface DirectoryEntryLike {
  name: string;
  isDirectory(): boolean;
  isSymbolicLink(): boolean;
}

export type ReadDirectory = (
  directoryPath: string,
  options: { withFileTypes: true },
) => Promise<readonly DirectoryEntryLike[]>;

export function listRoots(
  platform: NodeJS.Platform,
  homeDirectory: string,
  driveExists: (drivePath: string) => boolean,
): FsListing {
  const entries: FsEntry[] = [{ name: "Home", path: homeDirectory }];
  if (platform === "win32") {
    for (let code = "A".charCodeAt(0); code <= "Z".charCodeAt(0); code += 1) {
      const drivePath = `${String.fromCharCode(code)}:\\`;
      try {
        if (driveExists(drivePath)) {
          entries.push({ name: drivePath, path: drivePath });
        }
      } catch {
        // An inaccessible drive is indistinguishable from an absent one here.
      }
    }
  } else {
    entries.push({ name: "/", path: "/" });
  }
  return {
    path: null,
    parent: null,
    entries,
    truncated: false,
    error: null,
  };
}

export async function listDirectory(
  requestedPath: string,
  readDirectory: ReadDirectory,
): Promise<FsListing> {
  if (requestedPath.length > MAX_PATH_CHARS) {
    return errorListing(requestedPath, null, "Path is too long");
  }
  if (isUncPath(requestedPath) || !path.isAbsolute(requestedPath)) {
    return errorListing(requestedPath, null, "Path must be a local absolute path");
  }

  const directoryParent = path.dirname(requestedPath);
  const parent = directoryParent === requestedPath ? null : directoryParent;
  try {
    const directoryEntries = await readDirectory(requestedPath, { withFileTypes: true });
    const entries = directoryEntries
      .filter(
        (entry) =>
          !entry.name.startsWith(".") && entry.isDirectory() && !entry.isSymbolicLink(),
      )
      .map((entry) => ({ name: entry.name, path: path.join(requestedPath, entry.name) }))
      .sort(compareEntries);
    return {
      path: requestedPath,
      parent,
      entries: entries.slice(0, MAX_FS_ENTRIES),
      truncated: entries.length > MAX_FS_ENTRIES,
      error: null,
    };
  } catch (error) {
    return errorListing(requestedPath, parent, errorMessage(error));
  }
}

function isUncPath(requestedPath: string): boolean {
  return requestedPath.startsWith("\\\\") ||
    (process.platform === "win32" && requestedPath.startsWith("//"));
}

function compareEntries(left: FsEntry, right: FsEntry): number {
  const insensitive = left.name.toLowerCase().localeCompare(right.name.toLowerCase());
  return insensitive || left.name.localeCompare(right.name);
}

function errorListing(
  requestedPath: string,
  parent: string | null,
  error: string,
): FsListing {
  return {
    path: requestedPath,
    parent,
    entries: [],
    truncated: false,
    error,
  };
}

function errorMessage(error: unknown): string {
  const code =
    error && typeof error === "object" && "code" in error
      ? (error as { code?: unknown }).code
      : undefined;
  switch (code) {
    case "ENOENT":
      return "Path does not exist";
    case "EACCES":
    case "EPERM":
      return "Permission denied";
    case "ENOTDIR":
      return "Path is not a directory";
    case "ENAMETOOLONG":
      return "Path is too long";
    default:
      return "Unable to list directory";
  }
}
