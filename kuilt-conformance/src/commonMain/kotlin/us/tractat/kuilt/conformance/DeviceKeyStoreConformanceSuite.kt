package us.tractat.kuilt.conformance

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import us.tractat.kuilt.identity.DeviceKey
import us.tractat.kuilt.identity.DeviceKeyStore
import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.identity.Grant
import us.tractat.kuilt.identity.GrantScope
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * Reusable contract test suite for [DeviceKeyStore] implementations.
 *
 * A device is recognised when it comes back only if it comes back with the same key, so a store has
 * one job that matters: hand back the same key, made once, for as long as it promises to keep it —
 * and when what it kept is damaged, hand back a *new* key rather than a crash. Every [Test] here is a
 * required invariant of that contract.
 *
 * Subclass and implement [newFixture]. Lives in `commonMain` of `:kuilt-conformance`, beside
 * [DurableStoreConformanceSuite], so every store — the in-memory one, each platform store in
 * `:kuilt-identity`, and a store a consumer writes against the public interface — subclasses it from
 * its own test source set on whichever targets it exists for.
 *
 * ```kotlin
 * class SecureEnclaveDeviceKeyStoreConformanceTest : DeviceKeyStoreConformanceSuite() {
 *     override suspend fun newFixture(): DeviceKeyStoreFixture {
 *         val place = freshKeychainService()
 *         return DeviceKeyStoreFixture.Durable(
 *             open = { beforeKeyIsMade -> SecureEnclaveDeviceKeyStore(place, beforeKeyIsMade) },
 *             corruptKey = { place.overwriteKeyItem(byteArrayOf(1, 2, 3)) },
 *             // …one hook per failure the store must survive
 *         )
 *     }
 * }
 * ```
 *
 * ## What the suite rests on, and checks
 *
 * - **[newFixture] hands back an unshared medium.** [distinctFixturesAreDistinctDevices] checks it.
 * - **A reopen crosses a handle boundary.** The [DeviceKeyStoreFixture.Durable] arm asserts the
 *   reopened store and the key it returns are different objects, and that the reopened store made no
 *   key ([KeyGate.made] is zero), so a reopen that returned a cached store, or quietly made a fresh
 *   key, is a red rather than a pass.
 * - **Every damage hook really damages.** Each damage property first runs a control reopen that keeps
 *   the key, so a "new key after damage" verdict cannot come from a store that never kept one.
 *
 * ## What it deliberately does not require
 *
 * - **Two stores over one medium at once.** Two processes opening the same directory are not part of
 *   the contract; a store may say in its own KDoc that one medium belongs to one process.
 * - **That a store drops grants when it replaces a key.** A grant names the old key, so it is useless
 *   with the new one and harmless to keep; whether to tidy it away is the store's choice.
 * - **That a replacement is reported.** A store should log it, and a platform store's own tests can
 *   assert its log line, but a log line is a platform backend's business and not reachable here.
 *
 * ## What it cannot detect
 *
 * That a durable store's bytes reached stable storage rather than the operating system's cache. A
 * reopen in one process reads through the same page cache, so dropping an `fsync` reds nothing here —
 * the residual [DurableStoreConformanceSuite] states for the same reason. And how well the key is
 * *kept* — file permissions, a Keychain accessibility class, a non-extractable browser key — is each
 * platform store's own test, since the contract cannot see where a key lives.
 */
public abstract class DeviceKeyStoreConformanceSuite {

    /**
     * Returns a **fresh medium** for one test: a new temp directory, a new Keychain service name, a new
     * IndexedDB database — sharing nothing with any fixture an earlier call returned.
     *
     * The fixture's arm is a decision the subclass states once; see [DeviceKeyStoreFixture] for why it
     * is two-armed and not a nullable hook.
     */
    protected abstract suspend fun newFixture(): DeviceKeyStoreFixture

    // ── The key ──────────────────────────────────────────────────────────────

    /**
     * The first call makes a key and every call after returns it, and the key signs under its own name.
     *
     * The gate count is what makes "made once" observable at all: comparing ids would pass for a store
     * that made a key on every call and happened to keep the first.
     */
    @Test
    public fun aKeyIsMadeOnceAndSignsUnderItsOwnName(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val gate = KeyGate()
        val store = newFixture().open(gate::pass)

        val first = store.deviceKey()
        val second = store.deviceKey()
        val signature = second.sign(MESSAGE)
        val verifies = first.id.verify(MESSAGE, signature)

        assertAll(
            { assertEquals(1, gate.made, "one key is made, on the first call, and none after") },
            { assertEquals(first.id, second.id, "every call names the same key") },
            { assertTrue(verifies, "the stored key signs under its own name") },
        )
    }

    /**
     * Concurrent first calls agree on one key, and a store makes it once.
     *
     * The interleaving is forced rather than hoped for: the gate yields while the first key is being
     * made, so every one of the [CALLERS] callers has entered [DeviceKeyStore.deviceKey] before the key
     * exists, and the test asserts that they had. An unguarded store makes [CALLERS] keys here, every
     * time. The knob: at one caller this property switches off entirely; any count above one switches
     * it on, and fifty keeps the miss loud.
     */
    @Test
    public fun concurrentFirstCallsAgreeOnOneKey(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val fixture = newFixture()
        var callersInside = 0
        var callersInsideWhileMaking = -1
        val gate = KeyGate {
            yield() // let every other caller reach the store while this key is still being made
            callersInsideWhileMaking = callersInside
        }
        val store = fixture.open(gate::pass)

        val ids = List(CALLERS) {
            async {
                callersInside++
                store.deviceKey().id
            }
        }.awaitAll()
        val later = store.deviceKey().id

        assertAll(
            { assertEquals(CALLERS, callersInsideWhileMaking, "precondition: all $CALLERS calls were in flight while the key was made") },
            { assertEquals(1, gate.made, "one key made for $CALLERS concurrent first calls") },
            { assertEquals(setOf(later), ids.toSet(), "every call, concurrent or later, names the same key") },
        )
        if (fixture is DeviceKeyStoreFixture.Durable) {
            assertEquals(later, fixture.open { }.deviceKey().id, "and the one key agreed on is the one kept")
        }
    }

    /**
     * The fixture precondition the rest of this file rests on: two fixtures are two devices.
     *
     * Against a subclass whose fixtures shared one medium, an earlier test's key would answer a later
     * test's first call, and every "a new key is made" assertion below would be reporting on leftovers.
     */
    @Test
    public fun distinctFixturesAreDistinctDevices(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val one = newFixture().open { }.deviceKey().id
        val other = newFixture().open { }.deviceKey().id
        assertNotEquals(one, other, "two fresh fixtures must not share a key")
    }

    // ── Restart ──────────────────────────────────────────────────────────────

    /**
     * What a restart does to the key: the [DeviceKeyStoreFixture.Durable] arm keeps it, the
     * [DeviceKeyStoreFixture.KeepsNothing] arm forgets it. The wrong arm is a red in both directions.
     *
     * On the durable arm the reopened key must also **sign** under the name. A store that kept the name
     * and lost the private half would answer [DeviceKey.id] correctly and fail every proof it was asked
     * for, and comparing ids alone cannot see that.
     */
    @Test
    public fun aRestartKeepsTheKeyOrForgetsIt(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        when (val fixture = newFixture()) {
            is DeviceKeyStoreFixture.KeepsNothing -> assertForgets(fixture)
            is DeviceKeyStoreFixture.Durable -> {
                val store = fixture.open { }
                val key = store.deviceKey()
                val gate = KeyGate()
                val reopened = fixture.open(gate::pass)
                val reopenedKey = reopened.deviceKey()
                val verifies = key.id.verify(MESSAGE, reopenedKey.sign(MESSAGE))
                assertAll(
                    { assertNotSame(store, reopened, "precondition: a reopen is a new store, not the old one") },
                    { assertNotSame(key, reopenedKey, "precondition: the reopened key was read back, not shared") },
                    { assertEquals(0, gate.made, "a reopen reads the kept key; it makes none") },
                    { assertEquals(key.id, reopenedKey.id, "the same device after a restart") },
                    { assertTrue(verifies, "and the reopened key still signs under that name") },
                )
            }
        }
    }

    /** What a restart does to the grants: kept by the durable arm, forgotten by the other. */
    @Test
    public fun grantsSurviveARestartOrAreForgotten(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val fixture = newFixture()
        val store = fixture.open { }
        val me = store.deviceKey().id
        val host = DeviceKey.generate()
        val roomA = grant(host, me, T, GrantScope.Room("A"))
        val anyRoom = grant(host, me, T + 1_000, GrantScope.AnyRoomOfIssuer)
        store.keepGrant(roomA)
        store.keepGrant(anyRoom)
        val before = store.grants().toSet()

        val after = fixture.open { }.grants().toSet()

        assertAll(
            { assertEquals(setOf(roomA, anyRoom), before, "precondition: both grants are held before the restart") },
            {
                when (fixture) {
                    is DeviceKeyStoreFixture.Durable -> assertEquals(before, after, "a restart keeps every grant")
                    is DeviceKeyStoreFixture.KeepsNothing -> assertEquals(emptySet<Grant>(), after, "a restart forgets every grant")
                }
            },
        )
    }

    // ── Grants ───────────────────────────────────────────────────────────────

    /**
     * The one rule every store applies: the newest grant per issuer **and** scope, where a tie keeps
     * the held one.
     *
     * The tie is its own knob: two grants issued at the same instant are the one case where "newer"
     * and "not older" disagree, so a store comparing with `>=` passes every other line here and fails
     * that one. On the durable arm the rule is read again after a restart, which is what catches a
     * store whose cache applies the rule while its medium appends every grant it was given.
     */
    @Test
    public fun keepsTheNewestGrantPerIssuerAndScope(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val fixture = newFixture()
        val store = fixture.open { }
        val me = store.deviceKey().id
        val host = DeviceKey.generate()
        val other = DeviceKey.generate()
        val middle = grant(host, me, T + 1_000)
        val older = grant(host, me, T)
        val sameInstant = grant(host, me, T + 1_000)
        val roomA = grant(host, me, T, GrantScope.Room("A")) // older, but another scope
        val fromOther = grant(other, me, T) // older, but another issuer
        val newer = grant(host, me, T + 2_000)

        store.keepGrant(middle)
        store.keepGrant(older)
        store.keepGrant(sameInstant)
        val afterIgnored = store.grants()
        store.keepGrant(roomA)
        store.keepGrant(fromOther)
        store.keepGrant(newer)
        val kept = store.grants()
        val expected = setOf(newer, roomA, fromOther)
        // Read as a list, not a set, on both sides: a medium that appended a duplicate would collapse
        // back into the expected set and pass.
        val keptAcrossRestart = (fixture as? DeviceKeyStoreFixture.Durable)?.open { }?.grants()

        assertAll(
            { assertNotEquals(middle, sameInstant, "precondition: the tie is two different grants") },
            { assertEquals(listOf(middle), afterIgnored, "an older grant, and one issued at the same instant, are ignored") },
            { assertEquals(expected, kept.toSet(), "a newer grant replaces the held one; another scope or issuer sits beside it") },
            { assertEquals(expected.size, kept.size, "each exactly once") },
            {
                if (keptAcrossRestart != null) {
                    assertEquals(expected, keptAcrossRestart.toSet(), "and the kept medium holds what the rule kept")
                    assertEquals(expected.size, keptAcrossRestart.size, "each exactly once")
                }
            },
        )
    }

    // ── Damage: what a durable medium can suffer and the in-memory reference cannot ───────────

    /**
     * A key the store cannot read back — a truncated file, garbage in a Keychain item — is replaced
     * with a new key, and the app keeps working as a new device: no throw, and no prompt.
     */
    @Test
    public fun aCorruptKeyIsReplacedNotFatal(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        when (val fixture = newFixture()) {
            is DeviceKeyStoreFixture.KeepsNothing -> assertForgets(fixture)
            is DeviceKeyStoreFixture.Durable -> assertReplacedAfter(fixture, "corrupted") { fixture.corruptKey() }
        }
    }

    /**
     * A key whose private half does not sign for the name it is stored under is refused like a corrupt
     * one. This is the self-test's property: without it a store reading such a pair would hand out a
     * name it cannot prove, and the device would fail every admission while looking healthy.
     */
    @Test
    public fun aKeyThatFailsItsSelfTestIsReplaced(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        when (val fixture = newFixture()) {
            is DeviceKeyStoreFixture.KeepsNothing -> assertForgets(fixture)
            is DeviceKeyStoreFixture.Durable -> {
                var claimed: DidKey? = null
                val replacement = assertReplacedAfter(fixture, "replaced by a mismatched pair") {
                    claimed = fixture.plantMismatchedKey()
                }
                assertNotEquals(claimed, replacement, "the store must not adopt the name the mismatched key claims")
            }
        }
    }

    /** A cleared key — a deleted file, a removed Keychain item — starts the device afresh, without a throw. */
    @Test
    public fun aClearedKeyStartsAsANewDevice(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        when (val fixture = newFixture()) {
            is DeviceKeyStoreFixture.KeepsNothing -> assertForgets(fixture)
            is DeviceKeyStoreFixture.Durable -> assertReplacedAfter(fixture, "cleared") { fixture.clearKey() }
        }
    }

    /**
     * A crash part-way through writing a key leaves the store readable: the key held before the write
     * is still the key, and a later write over the crash's leftovers succeeds.
     *
     * Two phases, because a store writes a key in two situations. Over a held key (replacing a corrupt
     * one), a crash must leave the held key in force. Over no key (a first launch), a crash must leave
     * a medium the next launch can make a key in.
     */
    @Test
    public fun anInterruptedKeyWriteLeavesTheStoreReadable(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        when (val fixture = newFixture()) {
            is DeviceKeyStoreFixture.KeepsNothing -> assertForgets(fixture)
            is DeviceKeyStoreFixture.Durable -> {
                val held = fixture.open { }.deviceKey().id
                fixture.interruptKeyWrite()
                val overHeld = KeyGate()
                val afterCrashOverHeld = fixture.open(overHeld::pass).deviceKey().id

                fixture.clearKey()
                fixture.interruptKeyWrite()
                val overNothing = KeyGate()
                val fresh = fixture.open(overNothing::pass).deviceKey().id
                val freshAgain = fixture.open { }.deviceKey().id

                assertAll(
                    { assertEquals(held, afterCrashOverHeld, "a crash while writing over a held key leaves the held key") },
                    { assertEquals(0, overHeld.made, "and makes no new one") },
                    { assertEquals(1, overNothing.made, "over no key, the next launch makes one") },
                    { assertNotEquals(held, fresh, "precondition: the cleared key really was gone") },
                    { assertEquals(fresh, freshAgain, "and that key is kept, despite the crash's leftovers") },
                )
            }
        }
    }

    /**
     * Grants the store cannot read back are dropped, not fatal: the key is untouched, the device holds
     * no grants, and the next grant it is given is kept. A grant is not a secret and a lost one costs
     * only an ordinary admission next time, so dropping them is the right failure.
     */
    @Test
    public fun corruptGrantsAreDroppedNotFatal(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        when (val fixture = newFixture()) {
            is DeviceKeyStoreFixture.KeepsNothing -> assertForgets(fixture)
            is DeviceKeyStoreFixture.Durable -> {
                val store = fixture.open { }
                val me = store.deviceKey().id
                val host = DeviceKey.generate()
                store.keepGrant(grant(host, me, T))
                val control = fixture.open { }.grants().size

                fixture.corruptGrants()
                val reopened = fixture.open { }
                val keyAfter = reopened.deviceKey().id
                val grantsAfter = reopened.grants()
                val next = grant(host, me, T + 1_000)
                reopened.keepGrant(next)
                val keptAfterward = fixture.open { }.grants()

                assertAll(
                    { assertEquals(1, control, "precondition: the grant was kept before the damage") },
                    { assertEquals(me, keyAfter, "damaged grants do not touch the key") },
                    { assertEquals(emptyList<Grant>(), grantsAfter, "unreadable grants are dropped") },
                    { assertEquals(listOf(next), keptAfterward, "and the next grant is kept") },
                )
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * The [DeviceKeyStoreFixture.KeepsNothing] arm's promise, asserted wherever a property's subject
     * has no medium to act on: a restart is a new device holding no grants. Never an empty pass — a
     * durable store that declared this arm reds here.
     */
    private suspend fun assertForgets(fixture: DeviceKeyStoreFixture.KeepsNothing) {
        val store = fixture.open { }
        val before = store.deviceKey().id
        store.keepGrant(grant(DeviceKey.generate(), before, T))
        val restarted = fixture.open { }
        val after = restarted.deviceKey().id
        val grants = restarted.grants()
        assertAll(
            { assertNotSame(store, restarted, "precondition: a restart is a new store") },
            { assertNotEquals(before, after, "a store that keeps nothing is a new device after a restart") },
            { assertEquals(emptyList<Grant>(), grants, "holding no grants") },
        )
    }

    /**
     * Keep a key, damage it with [damage], and assert a reopen replaces it: no throw, one new key made,
     * a different name that signs, and the replacement kept. Returns the replacement's name.
     *
     * Opens with a control reopen first: if the key did not survive an undamaged reopen, "a new key
     * after damage" would prove nothing about the damage.
     */
    private suspend fun assertReplacedAfter(
        fixture: DeviceKeyStoreFixture.Durable,
        what: String,
        damage: suspend () -> Unit,
    ): DidKey {
        val original = fixture.open { }.deviceKey().id
        val control = fixture.open { }.deviceKey().id

        damage()
        val gate = KeyGate()
        val reopened = fixture.open(gate::pass)
        val replacement = reopened.deviceKey()
        val verifies = replacement.id.verify(MESSAGE, replacement.sign(MESSAGE))
        val kept = fixture.open { }.deviceKey().id

        assertAll(
            { assertEquals(original, control, "precondition: an undamaged reopen keeps the key") },
            { assertEquals(1, gate.made, "a key $what is replaced by one new key") },
            { assertNotEquals(original, replacement.id, "the device starts afresh under a new name") },
            { assertTrue(verifies, "and the new key signs under it") },
            { assertEquals(replacement.id, kept, "the replacement is the key kept from then on") },
        )
        return replacement.id
    }

    private suspend fun grant(
        issuer: DeviceKey,
        subject: DidKey,
        issuedAt: Long,
        scope: GrantScope = GrantScope.AnyRoomOfIssuer,
    ): Grant = Grant.issue(issuer, subject, scope, issuedAt, issuedAt + HOUR)

    /**
     * Counts the keys a store makes, through the `beforeKeyIsMade` hook every store calls each time it
     * is about to make one, and runs [during] inside it.
     */
    private class KeyGate(private val during: suspend () -> Unit = {}) {
        var made: Int = 0
            private set

        suspend fun pass() {
            made++
            during()
        }
    }

    private companion object {
        const val CALLERS = 50
        const val T = 1_700_000_000_000L
        const val HOUR = 3_600_000L
        val MESSAGE = "signed by the stored key".encodeToByteArray()
    }
}

/**
 * How a [DeviceKeyStoreConformanceSuite] reaches the store under test, and what its medium can suffer.
 *
 * Every arm opens a store through `open(beforeKeyIsMade)`. The store must call `beforeKeyIsMade` each
 * time it is about to make a key — the first one, and every replacement — and at no other time. It is
 * how the suite counts the keys a store makes and forces concurrent first calls to overlap, so a store
 * takes it through an internal constructor or factory, and its public entry points pass a no-op.
 *
 * ## Why this is two-armed and not nullable
 *
 * [InMemoryDeviceKeyStore][us.tractat.kuilt.identity.InMemoryDeviceKeyStore] has no medium: it cannot
 * survive a restart, and so cannot suffer a corrupt key, a mismatched pair, a cleared item or a crash
 * between writes. That is correct rather than a gap, and its own KDoc says so. Nullable damage hooks
 * would express it, and would let a durable store whose damage handling was broken opt out the same
 * way, so the suite would report nothing. A sealed pair makes the medium a decision the subclass
 * states once, and makes each arm checkable: [Durable] must keep its key across a restart and survive
 * every damage hook, while [KeepsNothing] must **forget** across a restart, so a durable store that
 * declared it fails just as loudly. Every damage hook on [Durable] is non-nullable for the same reason.
 *
 * ## What the arms cannot detect
 *
 * [KeepsNothing] proves only that a restart is a new device. It proves nothing about keeping a key,
 * because there is nothing to keep. [Durable] cannot tell a genuine reopen from a thin wrapper that
 * delegates every call to the original store, though it does assert the reopened store and key are
 * new objects that made no key; and it cannot tell stable storage from a page cache.
 */
public sealed interface DeviceKeyStoreFixture {
    /** Opens a store over this fixture's medium; see [DeviceKeyStoreFixture] for `beforeKeyIsMade`. */
    public suspend fun open(beforeKeyIsMade: suspend () -> Unit): DeviceKeyStore

    /**
     * A store with no medium. Each [open] is a new process: a new device, holding no grants.
     *
     * @param start opens a new store, passing it `beforeKeyIsMade`.
     */
    public class KeepsNothing(
        private val start: suspend (beforeKeyIsMade: suspend () -> Unit) -> DeviceKeyStore,
    ) : DeviceKeyStoreFixture {
        override suspend fun open(beforeKeyIsMade: suspend () -> Unit): DeviceKeyStore = start(beforeKeyIsMade)
    }

    /**
     * A store over a medium that outlives the process. Each [open] is a restart: a new store over the
     * same medium, sharing no memory with the stores opened before it.
     *
     * Each damage hook leaves the medium as that failure would, with no store open.
     *
     * @param reopen opens a new store over this fixture's medium, passing it `beforeKeyIsMade`.
     * @param corruptKey makes the stored key unreadable: truncated, or bytes that are not a key at all.
     * @param plantMismatchedKey stores a key that decodes, but whose private half does not sign for the
     *   name it carries, and returns that name. A store that cannot represent such a pair at all (a key
     *   the platform holds as one object) still owes this property: it needs a two-armed fixture here,
     *   reasoned in its own KDoc, never a stub that plants nothing.
     * @param clearKey removes the stored key, leaving the grants.
     * @param interruptKeyWrite leaves what a crash part-way through writing a new key would leave, over
     *   whatever key is held now.
     * @param corruptGrants makes the stored grants unreadable, leaving the key.
     */
    public class Durable(
        private val reopen: suspend (beforeKeyIsMade: suspend () -> Unit) -> DeviceKeyStore,
        public val corruptKey: suspend () -> Unit,
        public val plantMismatchedKey: suspend () -> DidKey,
        public val clearKey: suspend () -> Unit,
        public val interruptKeyWrite: suspend () -> Unit,
        public val corruptGrants: suspend () -> Unit,
    ) : DeviceKeyStoreFixture {
        override suspend fun open(beforeKeyIsMade: suspend () -> Unit): DeviceKeyStore = reopen(beforeKeyIsMade)
    }
}
