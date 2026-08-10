# Changelog — Photos Sync

## 1.1.0

- **Choose which capture types sync.** Settings now lists photos, AR photos,
  videos, and AR videos as separate switches, so a type you never want copied
  stops crossing the Bluetooth link at all instead of being deleted after the
  fact. Everything stays on by default.
- The settings screen says plainly what a synced file will look like: AR
  overlays and video stabilization are added by the Hi Rokid app after its own
  import, so captures synced here arrive as the raw file — plain photos, and
  clips without stabilization. One line per type, next to its switch.

## 1.0.2

- The plugin's mark matches its Store icon again. Converting the glyph set to
  stroke-only in July had shrunk the sync arrow into a corner decoration and
  closed the frame around it, which lost the one thing the mark says: the
  capture leaves the glasses. The icon is now transcribed from the published
  artwork measurement by measurement.

## 1.0.1

- A capture taken seconds ago is no longer skipped by auto-sync: scanning and
  settling are separate decisions, so a file still being written is re-checked
  instead of being dropped from the run.
- The phone hub restarts itself after a reboot or an app update, rather than
  waiting to be opened by hand while captures pile up on the glasses.

## 1.0.0

- First release: glasses captures copy themselves into `Download/Hi Rokid/` on
  the phone, over the Bluetooth connection the glasses already have — no Wi-Fi on
  either device.
- Three sync modes: always, while charging (default), or manual only. New
  captures trigger a sync on their own in always mode.
- Interrupted transfers resume from where they stopped, so long videos finish
  across several sessions.
- Settings screen with live status and progress, a *Sync now* button, the recent
  sync history, and delete-from-glasses-after-sync (off by default, with the
  honest warning when the glasses refuse to delete).
