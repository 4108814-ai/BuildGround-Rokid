package com.anezium.rokidbus.plugin.assistant

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface SyncResult {
    data class Success(
        val memoryCount: Int,
        val chars: Int,
    ) : SyncResult

    data class Unavailable(val reason: SyncUnavailableReason) : SyncResult

    data object NotSignedIn : SyncResult
    data object Disabled : SyncResult
}

enum class SyncUnavailableReason {
    NETWORK,
    UNAUTHORIZED,
    HTTP_ERROR,
    INVALID_RESPONSE,
    REFRESH_FAILED,
    UNKNOWN,
}

class AccountContextSync internal constructor(
    private val authStore: CodexAuthStore,
    private val client: ChatGptAccountContextClient,
    private val nowMs: () -> Long,
) {
    constructor(context: Context) : this(
        authStore = CodexAuthStore(context.applicationContext),
        client = ChatGptAccountContextClient(context.applicationContext),
        nowMs = System::currentTimeMillis,
    )

    private val syncMutex = Mutex()

    suspend fun syncNow(): SyncResult = withContext(Dispatchers.IO) {
        syncMutex.withLock { syncNowLocked() }
    }

    suspend fun syncIfStale(): SyncResult? = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            if (!canSync() || !isStale()) return@withLock null
            syncNowLocked()
        }
    }

    fun isStale(): Boolean = isAccountContextCacheStale(
        syncedAtMs = authStore.accountContextSyncedAtMs(),
        nowMs = nowMs(),
        ttlMs = SYNC_TTL_MS,
    )

    private suspend fun syncNowLocked(): SyncResult {
        if (!authStore.syncAccountContext()) return SyncResult.Disabled
        if (!hasChatGptOAuth()) return SyncResult.NotSignedIn

        return try {
            val context = client.fetch()
            val rendered = context.renderForPrompt(
                maxChars = CodexAuthStore.MAX_SYNCED_ACCOUNT_CONTEXT_CHARS,
            )
            authStore.setSyncedAccountContext(rendered)
            authStore.setAccountContextSyncedAtMs(nowMs())
            SyncResult.Success(
                memoryCount = context.memories.count(String::isNotBlank),
                chars = rendered.length,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ChatGptAccountContextNotSignedInException) {
            SyncResult.NotSignedIn
        } catch (unavailable: ChatGptAccountContextUnavailableException) {
            SyncResult.Unavailable(unavailable.reason)
        } catch (_: Throwable) {
            SyncResult.Unavailable(SyncUnavailableReason.UNKNOWN)
        }
    }

    private fun canSync(): Boolean = authStore.syncAccountContext() && hasChatGptOAuth()

    private fun hasChatGptOAuth(): Boolean =
        authStore.authMode() == CodexAuthStore.AUTH_MODE_CHATGPT &&
            authStore.oauthTokens() != null

    companion object {
        const val SYNC_TTL_MS = 12L * 60L * 60L * 1_000L
    }
}

internal fun combineAccountContextForPrompt(
    syncEnabled: Boolean,
    syncedAccountContext: String,
    assistantMemory: String,
): String = listOfNotNull(
    syncedAccountContext.takeIf { syncEnabled && it.isNotBlank() },
    assistantMemory.takeIf(String::isNotBlank),
).joinToString("\n\n")

internal fun CodexAuthStore.combinedAssistantContextForPrompt(): String =
    combineAccountContextForPrompt(
        syncEnabled = syncAccountContext() &&
            authMode() == CodexAuthStore.AUTH_MODE_CHATGPT &&
            oauthTokens() != null,
        syncedAccountContext = syncedAccountContext(),
        assistantMemory = assistantMemory(),
    )

internal fun isAccountContextCacheStale(
    syncedAtMs: Long,
    nowMs: Long,
    ttlMs: Long,
): Boolean = syncedAtMs <= 0L || syncedAtMs > nowMs || nowMs - syncedAtMs >= ttlMs
