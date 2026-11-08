# hopliter

Item clone fixture for Minecraft staging tests.

This Java 21 executable clones one item from a source inventory slot into a
destination inventory slot on an authenticated staging RCON backend, so both
slots end up filled with the same item (same id, components, and UID). By
default it clones hotbar slot `1` into hotbar slot `2`. It preserves the source
stack and does not consume or move it.

## What it does

1. Reads the item in the source slot via `/data get`.
2. Confirms the destination slot is empty (`unless items ...`).
3. Places a copy of the source item (count 1) into the destination slot via
   `item replace entity @s inventory.<dest> with <id>{components:<snbt>} 1`.
4. Reports whether both slots are now filled.

The clone carries the source item's full component data, including any UID
stored under `components`, so the two slots contain matching items.

## Run on staging

1. Use an existing RCON endpoint on your Java 1.21.11 staging backend, reached
   at localhost or through an SSH tunnel. The default local port is 25575.
2. Log in with the test player and place an item in the source hotbar slot
   (default `1`). Keep the destination slot (default `2`) empty.
3. Open `HeldItemUidFixture.jar` from any folder with Java 21. Enter the host,
   port, player username, source/dest slot ids, and RCON password, then click
   **Clone Hotbar 1 → 2**.
4. Confirm both slots are filled in-game.

For a terminal run:

```text
java -jar HeldItemUidFixture.jar --host 127.0.0.1 --port 25575 --player TestPlayer
```

Override the slots with `--slot` and `--dest-slot`:

```text
java -jar HeldItemUidFixture.jar --player TestPlayer --slot 1 --dest-slot 2
```

## Result meanings

| Result | Meaning |
|---|---|
| `CLONED` | The source item was copied into the destination slot; both slots are filled. |
| `ERROR` | The clone did not complete. The message explains why (empty source, occupied destination, offline player, authentication failure, truncated item data, or a rejected command). |

Exit codes: `0` = cloned, `1` = error, `2` = invalid arguments.

## Failsafes and limits

- **Localhost only** — the tool refuses to connect to non-loopback hosts. Use an SSH tunnel for remote staging.
- **Empty source** — aborts with a clear message instead of sending a write.
- **Occupied destination** — aborts before writing; never overwrites an existing slot.
- **Offline player** — `/data get` reports the player missing; the tool aborts with no write sent.
- **Bad authentication** — the RCON password is verified before any inventory command is sent.
- **Truncated item data** — if `/data get` folds the source item's SNBT (Paper truncates long replies with `...`), the tool aborts rather than building a malformed command.
- **Unreadable item** — if the source slot's item id can't be parsed, the tool aborts instead of sending an invalid command.
- **Invalid slots** — source and destination must be integers `0`–`26` and must differ.

This is a staging/test utility. Run it against a disposable staging server as a positive control; it does not interact with any anti-dupe system.

## Build and verify

Build with a JDK 21 and Maven using `mvn clean package`. The Maven build creates
`target/helditem-uid-fixture-1.1.0.jar`. The ready-to-run JAR in this package was
compiled with OpenJDK 21. Run `python3 tests/test_fixture.py` on Linux or macOS
to test the packaged JAR against the scripted RCON endpoint.
