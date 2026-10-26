package us.tractat.kuilt.seal

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.providers.jdk.JDK

internal actual fun platformProvider(): CryptographyProvider = CryptographyProvider.JDK
