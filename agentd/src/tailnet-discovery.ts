import { execFile } from "node:child_process";
import { DEFAULT_PHONE_LINK_PORT } from "./config";
import type { Logger } from "./types";

export const TAILSCALE_STATUS_TIMEOUT_MS = 5_000;
export const MAX_TAILNET_PEERS = 8;

interface CommandResult {
  missing: boolean;
  stdout?: string;
}

export type TailscaleCommandRunner = (binary: string) => Promise<CommandResult>;

export interface TailnetPeerSelection {
  targets: string[];
  dropped: number;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function isTailnetIpv4(value: string): boolean {
  const parts = value.split(".");
  if (
    parts.length !== 4 ||
    parts.some((part) => !/^\d{1,3}$/.test(part) || Number(part) > 255)
  ) {
    return false;
  }
  return Number(parts[0]) === 100 && Number(parts[1]) >= 64 && Number(parts[1]) <= 127;
}

export function selectTailnetPeers(status: unknown): TailnetPeerSelection {
  if (!isRecord(status) || status.BackendState === "NoState" || !isRecord(status.Peer)) {
    return { targets: [], dropped: 0 };
  }

  const addresses: string[] = [];
  const seen = new Set<string>();
  for (const peer of Object.values(status.Peer)) {
    if (!isRecord(peer) || peer.OS !== "android" || peer.Online !== true) {
      continue;
    }
    const tailscaleIps = Array.isArray(peer.TailscaleIPs) ? peer.TailscaleIPs : [];
    const address = tailscaleIps.find(
      (entry): entry is string => typeof entry === "string" && isTailnetIpv4(entry),
    );
    if (address && !seen.has(address)) {
      seen.add(address);
      addresses.push(address);
    }
  }

  return {
    targets: addresses
      .slice(0, MAX_TAILNET_PEERS)
      .map((address) => `${address}:${DEFAULT_PHONE_LINK_PORT}`),
    dropped: Math.max(0, addresses.length - MAX_TAILNET_PEERS),
  };
}

export function tailscaleExecutableCandidates(platform = process.platform): string[] {
  const fallback =
    platform === "win32"
      ? "C:\\Program Files\\Tailscale\\tailscale.exe"
      : platform === "darwin"
        ? "/Applications/Tailscale.app/Contents/MacOS/Tailscale"
        : platform === "linux"
          ? "/usr/bin/tailscale"
          : undefined;
  return fallback ? ["tailscale", fallback] : ["tailscale"];
}

const runTailscaleStatus: TailscaleCommandRunner = (binary) =>
  new Promise((resolve) => {
    execFile(
      binary,
      ["status", "--json"],
      {
        encoding: "utf8",
        timeout: TAILSCALE_STATUS_TIMEOUT_MS,
        killSignal: "SIGKILL",
        windowsHide: true,
        shell: false,
      },
      (error, stdout) => {
        resolve({
          missing: (error as NodeJS.ErrnoException | null)?.code === "ENOENT",
          stdout: error ? undefined : stdout,
        });
      },
    );
  });

export class TailscalePeerDiscovery {
  private resolvedBinary?: string;
  private hadPeers = false;
  private wasCapped = false;

  constructor(
    private readonly logger: Logger,
    private readonly runner: TailscaleCommandRunner = runTailscaleStatus,
    private readonly platform = process.platform,
  ) {}

  async discover(): Promise<string[]> {
    const result = await this.readStatus();
    let selection: TailnetPeerSelection = { targets: [], dropped: 0 };
    if (result) {
      try {
        selection = selectTailnetPeers(JSON.parse(result));
      } catch {
        // Invalid or partial CLI output is the same as no discovery result.
      }
    }

    const hasPeers = selection.targets.length > 0;
    if (hasPeers !== this.hadPeers) {
      if (hasPeers) {
        this.logger.info("tailnet_discovery_found", { peers: selection.targets.length });
      } else {
        this.logger.warn("tailnet_discovery_lost");
      }
      this.hadPeers = hasPeers;
    }
    if (selection.dropped > 0 && !this.wasCapped) {
      this.logger.warn("tailnet_discovery_capped", {
        peers: selection.targets.length,
        dropped: selection.dropped,
      });
    }
    this.wasCapped = selection.dropped > 0;
    return selection.targets;
  }

  private async readStatus(): Promise<string | undefined> {
    if (this.resolvedBinary) {
      const result = await this.run(this.resolvedBinary);
      if (!result.missing) {
        return result.stdout;
      }
      this.resolvedBinary = undefined;
    }

    for (const candidate of tailscaleExecutableCandidates(this.platform)) {
      const result = await this.run(candidate);
      if (result.missing) {
        continue;
      }
      this.resolvedBinary = candidate;
      return result.stdout;
    }
    return undefined;
  }

  private async run(binary: string): Promise<CommandResult> {
    try {
      return await this.runner(binary);
    } catch {
      return { missing: false };
    }
  }
}
