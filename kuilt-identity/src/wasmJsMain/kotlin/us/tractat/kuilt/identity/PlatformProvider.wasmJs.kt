package us.tractat.kuilt.identity

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.providers.webcrypto.WebCrypto

internal actual fun platformProvider(): CryptographyProvider = CryptographyProvider.WebCrypto
