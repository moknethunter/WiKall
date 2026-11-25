package com.mokasas.wikall

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Entity
internal data class Device(@PrimaryKey val number: String, val credentialDigest: String)

@Entity
internal data class ConnectionProfile(@PrimaryKey val slot: Int, val address: String, val pin: String, val credential: String)

@Entity
internal data class CallHistory(@PrimaryKey val id: String, val peer: String, val state: String, val endedAt: Long)

@Dao
internal interface LocalData {
    @Query("SELECT * FROM Device WHERE number = '1001'")
    fun device(): Device?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun rememberDevice(device: Device)

    @Query("DELETE FROM Device WHERE number = '1001'")
    fun revokeDevice()

    @Query("SELECT * FROM ConnectionProfile WHERE slot = 0")
    fun profile(): ConnectionProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveProfile(profile: ConnectionProfile)

    @Query("DELETE FROM ConnectionProfile")
    fun forgetProfile()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun record(call: CallHistory)
}

@Database(entities = [Device::class, ConnectionProfile::class, CallHistory::class], version = 1, exportSchema = false)
internal abstract class LocalDatabase : RoomDatabase() {
    abstract fun data(): LocalData
}

internal data class SavedConnection(val address: String, val pin: String, val credential: String)

@Singleton
internal class CallStore @Inject constructor(@ApplicationContext context: Context) : PairedDevice {
    private val data = Room.databaseBuilder(context, LocalDatabase::class.java, "wi-call.db").build().data()

    override fun digest(): String? = data.device()?.credentialDigest
    override fun remember(digest: String) = data.rememberDevice(Device("1001", digest))
    override fun revoke() = data.revokeDevice()

    fun saved(): SavedConnection? = data.profile()?.let {
        val (iv, bytes) = it.credential.split('.')
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Wire.decode(iv)))
        cipher.updateAAD("${it.address}|${it.pin}".encodeToByteArray())
        SavedConnection(it.address, it.pin, cipher.doFinal(Wire.decode(bytes)).decodeToString())
    }

    fun save(connection: SavedConnection) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD("${connection.address}|${connection.pin}".encodeToByteArray())
        val encrypted = cipher.doFinal(connection.credential.encodeToByteArray())
        data.saveProfile(ConnectionProfile(0, connection.address, connection.pin, "${Wire.encode(cipher.iv)}.${Wire.encode(encrypted)}"))
    }

    fun forget() = data.forgetProfile()
    fun record(call: CallHistory) = data.record(call)

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "wi-call-resume-v1"
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }
}
