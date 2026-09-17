import re, sys, os
root="/Users/keddie/tractatus/kuilt-worktrees/bugs"
targets=[
("ChannelView","kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/MuxBase.kt"),
("CompositeSeam","kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/composite/CompositeSeam.kt"),
("InMemorySeam","kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/InMemoryLoom.kt"),
("LinkSeam","kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/fabric/LinkSeam.kt"),
("MeshSeam","kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/fabric/MeshSeam.kt"),
("ResumableChannel","kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/MuxClientLoom.kt"),
("RoomHubSeam","kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/RoomHubSeam.kt"),
("TieredSeam","kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/TieredSeam.kt"),
("ManagedSeam","kuilt-cluster/src/commonMain/kotlin/us/tractat/kuilt/cluster/ManagedSeam.kt"),
("PeerlessSeam","kuilt-cluster/src/jvmAndAndroidMain/kotlin/us/tractat/kuilt/cluster/ServerCluster.kt"),
("DelayedWovenSeam","kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/DelayedWovenLoom.kt"),
("GamePerPeerSeam","kuilt-game/src/commonMain/kotlin/us/tractat/kuilt/game/GameNode.kt"),
("GossipSeam","kuilt-gossip/src/commonMain/kotlin/us/tractat/kuilt/gossip/GossipSeam.kt"),
("PerPeerSeam","kuilt-gossip/src/commonMain/kotlin/us/tractat/kuilt/gossip/GossipView.kt"),
("PerPeerLivenessSeam","kuilt-heddle/src/commonMain/kotlin/us/tractat/kuilt/heddle/HeddleBootstrap.kt"),
("MCSessionLink","kuilt-multipeer/src/appleMain/kotlin/us/tractat/kuilt/multipeer/internal/MCSessionLink.kt"),
("BridgePeerLink","kuilt-multipeer/src/jvmMain/kotlin/us/tractat/kuilt/multipeer/internal/BridgePeerLink.kt"),
("NearbySeam","kuilt-nearby/src/commonMain/kotlin/us/tractat/kuilt/nearby/NearbySeam.kt"),
("NwSeam","kuilt-nw/src/commonMain/kotlin/us/tractat/kuilt/nw/NwSeam.kt"),
("TokenGatedSeam","kuilt-otel-tap/src/commonMain/kotlin/us/tractat/kuilt/otel/tap/admit/TokenGatedSeam.kt"),
("MeteredSeam","kuilt-scale/src/main/kotlin/us/tractat/kuilt/scale/MeteredSeam.kt"),
("PrincipalSeam","kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/Principal.kt"),
("RoomChannelSeam","kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/RoomChannel.kt"),
("PerPeerSeam","kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt"),
("FakeChannelSeam","kuilt-session-test/src/commonMain/kotlin/us/tractat/kuilt/session/test/FakeRoom.kt"),
("ControllableSeam","kuilt-test/src/commonMain/kotlin/us/tractat/kuilt/test/ControllableLoom.kt"),
("FakeSeam","kuilt-test/src/commonMain/kotlin/us/tractat/kuilt/test/FakeSeam.kt"),
("FaultySeam","kuilt-test/src/commonMain/kotlin/us/tractat/kuilt/test/FaultySeam.kt"),
("FlakyLifecycleSeam","kuilt-test/src/commonMain/kotlin/us/tractat/kuilt/test/FlakyLifecycleSeam.kt"),
("PerPeerSeam","kuilt-warp/src/commonMain/kotlin/us/tractat/kuilt/warp/WarpNode.kt"),
("RawIncomingProxy","kuilt-warp/src/commonMain/kotlin/us/tractat/kuilt/warp/WarpNode.kt"),
("ObservedCapabilitySeam","kuilt-websocket/src/commonMain/kotlin/us/tractat/kuilt/websocket/WebSocketSeam.kt"),
("WebRTCPeerLink","kuilt-webrtc/src/wasmJsMain/kotlin/us/tractat/kuilt/webrtc/internal/WebRTCPeerLink.kt"),
]
concerns={
 "gate":r"SeamStateGate\(",
 "bareState":r"MutableStateFlow<SeamState>|MutableStateFlow\(SeamState",
 "spool":r"Spool<",
 "pumpIn":r"\.pumpIn\(",
 "launchIn":r"\.launchIn\(",
 "launch":r"\b(scope|Scope)\w*\.launch\b|launch\s*\(start\s*=",
 "ownScope":r"CoroutineScope\(",
 "closeOnce":r"compareAndSet|AtomicBoolean|atomic\(false\)",
 "peersFlow":r"_peers|peers\s*:\s*StateFlow",
 "budget":r"override val maxPayloadBytes",
 "lock":r"reentrantLock\(|Mutex\(",
 "confine":r"limitedParallelism\(1\)",
 "selfsend":r"Cannot send to self",
 "latching":r"latchingTo|LatchingStateFlow",
}
def body(lines, name):
    # find declaration line for class name
    pat=re.compile(r"\b(class|object)\s+"+re.escape(name)+r"\b")
    for i,l in enumerate(lines):
        if pat.search(l):
            start=i
            break
    else:
        return None
    # find the opening brace of the class body: scan forward, track parens, first '{' at paren depth 0
    depth=0; j=start; opened=False
    while j < len(lines):
        for ch in lines[j]:
            if ch in "([": depth+=1
            elif ch in ")]": depth-=1
            elif ch=="{" and depth==0:
                opened=True; break
        if opened: break
        j+=1
    if not opened:
        return (start+1, start+1)
    # brace balance from j
    b=0; k=j
    started=False
    while k < len(lines):
        for ch in lines[k]:
            if ch=="{": b+=1; started=True
            elif ch=="}":
                b-=1
                if started and b==0:
                    return (start+1, k+1)
        k+=1
    return (start+1, len(lines))
for name,rel in targets:
    p=os.path.join(root,rel)
    lines=open(p).read().split("\n")
    r=body(lines,name)
    if r is None:
        print(f"{name}\t{rel}\tNOT-FOUND"); continue
    s,e=r
    txt="\n".join(lines[s-1:e])
    # strip block comments crudely
    code=re.sub(r"/\*.*?\*/","",txt,flags=re.S)
    code="\n".join(l for l in code.split("\n") if not l.strip().startswith("*") and not l.strip().startswith("//"))
    hits=[]
    for c,rx in concerns.items():
        n=len(re.findall(rx,code))
        if n: hits.append(f"{c}:{n}")
    print(f"{name}\t{rel}\t{s}-{e}\t{e-s+1}\t"+",".join(hits))
