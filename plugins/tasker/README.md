# Tasker

Tasker puts your phone's automations on the glasses: open the plugin from the
glasses launcher, swipe through your named Tasker tasks, tap to run one. It
replaces the standalone Tasker Bridge app — the Nexus bus does everything its
custom BLE/RFCOMM transport used to.

## How it works

- `TaskerRepository` talks to Tasker on the phone: it reads the task list and
  preferences through Tasker's content provider and fires tasks with the
  standard `ACTION_TASK` run broadcast.
- `TaskerPluginRuntime` owns the HUD logic (task list, cursor, paging, run
  status) behind a small host interface; `TaskerPluginService` is the
  `NexusPluginService` adapter that renders it through SDK cards.
- `TaskerSettingsActivity` (NexusUi kit) shows the Tasker readiness checklist
  and hosts the run-permission grant and uninstall.

The plugin requests only the `surfaces` capability; running a task is a
fire-and-forget broadcast to Tasker, so the card reports "Sent", not task
completion.

## Requirements

- Tasker installed on the phone, with at least one named task.
- **Allow External Access** enabled in Tasker preferences (Tasker → Preferences
  → Misc).
- The Tasker run permission, granted from the plugin settings screen.
