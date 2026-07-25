# Photo Sync

Copies the photos and videos you shoot with the glasses' camera button into your
phone gallery, on their own, the way a phone camera backs itself up.

## How it works

- The **glasses hub** watches `/sdcard/DCIM/Camera`. When the glasses start
  charging (or when the phone reconnects while they are already charging, or when
  you press *Sync now*), it stands up its own Wi-Fi Direct group and serves the
  captures over it. A file only becomes eligible once two scans agree on its size
  and mtime, so a video that is still recording is never sent.
- The **phone hub** joins that group by credentials, pulls the catalog, diffs it
  against a persistent ledger, and writes each new capture into
  `Download/Hi Rokid/` through MediaStore — the same album Hi Rokid's own imports
  use, with the same filenames. Every file is SHA-256 verified before it is
  published, and the ledger is authoritative: deleting a photo from your gallery
  does not bring it back on the next sync.
- This plugin is the control surface. It holds no sync state of its own: sync
  keeps running while the plugin process is dormant. `PhotoSyncRuntime` mirrors
  the hub's `/mediasync/status` pushes and turns the screen's actions into
  `/mediasync/settings` and `/mediasync/now` sends; `PhotoSyncSettingsActivity`
  renders them on the Nexus design kit.

## Settings

- **Sync when charging** (on by default) — the automatic trigger. Turning it off
  leaves *Sync now* working.
- **Delete from glasses after sync** (off by default) — frees glasses storage. A
  capture is deleted only after it is safely published on the phone. If the
  glasses ROM refuses the delete, the screen says so instead of pretending: the
  files stay where they are.

## Requirements

- The `mediasync` capability, approved once in Rokid Nexus → Plugin access. That
  approval is what switches photo sync on at all.
- Phone Wi-Fi on while a sync runs. Photo sync never toggles it for you; if it is
  off the status says so and the next trigger retries.
- Storage access granted to the glasses hub (it asks once, on the glasses).
