package us.tractat.kuilt.otel.tap

import kotlinx.coroutines.CoroutineScope
import us.tractat.kuilt.multipeer.MultipeerPeerLinkFactory
import us.tractat.kuilt.otel.WarpLogRecordExporter
import us.tractat.kuilt.otel.WarpMetricExporter

/**
 * Reach a real iPhone's captured logs from a nearby Mac, **encrypted end to end**.
 *
 * This is the Apple-native complement to the plain-Wi-Fi reach path. On a shared network the
 * plain path carries the log bytes unencrypted and leans on a join code only to control *who*
 * may pull. Apple's Multipeer Connectivity fabric closes that gap: every frame travels over a
 * DTLS-encrypted link (the underlying session is created with encryption *required*), so even a
 * listener already positioned to snoop the network reads nothing.
 *
 * It also removes the awkward role inversion the plain path needs. An iPhone can't run a
 * Wi-Fi server, so over that fabric it has to *join* a laptop-hosted rendezvous. Over Multipeer
 * the iPhone advertises itself natively, so it simply **hosts** the tap and a Mac discovers it
 * and pulls — the natural topology.
 *
 * The one honest trade-off, by design: there is **no JVM/CI puller**. Multipeer is Apple-only,
 * so a **Mac** must be the machine that pulls. That is exactly why this is the *encrypted
 * complement* to the mDNS+WebSocket path, not a replacement for it — reach a simulator or a CI
 * runner over the loopback/WebSocket path; reach a real iPhone from a Mac over this one.
 *
 * @param factory the Multipeer fabric for this device. Per its own contract, construct **one
 *   per device** and share it via DI; this call weaves a session over it. The caller owns the
 *   factory's lifecycle — closing the returned host stops the tap, and closing the factory
 *   releases the Multipeer advertiser/browser/session.
 * @param exporter the device's captured-log buffer to offer.
 * @param scope the scope the host's replicator runs in. Closing the returned host (or
 *   cancelling this scope) stops the tap.
 * @param config tap tuning; the defaults suit a developer turning the tap on to debug.
 * @param admission how the pulling Mac is admitted. The Multipeer link is already encrypted, so
 *   [LogTapAdmission.Open] is safe on a trusted pairing; pass [LogTapAdmission.Verify] to *also*
 *   require a short join code (admission control layered over the encrypted transport — the same
 *   fabric-agnostic gate the plain path uses, unchanged). The offering side must hold
 *   [LogTapAdmission.Verify] or [LogTapAdmission.Open]; [LogTapAdmission.Present] is the puller's
 *   role.
 */
public suspend fun installMultipeerLogTap(
    factory: MultipeerPeerLinkFactory,
    exporter: WarpLogRecordExporter,
    scope: CoroutineScope,
    config: LogTapConfig = LogTapConfig(),
    admission: LogTapAdmission = LogTapAdmission.Open,
): LogTapHost = installLogTap(factory, exporter, scope, config, admission)

/**
 * Reach a real iPhone's converged metric buffer from a nearby Mac over the same DTLS-encrypted
 * Multipeer fabric as [installMultipeerLogTap].
 *
 * The iPhone hosts and offers its [WarpMetricExporter] buffer; a Mac discovers it and pulls with
 * a [MetricTapClient]. Metrics ride the encrypted link too, but the link's encryption only
 * answers *what* a snooper can read off the wire (nothing) — it says nothing about *who* is
 * allowed to connect in the first place. [MultipeerPeerLinkFactory] auto-accepts every Multipeer
 * invitation, so any Mac on the LAN that discovers the advertised service can otherwise join and
 * pull; admission is a separate, layered concern, exactly as for [installMultipeerLogTap]. As
 * with the log tap, there is no JVM/CI puller: a Mac must be the puller because Multipeer is
 * Apple-only.
 *
 * @param factory the Multipeer fabric for this device (construct one per device, share via DI;
 *   the caller owns closing it).
 * @param exporter the device's metric buffer to offer.
 * @param scope the scope the host's replicator runs in.
 * @param config tap tuning.
 * @param admission how the pulling Mac is admitted. The Multipeer link is already encrypted, so
 *   [LogTapAdmission.Open] is safe on a trusted pairing; pass [LogTapAdmission.Verify] to *also*
 *   require a short join code (admission control layered over the encrypted transport — the same
 *   fabric-agnostic gate the log path and the plain path use, unchanged). The offering side must
 *   hold [LogTapAdmission.Verify] or [LogTapAdmission.Open]; [LogTapAdmission.Present] is the
 *   puller's role.
 */
public suspend fun installMultipeerMetricTap(
    factory: MultipeerPeerLinkFactory,
    exporter: WarpMetricExporter,
    scope: CoroutineScope,
    config: MetricTapConfig = MetricTapConfig(),
    admission: LogTapAdmission = LogTapAdmission.Open,
): MetricTapHost = installMetricTap(factory, exporter, scope, config, admission)
