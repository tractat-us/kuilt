package us.tractat.kuilt.heddle

/**
 * Format 1 of a [LocalHeddleSnapshot], encoded with a default `Json`: the waker scenario of
 * `LocalHeddlePersistenceTest`, two leases open and `b`'s wake offset set.
 *
 * **Never edit this to make a test pass once format 1 has shipped.** It is what a later release
 * must still read. A change to what a snapshot encodes adds a new format beside this one, and the
 * golden of the format it replaces stays here.
 */
internal const val GOLDEN_FORMAT_1: String = """[1,{"epoch":0,"totalSlots":2,"ledger":{"records":{"root/a@1:1#0":[{"id":"root/a@1:1#0","parent":"root","child":"root/a","weight":{"numerator":1,"denominator":1}}],"root/b@1:1#0":[{"id":"root/b@1:1#0","parent":"root","child":"root/b","weight":{"numerator":1,"denominator":1}}]},"minted":{"local#0":{"root":"root","holder":"local","amount":56}},"issued":{"root/a@1:1#0":{"counts":{"local":51}},"root/b@1:1#0":{"counts":{"local":1}}},"returned":{},"leafSpent":{"root/a@1:1#0":{"counts":{"local":50}}},"rollupSpent":{},"transfers":{},"lifecycle":{"root/a@1:1#0":"ACTIVE","root/b@1:1#0":"ACTIVE"},"gauges":{"root/a@1:1#0":{"floor":{"numerator":51,"denominator":1},"folded":51},"root/b@1:1#0":{"floor":{"numerator":1,"denominator":1},"folded":1}}},"allocatorMemory":{"wakeOffsets":{"root/b@1:1#0":{"numerator":50,"denominator":1}},"demandingObserved":{"root/a@1:1#0":true,"root/b@1:1#0":true}},"leases":[{"epoch":0,"seq":50,"reserved":1,"startedAt":100,"path":["root/a@1:1#0"]},{"epoch":0,"seq":51,"reserved":1,"startedAt":101,"path":["root/b@1:1#0"]}],"lastApplied":{"root":"root","children":[{"type":"lane","name":"a"},{"type":"lane","name":"b"}]}}]"""
