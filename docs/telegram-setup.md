# Telegram Integration Guide

This guide explains how to connect a Telegram bot to NeoPsyke.

It covers:

- what to create in Telegram
- which NeoPsyke settings to fill
- local development with long polling
- public deployment with webhooks
- how to test the integration end to end

---

## 1. Current NeoPsyke Support

NeoPsyke currently supports:

- outbound Telegram messages through the Bot API
- inbound owner-only Telegram chat through:
  - `polling` mode
  - `webhook` mode

Recommended mode:

- local machine / development: `polling`
- public deployment: `webhook`

Security model:

- only the configured owner chat/user is accepted
- direct/private chats can be required
- unauthorized messages are rejected or silently dropped
- the bot token and webhook secret are referenced by handle name in YAML and
  resolved from environment variables

---

## 2. What You Need

You need:

1. a normal Telegram user account
2. a Telegram bot created with `@BotFather`
3. your bot token
4. your owner `chat_id`
5. your owner `user_id`
6. a NeoPsyke runtime with Telegram enabled

You do not need:

- a separate human Telegram account for the bot

The bot itself is the Telegram identity NeoPsyke will use.

---

## 3. Create The Bot

In Telegram:

1. open `@BotFather`
2. run `/newbot`
3. choose a bot name
4. choose a bot username
5. copy the bot token

The bot token looks like:

```text
1234567890:AA...
```

Set it in your shell:

```bash
export TELEGRAM_BOT_TOKEN='paste-bot-token-here'
```

---

## 4. Get Your Owner IDs

NeoPsyke uses two allowlist fields:

- `owner_chat_id`
- `owner_user_id`

For a one-owner private chat bot, these are often the same value, but NeoPsyke
checks both.

### 4.1 Easiest Way In Polling Mode

1. send any message to the bot from your Telegram account
2. call:

```bash
curl "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/getUpdates"
```

Look for:

```json
{
  "message": {
    "chat": {
      "id": 123456789,
      "type": "private"
    },
    "from": {
      "id": 123456789,
      "is_bot": false
    }
  }
}
```

Map the values as:

- `owner_chat_id` = `message.chat.id`
- `owner_user_id` = `message.from.id`

### 4.2 If `getUpdates` Returns `[]`

That usually means:

- you have not messaged the bot yet, or
- a webhook is already set

Check:

```bash
curl "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/getWebhookInfo"
```

If a webhook is already set and you want to use polling:

```bash
curl -X POST "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/deleteWebhook"
```

Then send the bot a message and call `getUpdates` again.

---

## 5. Telegram Config In `agent-runtime.yaml`

NeoPsyke reads Telegram config from:

- [agent-runtime.yaml](/Users/victor.toral/atomitl/ai/psyke/agent-runtime.yaml)

Current Telegram section:

```yaml
agent:
  native_integrations:
    telegram:
      enabled: true
      mode: polling
      webhook_path: /api/channels/telegram/webhook
      owner_chat_id: "123456789"
      owner_user_id: "123456789"
      bot_token_handle: TELEGRAM_BOT_TOKEN
      webhook_secret_handle: TELEGRAM_WEBHOOK_SECRET
      policy_scope_id: telegram-owner
      session_id_prefix: telegram
      require_direct_chat: true
      drop_unauthorized_messages: true
      poll_timeout_seconds: 25
      poll_retry_delay_ms: 1000
```

Field meanings:

- `enabled`
  - enables Telegram integration
- `mode`
  - `polling` or `webhook`
- `webhook_path`
  - only used in webhook mode
- `owner_chat_id`
  - allowlisted direct chat id
- `owner_user_id`
  - allowlisted Telegram user id
- `bot_token_handle`
  - handle name for the bot token secret
- `webhook_secret_handle`
  - handle name for the webhook secret
- `policy_scope_id`
  - policy scope used for Telegram owner conversations
- `session_id_prefix`
  - session id prefix, typically `telegram`
- `require_direct_chat`
  - only accept `private` chats
- `drop_unauthorized_messages`
  - silently drop unauthorized traffic instead of returning `403`
- `poll_timeout_seconds`
  - long-poll timeout used in polling mode
- `poll_retry_delay_ms`
  - retry backoff after polling errors

---

## 6. Which Settings Belong In Env

Only secrets need to be env-backed today.

Recommended environment variables:

```bash
export TELEGRAM_BOT_TOKEN='paste-bot-token-here'
export TELEGRAM_WEBHOOK_SECRET='generate-a-random-secret'
```

Optional env overrides for non-secret config:

```bash
export NEOPSYKE_TELEGRAM_ENABLED=true
export NEOPSYKE_TELEGRAM_MODE=polling
export NEOPSYKE_TELEGRAM_OWNER_CHAT_ID=123456789
export NEOPSYKE_TELEGRAM_OWNER_USER_ID=123456789
```

Current runtime behavior:

- the YAML stores handle names like `TELEGRAM_BOT_TOKEN`
- NeoPsyke resolves those handles from environment variables

`owner_chat_id` and `owner_user_id` are supported in YAML, but for a
commit-safe repo config the recommended default is:

- keep them blank in committed YAML
- provide real values through env overrides locally

---

## 7. Generate `TELEGRAM_WEBHOOK_SECRET`

This secret is not issued by Telegram.

You generate it yourself and use the same value:

- in your environment variable
- when registering the webhook with Telegram

Generate one:

```bash
openssl rand -hex 32
```

Set it:

```bash
export TELEGRAM_WEBHOOK_SECRET='paste-random-secret-here'
```

In `polling` mode, this secret is not needed for inbound delivery, but keeping
it set makes switching back to `webhook` mode trivial.

---

## 8. Local Development: Polling Mode

This is the easiest way to run Telegram from your local machine.

You do not need:

- a public URL
- a tunnel
- webhook registration

### 8.1 Recommended Settings

Use:

```yaml
agent:
  native_integrations:
    telegram:
      enabled: true
      mode: polling
      owner_chat_id: "123456789"
      owner_user_id: "123456789"
      bot_token_handle: TELEGRAM_BOT_TOKEN
      webhook_secret_handle: TELEGRAM_WEBHOOK_SECRET
      require_direct_chat: true
      drop_unauthorized_messages: true
      poll_timeout_seconds: 25
      poll_retry_delay_ms: 1000
```

Recommended poll settings:

- `poll_timeout_seconds: 25`
  - long enough to avoid wasteful short polling
  - short enough to keep shutdown/reconnect reasonable
- `poll_retry_delay_ms: 1000`
  - one second retry after transient failure

### 8.2 Run It

```bash
./run-neopsyke.sh
```

Then:

1. open the bot chat in Telegram
2. send a direct message
3. NeoPsyke should ingest it as a trusted owner-direct Telegram session

Important:

- polling mode clears any existing Telegram webhook on startup
- Telegram does not deliver the same bot updates through webhook and polling at
  the same time

---

## 9. Public Deployment: Webhook Mode

Use webhook mode when NeoPsyke is reachable over public HTTPS.

### 9.1 Requirements

You need:

- a public HTTPS URL
- NeoPsyke running with the dashboard server enabled
- a matching webhook secret

### 9.2 Recommended Config

```yaml
agent:
  native_integrations:
    telegram:
      enabled: true
      mode: webhook
      webhook_path: /api/channels/telegram/webhook
      owner_chat_id: "123456789"
      owner_user_id: "123456789"
      bot_token_handle: TELEGRAM_BOT_TOKEN
      webhook_secret_handle: TELEGRAM_WEBHOOK_SECRET
      require_direct_chat: true
      drop_unauthorized_messages: true
```

### 9.3 Register The Webhook

If your public base URL is:

```text
https://example.yourdomain.com
```

Register:

```bash
curl -X POST "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/setWebhook" \
  -d "url=https://example.yourdomain.com/api/channels/telegram/webhook" \
  -d "secret_token=$TELEGRAM_WEBHOOK_SECRET"
```

Verify:

```bash
curl "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/getWebhookInfo"
```

Look for:

- correct `url`
- low or zero `pending_update_count`
- no `last_error_message`

---

## 10. Run Webhook Mode From A Local Machine

If you want to use webhook mode locally, you need a public HTTPS tunnel.

Typical options:

- `cloudflared`
- `ngrok`
- `tailscale funnel`

Example with `cloudflared`:

```bash
cloudflared tunnel --url http://localhost:8787
```

Suppose it gives:

```text
https://abc123.trycloudflare.com
```

Then register:

```bash
curl -X POST "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/setWebhook" \
  -d "url=https://abc123.trycloudflare.com/api/channels/telegram/webhook" \
  -d "secret_token=$TELEGRAM_WEBHOOK_SECRET"
```

If the tunnel URL changes, run `setWebhook` again.

---

## 11. End-To-End Testing

### 11.1 Polling Mode Test

1. ensure:
   - `mode: polling`
   - `enabled: true`
   - correct `owner_chat_id`
   - correct `owner_user_id`
   - `TELEGRAM_BOT_TOKEN` is set
2. run:

```bash
./run-neopsyke.sh
```

3. send the bot a direct message
4. verify NeoPsyke receives it

### 11.2 Webhook Mode Test

1. ensure:
   - `mode: webhook`
   - NeoPsyke is running
   - public HTTPS URL works
   - webhook is registered
2. send the bot a direct message
3. verify delivery:

```bash
curl "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/getWebhookInfo"
```

### 11.3 Local Webhook Handler Test Without Telegram

You can test the NeoPsyke webhook handler directly:

```bash
curl -i -X POST "http://localhost:8787/api/channels/telegram/webhook" \
  -H "Content-Type: application/json" \
  -H "X-Telegram-Bot-Api-Secret-Token: $TELEGRAM_WEBHOOK_SECRET" \
  -d '{"message":{"text":"hello from local test","chat":{"id":123456789,"type":"private"},"from":{"id":123456789,"first_name":"Owner"}}}'
```

This validates:

- route is mounted
- secret matches
- owner chat/user checks pass

---

## 12. Troubleshooting

### `getUpdates` returns `[]`

Possible causes:

- you have not messaged the bot yet
- a webhook is still set

Fix:

```bash
curl "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/getWebhookInfo"
curl -X POST "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/deleteWebhook"
```

Then message the bot again.

### Messages are not received in webhook mode

Check:

1. NeoPsyke is running
2. the public URL is reachable
3. the path is exactly:
   - `/api/channels/telegram/webhook`
4. `secret_token` matches `TELEGRAM_WEBHOOK_SECRET`
5. `getWebhookInfo` shows no recent delivery error

### Messages are not received in polling mode

Check:

1. `mode: polling`
2. `enabled: true`
3. `TELEGRAM_BOT_TOKEN` is set in the same shell that launched NeoPsyke
4. you messaged the correct bot
5. `owner_chat_id` and `owner_user_id` match the sender

### Messages are silently ignored

Most likely:

- `drop_unauthorized_messages: true`
- and either `owner_chat_id` or `owner_user_id` is wrong

Double-check them using Telegram update payloads.

### Bot can send but cannot receive

That usually means:

- outbound token is correct
- inbound transport is wrong

Common cases:

- webhook mode without public HTTPS
- webhook mode with wrong secret
- polling mode while a webhook is still active elsewhere

---

## 13. Security Notes

NeoPsyke treats Telegram as owner-only, not as a public bot channel.

Recommended settings:

- `require_direct_chat: true`
- `drop_unauthorized_messages: true`

Do not:

- commit `TELEGRAM_BOT_TOKEN`
- commit `TELEGRAM_WEBHOOK_SECRET`
- disable owner filtering for a personal bot

Current trust boundary:

- only accepted owner Telegram messages become trusted owner-direct chat input
- all other traffic is rejected or dropped

---

## 14. Recommended Default

For most development and personal use:

- use `polling`
- keep `require_direct_chat: true`
- keep `drop_unauthorized_messages: true`
- keep only secrets in env
- keep IDs and mode in YAML

That is the simplest reliable setup for running NeoPsyke from your local
machine.
