package us.tractat.kuilt.nw

/**
 * Entry point for the `:kuilt-nw` module — a Network.framework peer-to-peer full-mesh
 * fabric implementing kuilt's `Loom`/`Seam` contract.
 *
 * See `module.md` for the design walk and
 * `docs/superpowers/plans/2026-07-11-kuilt-nw-transport.md` for the phased implementation plan.
 *
 * This object is a build-wiring marker — a real `.kt` source is required in every declared
 * KMP target's compile closure (`forbidSourcelessKmpTarget`, #1023), and `commonMain` is the
 * shared ancestor of all of them. It is replaced/joined by the real `NwApi` surface in Task 2.2.
 */
public object Nw
