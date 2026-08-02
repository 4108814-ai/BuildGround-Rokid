/**
 * Minimal stable protocol projection generated and checked against
 * `codex-cli 0.145.0` with:
 *
 *   codex app-server generate-ts
 *   codex app-server generate-json-schema
 *
 * Only fields consumed by agentd are retained; the full generated output is
 * intentionally not vendored.
 */

export const CODEX_APP_SERVER_BINDINGS_VERSION = "0.145.0";

export type RequestId = string | number;
export type TurnStatus = "completed" | "interrupted" | "failed" | "inProgress";
export type ThreadActiveFlag = "waitingOnApproval" | "waitingOnUserInput";

export type ThreadStatus =
  | { type: "notLoaded" }
  | { type: "idle" }
  | { type: "systemError" }
  | { type: "active"; activeFlags: ThreadActiveFlag[] };

export interface TurnError {
  message: string;
  codexErrorInfo: unknown | null;
  additionalDetails: string | null;
}

export interface Turn {
  id: string;
  items: unknown[];
  itemsView: unknown;
  status: TurnStatus;
  error: TurnError | null;
  startedAt: number | null;
  completedAt: number | null;
  durationMs: number | null;
}

export interface Thread {
  id: string;
  sessionId: string;
  forkedFromId: string | null;
  parentThreadId: string | null;
  preview: string;
  ephemeral: boolean;
  modelProvider: string;
  createdAt: number;
  updatedAt: number;
  recencyAt: number | null;
  status: ThreadStatus;
  path: string | null;
  cwd: string;
  cliVersion: string;
  source: unknown;
  threadSource: unknown | null;
  agentNickname: string | null;
  agentRole: string | null;
  gitInfo: unknown | null;
  name: string | null;
  turns: Turn[];
}

export interface ThreadListResponse {
  data: Thread[];
  nextCursor: string | null;
  backwardsCursor: string | null;
}

export interface ThreadReadResponse {
  thread: Thread;
}

export interface ThreadResumeResponse {
  thread: Thread;
  model: string;
  modelProvider: string;
  serviceTier: string | null;
  cwd: string;
}

export interface CommandApprovalParams {
  threadId: string;
  turnId: string;
  itemId: string;
  startedAtMs: number;
  approvalId?: string | null;
  environmentId: string | null;
  reason?: string | null;
  networkApprovalContext?: unknown | null;
  command?: string | null;
  cwd?: string | null;
  commandActions?: unknown[] | null;
  proposedExecpolicyAmendment?: unknown | null;
  proposedNetworkPolicyAmendments?: unknown[] | null;
}

export interface FileChangeApprovalParams {
  threadId: string;
  turnId: string;
  itemId: string;
  startedAtMs: number;
  reason?: string | null;
  grantRoot?: string | null;
}

export interface AdditionalNetworkPermissions {
  enabled: boolean | null;
}

export interface AdditionalFileSystemPermissions {
  read: string[] | null;
  write: string[] | null;
  globScanMaxDepth?: number;
  entries?: unknown[];
}

export interface RequestPermissionProfile {
  network: AdditionalNetworkPermissions | null;
  fileSystem: AdditionalFileSystemPermissions | null;
}

export interface PermissionsApprovalParams {
  threadId: string;
  turnId: string;
  itemId: string;
  environmentId: string | null;
  startedAtMs: number;
  cwd: string;
  reason: string | null;
  permissions: RequestPermissionProfile;
}

export type ApprovalServerRequest =
  | {
      id: RequestId;
      method: "item/commandExecution/requestApproval";
      params: CommandApprovalParams;
    }
  | {
      id: RequestId;
      method: "item/fileChange/requestApproval";
      params: FileChangeApprovalParams;
    }
  | {
      id: RequestId;
      method: "item/permissions/requestApproval";
      params: PermissionsApprovalParams;
    };

export interface RpcError {
  code: number;
  message: string;
  data?: unknown;
}

export interface RpcMessage {
  id?: RequestId;
  method?: string;
  params?: unknown;
  result?: unknown;
  error?: RpcError;
}
