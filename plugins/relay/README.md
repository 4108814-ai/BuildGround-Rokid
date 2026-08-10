# Relay

Relay forwards directly repliable Android notifications to a Nexus notice band,
keeps a menu-launched in-memory inbox, and sends an explicitly confirmed voice
reply through the source notification's `RemoteInput` action.

Inbox conversations open as native reader documents. When the source
notification adds a message to the conversation already being read, Relay
updates that document in place without interrupting an active reply flow.

Notification content, sender names, images, and speech text are process-memory
only. The settings screen stores only feature flags and the thread message
limit.
