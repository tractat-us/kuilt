package us.tractat.kuilt.session.test

import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.session.admit.AdmissionPolicy
import us.tractat.kuilt.session.admit.AdmissionRequest

/**
 * Marks test-only ways to build what only a room builds in production. **For tests; no stability
 * promise.**
 *
 * In production an [AdmissionRequest] exists only after a joiner has proved to the room that it
 * holds its key. [admissionRequest] skips that proof, so a request built with it says nothing about
 * who holds the key. That is what a unit test of your own [AdmissionPolicy] wants, and what
 * production code must never do: a policy fed a hand-built request decides on a key nobody proved.
 *
 * Opt in where you test, not module-wide in production code:
 *
 * ```kotlin
 * @OptIn(AdmissionTestApi::class)
 * class MyPolicyTest { … }
 * ```
 *
 * An annotated declaration may change shape or disappear in any release, patch releases included.
 * The opt-in level is `ERROR`, so using one without opting in does not compile.
 */
@RequiresOptIn(
    message = "Builds a request no joiner proved. For tests only; no stability promise.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class AdmissionTestApi

/**
 * Builds the [AdmissionRequest] a room would hand your [AdmissionPolicy] for a newcomer holding
 * [key], so you can test `decide` without standing up a room.
 *
 * No proof is checked: the request carries [key] as if the joiner had signed for it. Take a real key
 * from `DeviceKey.generate().id`, or `DidKey.parse` a fixed one.
 *
 * @param key The device key the newcomer proved, as the policy will see it.
 * @param displayName The name the newcomer asked to be shown under. Unverified in production too.
 * @param deviceId The app's own device id, or `null` when the joiner sent none.
 * @sample us.tractat.kuilt.session.test.admissionPolicyTestSample
 */
@AdmissionTestApi
public fun admissionRequest(
    key: DidKey,
    displayName: String = "",
    deviceId: String? = null,
): AdmissionRequest = AdmissionRequest(key, displayName, deviceId)
