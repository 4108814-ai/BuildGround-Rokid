# Changelog

## 1.1.0

- **Read notifications aloud.** A new switch, off by default, has the glasses
  speak a message when it arrives. It reads the newest message whole — not a
  preview, not the first line, because a message cut off halfway sends you back
  to your phone anyway, which is the thing Relay exists to avoid. The band is
  held open while it reads and gives you the usual answering window once it
  stops, so a message is never still being read after its band has gone.
  Answering interrupts the reading, and so does dictating: the glasses stop
  talking the moment they start listening. Needs Rokid Nexus 1.1.5 on the hub;
  the reading is produced on the glasses, so nothing leaves the device and no
  network is involved. Speed and voice come from your Rokid assistant settings.

- **"Sent" lands on the chip you were watching.** Sending used to confirm
  itself above the button while the button went on counting down to a send that
  had already happened. The countdown chip now becomes the confirmation in
  place, and the line above it goes quiet.

## 1.0.2

- **Black out behind notifications.** A new switch, off by default, asks the
  glasses to hide everything else while a Relay notification is up — only the
  notification shows, the way the Even G2 does it. Leaving it off keeps the
  band floating over whatever you were looking at. Needs Nexus 1.1.4 on the
  hub; older hubs simply ignore the request.

- **The test harness can crowd the inbox.** An Eight threads button posts
  eight conversations at once, each from its own sender — one more than the
  glasses list shows at a time, which is exactly the case the inbox needed
  testing against.

## 1.0.1

- Relay has its own mark instead of the shared `chat` icon: two bubbles, the
  message that arrived and the answer going back, with the upper outline
  breaking where the reply crosses in front. The same mark is now the app icon,
  so what you tap in the Store is what you find afterwards.

## 1.0.0

- Initial notification listener, notice, menu-launched inbox, explicit voice
  reply, and local fake notification harness.
