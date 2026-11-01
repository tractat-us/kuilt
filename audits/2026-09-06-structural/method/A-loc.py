import re,os
root="/Users/keddie/tractatus/kuilt-worktrees/bugs"
ranges=[
("ChannelView","kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/MuxBase.kt",121,305),
("CompositeSeam","kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/composite/CompositeSeam.kt",201,1468),
("InMemorySeam","kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/InMemoryLoom.kt",193,262),
("LinkSeam","kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/fabric/LinkSeam.kt",72,200),
("MeshSeam","kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/fabric/MeshSeam.kt",699,1407),
("ResumableChannel","kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/MuxClientLoom.kt",147,250),
("RoomHubSeam","kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/RoomHubSeam.kt",90,333),
("TieredSeam","kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/TieredSeam.kt",89,296),
("ManagedSeam","kuilt-cluster/src/commonMain/kotlin/us/tractat/kuilt/cluster/ManagedSeam.kt",71,232),
("GossipSeam","kuilt-gossip/src/commonMain/kotlin/us/tractat/kuilt/gossip/GossipSeam.kt",105,435),
("MCSessionLink","kuilt-multipeer/src/appleMain/kotlin/us/tractat/kuilt/multipeer/internal/MCSessionLink.kt",78,523),
("BridgePeerLink","kuilt-multipeer/src/jvmMain/kotlin/us/tractat/kuilt/multipeer/internal/BridgePeerLink.kt",61,401),
("NearbySeam","kuilt-nearby/src/commonMain/kotlin/us/tractat/kuilt/nearby/NearbySeam.kt",84,461),
("NwSeam","kuilt-nw/src/commonMain/kotlin/us/tractat/kuilt/nw/NwSeam.kt",251,2437),
("TokenGatedSeam","kuilt-otel-tap/src/commonMain/kotlin/us/tractat/kuilt/otel/tap/admit/TokenGatedSeam.kt",58,235),
("WebRTCPeerLink","kuilt-webrtc/src/wasmJsMain/kotlin/us/tractat/kuilt/webrtc/internal/WebRTCPeerLink.kt",52,244),
("FakeSeam","kuilt-test/src/commonMain/kotlin/us/tractat/kuilt/test/FakeSeam.kt",62,282),
("FaultySeam","kuilt-test/src/commonMain/kotlin/us/tractat/kuilt/test/FaultySeam.kt",50,228),
("FlakyLifecycleSeam","kuilt-test/src/commonMain/kotlin/us/tractat/kuilt/test/FlakyLifecycleSeam.kt",72,294),
("RoomChannelSeam","kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/RoomChannel.kt",156,235),
("DelayedWovenSeam","kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/DelayedWovenLoom.kt",106,164),
("ControllableSeam","kuilt-test/src/commonMain/kotlin/us/tractat/kuilt/test/ControllableLoom.kt",188,259),
("MeteredSeam","kuilt-scale/src/main/kotlin/us/tractat/kuilt/scale/MeteredSeam.kt",23,63),
("PeerlessSeam","kuilt-cluster/src/jvmAndAndroidMain/kotlin/us/tractat/kuilt/cluster/ServerCluster.kt",332,349),
]
print(f"{'class':22} {'total':>6} {'code':>6} {'cmt':>6}")
tot_code=0
for name,rel,s,e in ranges:
    lines=open(os.path.join(root,rel)).read().split("\n")[s-1:e]
    txt="\n".join(lines)
    inblk=False; code=0; cmt=0
    for l in lines:
        t=l.strip()
        if not t: continue
        if inblk:
            cmt+=1
            if "*/" in t: inblk=False
            continue
        if t.startswith("/*"):
            cmt+=1
            if "*/" not in t: inblk=True
            continue
        if t.startswith("//") or t.startswith("*"):
            cmt+=1; continue
        code+=1
    tot_code+=code
    print(f"{name:22} {e-s+1:6} {code:6} {cmt:6}")
print(f"{'TOTAL':22} {'':6} {tot_code:6}")
