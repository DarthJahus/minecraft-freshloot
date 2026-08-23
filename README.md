# FreshLoot

A chest or barrel with a loot table gives out an **independent loot roll to each player**, once per player and per position.

## The problem this solves

By default, a loot chest (in a dungeon, a structure, an ancient city, a boat, etc.) generates its contents once, on first open. The first player to open it picks from the pool; everyone after that only finds whatever is left; if anything is left.

On a multiplayer server, that just rewards whoever gets there fastest. This might not be very fair; *your server, your rules*.

With **FreshLoot**, every player who opens the chest for the first time gets their own roll, as if he was alone in front of a brand-new chest. Once a player has looted it, he can't pull a second roll from that position.

## Mechanics
- **Opening**: when a player opens a chest or barrel carrying a loot table, the mod checks whether he already has looted this position. If he has, the open is denied (no GUI ever shows up). If not, loot is generated normally for him.
- **One chest at a time**: if a player has a chest open, no other player can open it at the same time. He has to wait for the first one to close his GUI.
- **Rotation**: when the GUI closes, the chest is destroyed and immediately replaced with a new one, carrying the same loot table, ready for the next player. This same mechanism is what lets the mod work without depending on in-memory state that could get lost: the new chest carries its loot table directly in its own data, exactly like a vanilla chest that's never been opened.
- **Breaking instead of opening is griefing**: breaking a chest that still has an unclaimed loot table (whether it's brand-new, freshly put back into rotation, or *currently* open by someone, including the breaker themselves) sends a public message to the server and empties the loot before the block drops. The chest then gives out nothing: not to the player who broke it, not to anyone after them, since the position itself is destroyed (unless *currently* open by someone, in case the content is dropped).
- **Persistence**: the record of "which player has already looted which position" survives a server restart. Everything else (who currently has a chest open, etc.) is deliberately non-persistent state. It only makes sense while the server is running.
- **Spectator mode**: entirely ignored. A spectator can look inside any chest, including one currently open by a player, without triggering any of the rules above.

## Known limitations
- **Double chests aren't handled.** A double chest (two single chests joined together) is left to full-vanilla behavior. The mod doesn't touch it at all, even if it carries a loot table.
- **Only chests and barrels.** Any other loot-table-carrying container (shulker box holder, modded barrel, etc.) follows standard vanilla behavior.
- **Rotation breaks and replaces the block.** The refresh mechanism relies on physically destroying the chest and recreating it identically. It's invisible in-game, but it means a chest "in rotation" literally changes block identity on every cycle. A third-party mod that tracks block entities by reference might not be happy about that.

## Wishlist
- [ ] Double chest support.
- [ ] Support for other loot-table-carrying containers (maybe)
- [ ] External configuration, instead of hardcoded constants (e.g. being able to disable the grief message, edit its text, or even its behavior).
- [ ] Translation of the grief message (currently English-only, not localized).

## Licence

[Apache 2.0 Licence](/LICENCE.md)