package com.tofu.client.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Auth mechanism used to obtain a session for a server. */
enum class AuthType {
    /** code-server `--auth password`: headless POST /login is replayable. */
    CODE_SERVER_PASSWORD,

    /** Layer-1 interactive SSO: WebView completes login once, we persist the jar. */
    INTERACTIVE_SSO,

    /** No auth (bare Tofu / trusted network). */
    NONE,
}

/**
 * A remembered server.
 *
 * The [alias] is the STABLE logical identity and the switcher key. The secret
 * (in [com.tofu.client.session.SecretStore]) is keyed by [alias], NOT by
 * [baseUrl] — so when a sandbox is re-provisioned the user edits [baseUrl] in
 * one tap and the saved credential is reused.
 *
 * [instanceUuid] is the codelab instance id parsed out of the host (nullable —
 * only present for MLP-style `<uuid>-vscode-<idc>.…` hosts). It lets us detect
 * "same logical server, new URL" and drive the purge-and-relogin path.
 *
 * [cookieHost] records the exact host the current cached session cookie is
 * `Domain`-pinned to. On a [baseUrl] change we compare against it: a mismatch
 * means the cached jar is bound to a dead host and MUST be hard-invalidated.
 */
@Entity(tableName = "profiles")
data class Profile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "alias") val alias: String,
    @ColumnInfo(name = "instance_uuid") val instanceUuid: String? = null,
    @ColumnInfo(name = "base_url") val baseUrl: String,
    @ColumnInfo(name = "auth_type") val authType: AuthType = AuthType.CODE_SERVER_PASSWORD,
    @ColumnInfo(name = "cookie_host") val cookieHost: String? = null,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long = 0,
)

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY last_used_at DESC")
    fun observeAll(): Flow<List<Profile>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getById(id: Long): Profile?

    @Query("SELECT * FROM profiles WHERE alias = :alias LIMIT 1")
    suspend fun getByAlias(alias: String): Profile?

    /** One-shot snapshot (not a Flow) — used to find a same-host stored secret. */
    @Query("SELECT * FROM profiles")
    suspend fun getAllOnce(): List<Profile>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(profile: Profile): Long

    @Update
    suspend fun update(profile: Profile): Int

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteById(id: Long)
}
