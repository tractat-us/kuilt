package us.tractat.kuilt.identity.test

/**
 * Marks the fake key scheme, which is for tests only. **A room built on it accepts signatures anyone
 * can compute.**
 *
 * The fake signs with a hash anyone can recompute from the key and the message, so it proves nothing
 * about who signed. What it gives a test is a signer and a checker that never wait on the platform's
 * cryptographic provider: on wasmJs every real signature is a WebCrypto promise, and a room test that
 * waits on one stops running on virtual time.
 *
 * The opt-in level is `ERROR`, so code that has not opted in does not compile against it:
 *
 * ```kotlin
 * @file:OptIn(TestOnlyKeyScheme::class)
 * ```
 *
 * Opt in from test source sets only. Java callers do not see opt-in requirements at all, so on the
 * JVM this is a convention, not a fence; the fence there is that no production module depends on
 * `:kuilt-identity-test`.
 */
@RequiresOptIn(
    message = "A fake key scheme whose signatures anyone can compute. For tests only; never use it in production.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CONSTRUCTOR)
public annotation class TestOnlyKeyScheme
