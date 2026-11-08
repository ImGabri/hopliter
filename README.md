# Hopliter Injector

An executable Java agent for Minecraft Java Edition 1.21.11. Open the launcher
with a Java 21 JDK under the same desktop account as your Vanilla or Fabric
Minecraft client.

[Download Hopliter-Injector.jar](Hopliter-Injector.jar?raw=true)

## Use

1. Hold an item in a single-player world or a LAN world you host.
2. Open `Hopliter-Injector.jar`, or run `java -jar Hopliter-Injector.jar`.
3. Select Minecraft and click **Attach and Copy Held Item**. **auto** selects
   the first free hotbar or main-inventory slot. You can enter a specific
   empty slot from 0 to 35.

The agent copies one item on Minecraft's integrated server. It preserves the
item components, including a UID stored in those components. The source
stack keeps its original count. The launcher works without RCON or credentials.

## Attach from a terminal

```text
java -jar Hopliter-Injector.jar --list
java -jar Hopliter-Injector.jar --attach <pid> [auto|0-35]
```

For a JVM that requires explicit opt-in, start Minecraft with
`-XX:+EnableDynamicAgentLoading` in its JVM arguments.

## Build

Run `mvn clean package` with JDK 21. The output is
`target/Hopliter-Injector.jar`.

Run `python3 tests/test_fixture.py` to check the packaged launcher and agent.
