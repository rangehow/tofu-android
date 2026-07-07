package com.tofu.client.session

import android.util.Log
import com.tofu.client.data.AuthType
import com.tofu.client.data.Profile
import com.tofu.client.data.ProfileDao

/**
 * Orchestrates every profile mutation the UI triggers, over the same seams
 * [SessionManager] uses (so it is unit-testable with fakes). Keeps the Compose
 * layer thin: the UI validates via [ProfileForm], then calls one of these.
 *
 * Ordering invariants that matter for correctness:
 *  - ADD: store the secret BEFORE login (login reads it via the vault).
 *  - EDIT with a URL host change: delegate to [SessionManager.updateUrlAndReauth]
 *    so the dead host's Domain-pinned jar is purged before re-login.
 *  - DELETE: remove the secret AND the row (never orphan a credential).
 *  - RENAME: the secret is alias-keyed, so a rename must MOVE the secret to the
 *    new alias key or login would silently lose the credential.
 */
class SessionController(
    private val dao: ProfileDao,
    private val secrets: SecretVault,
    private val session: SessionManager,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** Add a new server, store its secret, then attempt the first login. */
    suspend fun addProfile(
        alias: String,
        baseUrl: String,
        authType: AuthType,
        secret: String,
    ): AddResult {
        val a = alias.trim()
        if (dao.getByAlias(a) != null) return AddResult.DuplicateAlias
        val profile = ProfileForm.toProfile(0, a, baseUrl, authType, clock())
        // Store the secret FIRST — login reads it via the vault.
        if (authType == AuthType.CODE_SERVER_PASSWORD && secret.isNotEmpty()) {
            secrets.putSecret(a, secret)
        }
        val id = dao.insert(profile)
        val saved = profile.copy(id = id)
        val result = session.login(saved)
        Log.i(TAG, "addProfile alias=$a login=${result::class.simpleName}")
        return AddResult.Added(saved, result)
    }

    /**
     * Edit an existing profile. Handles four cases in one entry point:
     *  - rename (alias change) → move the secret key,
     *  - new secret provided → overwrite it,
     *  - URL host change → purge + re-login (via updateUrlAndReauth),
     *  - plain field change → persist + re-login.
     */
    suspend fun editProfile(
        current: Profile,
        newAlias: String,
        newUrl: String,
        newAuthType: AuthType,
        newSecret: String,
    ): LoginResult {
        val a = newAlias.trim()

        // Rename: move the alias-keyed secret so the credential isn't orphaned.
        if (a != current.alias) {
            val existing = secrets.secretFor(current.alias)
            if (existing != null) {
                secrets.putSecret(a, existing)
                secrets.removeSecret(current.alias)
            }
        }
        // New secret overwrites; blank keeps the existing one.
        if (newSecret.isNotEmpty()) secrets.putSecret(a, newSecret)

        val oldHost = ServerUrl.parse(current.baseUrl)?.host
        val newHost = ServerUrl.parse(newUrl)?.host
        val base = current.copy(alias = a, authType = newAuthType)

        return if (oldHost != null && newHost != null && oldHost != newHost) {
            // URL host changed → the purge-and-relogin path owns persistence.
            session.updateUrlAndReauth(base, newUrl)
        } else {
            val updated = ProfileForm.toProfile(
                current.id, a, newUrl, newAuthType, current.lastUsedAt,
            )
            dao.update(updated)
            session.login(updated)
        }
    }

    /** Activate a profile (make it current): bump recency, then log in. */
    suspend fun activate(profile: Profile): LoginResult {
        dao.update(profile.copy(lastUsedAt = clock()))
        return session.login(profile)
    }

    /** Delete a profile and its stored secret (never orphan a credential). */
    suspend fun deleteProfile(profile: Profile) {
        secrets.removeSecret(profile.alias)
        dao.deleteById(profile.id)
        Log.i(TAG, "deleteProfile alias=${profile.alias}")
    }

    sealed interface AddResult {
        data class Added(val profile: Profile, val login: LoginResult) : AddResult
        data object DuplicateAlias : AddResult
    }

    private companion object {
        const val TAG = "SessionController"
    }
}
