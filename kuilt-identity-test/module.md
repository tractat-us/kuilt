# Module kuilt-identity-test

**Fake device keys, so room tests run on a clock the test controls.**

A room test usually runs on virtual time: a heartbeat that is due in five seconds fires when the test
says five seconds have passed, and not before. Real device keys break that in the browser, where
every signature is a WebCrypto promise. While a test waits on one, the test's clock and the room's
timers stop agreeing, and a test that passes everywhere else fails there.

This module swaps the real signatures for fake ones that are computed on the spot. A test builds
each device's store with `fakeKeyStore(n)`, a different number for each device, and a room built on
that store signs and checks with the fake. A room built on a real store never does. The number fixes
the device's key, so a test gets the same keys in every run and a failure replays the same way.

## What the fake still refuses

Anyone can compute a fake signature: it is SHA-512 of a fixed tag, the key and the message. So it
proves nothing about who signed. It still says no wherever a real signature would for the reasons
room tests check: a signature under another key, over another message, or with any byte changed. A
test asserting that a room turns away a forged or replayed proof stays a real test.

Real P-256 is not tested here. `:kuilt-identity`'s own tests, its signature vectors and the WebKit
nightly cover it.

## Underneath

`FakeKeyScheme` implements `:kuilt-identity`'s `KeyScheme`, the seam a room signs, checks and reads
keys through. A `DeviceKeyStore` answers its scheme as `keyScheme`; every production store answers
`KeyScheme.P256`, and `fakeKeyStore(n)` answers the fake, with `fakeDeviceKey(n)` as its key. The scheme
travels with the store, never with a key: a key read off the wire is checked in the scheme of the room
that reads it, so a room on the real scheme treats a fake signature as a bad one.

Both are behind `@TestOnlyKeyScheme`, an opt-in at level `ERROR`, so using them means writing
`@OptIn(TestOnlyKeyScheme::class)` at the use site. Opt in from test source sets, and from
test-support modules whose published code builds rooms for tests, such as a room conformance suite.
Never opt in from production code. The opt-in is the fence: nothing in the build stops a production
module from depending on this one.
