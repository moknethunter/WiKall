package com.mokasas.wikall

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Entity(indices = [Index(value = ["serial"], unique = true)])
internal data class AssignedDevice(
    @PrimaryKey val number: String,
    val serial: String,
    val signing: String,
    val encryption: String,
    val activationId: String,
    val revoked: Boolean,
) {
    constructor(value: Enrollment) : this(value.number, value.serial, value.signing, value.encryption, value.activationId, value.revoked)
    fun enrollment() = Enrollment(number, serial, signing, encryption, activationId, revoked)
}

@Entity
internal data class ConnectionProfile(@PrimaryKey val slot: Int, val address: String, val pin: String, val credential: String)

@Entity
internal data class CallHistory(@PrimaryKey val id: String, val peer: String, val state: String, val endedAt: Long)

@Entity(primaryKeys = ["account", "id"])
internal data class IncomingMissed(val account: String, val id: String, val peer: String, val endedAt: Long)

@Dao
internal abstract class LocalData {
    @Query("SELECT * FROM AssignedDevice ORDER BY number")
    abstract fun devices(): List<AssignedDevice>

    @Query("SELECT * FROM AssignedDevice WHERE number = :number")
    abstract fun device(number: String): AssignedDevice?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun saveDevice(device: AssignedDevice)

    @Transaction
    open fun issue(request: DeviceRequest): Enrollment {
        val issued = allocate(devices().map(AssignedDevice::enrollment), request)
        saveDevice(AssignedDevice(issued))
        return issued
    }

    @Query("UPDATE AssignedDevice SET revoked = 1 WHERE number = :number")
    abstract fun revokeDevice(number: String)

    @Query("SELECT * FROM ConnectionProfile WHERE slot = 0")
    abstract fun profile(): ConnectionProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun saveProfile(profile: ConnectionProfile)

    @Query("DELETE FROM ConnectionProfile")
    abstract fun forgetProfile()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun record(call: CallHistory)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun recordMissed(call: IncomingMissed): Long

    @Query("SELECT * FROM IncomingMissed WHERE account = :account ORDER BY endedAt DESC LIMIT 20")
    abstract fun missed(account: String): List<IncomingMissed>
}

@Database(
    entities = [AssignedDevice::class, ConnectionProfile::class, CallHistory::class, IncomingMissed::class],
    version = 3,
    exportSchema = false,
)
internal abstract class LocalDatabase : RoomDatabase() {
    abstract fun data(): LocalData
}

internal data class SavedConnection(val address: String, val activation: Activation, val file: ByteArray) {
    val pin get() = activation.pin
}

@Singleton
internal class CallStore @Inject constructor(@ApplicationContext context: Context) : EnrollmentRegistry {
    private val data = Room.databaseBuilder(context, LocalDatabase::class.java, "wi-call.db")
        .addMigrations(object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS Device")
                db.execSQL("DELETE FROM ConnectionProfile")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS AssignedDevice (
                        number TEXT NOT NULL PRIMARY KEY, serial TEXT NOT NULL, signing TEXT NOT NULL,
                        encryption TEXT NOT NULL, activationId TEXT NOT NULL, revoked INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_AssignedDevice_serial ON AssignedDevice(serial)")
            }
        }, object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS IncomingMissed (
                        account TEXT NOT NULL, id TEXT NOT NULL, peer TEXT NOT NULL, endedAt INTEGER NOT NULL,
                        PRIMARY KEY(account, id)
                    )
                """.trimIndent())
            }
        }).build().data()

    override fun find(number: String) = data.device(number)?.enrollment()
    override fun revoke(number: String) = data.revokeDevice(number)
    fun devices() = data.devices().map(AssignedDevice::enrollment)
    fun issue(request: DeviceRequest) = data.issue(request)

    fun saved(identity: DeviceIdentity): SavedConnection? = data.profile()?.let {
        val bytes = Wire.decode(it.credential)
        val activation = Activation.read(bytes, identity.decryption, identity.request.serial)
        SavedConnection(it.address, activation, bytes)
    }

    fun save(connection: SavedConnection) =
        data.saveProfile(ConnectionProfile(0, connection.address, "", Wire.encode(connection.file)))

    fun forget() = data.forgetProfile()
    fun record(call: CallHistory) = data.record(call)
    fun recordMissed(call: IncomingMissed) = data.recordMissed(call)
    fun missed(account: String) = data.missed(account)
}
