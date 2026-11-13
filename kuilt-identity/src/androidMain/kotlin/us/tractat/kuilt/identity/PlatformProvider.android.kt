package us.tractat.kuilt.identity

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.providers.jdk.JDK

internal actual fun platformProvider(): CryptographyProvider = CryptographyProvider.JDK
