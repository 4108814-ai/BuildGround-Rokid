import { condense, toolSummary } from "../transcript-messages";
import type { SessionMessage } from "../types";
import type { Thread } from "./protocol";

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown>
    : undefined;
}

function asString(value: unknown): string | undefined {
  return typeof value === "string" && value.trim() ? value : undefined;
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

function textFromCodexContent(content: unknown): string {
  if (typeof content === "string") {
    return condense(content);
  }
  if (!Array.isArray(content)) {
    return "";
  }
  const parts: string[] = [];
  for (const value of content) {
    if (typeof value === "string") {
      parts.push(value);
      continue;
    }
    const block = asRecord(value);
    if (typeof block?.text === "string") {
      parts.push(block.text);
    }
  }
  return condense(parts.join("\n"));
}

function decodedInput(value: unknown): Record<string, unknown> {
  const direct = asRecord(value);
  if (direct) {
    return direct;
  }
  if (typeof value !== "string") {
    return {};
  }
  try {
    return asRecord(JSON.parse(value)) ?? { command: value };
  } catch {
    return { command: value };
  }
}

function summaryInput(value: unknown): Record<string, unknown> {
  const input = decodedInput(value);
  const command = [input.command, input.cmd, input.code]
    .find((candidate): candidate is string =>
      typeof candidate === "string" && candidate.trim().length > 0
    );
  return command && typeof input.command !== "string" ? { ...input, command } : input;
}

function commandFrom(value: unknown): string | undefined {
  if (typeof value === "string" && value.trim()) {
    return value;
  }
  if (Array.isArray(value)) {
    const parts = value.filter((part): part is string =>
      typeof part === "string" && part.trim().length > 0
    );
    return parts.length ? parts.join(" ") : undefined;
  }
  return undefined;
}

function pathFromChanges(value: unknown): string | undefined {
  if (Array.isArray(value)) {
    for (const change of value) {
      const path = asString(asRecord(change)?.path);
      if (path) {
        return path;
      }
    }
    return undefined;
  }
  const changes = asRecord(value);
  return changes ? Object.keys(changes).find((path) => path.trim().length > 0) : undefined;
}

function codexToolMessage(
  tool: string,
  input: unknown,
  at: number,
): SessionMessage | undefined {
  const safeTool = toolSummary(tool, {});
  const text = toolSummary(tool, summaryInput(input));
  return safeTool && text
    ? { role: "tool", text, at, tool: safeTool }
    : undefined;
}

/** Maps either an app-server thread item or its raw rollout equivalent. */
export function extractCodexMessage(
  value: unknown,
  fallbackAt: number = Date.now(),
): SessionMessage | undefined {
  const wrapper = asRecord(value);
  if (!wrapper) {
    return undefined;
  }
  const item = asRecord(wrapper.item) ?? asRecord(wrapper.payload) ?? wrapper;
  const type = asString(item.type) ?? "";
  const at = timestampFrom(
    item.timestamp ?? item.createdAt ?? item.startedAt ?? item.completedAt ?? wrapper.timestamp,
    fallbackAt,
  );

  if (type === "userMessage" || type === "user_message") {
    const text = textFromCodexContent(item.content ?? item.message);
    return text && !text.startsWith("<") ? { role: "user", text, at } : undefined;
  }

  if (type === "agentMessage" || type === "assistantMessage" || type === "agent_message") {
    const text = textFromCodexContent(item.text ?? item.content ?? item.message);
    return text ? { role: "assistant", text, at } : undefined;
  }

  if (type === "message") {
    const role = asString(item.role)?.toLowerCase();
    if (role !== "user" && role !== "assistant") {
      return undefined;
    }
    const text = textFromCodexContent(item.content);
    if (!text || (role === "user" && text.startsWith("<"))) {
      return undefined;
    }
    return { role, text, at };
  }

  if (type === "commandExecution" || type === "command_execution" || type === "exec_command_end") {
    const command = commandFrom(item.command) ?? commandFrom(item.parsed_cmd);
    return codexToolMessage("shell", { command }, at);
  }

  if (type === "fileChange" || type === "file_change" || type === "patch_apply_end") {
    return codexToolMessage("edit", { path: pathFromChanges(item.changes) }, at);
  }

  if (type === "mcpToolCall" || type === "mcp_tool_call_end") {
    const invocation = asRecord(item.invocation);
    const tool = asString(item.tool) ?? asString(invocation?.tool);
    return tool
      ? codexToolMessage(tool, item.arguments ?? invocation?.arguments, at)
      : undefined;
  }

  if (type === "dynamicToolCall" || type === "dynamic_tool_call") {
    const tool = asString(item.tool) ?? asString(item.name);
    return tool ? codexToolMessage(tool, item.arguments ?? item.input, at) : undefined;
  }

  if (type === "view_image_tool_call") {
    return codexToolMessage("view_image", { path: item.path }, at);
  }

  if (type === "function_call") {
    const name = asString(item.name)?.toLowerCase();
    if (name === "exec_command" || name === "shell_command") {
      return codexToolMessage("shell", item.arguments, at);
    }
    if (name === "view_image") {
      return codexToolMessage("view_image", item.arguments, at);
    }
    if (name === "apply_patch") {
      return codexToolMessage("edit", {}, at);
    }
  }

  if (type === "custom_tool_call" && asString(item.name)?.toLowerCase() === "apply_patch") {
    return codexToolMessage("edit", {}, at);
  }

  return undefined;
}

export function codexThreadMessages(thread: Thread, limit: number): SessionMessage[] {
  if (!Number.isFinite(limit) || limit <= 0) {
    return [];
  }
  const threadAt = timestampFrom(thread.updatedAt, Date.now());
  const messages: SessionMessage[] = [];
  for (const turn of thread.turns) {
    const turnAt = timestampFrom(turn.startedAt ?? turn.completedAt, threadAt);
    for (const item of turn.items) {
      const message = extractCodexMessage(item, turnAt);
      if (message) {
        messages.push(message);
      }
    }
  }
  return messages.slice(-Math.floor(limit));
}
