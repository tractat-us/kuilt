# Nearby Apple devices

Nearby iPhones and Macs find each other and connect directly — no server, and no
shared Wi-Fi network needed. Put a few devices in the same room, give them all
the same short code, and they start exchanging messages on their own.

That is the whole idea: a card game around a kitchen table, a shared drawing on
a train, two phones swapping notes on a walk. Nothing in the middle, nothing to
set up first.

The module is `kuilt-nw`, and it is the Apple fabric to reach for.

## Starting a session

One device hosts, the others join. Everybody needs the same **code** ahead of
time — shared through a QR image, a spoken word, a chat message, anything
outside this fabric.

```kotlin
// On the device that opens the session:
val seam = nwHost(Pattern(sessionName = "kitchen-game", roomKey = code), "_kuilt._tcp")

// On a device joining it, given the same code out of band:
val seam = nwJoin(NwTag("kitchen-game", peerKey = myId, roomKey = code), "_kuilt._tcp")
```

Both calls return once the first peer is reached, and both return an ordinary
[`Seam`](contract.md) — so everything above it is unchanged. `Quilter`, Raft,
`kuilt-session`, your own app code: none of them know which fabric they are
sitting on.

The second argument is a Bonjour service type, the name devices advertise
themselves under on the local network. Pick one for your app and use it
everywhere.

## The code is a password, not a label

This is the one thing to know before writing any code against this fabric.

On most fabrics a room key is just a name used to keep two sessions apart. Here
it is the **secret that encrypts the session**. It never travels over the air —
each device runs it through a key derivation function and uses the result to
secure every link — so:

- Anyone who has the code can join and read the traffic. Anyone who doesn't
  cannot connect at all.
- It is **required**. `nwHost` and `nwJoin` throw `IllegalArgumentException` on a
  `null` `roomKey`, immediately, before touching the network. There is no such
  thing as an open session here.
- Two groups in the same room using the same service type but different codes
  can never merge, because they never agree on a key.

Use a high-entropy code where you can — one generated at random and carried in a
QR code or a link. A short code somebody types is guessable offline by anyone who
captured a single handshake.

## Every device does the same thing

There is no host/joiner asymmetry underneath. On every device the fabric
advertises itself, browses for others, and dials everything it finds. Each pair
of devices therefore dials *both* ways at once; the fabric notices the two
connections are the same logical link and folds them into one, with both sides
picking the same survivor.

The result is a full mesh: every device holds a direct link to every other one.
For lobby-sized sessions that is a handful of connections and costs nothing.

If you want a lobby view — a list of who is nearby before anyone commits — build
the loom directly and read its discovery roster:

```kotlin
val loom = appleNwLoom(serviceType = "_kuilt._tcp", roomKey = code)
loom.visiblePeers.collect { nearby -> render(nearby) }
```

`appleNwLoom` is also the drop-in replacement for the older
`MultipeerPeerLinkFactory`, and `NwRoomHost` for `MultipeerRoomHost`.

## What your app has to declare

iOS and macOS gate local-network and Bonjour access behind two `Info.plist`
keys, which the *app* supplies — a library cannot set them for you:

- `NSLocalNetworkUsageDescription` — the reason string shown in the iOS Local
  Network permission prompt.
- `NSBonjourServices` — every service type you advertise or browse, e.g.
  `<string>_kuilt._tcp</string>`.

Without them the system blocks discovery silently, and you will see nothing at
all rather than an error.

## Running it from a Mac desktop app

The fabric is not phones-only. A **macOS JVM** can host and join through the same
`nwHost`/`nwJoin` calls: on the JVM they bridge to a bundled native library
(`libkuilt.dylib`) that drives the real Network.framework binding underneath.

This works on Apple-silicon macOS and nowhere else. On any other JVM — Linux,
Windows, Intel Macs — the library does not load, `availability()` reports the
fabric unavailable, and the calls fail fast with an actionable message. Use the
mDNS or WebSocket fabrics for cross-platform LAN there. Probe
`NwNativeLib.jvmAvailability()` first if you want to branch gracefully.

## When two devices see each other but never connect

Both phones are in the lobby, the signal is strong, and no session ever forms.
Nothing crashes and nothing is logged as an error, because from each device's
point of view it is simply still trying.

Start with the `nw.loom.formation-stuck` log line. A device that can see somebody
and has not connected writes one `WARN` line carrying its whole formation state:
what it is advertising, which peers it can see, which it has stopped dialling and
why, which it is still dialling and how hard, and every live link. It fires
partway to the connection timeout, again on the timeout, and then on a widening
schedule for as long as the session stays unformed — so a wedged device writes a
trail and an idle one with nobody around writes nothing.

`NwLoom.dumpFormationState()` returns the same text on demand, which is useful
from your own "still connecting…" timeout or a crash report.

Read the trail on **both** devices, not one. The rest of the log vocabulary, and
what each line settles, is in `kuilt-nw`'s module reference.

## What it replaces

`kuilt-multipeer` wraps Apple's Multipeer Connectivity. Apple has deprecated
that framework, and its peer-to-peer data path regressed on recent iOS releases
in exactly the case it existed for — two phones with no Wi-Fi router between
them. `kuilt-nw` supersedes it; prefer it for new code.

Both ride the same radio, so this removes one known, fixable failure mode rather
than promising a bulletproof link. Range is short and unspecified, and a device
can still fail to reach a peer for reasons no library can route around. Design
for a session that sometimes does not form.

→ [Connections](fabrics.md) · [The contract](contract.md)
