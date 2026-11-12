# Hopliter Multiplayer

Hopliter copies the full stack in **hotbar slot 1** into an empty inventory
slot. The default destination is **hotbar slot 2**. The copy includes the
stack's count and item components, including a UID stored in the item data.

## Vanilla multiplayer with operator permission

1. Join your Minecraft Java server with an operator account that can run
   `/execute` and `/item` (permission level 2 or higher on vanilla).
2. Put the stack in hotbar slot 1 and leave slot 2 empty.
3. Run `Hopliter-Multiplayer.jar` on your computer, select Minecraft, and click
   **Copy Slot 1**. Check Minecraft chat for the server result.

The launcher sends a built-in Minecraft command through your current session.
This requires no plugin, mod, or data pack on the server. The server checks that
the source contains an item and the destination is empty before copying.
For `auto`, the launcher chooses the first empty slot it sees and the server
checks that the slot is still empty.

## Paper multiplayer

Servers that install the plugin can grant `hopliter.copy` to specific players.
The launcher uses `/hoplitercopy` when the server advertises it.

1. Put `Hopliter-Multiplayer.jar` in the Paper server's `plugins/` folder and
   restart the server. The same JAR is also the desktop launcher.
2. Use an operator account or have the server administrator grant your player
   `hopliter.copy`. Paper checks this permission before copying.
3. Put the stack in hotbar slot 1 and leave slot 2 empty. In Minecraft, run
   `/hoplitercopy`, or open the launcher and click **Copy Slot 1**.

Use `/hoplitercopy auto` for the first empty hotbar or main-inventory slot.
A destination index from 1 to 35 selects a specific empty slot. The index is
zero-based, so 1 selects the visible hotbar slot 2. Minecraft chat reports
whether the server completed the copy. The command uses the player's existing
Minecraft session.

| Minecraft Java | Java for the game and server |
| --- | --- |
| 1.21.11 | 21 |
| 26.3 | 25 |

## Launcher

Open `Hopliter-Multiplayer.jar` with JDK 21 or newer, under the same OS account
as Minecraft. The launcher also supports integrated single-player and hosted
LAN worlds. Select Minecraft and click **Copy Slot 1**.

```sh
java -jar Hopliter-Multiplayer.jar --list
java -jar Hopliter-Multiplayer.jar --attach <pid>
java -jar Hopliter-Multiplayer.jar --attach <pid> auto
```

On a JVM that requires explicit opt-in, add `-XX:+EnableDynamicAgentLoading` to
Minecraft's JVM arguments and restart the game. On a server with the Paper
plugin, `/hoplitercopy` is also available directly from chat. Restart Minecraft
when switching between Hopliter injector builds.

## Build and verify

Use JDK 21 and Maven.

```sh
mvn clean package
python3 tests/test_fixture.py -v
```

The build writes `target/Hopliter-Multiplayer.jar`.
