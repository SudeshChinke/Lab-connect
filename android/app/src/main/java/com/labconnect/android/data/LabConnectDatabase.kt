package com.labconnect.android.data

import androidx.room.*
import java.util.UUID

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val ipAddress: String,
    val tcpPort: Int,
    val publicKey: String,
    val lastSeen: Long,
    val isPaired: Boolean = false,
    val pairedAt: Long? = null
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey
    val messageId: String,
    val chatId: String,
    val senderId: String,
    val content: String,
    val contentType: String,
    val timestamp: Long,
    val replyTo: String? = null,
    val status: String,
    val isLocal: Boolean
)

@Entity(tableName = "transfers")
data class TransferEntity(
    @PrimaryKey
    val transferId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val sha256: String,
    val chunkSize: Int,
    val totalChunks: Long,
    val senderId: String,
    val receiverId: String,
    val status: String,
    val bytesTransferred: Long,
    val createdAt: Long,
    val completedAt: Long? = null,
    val localFilePath: String? = null
)

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey
    val groupId: String,
    val name: String,
    val creatorId: String,
    val createdAt: Long,
    val description: String
)

@Entity(
    tableName = "group_members",
    primaryKeys = ["groupId", "deviceId"]
)
data class GroupMemberEntity(
    val groupId: String,
    val deviceId: String,
    val joinedAt: Long
)

@Dao
interface DeviceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(device: DeviceEntity)
    
    @Query("SELECT * FROM devices WHERE deviceId = :deviceId")
    suspend fun getById(deviceId: String): DeviceEntity?
    
    @Query("SELECT * FROM devices WHERE isPaired = 1")
    suspend fun getPairedDevices(): List<DeviceEntity>
    
    @Query("SELECT * FROM devices")
    suspend fun getAllDevices(): List<DeviceEntity>
    
    @Query("DELETE FROM devices WHERE deviceId = :deviceId")
    suspend fun delete(deviceId: String)
    
    @Query("UPDATE devices SET isPaired = :isPaired, pairedAt = :pairedAt WHERE deviceId = :deviceId")
    suspend fun updatePairStatus(deviceId: String, isPaired: Boolean, pairedAt: Long?)
}

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)
    
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    suspend fun getMessagesByChat(chatId: String): List<MessageEntity>
    
    @Query("SELECT * FROM messages WHERE messageId = :messageId")
    suspend fun getById(messageId: String): MessageEntity?
    
    @Query("UPDATE messages SET status = :status WHERE messageId = :messageId")
    suspend fun updateStatus(messageId: String, status: String)
}

@Dao
interface TransferDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transfer: TransferEntity)
    
    @Query("SELECT * FROM transfers WHERE transferId = :transferId")
    suspend fun getById(transferId: String): TransferEntity?
    
    @Query("SELECT * FROM transfers WHERE senderId = :deviceId OR receiverId = :deviceId ORDER BY createdAt DESC")
    suspend fun getTransfersForDevice(deviceId: String): List<TransferEntity>
    
    @Query("SELECT * FROM transfers WHERE status = 'IN_PROGRESS'")
    suspend fun getActiveTransfers(): List<TransferEntity>
    
    @Query("UPDATE transfers SET status = :status, bytesTransferred = :bytesTransferred WHERE transferId = :transferId")
    suspend fun updateProgress(transferId: String, status: String, bytesTransferred: Long)
}

@Dao
interface GroupDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(group: GroupEntity)
    
    @Query("SELECT * FROM groups WHERE groupId = :groupId")
    suspend fun getById(groupId: String): GroupEntity?
    
    @Query("SELECT * FROM groups WHERE creatorId = :deviceId")
    suspend fun getGroupsByCreator(deviceId: String): List<GroupEntity>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMember(member: GroupMemberEntity)
    
    @Query("SELECT * FROM group_members WHERE groupId = :groupId")
    suspend fun getMembers(groupId: String): List<GroupMemberEntity>
    
    @Query("DELETE FROM group_members WHERE groupId = :groupId AND deviceId = :deviceId")
    suspend fun removeMember(groupId: String, deviceId: String)
}

@Database(
    entities = [
        DeviceEntity::class,
        MessageEntity::class,
        TransferEntity::class,
        GroupEntity::class,
        GroupMemberEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class LabConnectDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun messageDao(): MessageDao
    abstract fun transferDao(): TransferDao
    abstract fun groupDao(): GroupDao
}