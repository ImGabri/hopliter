# Hopliter Multiplayer

Hopliter copies the full stack in **hotbar slot 1** into an empty inventory
slot. The default destination is **hotbar slot 2**. The copy includes the
stack's count and item components, including a UID stored in the item data.

## Paper multiplayer

1. Put `Hopliter-Multiplayer.jar` in the Paper server's `plugins/` folder and
   restart the server. The same JAR is also the desktop launcher.
2. Use an operator account or have the server administrator grant your player
   `hopliter.copy`. Paper checks this permission before copying.
3. Put the stack in hotbar slot 1 and leave slot 2 empty. In Minecraft, run
   `/hoplitercopy`, or open the launcher and click **Copy Slot 1**.

Use `/hoplitercopy auto` for the first empty hotbar or main-inventory slot.
A destination index from 1 to 35 selects a specific empty slot. The index is
zero-based, so 1 selects the visible hotbar slot 2. Minecraft chat reports
whether the server completed the copy. The launcher reports when it sends the
command through the player's existing Minecraft session. Look for the server's
confirmation in Minecraft chat.

| Minecraft and Paper | Java for the game and server |
| --- | --- |
| 1.21.11 | 21 |
| 26.3 | 25 |

## Launcher

Open `Hopliter-Multiplayer.jar` with JDK 21 or newer, under the same OS account
and elevation level as Minecraft. On Windows, **Refresh** combines Java's JVM
list with a scan of visible top-level Minecraft windows. If the game is absent
from the list, get its `javaw.exe` or `java.exe` PID from the **Details** tab in
Task Manager and enter it in **Minecraft PID**. Select the game or enter its PID,
then click **Copy Slot 1**. The launcher validates the client before copying or
sending a command. It also supports integrated single-player and hosted LAN worlds.

```sh
java -jar Hopliter-Multiplayer.jar --list
java -jar Hopliter-Multiplayer.jar --attach <pid>
java -jar Hopliter-Multiplayer.jar --attach <pid> auto
```

On a JVM that requires explicit opt-in, add `-XX:+EnableDynamicAgentLoading` to
Minecraft's JVM arguments and restart the game. The in-game server command is
also available directly from chat. Restart Minecraft when switching between
Hopliter injector builds.

## Build and verify

Use JDK 21 and Maven.

```sh
mvn clean package
python3 tests/test_fixture.py -v
```

The build writes `target/Hopliter-Multiplayer.jar`.
