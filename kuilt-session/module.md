# Module kuilt-session

**Keep a group of people together in one room, even when their connections drop.**

Four friends open the same game. One of them walks into a lift, and her phone loses the network for
a minute. When it comes back she is still in the game, in the same seat, and nobody had to press
anything. Later she closes the app by mistake and opens it again: she is back again. This module is
the room that makes that happen.

A `Room` knows who is in it, whether each of them can still be reached, and how a new arrival is
let in. One device hosts the room; the others join it. `SeamRoomFactory` builds a room over any of
kuilt's fabrics, from a `DeviceKeyStore` that holds this device's key.

## A device that comes back

Every device proves its key when it says hello, and the host keeps a seat for each key. So a device
coming back is recognised by the key it proves, never by a name or a token it was handed:

- **Back while the host still holds its seat** (a minute, by default): it gets the same seat back,
  whether its link healed by itself or the app was closed and opened again. The app makes no call
  for a healed link, and only its ordinary `join` after a restart.
- **Back after the seat was let go, but inside its pass:** the host gave it a pass when it was let
  in, and renewed the pass while it stayed connected. On that pass it is let back in as a new
  membership without the host's `AdmissionPolicy` being asked, and everyone in the room hears a
  `MembershipEvent.Joined` carrying a `ReturningMark`, so they know it is the same device.
- **Back after the pass ran out, or after its host revoked it:** the policy decides it like any
  newcomer.

A host revokes a lost device in the `RevocationList` its `HostAdmission` holds. Every pass issued to
the device before that moment stops working, and when the device next says hello the policy decides
it like a newcomer. A seat the room still holds for it is let go only if the policy lets it in; if
the policy says no, the seat stays held until its hold runs out. A pass issued after the revocation,
which a later admission gave the device, still works. Revoking removes nobody who is in the room
now, though their pass stops being renewed, and it is not a ban: to keep a device out, the policy
refuses it.

`RoomConformanceSuite` in `:kuilt-conformance` checks each of these returns against a real room,
counting the calls the app made and the times the policy was asked.

## Underneath

The admission exchange is a signed challenge and proof. The host signs a challenge naming the room,
the joiner signs a proof under the key its hello named, and the host seats the joiner only on a
proof that checks. A pass is a `Grant` from `:kuilt-identity`, signed by the host's own key, and a
host trusts no other issuer's. The host checks a presented pass's fields before its signature, and
checks at most a few signatures for any one proof, since anyone sent a challenge can answer it.
