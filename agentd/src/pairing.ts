import os from "node:os";
import type { AgentConfig } from "./types";

function familyIsV4(family: string | number): boolean {
  return family === "IPv4" || family === 4;
}

export function bestHostGuess(interfaces = os.networkInterfaces()): string {
  const candidates: Array<{ name: string; address: string }> = [];
  for (const [name, addresses] of Object.entries(interfaces)) {
    for (const address of addresses ?? []) {
      if (familyIsV4(address.family) && !address.internal) {
        candidates.push({ name, address: address.address });
      }
    }
  }
  return (
    candidates.find((candidate) => candidate.name.toLowerCase().includes("tailscale"))?.address ||
    candidates[0]?.address ||
    "PASTE-YOUR-TAILNET-IP"
  );
}

export function pairingPayload(config: AgentConfig): string {
  return JSON.stringify({
    v: 1,
    kind: "nexus-agentd",
    name: config.machineName,
    host: bestHostGuess(),
    port: config.wsPort,
    token: config.token,
  });
}
