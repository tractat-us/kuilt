package us.tractat.kuilt.seal

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.providers.webcrypto.WebCrypto

internal actual fun platformProvider(): CryptographyProvider = CryptographyProvider.WebCrypto
