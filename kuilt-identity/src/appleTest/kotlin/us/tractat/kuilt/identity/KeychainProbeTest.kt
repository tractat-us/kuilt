@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package us.tractat.kuilt.identity

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecAttrSynchronizable
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecReturnAttributes
import platform.Security.kSecReturnData
import platform.Security.kSecUseDataProtectionKeychain
import platform.Security.kSecValueData
import kotlin.test.Test

class KeychainProbeTest {
    @Test
    fun probe() {
        for (dp in listOf(false, true)) {
            val service = CFBridgingRetain("us.tractat.kuilt.identity.probe" as NSString)
            val account = CFBridgingRetain("probe" as NSString)
            val data = CFBridgingRetain(NSData.create(base64EncodedString = "AQID", options = 0u))
            fun base() = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr).also {
                CFDictionaryAddValue(it, kSecClass, kSecClassGenericPassword)
                CFDictionaryAddValue(it, kSecAttrService, service)
                CFDictionaryAddValue(it, kSecAttrAccount, account)
                if (dp) CFDictionaryAddValue(it, kSecUseDataProtectionKeychain, kCFBooleanTrue)
            }
            val del0 = SecItemDelete(base())
            val add = base().also {
                CFDictionaryAddValue(it, kSecValueData, data)
                CFDictionaryAddValue(it, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                CFDictionaryAddValue(it, kSecAttrSynchronizable, kCFBooleanFalse)
            }
            val addStatus = SecItemAdd(add, null)
            val copy = memScoped {
                val q = base().also {
                    CFDictionaryAddValue(it, kSecReturnAttributes, kCFBooleanTrue)
                    CFDictionaryAddValue(it, kSecReturnData, kCFBooleanTrue)
                }
                val out = alloc<CFTypeRefVar>()
                val st = SecItemCopyMatching(q, out.ptr)
                val desc = out.value?.let { CFBridgingRelease(it).toString() }
                "status=$st result=$desc"
            }
            val del = SecItemDelete(base())
            println("KEYCHAIN-PROBE dataProtection=$dp del0=$del0 add=$addStatus copy=$copy del=$del")
        }
    }
}
