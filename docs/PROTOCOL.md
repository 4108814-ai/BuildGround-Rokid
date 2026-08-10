# Nexus protocol

The authoritative wire, identity, lifecycle, capability, surface, Ink, trusted
hub-control, input, and transport contract is maintained in
[BUSSPEC.md](../BUSSPEC.md). This page is a stable documentation entry point
and intentionally does not duplicate the spec.

External developers should normally use the typed `bus-client` API described in
[PLUGIN_SDK.md](PLUGIN_SDK.md), not construct reserved paths or trusted metadata.
The `/core/native-apps/*`, `/core/remote-input/*`, and `/core/navigation/*`
families are platform-only and are never plugin SDK endpoints.
