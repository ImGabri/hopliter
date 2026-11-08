"""CLI contract tests over a local scripted RCON endpoint."""

from contextlib import contextmanager
import errno
import os
from pathlib import Path
import pty
import re
import select
import socketserver
import struct
import subprocess
from tempfile import TemporaryDirectory
from threading import Thread
from time import monotonic
import unittest


JAR = Path(__file__).resolve().parents[1] / "HeldItemUidFixture.jar"
PLAYER = "TestPlayer"
HAND = '"minecraft:custom_data":{fixture_uid:"test-uid", marker:1b}, "minecraft:damage":0'
COPY = '"minecraft:damage":0, "minecraft:custom_data":{marker:1b, fixture_uid:"test-uid"}'
WRITE = (
    "execute as TestPlayer if items entity @s weapon.mainhand * unless items entity @s inventory.0 * "
    'run item replace entity @s inventory.0 from entity @s weapon.mainhand {function:"minecraft:set_count",count:1}'
)
QUERY = f"data get entity {PLAYER} "


def batch_command(source, destination):
    return (
        f"execute as {PLAYER} if data entity @s {{SelectedItemSlot:{source}}} "
        f"if items entity @s hotbar.{source} * unless items entity @s inventory.{destination} * "
        f"run item replace entity @s inventory.{destination} from entity @s hotbar.{source} "
        '{function:"minecraft:set_count",count:1}'
    )


def response(request_id, kind, body=b""):
    return struct.pack("<iii", len(body) + 10, request_id, kind) + body + b"\0\0"


class Handler(socketserver.StreamRequestHandler):
    def handle(self):
        authenticated = False
        while prefix := self.rfile.read(4):
            length, = struct.unpack("<i", prefix)
            packet = self.rfile.read(length)
            request_id, kind = struct.unpack("<ii", packet[:8])
            body = packet[8:-2].decode("utf-8")
            if kind == 3:
                authenticated = body == "local-test-password" and not self.server.reject_password
                self.wfile.write(response(request_id if authenticated else -1, 2))
                continue
            if not authenticated:
                self.wfile.write(response(-1, 2))
                return
            if kind == 0:
                self.wfile.write(response(request_id, 0, b"Unknown request 0"))
                continue
            reply = self.server.execute(body)
            if reply is None:
                return
            data = reply.encode("utf-8")
            for start in range(0, max(1, len(data)), 4096):
                self.wfile.write(response(request_id, 0, data[start:start + 4096]))


class StagingServer(socketserver.ThreadingTCPServer):
    daemon_threads = True

    def __init__(self):
        super().__init__(("127.0.0.1", 0), Handler)
        self.commands = []
        self.writes = 0
        self.hand = HAND
        self.copy = COPY
        self.destinations = {}
        self.slot_suffix = "b"
        self.source_hotbar = 4
        self.selected_hotbar = self.source_hotbar
        self.offline = False
        self.reject_password = False
        self.disconnect_on_write = None
        self.occupy_on_write = None
        self.switch_after_write = None
        self.clear_after_write = False

    @property
    def destination(self):
        return self.destinations.get(0)

    @destination.setter
    def destination(self, contents):
        self.destinations[0] = contents

    def execute(self, command):
        self.commands.append(command)
        if self.offline:
            return "No entity was found"
        if command.startswith(f"execute as {PLAYER} "):
            self.writes += 1
            if command == WRITE:
                destination = 0
            else:
                destination = next(
                    (slot for slot in range(27) if command == batch_command(self.source_hotbar, slot)), None
                )
                if destination is None:
                    return "Unexpected batch write command"
                if self.selected_hotbar != self.source_hotbar:
                    return "Test failed"
            if self.occupy_on_write == self.writes:
                self.destinations[destination] = "arrived_during_batch:1b"
            if self.hand is None or destination in self.destinations:
                return "Test failed"
            if not self.clear_after_write:
                self.destinations[destination] = self.copy
            if self.switch_after_write == self.writes:
                self.selected_hotbar = self.source_hotbar + 1
            if self.disconnect_on_write == self.writes:
                return None
            return f"Replaced a slot on {PLAYER} with [Paper]"
        if command == QUERY + "SelectedItemSlot":
            return f"{PLAYER} has the following entity data: {self.selected_hotbar}"
        if command == QUERY + "SelectedItem":
            if self.hand is None:
                return "Found no elements matching SelectedItem"
            return (
                f'{PLAYER} has the following entity data: '
                f'{{id:"minecraft:paper",count:5,components:{{{self.hand}}}}}'
            )
        path = command.removeprefix(QUERY)
        slot_query = re.fullmatch(r"Inventory\[\{Slot:(\d+)(b?)\}\]", path)
        if slot_query:
            index = int(slot_query.group(1))
            if index < 9:
                components = self.hand if index in (self.source_hotbar, self.selected_hotbar) else None
                count = 5
            else:
                components = self.destinations.get(index - 9)
                count = 1
            if components is None or slot_query.group(2) != self.slot_suffix:
                return f"Found no elements matching {path}"
            return (
                f'{PLAYER} has the following entity data: '
                f'{{Slot:{index}{self.slot_suffix},components:{{{components}}},count:{count},id:"minecraft:paper"}}'
            )
        return "Unexpected command"


@contextmanager
def staging():
    with StagingServer() as server:
        worker = Thread(target=server.serve_forever, kwargs={"poll_interval": 0.02}, daemon=True)
        worker.start()
        try:
            yield server
        finally:
            server.shutdown()
            worker.join()


def launch(server, home, *options):
    master, slave = pty.openpty()
    try:
        process = subprocess.Popen(
            ["java", f"-Duser.home={home}", "-jar", str(JAR), "--player", PLAYER,
             "--port", str(server.server_address[1]), *options],
            stdin=slave, stdout=slave, stderr=slave,
        )
    finally:
        os.close(slave)
    result = b""
    password_sent = False
    deadline = monotonic() + 12
    try:
        while monotonic() < deadline:
            readable, _, _ = select.select([master], [], [], 0.1)
            if not readable:
                continue
            try:
                block = os.read(master, 8192)
            except OSError as error:
                if error.errno == errno.EIO:
                    break
                raise
            if not block:
                break
            result += block
            if not password_sent and b"Staging RCON password:" in result:
                os.write(master, b"local-test-password\n")
                password_sent = True
        return process.wait(timeout=1), result.decode("utf-8")
    finally:
        os.close(master)
        if process.poll() is None:
            process.kill()
            process.wait()


class FixtureTests(unittest.TestCase):
    def setUp(self):
        home = TemporaryDirectory()
        self.addCleanup(home.cleanup)
        self.home = home.name

    def test_copy_and_verify_uid_from_slot_nine(self):
        with staging() as server:
            code, output = launch(
                server, self.home, "--uid-path", "minecraft:custom_data.fixture_uid", "--uid-type", "string"
            )
            self.assertEqual(code, 0, output)
            self.assertIn("PASS", output)
            self.assertIn("Matching UID (string) = test-uid", output)
            self.assertIn("original count is 5", output)
            self.assertNotIn("local-test-password", output)
            self.assertEqual(server.writes, 1)
            self.assertEqual(server.destination, COPY)
            self.assertNotIn(QUERY + "Inventory[{Slot:0}]", server.commands)

    def test_multipart_snbt_with_typed_array_and_quoted_path(self):
        with staging() as server:
            server.slot_suffix = ""
            server.hand = server.copy = (
                '"minecraft:custom_data":{PublicBukkitValues:{"demo.plugin:uid":[I;1,2,3,4]},'
                'note:"' + "x" * 4300 + '"}'
            )
            path = 'minecraft:custom_data.PublicBukkitValues."demo.plugin:uid"'
            code, output = launch(server, self.home, "--uid-path", path, "--uid-type", "int_array")
            self.assertEqual(code, 0, output)
            self.assertIn("Matching UID (int_array) = [1, 2, 3, 4]", output)
            self.assertEqual(server.writes, 1)

    def test_uid_type_mismatch_stays_unverified(self):
        with staging() as server:
            code, output = launch(
                server, self.home, "--uid-path", "minecraft:custom_data.fixture_uid", "--uid-type", "int"
            )
            self.assertEqual(code, 0, output)
            self.assertIn("UID_UNVERIFIED", output)
            self.assertIn("different NBT type", output)
            self.assertEqual(server.writes, 1)

    def test_empty_hand_occupied_slot_and_offline_player_send_no_writes(self):
        cases = [
            ("hand", None, "main hand is empty"),
            ("destination", COPY, "inventory.0) is occupied"),
            ("offline", True, "player is offline"),
        ]
        for attribute, value, message in cases:
            with self.subTest(attribute=attribute), staging() as server:
                setattr(server, attribute, value)
                code, output = launch(server, self.home)
                self.assertEqual(code, 3, output)
                self.assertIn("NO_COPY", output)
                self.assertIn(message, output)
                self.assertEqual(server.writes, 0)

    def test_interrupted_write_forces_read_only_next_run(self):
        with staging() as server:
            server.disconnect_on_write = 1
            server.clear_after_write = True
            code, output = launch(server, self.home)
            self.assertEqual(code, 4, output)
            self.assertIn("INCONCLUSIVE", output)
            self.assertEqual(server.writes, 1)

            code, output = launch(server, self.home)
            self.assertEqual(code, 4, output)
            self.assertIn("RECOVERY_READBACK", output)
            self.assertIn("inventory.0 = empty", output)
            self.assertEqual(server.writes, 1)

    def test_changed_copy_uid_is_not_reported_as_a_match(self):
        with staging() as server:
            server.copy = COPY.replace('"test-uid"', '"different-uid"')
            code, output = launch(
                server, self.home, "--uid-path", "minecraft:custom_data.fixture_uid", "--uid-type", "string"
            )
            self.assertEqual(code, 4, output)
            self.assertIn("INCONCLUSIVE", output)
            self.assertNotIn("Matching UID", output)
            self.assertEqual(server.writes, 1)

    def test_failed_authentication_sends_no_commands(self):
        with staging() as server:
            server.reject_password = True
            code, output = launch(server, self.home)
            self.assertEqual(code, 1, output)
            self.assertIn("authentication failed", output)
            self.assertEqual(server.commands, [])

    def test_remote_host_and_selector_are_rejected_before_connecting(self):
        for options in (["--host", "staging.example", "--player", PLAYER], ["--player", "@a"]):
            result = subprocess.run(
                ["java", "-jar", str(JAR), *options], capture_output=True, text=True, timeout=5
            )
            self.assertEqual(result.returncode, 2, result.stderr)

    def test_multiply_uses_available_main_inventory_slots_and_preserves_typed_uid(self):
        with staging() as server:
            occupied = {0: "untouched:1b", 2: "untouched:2b"}
            server.destinations.update(occupied)
            code, output = launch(
                server, self.home, "--experimental-multiply", "4",
                "--uid-path", "minecraft:custom_data.fixture_uid", "--uid-type", "string",
            )
            self.assertEqual(code, 0, output)
            self.assertIn("Requested copies = 3. Verified copies = 3.", output)
            self.assertIn("[inventory.1, inventory.3, inventory.4]", output)
            self.assertIn("original count is 5", output)
            self.assertIn("Matching UID (string) = test-uid", output)
            self.assertEqual(server.destinations, occupied | {1: COPY, 3: COPY, 4: COPY})
            self.assertEqual(
                [command for command in server.commands if command.startswith("execute as ")],
                [batch_command(server.source_hotbar, slot) for slot in (1, 3, 4)],
            )

    def test_multiply_checks_space_before_writing(self):
        with staging() as server:
            server.destinations.update(dict.fromkeys(range(25), "untouched:1b"))
            code, output = launch(server, self.home, "--experimental-multiply", "4")
            self.assertEqual(code, 3, output)
            self.assertIn("Need 3 empty main-inventory slots. Found 2.", output)
            self.assertEqual(server.writes, 0)

    def test_multiply_stops_after_selected_slot_changes(self):
        with staging() as server:
            server.switch_after_write = 1
            code, output = launch(server, self.home, "--experimental-multiply", "4")
            self.assertEqual(code, 4, output)
            self.assertIn("PARTIAL", output)
            self.assertIn("Verified copies = 1", output)
            self.assertIn("selected slot changed", output)
            self.assertEqual(server.writes, 1)
            self.assertEqual(server.destinations, {0: COPY})

    def test_multiply_preserves_a_slot_occupied_after_preflight(self):
        with staging() as server:
            server.occupy_on_write = 2
            code, output = launch(server, self.home, "--experimental-multiply", "4")
            self.assertEqual(code, 4, output)
            self.assertIn("INCONCLUSIVE", output)
            self.assertIn("Verified copies = 1", output)
            self.assertIn("Uncertain slot = inventory.1.", output)
            self.assertEqual(server.writes, 2)
            self.assertEqual(server.destinations, {0: COPY, 1: "arrived_during_batch:1b"})

    def test_multiply_recovery_reads_attempted_slots_after_second_write(self):
        with staging() as server:
            server.disconnect_on_write = 2
            code, output = launch(server, self.home, "--experimental-multiply", "4")
            self.assertEqual(code, 4, output)
            self.assertIn("INCONCLUSIVE", output)
            self.assertIn("Verified copies = 1", output)
            self.assertIn("Uncertain slot = inventory.1.", output)
            self.assertEqual(server.writes, 2)
            self.assertEqual(server.destinations, {0: COPY, 1: COPY})

            server.commands.clear()
            code, output = launch(server, self.home, "--experimental-multiply", "4")
            self.assertEqual(code, 4, output)
            self.assertIn("RECOVERY_READBACK", output)
            self.assertIn("inventory.0 = 1 × minecraft:paper", output)
            self.assertIn("inventory.1 = 1 × minecraft:paper", output)
            self.assertEqual(server.writes, 2)
            self.assertEqual(server.commands, [QUERY + "Inventory[{Slot:9b}]", QUERY + "Inventory[{Slot:10b}]"])

    def test_multiply_recovers_previous_versions_single_slot_marker(self):
        with staging() as server:
            directory = Path(self.home) / ".helditemuidfixture"
            directory.mkdir()
            marker = directory / f"loopback4-{server.server_address[1]}-testplayer.state"
            marker.write_bytes(b"\x01")
            code, output = launch(server, self.home, "--experimental-multiply", "4")
            self.assertEqual(code, 4, output)
            self.assertIn("RECOVERY_READBACK", output)
            self.assertIn("inventory.0 = empty", output)
            self.assertEqual(server.writes, 0)
            self.assertEqual(marker.read_bytes(), b"")

    def test_multiply_accepts_one_and_rejects_out_of_range_factors(self):
        with staging() as server:
            code, output = launch(server, self.home, "--experimental-multiply", "1")
            self.assertEqual(code, 3, output)
            self.assertIn("requests zero copies", output)
            self.assertEqual(server.writes, 0)
        for factor in ("0", "29", "two"):
            with self.subTest(factor=factor):
                result = subprocess.run(
                    ["java", "-jar", str(JAR), "--player", PLAYER, "--experimental-multiply", factor],
                    capture_output=True, text=True, timeout=5,
                )
                self.assertEqual(result.returncode, 2, result.stderr)
                self.assertIn("multiply factor from 1 to 28", result.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
