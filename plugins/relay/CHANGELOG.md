# Changelog

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
