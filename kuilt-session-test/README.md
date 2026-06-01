# kuilt-session-test

Test doubles for `kuilt-session` contracts. Add this as a `testImplementation` dependency so your tests stop hand-rolling `Room` implementations that break every time the interface evolves.

```kotlin
testImplementation("us.tractat.kuilt:kuilt-session-test:<version>")
```

## FakeRoom — one-liner setup

```kotlin
val room = FakeRoom()   // selfId=PeerId("self"), Host role, empty roster
room.addMember(Member(PeerId("alice"), MemberIdentity("alice", "alice"), Liveness.Connected))
room.deliver(PeerId("alice"), byteArrayOf(1, 2, 3))
val frame = room.incoming.first()
// frame.sender == PeerId("alice")
```

Lifecycle helpers: `leave(reason)` (idempotent; silences subsequent sends).

Roster helpers: `addMember(member)`, `removeMember(peerId, reason)`.

Liveness helpers: `partition(peerId, at)`, `recover(peerId, at)`.

Event helpers: `openWindow(peerId, expiresAt)`, `emitResumed(peerId)`, `hostLost(at)`, `emit(event)`.

Outgoing inspection: `room.broadcasts: List<ByteArray>`, `room.directed: List<Pair<PeerId, ByteArray>>`.

Resume: `room.resumeResult = ResumeResult.WindowClosed` — override the value returned by `resume()`.

State helpers: `setRole(role)`, `setResumeToken(token)`.

## fakeRoomPair — wired two-room scenario

```kotlin
val (host, joiner) = fakeRoomPair(PeerId("host"), PeerId("joiner"))
host.broadcast(byteArrayOf(1, 2, 3))
val frame = joiner.incoming.first()
// frame.sender == PeerId("host"), frame.payload == [1,2,3]
```

Each side's roster is seeded with the other as a `Liveness.Connected` member. `broadcast` on one side delivers a `RoomFrame` into the other's `incoming` with the correct `sender`.

## FakeRoomFactory

```kotlin
val factory = FakeRoomFactory()
val room = factory.host(Pattern("alice"))
// room.selfId == PeerId("alice"), room.role.value == SessionRole.Host
```

## Why this module

`:kuilt-test` doubles only `:kuilt-core` types. `FakeRoom` lives here — in its own module — so the dependency arrow stays clean: `kuilt-session-test` depends on `kuilt-session`; `kuilt-test` stays core-only.
