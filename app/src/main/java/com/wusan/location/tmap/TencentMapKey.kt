package com.wusan.location.tmap

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Obfuscation of a client SDK credential, not a server-side secret vault. */
internal object TencentMapKey {
    val value: String by lazy {
        val a = Base64.decode(GeneratedTencentMapKey.PART_A, Base64.NO_WRAP)
        val b = Base64.decode(GeneratedTencentMapKey.PART_B, Base64.NO_WRAP)
        val material = ByteArray(a.size) { (a[it].toInt() xor b[it].toInt()).toByte() }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(material, "AES"), GCMParameterSpec(
                128, Base64.decode(GeneratedTencentMapKey.NONCE, Base64.NO_WRAP)
            ))
            String(cipher.doFinal(Base64.decode(GeneratedTencentMapKey.CIPHERTEXT, Base64.NO_WRAP)),
                Charsets.UTF_8).also { check(it.isNotBlank()) { "腾讯地图 Key 为空，请重新构建应用" } }
        } catch (e: Exception) {
            throw IllegalStateException("腾讯地图 Key 解密失败，请重新构建应用", e)
        } finally {
            material.fill(0)
            a.fill(0)
            b.fill(0)
        }
    }
}
