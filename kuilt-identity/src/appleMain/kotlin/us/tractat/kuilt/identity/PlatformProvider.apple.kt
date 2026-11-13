package us.tractat.kuilt.identity

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.providers.cryptokit.CryptoKit

internal actual fun platformProvider(): CryptographyProvider = CryptographyProvider.CryptoKit
