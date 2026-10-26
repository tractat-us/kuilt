package us.tractat.kuilt.seal

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.providers.cryptokit.CryptoKit

internal actual fun platformProvider(): CryptographyProvider = CryptographyProvider.CryptoKit
