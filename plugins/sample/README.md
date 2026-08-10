# Sample plugin

The canonical copyable starting point for a Rokid Nexus plugin. Its manifest is
headless: the exported settings activity has no launcher intent filter, while one
exported `NexusPluginService` advertises the plugin action and descriptor metadata.

The service first demonstrates the public `NexusInkSurfaceSession`: an authored
page with bound metrics, a native chart, a tap action, and set-data-style
patches. It falls back to the ordinary surface API and also demonstrates a
bundled JPEG image, cards, directional input, tap state, and back-to-close
behavior. The settings activity uses the shared `NexusUi`/`BusTheme` kit and
provides the system uninstall action.

The manifest requests `ink_surface` separately from `surfaces`. After install,
approve both grants to exercise Ink; old glasses or a down SPP data link take
the fallback path. The implementation is in
[`HelloPluginService.kt`](src/main/java/com/anezium/rokidbus/plugin/sample/HelloPluginService.kt).

## Dictation

The Dictation menu entry demonstrates a typed `NexusSpeechSession`, including live
partials, accumulated final text, batch-mode feedback, stop reasons, and retry. It
requires `CAPABILITIES=surfaces,stt` and
`RECEIVE_PREFIXES=/plugin/hello,/system/plugin,/stt` in the service metadata.

After install, grant **Speech to text** in **Rokid Nexus > Settings > Plugin access**;
installation never grants it. A speech API key must also be configured in Rokid
Nexus's Speech screen.

To start a plugin, copy this module, rename its package and plugin ID consistently,
then replace the sample service and settings content. Read
[PLUGINS.md](../../docs/PLUGINS.md) for the headless and design-kit rules and
[PLUGIN_SDK.md](../../docs/PLUGIN_SDK.md) for the SDK contract.
