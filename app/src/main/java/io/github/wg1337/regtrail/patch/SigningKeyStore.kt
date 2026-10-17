package io.github.wg1337.regtrail.patch

import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * The key a patched APK is signed with.
 *
 * @param certificateSha256 fingerprint of the signing certificate, shown in the UI.
 *   Two patches signed with *different* keys can never update each other, so being
 *   able to see the fingerprint is the difference between "the update failed for no
 *   reason" and "the keystore was regenerated".
 */
internal class SigningIdentity(
    val privateKey: PrivateKey,
    val certificateChain: List<X509Certificate>,
    val certificateSha256: String,
    val createdAt: Long,
    /** False when an existing keystore was reused. */
    val justCreated: Boolean,
)

/**
 * Owns the self-signed signing key used for every patched APK.
 *
 * ## Why a generated key and not `keytool`
 *
 * Android ships no `keytool` — it is a JDK tool — so the certificate is built
 * in-process with BouncyCastle (`bcpkix-jdk15to18`, chosen over the `jdk18on` line
 * because that one assumes Java 9+ module APIs that are fragile on ART).
 *
 * ## Why the keystore must never be lost
 *
 * A patched APK cannot be installed over the original (different certificate ⇒
 * `INSTALL_FAILED_UPDATE_INCOMPATIBLE`), but it *can* be updated over a previous
 * patch **as long as it is signed with the same key**. Losing this file therefore
 * strands every already-patched app: the only way forward is uninstall + reinstall,
 * i.e. data loss. That is why:
 *
 *  - it is persisted before anything is signed,
 *  - the validity is 30 years (an expired certificate makes future updates fail),
 *  - the fingerprint is surfaced in the UI,
 *  - a corrupt keystore is only replaced loudly (logged and reported), never
 *    silently regenerated mid-flow.
 *
 * The file lives in app-private storage. The password is a constant: the key
 * protects nothing beyond "the same key is used again", it is not a credential, and
 * the keystore is no more sensitive than the APKs it signs. PKCS#12 is used because
 * it is the format both Android's `KeyStore` and apksig expect.
 */
internal class SigningKeyStore(private val directory: File) {

    private val keyStoreFile = File(directory, KEYSTORE_NAME)

    /** True when [keyStoreFile] exists; used only for diagnostics/UI. */
    val exists: Boolean get() = keyStoreFile.isFile

    /**
     * Fingerprint of the *existing* key, or null when none has been generated yet.
     *
     * Unlike [loadOrCreate] this never generates anything: the UI asks for it on a
     * cold start, and spending an RSA keygen there would be both slow and a
     * surprise side effect.
     */
    fun existingFingerprint(): String? {
        if (!keyStoreFile.isFile) return null
        return runCatching { load().certificateSha256 }.getOrNull()
    }

    /**
     * Loads the persistent key, generating it on first use.
     *
     * Blocking and CPU-bound (RSA-2048 keygen takes a moment); call it off the main
     * thread.
     */
    fun loadOrCreate(): SigningIdentity {
        if (keyStoreFile.isFile) {
            runCatching { return load() }
                .onFailure { error ->
                    // Loud, not silent: an unreadable keystore means the user's
                    // previously patched apps can no longer be updated.
                    Log.e(TAG, "existing keystore $keyStoreFile is unreadable", error)
                    throw PatchException(
                        "签名密钥库无法读取（${keyStoreFile.name}）：${error.message ?: "未知错误"}。" +
                            "继续会生成新密钥，已打过补丁的应用将无法升级——请先备份该文件或删除它后重试。",
                        error,
                    )
                }
        }
        return generate()
    }

    private fun load(): SigningIdentity {
        val keyStore = KeyStore.getInstance(KEYSTORE_TYPE)
        keyStoreFile.inputStream().use { keyStore.load(it, PASSWORD.toCharArray()) }

        val key = keyStore.getKey(ALIAS, PASSWORD.toCharArray()) as? PrivateKey
            ?: throw IllegalStateException("keystore has no key under alias $ALIAS")
        val certificate = keyStore.getCertificate(ALIAS) as? X509Certificate
            ?: throw IllegalStateException("keystore has no certificate under alias $ALIAS")

        Log.i(TAG, "signing identity loaded from $keyStoreFile")
        return SigningIdentity(
            privateKey = key,
            certificateChain = listOf(certificate),
            certificateSha256 = fingerprint(certificate),
            createdAt = certificate.notBefore.time,
            justCreated = false,
        )
    }

    private fun generate(): SigningIdentity {
        directory.mkdirs()

        val generator = KeyPairGenerator.getInstance("RSA").apply {
            initialize(KEY_SIZE_BITS, SecureRandom())
        }
        val pair = generator.generateKeyPair()

        val now = System.currentTimeMillis()
        val subject = X500Name(DISTINGUISHED_NAME)
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(64, SecureRandom()),
            Date(now - TimeUnit.DAYS.toMillis(1)), // tolerate clock skew
            Date(now + VALIDITY_DAYS * TimeUnit.DAYS.toMillis(1)),
            subject,
            pair.public,
        )
        // Non-CA + digitalSignature only: this is what a code-signing certificate
        // is supposed to be, and some Android releases reject a CA cert used to
        // sign an APK.
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(pair.private)
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))

        val keyStore = KeyStore.getInstance(KEYSTORE_TYPE)
        keyStore.load(null, PASSWORD.toCharArray())
        // `Array<Certificate>` explicitly: `KeyStore.setKeyEntry` takes
        // `Certificate[]`, and Java arrays are not covariant the way Kotlin lists are.
        keyStore.setKeyEntry(
            ALIAS,
            pair.private,
            PASSWORD.toCharArray(),
            arrayOf<Certificate>(certificate),
        )

        // Write to a temp file and rename, so a kill mid-write cannot leave a
        // truncated keystore that would strand every patched app.
        val temp = File(directory, "$KEYSTORE_NAME.tmp")
        temp.outputStream().use { keyStore.store(it, PASSWORD.toCharArray()) }
        if (!temp.renameTo(keyStoreFile)) {
            temp.copyTo(keyStoreFile, overwrite = true)
            temp.delete()
        }

        Log.i(TAG, "signing identity generated at $keyStoreFile")
        return SigningIdentity(
            privateKey = pair.private,
            certificateChain = listOf(certificate),
            certificateSha256 = fingerprint(certificate),
            createdAt = now,
            justCreated = true,
        )
    }

    private fun fingerprint(certificate: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        return digest.joinToString(":") { byte -> "%02X".format(byte) }
    }

    private companion object {
        const val TAG = "RegTrailPatch"
        const val KEYSTORE_NAME = "regtrail-patch.p12"
        const val KEYSTORE_TYPE = "PKCS12"

        /**
         * `key0`, matching `docs/PLAN.md` §4.4 and the LSPatch/Apatch convention, so
         * the persisted keystore can also be handed to a desktop `apksigner` later
         * without re-aliasing.
         */
        const val ALIAS = "key0"
        const val PASSWORD = "regtrail-patch"
        const val KEY_SIZE_BITS = 2048
        const val VALIDITY_DAYS = 30L * 365L
        const val DISTINGUISHED_NAME = "CN=RegTrail Payload, O=RegTrail, C=US"
    }
}
