package voice.core.webdav

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

public interface WebDavSecrets {
  public fun encrypt(plain: String): String
  public fun decrypt(encrypted: String): String?
}

@ContributesBinding(AppScope::class)
@Inject
public class KeystoreWebDavSecrets : WebDavSecrets {

  override fun encrypt(plain: String): String {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key())
    val iv = cipher.iv
    val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
    return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
  }

  override fun decrypt(encrypted: String): String? {
    return runCatching {
      val data = Base64.decode(encrypted, Base64.NO_WRAP)
      if (data.size <= IV_SIZE) return null
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, data, 0, IV_SIZE))
      String(cipher.doFinal(data, IV_SIZE, data.size - IV_SIZE), Charsets.UTF_8)
    }.getOrNull()
  }

  private fun key(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
    generator.init(
      KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(KEY_SIZE_BITS)
        .build(),
    )
    return generator.generateKey()
  }

  private companion object {
    const val ANDROID_KEY_STORE = "AndroidKeyStore"
    const val KEY_ALIAS = "voice_webdav_password"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val GCM_TAG_BITS = 128
    const val IV_SIZE = 12
    const val KEY_SIZE_BITS = 256
  }
}
