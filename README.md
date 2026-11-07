# BotGram — a Telegram client for bots

Sign in with a bot token from @BotFather and use Telegram *as the bot*: private chats, groups,
supergroups and channels, with media, polls, inline keyboards, admin tools and more.

## Build
1. Open this folder in **Android Studio** (Ladybug or newer, JDK 17). It will sync Gradle and create the wrapper.
2. Run on a device/emulator (Android 10+, minSdk 29), or `Build > Build APK(s)`.

## Features
- **Login** with token (validated through `getMe`), stored in encrypted storage; multiple bots with a drawer switcher; automatic webhook removal prompt.
- **Receiving**: long polling (`getUpdates`) with offset persistence, backoff, conflict/revoked-token detection, foreground service for background delivery, notifications with inline reply.
- **Chats**: private, group, supergroup, channel. Local history, unread counters, pin, search, filters, "open by ID/@username".
- **Messages**: text with Telegram formatting rendered (bold, italic, code, links…), reply, edit, delete (for everyone/local), forward, copy, react, pin/unpin, typing action.
- **Media**: send photo, video, file, audio, GIF, voice (hold mic), round video, sticker; view photos, download/open any file.
- **Other types**: location, contact, polls (create/stop/live results), dice, inline keyboards (compose + display, URL buttons open).
- **Callbacks**: users' button presses are shown in chat and auto-answered (toggleable).
- **Admin**: member events, ban/unban/mute/promote by user, join-request approve/decline, invite links, title/description, permissions, leave chat, administrators list, member count.
- **Bot profile**: commands, name, description, about; webhook status.
- **Broadcast** to selected chats (rate-limited) and an **API console** to call any Bot API method with JSON.

## Limits of the Bot API (not app bugs)
- Bots cannot fetch history or list chats/members — only messages received or sent while connected are stored.
- A bot can't DM a user who hasn't started it, and can't see other bots' messages in groups.
- Only one consumer can poll a bot at once; a running server/webhook for the same bot conflicts.
- Uploads ≤ 50 MB, downloads ≤ 20 MB. Voice calls and secret chats are not available to bots.
- In groups, privacy mode (BotFather `/setprivacy`) decides whether the bot sees all messages.
