import { open, stat } from "node:fs/promises";
import type { MessageRole, SessionMessage } from "./types";

const TAIL_READ_BYTES = 512 * 1024;
const MAX_TEXT_CHARS = 400;

function recordValue(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown>
    : undefined;
}

function condense(value: string, maxLength = MAX_TEXT_CHARS): string {
  return value.replace(/\s+/g, " ").trim().slice(0, maxLength);
}

function timestampFrom(value: unknown, fallback: number): number {
  if (typeof value === "number" && Number.isFinite(value)) {
    return value < 1_000_000_000_000 ? value * 1000 : value;
  }
  if (typeof value === "string") {
    const parsed = Date.parse(value);
    if (Number.isFinite(parsed)) {
      return parsed;
    }
  }
  return fallback;
}

function textFrom(content: unknown): string {
  if (typeof content === "string") {
    return condense(content);
  }
  if (!Array.isArray(content)) {
    return "";
  }
  const parts: string[] = [];
  for (const item of content) {
    if (typeof item === "string") {
      parts.push(item);
      continue;
    }
    const block = recordValue(item);
    if (block?.type === "text" && typeof block.text === "string") {
      parts.push(block.text);
    }
  }
  return condense(parts.join(" "));
}

function toolBlock(content: unknown): Record<string, unknown> | undefined {
  if (!Array.isArray(content)) {
    return undefined;
  }
  for (const item of content) {
    const block = recordValue(item);
    if (block?.type === "tool_use") {
      return block;
    }
  }
  return undefined;
}

function hasToolResult(content: unknown): boolean {
  return Array.isArray(content) &&
    content.some((item) => recordValue(item)?.type === "tool_result");
}

/** Absolute paths waste a HUD line; the last two segments locate a file well enough. */
function shortenPath(value: string): string {
  const segments = value.split(/[\\/]+/).filter(Boolean);
  return segments.length <= 2 ? value : `…/${segments.slice(-2).join("/")}`;
}

/** The one input field that says what a tool call is actually doing. */
export function toolSummary(name: string, input: unknown): string {
  const values = recordValue(input) ?? {};
  const pathValue = [values.file_path, values.path, values.notebook_path]
    .find((value): value is string => typeof value === "string" && value.trim().length > 0);
  const candidate = pathValue
    ? shortenPath(pathValue)
    : [
        values.command,
        values.pattern,
        values.description,
        values.prompt,
        values.url,
        values.query,
      ].find((value): value is string => typeof value === "string" && value.trim().length > 0);
  // A tool row is a glance, not a log: two HUD lines at most.
  return candidate ? `${name} · ${condense(candidate, 90)}` : name;
}

/**
 * Turns one transcript line into a displayable message, or undefined for the
 * entries a wearer should never see: tool results, sidechains, meta records.
 */
export function extractMessage(value: unknown, now: number = Date.now()): SessionMessage | undefined {
  const entry = recordValue(value);
  if (!entry || entry.isSidechain === true || entry.isMeta === true) {
    return undefined;
  }
  const message = recordValue(entry.message);
  const content = message?.content ?? entry.content;
  const type = typeof entry.type === "string" ? entry.type.toLowerCase() : "";
  const role = typeof message?.role === "string" ? message.role.toLowerCase() : type;
  const at = timestampFrom(entry.timestamp, now);

  if (role === "user") {
    if (hasToolResult(content)) {
      return undefined;
    }
    const text = textFrom(content);
    // Local command chatter (<command-name>…) is plumbing, not conversation.
    if (!text || text.startsWith("<")) {
      return undefined;
    }
    return { role: "user" as MessageRole, text, at };
  }

  if (role === "assistant") {
    const text = textFrom(content);
    if (text) {
      return { role: "assistant" as MessageRole, text, at };
    }
    const tool = toolBlock(content);
    if (tool && typeof tool.name === "string") {
      return {
        role: "tool" as MessageRole,
        text: toolSummary(tool.name, tool.input),
        at,
        tool: tool.name,
      };
    }
  }

  return undefined;
}

/** Reads the tail of a transcript and returns its last [limit] messages. */
export async function readRecentMessages(
  filePath: string,
  limit: number,
): Promise<SessionMessage[]> {
  let size: number;
  try {
    size = (await stat(filePath)).size;
  } catch {
    return [];
  }
  const start = Math.max(0, size - TAIL_READ_BYTES);
  const length = size - start;
  if (length <= 0) {
    return [];
  }

  let handle;
  try {
    handle = await open(filePath, "r");
    const buffer = Buffer.allocUnsafe(length);
    const { bytesRead } = await handle.read(buffer, 0, length, start);
    let slice = buffer.subarray(0, bytesRead);
    if (start > 0) {
      // A partial first line would fail to parse; drop it.
      const newline = slice.indexOf(0x0a);
      slice = newline >= 0 ? slice.subarray(newline + 1) : Buffer.alloc(0);
    }
    const messages: SessionMessage[] = [];
    for (const line of slice.toString("utf8").split("\n")) {
      if (!line.trim()) {
        continue;
      }
      try {
        const message = extractMessage(JSON.parse(line));
        if (message) {
          messages.push(message);
        }
      } catch {
        // Truncated or malformed line: skip it, never fail the whole read.
      }
    }
    return messages.slice(-limit);
  } catch {
    return [];
  } finally {
    await handle?.close();
  }
}
