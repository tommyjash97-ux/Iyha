# IYHA SMP - IYHACore

One custom plugin for the IYHA SMP Minecraft server (Paper). Built on GitHub Actions.

## Status (drop 2): everything is WRITTEN, nothing is TESTED

| System | State |
|---|---|
| Build pipeline, database (SQLite or MariaDB), migrations | Written |
| Economy ledger, /bal /pay /paytoggle /baltop /transactions /eco, kill switch | Written |
| Vault provider and PlaceholderAPI expansion (optional) | Written |
| Sell, worth, history, category multipliers | Written |
| Shop (categories, quantity, confirm, search, Quick Buy tab) | Written |
| Auction House (list, browse, search, sort, categories, buy, cancel, expire, claims, history) | Written |
| Buy orders (escrow, fill from menu or /sell, cancel, expire) | Written |
| Custom 3x3 enchanting (Model B and Model A), enchant books, special enchants | Written |
| Homes, warps, spawn, TPA, random teleport, warm-up, safe teleport | Written |
| Combat tag, logout rule, kills/deaths, anti-farm, bounties, kill messages | Written |
| Ranks menu, limits from permissions | Written |
| Profile, stats, playtime, settings, leaderboards | Written |
| Custom items (vouchers, effect items), duplicate scanner | Written |
| Events: reward drop, king of the hill, last man standing | Written |
| Messages, ignore, night vision, main menu, info commands | Written |
| Spawn protection, walk-in portals, world border | Written |

"Written" means a draft nobody has compiled or run. A system is only finished after it is built, run on a
staging server, and passes its test list. Expect compile errors on the first build and bugs after that.

## Not included yet (planned, in the roadmap)

Shards (second currency), Duels, virtual spawners, Bedrock support, the resource pack, spawn map and NPC/hologram
setup (use Citizens and DecentHolograms), moderation plugin choice, MariaDB backup scripts.

## Requirements

- Paper 26.1 or newer (built against the 26.1.2 API) and Java 25 (verify on docs.papermc.io)
- Optional: LuckPerms, Vault, PlaceholderAPI, TAB, Citizens, DecentHolograms, WorldGuard

## Getting the jar

1. Open the Actions tab, open the latest run, wait for the green check.
2. Under Artifacts, download `IYHACore`, unzip it, put the `.jar` in `plugins/`.
3. Restart the server. `plugins/IYHACore/` is created with `config.yml`, `messages.yml`, `worth.yml`, `shop.yml`,
   `enchantments.yml` and `items.yml`.

## First-time setup

1. Edit `config.yml` (database, spawn, events, links under `info:`).
2. Run `/setspawn` at your spawn. Optionally `/setwarp <name>`.
3. Ranks and limits come from LuckPerms permissions on groups, for example:
   - `lp group default permission set iyha.limit.homes.2 true`
   - `lp group iyha_plus permission set iyha.limit.homes.5 true`
   - `lp group iyha_plus permission set iyha.limit.ah-listings.40 true`
   - `lp group iyha_plus permission set iyha.limit.orders.20 true`
   - `lp group iyha_plus permission set iyha.limit.warmup.3 true`
   - `lp group iyha_plus permission set iyha.limit.cooldown-rtp.120 true`
   - `lp group iyha_plus permission set iyha.ah.skipconfirm true`
4. Admin permissions default to `op` so a fresh server works. For production, give `iyha.admin.*` nodes through LuckPerms.
5. Economy safety: `/eco audit` checks the ledger; `/eco freeze` stops all money movement.
6. Crash recovery: `/iyha journal list` shows unresolved item operations; `restore <id>` returns the items as claims.

## Layout note

All source files are in the repository root (flat layout) so they can be uploaded from a phone.
The Java package is `net.iyha.core`. The layout will be reorganised into folders later.

## Known deviations from the architecture plan

- `accounts` uses a composite key (owner, currency) instead of a separate `account_id`.
- The 3x3 enchanting menu has an extra item slot (15) and book slot (33) in Model B.
- Admin permissions default to `op`.
- `/kill` is called `/suicide` so it does not shadow the vanilla command.
- GUI text is written in code (MiniMessage) rather than in messages.yml; chat messages are all in messages.yml.

## Residual risks

- A crash in the milliseconds between a durable inventory change and its database commit can strand one item
  until staff restore it from the journal (no-duplication is preferred over no-loss).
- A crash while items sit inside the sell or enchant menu leaves a journal entry for staff to restore.
- Vault calls block for one database transaction.
- Vanilla or Paper-level duplication bugs outside this plugin are not prevented by it.
