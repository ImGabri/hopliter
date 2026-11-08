# hopliter

Held Item UID Fixture for Minecraft staging tests.

This Java 21 executable copies one unit of the test player's held item to the
first main inventory slot (`inventory.0`) by default. Experimental Multiply
creates a batch of one-unit copies in separate slots. Both modes use the
authenticated staging server console and can verify a UID stored on the item.

## Run on staging

1. Use an existing RCON endpoint on your Java 1.21.11 staging backend, reached
   at localhost or through an SSH tunnel. The default local port is 25575.
2. Log in with the test player and hold a UID-bearing item. Keep the same item
   selected during the run. For the default run, free the upper-left slot of the
   main inventory.
3. Open `HeldItemUidFixture.jar` from any folder with Java 21. In the dialog,
   enter the local port, Java username, and RCON password. Select **Run once**.
   Supply the optional UID path and type to get an exact comparison.

For a terminal run, use this command and enter the password when prompted.

```text
java -jar HeldItemUidFixture.jar --host 127.0.0.1 --port 25575 --player TestPlayer
```

## Experimental Multiply

Tick **Experimental Multiply** in the dialog and enter a factor from 1 to 28,
or add `--experimental-multiply N` in the terminal. The factor includes the
original stack. With **×4**, the tool adds three one-unit copies to three
empty main-inventory slots. A held stack of five stays at five. **×1** requests
zero copies.

```text
java -jar HeldItemUidFixture.jar --player TestPlayer --experimental-multiply 4
```

The tool checks for enough free slots before it starts. It uses the captured
hotbar slot as the source, compares the source again before each copy, and
checks the selected hotbar slot and empty destination in the write command.
Each copy is verified from its server-side readback.

## Verify a UID

UID paths are relative to the item's `components` compound. For example,
append `--uid-path minecraft:custom_data.fixture_uid --uid-type string` to
compare a string stored under the `fixture_uid` key in custom data. Quote
individual path keys that contain dots, such as
`minecraft:custom_data.PublicBukkitValues."demo.plugin:uid"`. Use
`java -jar HeldItemUidFixture.jar --help` for the supported NBT types.

## Interpret the result

| Result | Meaning |
| --- | --- |
| `PASS` | The requested copies and their typed UIDs match at readback. |
| `UID_UNVERIFIED` | The copy data and counts match. Supply a valid UID path and type for a direct UID check. |
| `NO_COPY` | No write was sent. The result explains why. |
| `PARTIAL` | Multiply stopped after confirming at least one copy. The report lists the copies it confirmed. |
| `INCONCLUSIVE` | A write or readback could not be confirmed. The report identifies any uncertain slot. |
| `RECOVERY_READBACK` | The tool inspected the attempted destinations and reported their current contents. |

The tool records each destination before it attempts a write. After a partial
or inconclusive run, reopen this version for the same endpoint and test player.
That run only reads the attempted slots and exits. The readback shows current
contents, so use staging logs to interpret any missing or rewritten copies.

## Build and verify

Build with a JDK 21 and Maven using `mvn clean package`. The Maven build creates
`target/helditem-uid-fixture-1.1.0.jar`. The ready-to-run JAR in this package was
compiled with OpenJDK 21. Run `python3 tests/test_fixture.py` on Linux or macOS
to test the packaged JAR against the scripted RCON endpoint.

For a staging integration check, use a held stack of five with a known UID and
at least three free main-inventory slots. Run **×4** with that UID path and
type. Confirm count five in the source and one unit with the same UID in each
of the three destinations. Repeat with only two free slots and check for
`NO_COPY`. The included simulator tests also cover a selected slot change,
a connection drop after the second write, and a recovery run with zero writes.
Run the integration check on your actual backend to confirm its command
syntax and English console output.
