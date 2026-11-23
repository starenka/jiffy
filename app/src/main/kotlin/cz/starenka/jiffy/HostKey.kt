package cz.starenka.jiffy

import android.content.Context
import android.util.Base64
import org.apache.sshd.common.config.keys.KeyUtils
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/** Persistent ECDSA P-256 host key, generated once on the phone. */
object HostKey {
    fun load(c: Context): KeyPair {
        val f = File(c.filesDir, "host_key")
        if (f.exists()) {
            val (priv, pub) = f.readLines().take(2)
            val kf = KeyFactory.getInstance("EC")
            return KeyPair(
                kf.generatePublic(X509EncodedKeySpec(Base64.decode(pub, Base64.NO_WRAP))),
                kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(priv, Base64.NO_WRAP))),
            )
        }
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val tmp = File(c.filesDir, "host_key.tmp")
        tmp.writeText(
            Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP) + "\n" +
                Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP) + "\n"
        )
        tmp.renameTo(f)
        return kp
    }

    fun fingerprint(pub: PublicKey): String = KeyUtils.getFingerPrint(pub)
}
