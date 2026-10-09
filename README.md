# StoreTrack

Store purchase tracking for Paper 1.21.11. It reads your payments from Tebex, CraftingStore or PayNow, keeps the rankings, runs a community goal, announces purchases and sends Discord messages. It can also run a GG wave and put the top buyers' skins on FancyNpcs NPCs.

## What it does

- **Payments** come from Tebex, CraftingStore, PayNow or by hand (`provider: manual`). They are fetched every `poll-interval-seconds` and stored once, so a payment is never rewarded twice.
- **On purchase:** a chat line, a title, a sound, fireworks, and console commands with `{player}`, `{amount}` and `{package}`.
- **Community goal** with a progress bar, milestones that run commands and send a Discord message, and commands for when the goal is reached.
- **Donor tiers:** when a player's total passes a threshold, the tier's commands run once for that player.
- **Rankings** for all-time, this month and the most recent purchases, as placeholders.
- **Refunds and chargebacks:** a payment that Tebex reports as refunded or charged back is taken out of the rankings and the goal, and the `on-chargeback` commands run.
- **GG wave:** `/storetrack ggwave start` opens a short window where the first "gg" of every player is rewarded.
- **Discord webhooks** for purchases, milestones, goal reached and sales, as Components V2 (templates in `webhooks/`) or a plain embed.
- **Storage:** SQLite by default, MySQL or MariaDB with `storage.type`.

Payments that exist in the store when StoreTrack is first installed are counted in the rankings but not announced, and no commands run for them.

## Setup

1. Put the jar in `plugins/` and start the server once.
2. Open `plugins/StoreTrack/config.yml`, set `provider` and paste the secret of that provider (`tebex.secret`, `craftingstore.secret`, or `paynow.api-key` with `paynow.store-id`).
3. `/storetrack reload`. The console says which provider is connected.

If the secret is missing the plugin says so and keeps working in manual mode.

## Commands

All commands need `storetrack.admin` (op by default). Alias: `/strack`.

| Command | What it does |
| --- | --- |
| `/storetrack status` | Provider, time of the last check, storage, goal and GG wave |
| `/storetrack reload` | Reload config.yml and messages.yml. A file with a mistake cancels the reload and keeps the old settings |
| `/storetrack manual <player> <amount> [package]` | Add a payment by hand |
| `/storetrack payment info\|remove\|restore <id> [reason]` | Look at a payment, take it out of the totals, or put it back. A reason with "refund" or "chargeback" in it also runs the chargeback commands |
| `/storetrack history <player>` | The payments of a player |
| `/storetrack recent [n]` | The latest purchases |
| `/storetrack goal create <name> <target>` | Start a new goal. Its milestones can be reached again |
| `/storetrack goal set current\|target <number>` | Change the goal by hand |
| `/storetrack goal delete` | Remove the goal |
| `/storetrack test purchase\|goal\|milestone\|sale` | Send a test Discord message |
| `/storetrack ggwave start\|stop` | Start or end a GG wave |
| `/storetrack npcrefresh` | Update the NPC skins now |

## Placeholders (PlaceholderAPI)

| Placeholder | Value |
| --- | --- |
| `%storetrack_alltime_<n>_name%`, `%storetrack_alltime_<n>_amount%` | The n-th biggest buyer of all time |
| `%storetrack_monthly_<n>_name%`, `%storetrack_monthly_<n>_amount%` | The same for this month |
| `%storetrack_recent_<n>_name%`, `_package`, `_amount`, `_date` | The n-th latest purchase |
| `%storetrack_player_alltime%`, `%storetrack_player_monthly%` | What the player has spent |
| `%storetrack_player_rank_alltime%`, `%storetrack_player_rank_monthly%` | The player's place, `-1` when not in the list |
| `%storetrack_goal_current%`, `_target`, `_percent`, `_bar` | The community goal |
| `%storetrack_sale_name%`, `%storetrack_sale_amount%` | From the `sale:` part of the config |

## Safety

The name of a buyer comes from the store, so it is checked before it goes into a console command, a chat line or a Discord message. A name with spaces or other characters that could carry extra arguments is shown as "Someone" and the on-purchase commands are skipped for that payment, with a line in the console. Text sent to Discord cannot ping `@everyone` or break the message.

## Files

`config.yml`, `messages.yml`, and the Discord templates in `webhooks/`. Data is in `storetrack.db` (SQLite) or your database.

## Telemetry

On startup StoreTrack sends a small anonymous beacon (plugin name/version, server software/version, online/max player counts, and a random ID with no player data) so we know which versions are in use. Turn it off with `metrics.enabled: false` in `config.yml`.

## Building

```
./gradlew build
```

The jar is in `build/libs/`.
