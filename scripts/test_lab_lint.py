"""Unit tests for scripts/lab-lint.py's rules. Run: python3 -m unittest scripts/test_lab_lint.py"""

from __future__ import annotations

import importlib.util
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

_SPEC = importlib.util.spec_from_file_location("lab_lint", Path(__file__).with_name("lab-lint.py"))
lint = importlib.util.module_from_spec(_SPEC)
sys.modules["lab_lint"] = lint  # dataclasses look their module up by name
assert _SPEC.loader is not None
_SPEC.loader.exec_module(lint)


def scenario(body: str, extra: str = "") -> str:
    """A minimal lab class with one scenario whose body is [body], ending in the oracle."""
    return f"""package app.getknit.knit.mesh.lab

class SampleLabTest {{
    @get:Rule
    val chaos = LabChaos.rule()

    @Test
    fun aScenario() =
        runBlocking {{
{body}
            lab.assertConverged(listOf(alice, bob), atLeast = 1) {{ it.dmWith(bob) }}
        }}
{extra}
}}
"""


def rules(src: str, name: str = "SampleLabTest.kt") -> list[str]:
    return [f.rule for f in lint.lint_text(Path(name), src)]


class MaskTest(unittest.TestCase):
    def test_strings_comments_and_templates_are_blanked(self):
        src = 'val a = "delay(1) ${x.map { "tryAwait(" }}" // delay(2)\n/* Thread.sleep(3) */ val b = 1'
        masked = lint.mask(src)
        self.assertEqual(len(src), len(masked))
        self.assertNotIn("delay", masked)
        self.assertNotIn("tryAwait", masked)
        self.assertNotIn("sleep", masked)
        self.assertIn("val b = 1", masked)

    def test_raw_strings_are_blanked(self):
        masked = lint.mask('val r = """a delay(1) "quoted" b""" + delay(2)')
        self.assertEqual(masked.count("delay"), 1)


FIXTURE_SRC = """package app.getknit.knit.mesh.lab

class LabNode {{
    private val collecting: Boolean
        get() = {getter}

    private fun compositeOver(scope: CoroutineScope) =
        CompositeMeshTransport(listOfNotNull({children}), scope)

    private suspend fun boot() {{
        {wait}
    }}
}}
"""


class FixtureTest(unittest.TestCase):
    def fixture(self, children: str, waits: str, getter: str = "true", wait: str | None = None) -> list[str]:
        boot = wait if wait is not None else f"while (!transport.collecting{waits}) delay(1)"
        return rules(FIXTURE_SRC.format(children=children, getter=getter, wait=boot), name="MeshLab.kt")

    def test_every_plane_awaited_passes(self):
        self.assertEqual([], self.fixture("transport, boardPlane, linkPlane", " || boardPlane?.collecting == false || linkPlane?.collecting == false"))

    def test_a_plane_missing_from_the_boot_wait(self):
        self.assertEqual(["plane-collectors-at-boot"], self.fixture("transport, boardPlane, linkPlane", " || linkPlane?.collecting == false"))

    def test_planes_awaited_through_the_getter_pass(self):
        getter = "transport.collecting && boardPlane?.collecting != false && linkPlane?.collecting != false"
        self.assertEqual([], self.fixture("transport, boardPlane, linkPlane", "", getter, "while (!collecting) delay(1)"))

    def test_a_getter_nothing_waits_on(self):
        getter = "transport.collecting && linkPlane?.collecting != false"
        self.assertEqual(["plane-collectors-at-boot"] * 2, self.fixture("transport, linkPlane", "", getter, "delay(1)"))

    def test_the_radio_is_a_child_too(self):
        self.assertEqual(["plane-collectors-at-boot"], self.fixture("transport, linkPlane", "", wait="while (linkPlane?.collecting == false) delay(1)"))

    def test_an_inline_child_cannot_be_awaited(self):
        self.assertEqual(["plane-collectors-at-boot"], self.fixture("transport, lora?.let(::LabBoardPlane)", ""))

    def test_the_fixture_skips_the_scenario_rules(self):
        self.assertEqual([], self.fixture("transport", ""))

    def test_the_real_fixture_is_clean(self):
        path = lint.LAB / lint.FIXTURE
        self.assertEqual([], [f.render() for f in lint.lint_text(path, path.read_text(encoding="utf-8"))])


class RuleTest(unittest.TestCase):
    def test_a_clean_scenario_passes(self):
        self.assertEqual([], rules(scenario("            assertTrue(alice.sendDm(bob, \"hi\"))")))

    def test_sleep_as_sync(self):
        self.assertEqual(["sleep-as-sync"], rules(scenario("            delay(200)")))
        self.assertEqual(["sleep-as-sync"], rules(scenario("            Thread.sleep(50)")))
        self.assertEqual(["sleep-as-sync"], rules(scenario("            TimeUnit.SECONDS.sleep(1)")))
        self.assertEqual(["sleep-as-sync"], rules(scenario("            lab.settle()")))
        self.assertEqual([], rules(scenario('            assertTrue("a delay(1) in prose", true)')))

    def test_an_allow_with_a_reason_clears_its_rule_only(self):
        allowed = scenario("            delay(lag) // lab-lint: allow sleep-as-sync — an offset into the jitter")
        self.assertEqual([], rules(allowed))
        no_reason = scenario("            delay(lag) // lab-lint: allow sleep-as-sync")
        self.assertEqual(["sleep-as-sync"], rules(no_reason))
        other_rule = scenario("            delay(lag) // lab-lint: allow direct-disconnect — wrong rule")
        self.assertEqual(["sleep-as-sync"], rules(other_rule))

    def test_an_allow_above_a_wrapped_statement_covers_its_lines(self):
        body = """            // lab-lint: allow sleep-as-sync — a 1 ms poll for one row
            val landed =
                withTimeoutOrNull(5_000) {
                    while (bob.rows().isEmpty()) delay(1)
                } != null"""
        self.assertEqual([], rules(scenario(body)))

    def test_dropped_await_result(self):
        self.assertEqual(["dropped-await-result"], rules(scenario("            lab.tryAwait(1) { bob.rows().size }")))
        used = [
            '            assertTrue("landed", lab.tryAwait(1) { bob.rows().size })',
            "            val ok = lab.tryAwait(1) { bob.rows().size }",
            '            assertTrue(\n                "landed",\n                lab.tryAwait(1) { bob.rows().size },\n            )',
            "            if (!lab.tryAwait(1) { bob.rows().size }) fail()",
            "            lab.await(1) { if (lab.tryAwait(1) { 1 }) 1 else 0 }",
            "            assertTrue(run { lab.tryAwait(1) { bob.rows().size } })",
            "            lab.tryAwait(1) { bob.rows().size } || fail(\"never landed\")",
            "            lab.tryAwait(1) { bob.rows().size }\n                .let { assertTrue(it) }",
        ]
        for body in used:
            with self.subTest(body=body):
                self.assertEqual([], rules(scenario(body)))

    def test_release_then_hold(self):
        gap = "            alice.transport.release(bob.transport)\n            alice.transport.hold(bob.transport)"
        self.assertEqual(["release-then-hold"], rules(scenario(gap)))
        kept = "            alice.transport.release(bob.transport, keepHolding = true)\n            alice.transport.hold(bob.transport)"
        self.assertEqual([], rules(scenario(kept)))
        # A wait between them widens the gap rather than closing it.
        for between in ("            lab.await(1) { bob.rows().size }\n", "            assertEquals(1, released.size)\n"):
            with self.subTest(between=between):
                src = "            alice.transport.release(bob.transport)\n" + between + "            alice.transport.hold(bob.transport)"
                self.assertEqual(["release-then-hold"], rules(scenario(src)))
        loop = (
            "            listOf(1, 2).forEach { lag ->\n"
            "                alice.transport.hold(bob.transport)\n"
            "                assertTrue(alice.sendDm(bob, \"x\"))\n"
            "                alice.transport.release(bob.transport)\n"
            "            }"
        )
        self.assertEqual(["release-then-hold"], rules(scenario(loop)))
        for head in ("for ((n, m) in pairs)", "while (bob.rows().isEmpty())", "repeat(count())"):
            with self.subTest(head=head):
                nested = loop.replace("listOf(1, 2).forEach { lag ->", head + " {")
                self.assertEqual(["release-then-hold"], rules(scenario(nested)))
        other_pipe = "            alice.transport.release(bob.transport)\n            alice.transport.hold(carol.transport)"
        self.assertEqual([], rules(scenario(other_pipe)))
        files = "            alice.transport.releaseFiles(bob.transport)\n            alice.transport.holdFiles(bob.transport)"
        self.assertEqual(["release-then-hold"], rules(scenario(files)))

    def test_filtered_release_then_oracle(self):
        dropped = "            alice.transport.release(bob.transport) { batch -> batch.filterNot { it.isGroupFrame() } }"
        self.assertEqual(["filtered-release-then-oracle"], rules(scenario(dropped)))
        relinked = dropped + "\n            lab.unlink(alice, bob)\n            lab.link(alice, bob)"
        self.assertEqual([], rules(scenario(relinked)))
        reordered = (
            "            alice.transport.release(bob.transport) { batch ->\n"
            "                batch.filter { it.isGroupFrame() }.reversed() + batch.filterNot { it.isGroupFrame() }\n"
            "            }"
        )
        self.assertEqual([], rules(scenario(reordered)))
        sorted_only = "            alice.transport.release(bob.transport) { it.sortedBy { f -> f.isGroupFrame() } }"
        self.assertEqual([], rules(scenario(sorted_only)))
        partitioned = (
            "            alice.transport.release(bob.transport) { batch ->\n"
            "                val (dms, rest) = batch.partition { it.isDm() }\n"
            "                rest + dms.sortedBy { it.sentAt() }\n"
            "            }"
        )
        self.assertEqual([], rules(scenario(partitioned)))
        swapped = "            alice.transport.release(bob.transport) { batch ->\n                val (a, b) = batch.partition { it.isDm() }\n                b + a\n            }"
        self.assertEqual([], rules(scenario(swapped)))
        self.assertEqual([], rules(scenario("            alice.transport.release(bob.transport) { it }")))
        drops = [
            "{ it.take(1) }",
            "{ batch -> batch.drop(1) }",
            "{ listOf(it.first()) }",
            "{ batch -> batch - batch.last() }",
            "{ it.filterIndexed { i, _ -> i > 0 } }",
            "{ b -> b.filter { it.isGroupFrame() }.take(1) + b.filterNot { it.isGroupFrame() } }",
            "{ batch -> keepOnlyTheSeed(batch) }",
            "{ batch ->\n                val (dms, rest) = batch.partition { it.isDm() }\n                dms\n            }",
        ]
        for lam in drops:
            with self.subTest(lam=lam):
                self.assertEqual(["filtered-release-then-oracle"], rules(scenario(f"            alice.transport.release(bob.transport) {lam}")))

    def test_direct_disconnect(self):
        self.assertEqual(["direct-disconnect"], rules(scenario("            alice.transport.disconnect(bob.transport)")))
        self.assertEqual([], rules(scenario("            bob.transport.connect(carol.transport, lossy = { it.hops > 0 })")))

    def test_virtual_time(self):
        src = scenario("            advanceTimeBy(1_000)").replace("runBlocking", "runTest")
        self.assertEqual(["virtual-time", "virtual-time"], rules(src))

    def test_clock_jump_over_48h(self):
        self.assertEqual([], rules(scenario("            lab.clock.advance(24 * 60 * 60_000L)\n            lab.clock.advance(23 * HOUR)", HOUR_CONST)))
        over = scenario("            lab.clock.advance(24 * 60 * 60_000L)\n            lab.clock.advance(25 * HOUR)\n            alice.heal()", HOUR_CONST)
        self.assertEqual(["clock-jump-over-48h"], rules(over))
        no_heal = scenario("            lab.clock.advance(7 * 24 * HOUR)", HOUR_CONST)
        self.assertEqual([], rules(no_heal))
        for sweep in ("bob.restart()", "bob.restoreFromBackup()"):
            with self.subTest(sweep=sweep):
                src = scenario(f"            lab.clock.advance(49 * HOUR)\n            {sweep}", HOUR_CONST)
                self.assertEqual(["clock-jump-over-48h"], rules(src))
        unsized = scenario("            lab.clock.advance(somethingUnknown())\n            alice.heal()")
        self.assertEqual(["clock-jump-over-48h"], rules(unsized))
        helper = HOUR_CONST + "\n    private suspend fun healAll() {\n        alice.heal()\n    }\n"
        through = scenario("            lab.clock.advance(49 * HOUR)\n            healAll()", helper)
        self.assertEqual(["clock-jump-over-48h"], rules(through))
        jump_helper = HOUR_CONST + "\n    private fun jumpTwoDays() {\n        lab.clock.advance(49 * HOUR)\n    }\n"
        jumped = scenario("            jumpTwoDays()\n            alice.heal()", jump_helper)
        self.assertEqual(["clock-jump-over-48h"], rules(jumped))
        unknown = scenario("            lab.clock.advance(somethingUnknown())")
        self.assertEqual([], rules(unknown))

    def test_relay_count_sync_only_inside_a_wait(self):
        wait = "            lab.await(before + 1) { bob.metrics.frames().framesRelayed.toInt() }"
        self.assertEqual(["relay-count-sync"], rules(scenario(wait)))
        outcome = '            assertEquals("no relay cancelled", 0L, bob.metrics.frames().framesSuppressed)'
        self.assertEqual([], rules(scenario(outcome)))

    def test_ignore_without_issue(self):
        flagged = scenario("").replace("    @Test\n", '    @Ignore("flaky")\n    @Test\n')
        self.assertEqual(["ignore-without-issue"], rules(flagged))
        parked = scenario("").replace("    @Test\n", '    @Ignore("#96: a live-link tick lost with its link")\n    @Test\n')
        self.assertEqual([], rules(parked))

    def test_missing_oracle_follows_helpers_in_the_file(self):
        no_oracle = scenario("").replace("            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(bob) }\n", "")
        self.assertEqual(["missing-oracle"], rules(no_oracle))
        helper = """
    private suspend fun converge() {
        lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(bob) }
    }
"""
        through = no_oracle.replace("        runBlocking {\n", "        runBlocking {\n            converge()\n").replace("\n}\n", helper + "}\n")
        self.assertEqual([], rules(through))
        allowed = no_oracle.replace("    @Test\n", "    // lab-lint: allow missing-oracle — the subject is a label\n    @Test\n")
        self.assertEqual([], rules(allowed))

    def test_missing_chaos_rule(self):
        src = scenario("").replace("    @get:Rule\n    val chaos = LabChaos.rule()\n", "")
        self.assertEqual(["missing-chaos-rule"], rules(src))
        self.assertEqual([], rules(src, name="MeshLab.kt"))


HOUR_CONST = "\n    private companion object {\n        const val HOUR = 3_600_000L\n    }\n"


class EvaluateTest(unittest.TestCase):
    def test_literals_and_known_constants(self):
        self.assertEqual(60_000, lint.evaluate("60_000L"))
        self.assertEqual(48 * 3_600_000, lint.evaluate("48 * 60 * 60_000L"))
        self.assertIsNone(lint.evaluate("someCall()"))


class HookTest(unittest.TestCase):
    """The PostToolUse entry point, run in-process against a temporary lab directory."""

    def run_hook(self, lab: Path, file_path: Path) -> tuple[int, str]:
        saved = lint.LAB, sys.stdin, sys.stderr
        lint.LAB = lab
        sys.stdin = io.StringIO(json.dumps({"tool_name": "Edit", "tool_input": {"file_path": str(file_path)}}))
        sys.stderr = io.StringIO()
        try:
            code = lint.hook()
            return code, sys.stderr.getvalue()
        finally:
            lint.LAB, sys.stdin, sys.stderr = saved

    def test_a_file_outside_the_lab_passes_silently(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual((0, ""), self.run_hook(Path(tmp), Path(__file__)))

    def test_a_clean_scenario_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            clean = Path(tmp) / "CleanLabTest.kt"
            clean.write_text(scenario("            assertTrue(alice.sendDm(bob, \"hi\"))"), encoding="utf-8")
            self.assertEqual((0, ""), self.run_hook(Path(tmp), clean))

    def test_a_flawed_scenario_hands_its_findings_back(self):
        with tempfile.TemporaryDirectory() as tmp:
            flawed = Path(tmp) / "FlawedLabTest.kt"
            flawed.write_text(scenario("            delay(200)"), encoding="utf-8")
            code, err = self.run_hook(Path(tmp), flawed)
        self.assertEqual(2, code)
        self.assertIn("lab-lint/sleep-as-sync", err)


if __name__ == "__main__":
    unittest.main()
