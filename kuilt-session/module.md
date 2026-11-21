# Module kuilt-session

**Keep a group of people in one room, and let someone back in when their connection drops.**

Two people are sketching on a shared board. One is on a phone, and the phone closes the app to save
battery. Ten minutes later they open it again, and the other person sees "Ada is back", not a
stranger arriving. Nobody pressed anything, and nobody was asked anything.

That is this module's job. A `Room` is the people in a session: who is here, who has gone quiet,
and who has really left. It lets a newcomer in through a short handshake, and it lets a device that
dropped out come back to its own seat.

## Coming back is automatic

Every device makes itself a key the first time it runs, and keeps it. While the device is in the
room the host keeps handing it a fresh signed pass: "this key may come back until this time".
Coming back is saying hello again and proving you hold the key. There are three ways back, and the
room picks one:

| Away for… | What happens |
|---|---|
| a moment, while the host still holds the seat (a minute by default) | the same membership comes back, whether the link healed or the app was killed and reopened |
| less than its pass lasts (an hour since last contact, by default) | let back in without asking anyone, and announced as returning |
| longer | let in the way anyone new is |

The app's part is one parameter: the device's key store, on the factory that builds its rooms. It
saves no token and calls no resume.

<!-- verbatim from kuilt-session/src/commonSamples/kotlin/us/tractat/kuilt/session/AgentCookbookSamples.kt#returnIsAutomaticSample -->
```kotlin
public suspend fun returnIsAutomaticSample(
    loom: Loom,
    scope: CoroutineScope,
    clock: () -> Instant,
    keyStore: DeviceKeyStore,
    tag: Tag,
): Room {
    // One store per device, opened once at launch and shared by every room: it IS the device. On a
    // phone or a browser that is DeviceKeyStore.platform("my-app"); a fresh InMemoryDeviceKeyStore()
    // every launch makes this a new device every launch, and nothing comes back.
    val factory = SeamRoomFactory(loom = loom, scope = scope, clock = clock, deviceKeyStore = keyStore)
    // The first join and the join after an app kill are the same call. While the host still holds
    // this device's seat, it hands that seat back, whatever peer id the fabric gives us this time.
    return factory.join(tag, memberName = "Ada")
}
```

The key store is a required parameter on purpose. A quiet default would either write to the
Keychain from every test or quietly turn recognition off, and both are worse than one line.
`DeviceKeyStore.platform(appId, …)` from `:kuilt-identity` is that line on each platform, and
`InMemoryDeviceKeyStore()` is how a test, or an app that has chosen not to be recognised, opts out.

## Who decides

The host does, and only for newcomers. A host is built with a `HostAdmission`: the app's
`AdmissionPolicy`, which answers each newcomer with a pass or a refusal, and the host's
`RevocationList`. A device whose seat is held, or that returns inside its pass, never reaches the
policy. A friend can be given a pass that never runs out and works in any room this host runs; a
stranger gets the room's default.

Revoking a device voids the passes it already holds and the seat held for it, so its next return
goes to the policy as a newcomer. It is not a ban, and it does not remove a device that is in the
room now; an app keeps its own blocked set for that.

A pass is good only with the host that signed it. After the host changes — a lobby that elected
someone else, a cluster client that moved to another server — a returning device is let in the
way anyone new is.

## Underneath

The admission handshake is four frames: `Hello`, then the host's signed `Challenge`, the joiner's
signed `Proof`, and a `Welcome` or a `Reject`. The challenge binds the host's key, the room, both
peer ids and a nonce from each side, so a proof answers exactly one challenge and cannot be replayed
into another. The host keeps, for each member, its key, and on every verified proof looks the key up
against the seats it holds:

- the same peer id with the same key restores that seat, as `MembershipEvent.Resumed`;
- a new peer id with a key whose seat has gone quiet takes that seat over: the old member leaves with
  `LeaveReason.Superseded(by)`, and the new one joins in its place;
- a new peer id with a key whose seats are all still connected gets a seat of its own beside them,
  which is how two tabs of one browser are both in the room;
- the same peer id with a different key against a connected seat is refused with
  `RejectCode.KeyConflict`, since that is another device claiming a live member's id;
- otherwise, a valid pass admits the device as returning, and anything else is an ordinary admission.

`Member.key` carries every member's key, and one key can hold several seats, so nothing may assume
one member per key. On the host every member's key is verified. On a joiner the host's own key is
verified by its signed challenge, and every other member's key is the host's word.

What this protects against, and what it does not, in the design's words:

> - **Protected:** a bystander in the room — who knows every peer id, the room id and every key —
>   cannot resume as, return as, or lock out another member. A captured proof cannot be replayed.
> - **Not protected:** an active relay sitting on the victim's own link over an unauthenticated
>   fabric. Peer ids are self-chosen on WebSocket and star fabrics, so such a relay can forward a
>   challenge and its answer. Closing that needs channel binding (a TLS exporter from #3157, or
>   `kuilt-nw`'s PSK session); the proof layout reserves a field for it, empty in this slice.
> - **The key binds the handshake, not later frames.** On a `Mesh`, a link that later takes a
>   member's peer id (#2387) sends frames under that member's `Member.key`. Deployments on
>   `HostedOverlay` should use a binding `LinkAdmission` until #2387 lands.
