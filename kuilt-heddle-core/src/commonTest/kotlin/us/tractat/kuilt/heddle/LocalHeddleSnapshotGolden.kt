package us.tractat.kuilt.heddle

/**
 * Format 1 of a [LocalHeddleSnapshot], encoded with a default `Json`: the waker scenario of
 * `LocalHeddlePersistenceTest`, two leases open and `b`'s wake offset set.
 *
 * **Never edit this to make a test pass once format 1 has shipped.** It is what a later release
 * must still read. A change to what a snapshot encodes adds a new format beside this one, and the
 * golden of the format it replaces stays here.
 */
internal const val GOLDEN_FORMAT_1: String = """{"formatVersion":1,"state":{"root":"root","epoch":0,"totalSlots":2,"ledger":{"records":{"root>a":[{"id":"root>a","parent":"root","child":"a","weight":{"numerator":1,"denominator":1}}],"root>b":[{"id":"root>b","parent":"root","child":"b","weight":{"numerator":1,"denominator":1}}]},"minted":{"local#0":{"root":"root","holder":"local","amount":56}},"issued":{"root>a":{"counts":{"local":51}},"root>b":{"counts":{"local":1}}},"returned":{},"leafSpent":{"root>a":{"counts":{"local":50}}},"rollupSpent":{},"transfers":{},"lifecycle":{"root>a":"ACTIVE","root>b":"ACTIVE"},"gauges":{"root>a":{"floor":{"numerator":51,"denominator":1},"folded":51},"root>b":{"floor":{"numerator":1,"denominator":1},"folded":1}}},"allocatorMemory":{"wakeOffsets":{"root>b":{"numerator":50,"denominator":1}},"demandingObserved":{"root>a":true,"root>b":true}},"leases":[{"epoch":0,"seq":50,"leaf":"a","reserved":1,"startedAt":100,"path":["root>a"]},{"epoch":0,"seq":51,"leaf":"b","reserved":1,"startedAt":101,"path":["root>b"]}]}}"""
