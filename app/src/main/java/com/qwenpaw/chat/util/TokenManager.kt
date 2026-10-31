package com.qwenpaw.chat.util

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.qwenpaw.chat.model.Account
import com.qwenpaw.chat.model.AccountColor

/**
 * 多账号 & Token 管理器
 *
 * 存储结构:
 * - accounts: List<Account> 以 JSON 形式存于 SharedPreferences
 * - current_account_id: 当前激活的账号 id
 *
 * 提供账号增删改查、当前账号切换、别名编辑、token 更新等 API。
 */
class TokenManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val gson = Gson()

    companion object {
        private const val PREFS_NAME = "qwenpaw_prefs"
        private const val KEY_ACCOUNTS = "accounts_json"
        private const val KEY_CURRENT_ACCOUNT_ID = "current_account_id"

        // 当用户处于"新增账号"状态(没有当前账号)时,临时保存用户输入的托管地址,
        // 以便在登录时使用,避免每次新建账号都要重新输入 URL。
        private const val KEY_DRAFT_BASE_URL = "draft_base_url"

        // 上一次分配的环境标识色下标,用于分配新账号颜色时递增,避免与相邻账号撞色
        private const val KEY_LAST_COLOR_INDEX = "last_color_index"

        // 每个账号各自记忆的当前 agent id(key = "agent_id_<accountId>"),
        // 切换账号环境时恢复上一次使用的 agent
        private fun agentIdKey(accountId: String) = "agent_id_$accountId"

        // 旧版本单账号使用的 key,用于首次启动时迁移到新的多账号结构
        private const val LEGACY_KEY_USERNAME = "username"
        private const val LEGACY_KEY_PASSWORD = "password"
        private const val LEGACY_KEY_BASE_URL = "base_url"
        private const val LEGACY_KEY_TOKEN = "auth_token"

        @Volatile
        private var INSTANCE: TokenManager? = null

        fun getInstance(context: Context): TokenManager {
            return INSTANCE ?: synchronized(this) {
                val instance = TokenManager(context.applicationContext)
                instance.migrateLegacyIfNeeded()
                INSTANCE = instance
                instance
            }
        }
    }

    init {
        migrateLegacyIfNeeded()
    }

    /**
     * 首次启动时,如果检测到旧版本单账号数据,自动迁移到账号列表中。
     * - 生成默认别名 = "默认账号"
     * - 旧数据迁移完成后清除旧 key
     */
    private fun migrateLegacyIfNeeded() {
        val legacyUsername = prefs.getString(LEGACY_KEY_USERNAME, null)
        val legacyPassword = prefs.getString(LEGACY_KEY_PASSWORD, null)
        val legacyBaseUrl = prefs.getString(LEGACY_KEY_BASE_URL, null)
        val legacyToken = prefs.getString(LEGACY_KEY_TOKEN, null)

        if (legacyUsername.isNullOrEmpty() &&
            legacyPassword.isNullOrEmpty() &&
            legacyBaseUrl.isNullOrEmpty()
        ) {
            return
        }

        val existing = getAccountsInternal()
        // 只有当账号列表为空时才迁移,避免覆盖已有数据
        if (existing.isEmpty() && !legacyUsername.isNullOrEmpty()) {
            val account = Account(
                alias = "默认账号",
                baseUrl = legacyBaseUrl.orEmpty(),
                username = legacyUsername,
                password = legacyPassword.orEmpty(),
                token = legacyToken.orEmpty()
            )
            saveAccountsInternal(listOf(account))
            saveCurrentAccountIdInternal(account.id)
        }

        // 清理旧 key
        prefs.edit()
            .remove(LEGACY_KEY_USERNAME)
            .remove(LEGACY_KEY_PASSWORD)
            .remove(LEGACY_KEY_BASE_URL)
            .remove(LEGACY_KEY_TOKEN)
            .apply()
    }

    // ---------------- 账号 CRUD ----------------

    fun getAccounts(): List<Account> = getAccountsInternal()

    fun getAccountById(id: String): Account? =
        getAccountsInternal().firstOrNull { it.id == id }

    fun getCurrentAccount(): Account? {
        val currentId = prefs.getString(KEY_CURRENT_ACCOUNT_ID, null) ?: return null
        return getAccountById(currentId)
    }

    fun setCurrentAccountId(id: String) {
        saveCurrentAccountIdInternal(id)
    }

    /**
     * 新增或更新账号:如果 id 存在则更新,否则新增。
     * 返回保存后的账号对象。
     */
    fun saveAccount(account: Account): Account {
        val accounts = getAccountsInternal().toMutableList()
        val index = accounts.indexOfFirst { it.id == account.id }
        if (index >= 0) {
            accounts[index] = account
        } else {
            accounts.add(account)
        }
        saveAccountsInternal(accounts)
        return account
    }

    /**
     * 登录成功后调用:把账号凭据与 token 一起写入,并设置为当前账号。
     * 若账号不存在(首次登录),则创建一个新账号(默认别名 = username)。
     */
    fun upsertAccountOnLogin(
        baseUrl: String,
        username: String,
        password: String,
        token: String,
        existingAccountId: String? = null
    ): Account {
        val accounts = getAccountsInternal().toMutableList()
        val target = if (existingAccountId != null) {
            accounts.indexOfFirst { it.id == existingAccountId }
                .takeIf { it >= 0 }
                ?.let { accounts[it] }
        } else {
            // 通过 baseUrl+username 唯一识别一个账号
            accounts.firstOrNull { it.baseUrl == baseUrl && it.username == username }
        }

        val account = if (target != null) {
            target.copy(
                baseUrl = baseUrl,
                username = username,
                password = password,
                token = token
            )
        } else {
            Account(
                alias = username,
                baseUrl = baseUrl,
                username = username,
                password = password,
                token = token
            )
        }
        saveAccount(account)
        saveCurrentAccountIdInternal(account.id)
        return account
    }

    fun updateAlias(accountId: String, alias: String) {
        val accounts = getAccountsInternal().toMutableList()
        val index = accounts.indexOfFirst { it.id == accountId }
        if (index >= 0) {
            accounts[index] = accounts[index].copy(alias = alias)
            saveAccountsInternal(accounts)
        }
    }

    /**
     * 登录成功后确保账号已分配环境标识色。
     *
     * - 账号已有颜色:直接返回,不做任何修改
     * - 账号未分配颜色:取上次分配下标的下一个颜色(循环递增)并记录下标,避免连续撞色
     *
     * @return 处理后的账号;账号不存在时返回 null
     */
    fun ensureAccountColor(accountId: String): Account? {
        val accounts = getAccountsInternal().toMutableList()
        val index = accounts.indexOfFirst { it.id == accountId }
        if (index < 0) return null

        val account = accounts[index]
        if (account.hasColor()) return account

        val nextIndex = AccountColor.nextIndex(getLastColorIndex())
        val color = AccountColor.fromIndex(nextIndex) ?: return account
        val updated = account.copy(colorName = color.name)
        accounts[index] = updated
        saveAccountsInternal(accounts)
        saveLastColorIndex(nextIndex)
        return updated
    }

    /** 上一次分配的环境标识色下标,尚未分配过时返回 [AccountColor.NO_COLOR_INDEX] */
    fun getLastColorIndex(): Int =
        prefs.getInt(KEY_LAST_COLOR_INDEX, AccountColor.NO_COLOR_INDEX)

    private fun saveLastColorIndex(index: Int) {
        prefs.edit().putInt(KEY_LAST_COLOR_INDEX, index).apply()
    }

    // ---------------- Agent 记忆(按账号隔离) ----------------

    /** 账号上一次使用的 agent id(每个账号各自记忆,未设置时返回 null) */
    fun getCurrentAgentId(accountId: String): String? =
        prefs.getString(agentIdKey(accountId), null)?.takeIf { it.isNotEmpty() }

    /** 记忆账号当前使用的 agent id,切换账号环境时恢复 */
    fun setCurrentAgentId(accountId: String, agentId: String) {
        prefs.edit().putString(agentIdKey(accountId), agentId).apply()
    }

    fun updateToken(accountId: String, token: String) {
        val accounts = getAccountsInternal().toMutableList()
        val index = accounts.indexOfFirst { it.id == accountId }
        if (index >= 0) {
            accounts[index] = accounts[index].copy(token = token)
            saveAccountsInternal(accounts)
        }
    }

    fun removeAccount(accountId: String) {
        val accounts = getAccountsInternal().toMutableList()
        accounts.removeAll { it.id == accountId }
        saveAccountsInternal(accounts)
        if (prefs.getString(KEY_CURRENT_ACCOUNT_ID, null) == accountId) {
            prefs.edit().remove(KEY_CURRENT_ACCOUNT_ID).apply()
        }
    }

    fun hasAnyAccount(): Boolean = getAccountsInternal().isNotEmpty()

    /**
     * 退出当前账号(只清空当前账号 id,不删除账号记录,方便下次快速登录)。
     */
    fun clearCurrentAccount() {
        prefs.edit().remove(KEY_CURRENT_ACCOUNT_ID).apply()
    }

    // ---------------- 兼容旧 API ----------------

    fun getToken(): String? = getCurrentAccount()?.token

    fun saveToken(token: String) {
        val current = getCurrentAccount() ?: return
        updateToken(current.id, token)
    }

    fun getBaseUrl(): String? {
        // 优先取当前账号的地址;若没有当前账号(新增账号场景),回退到 draft 地址
        return getCurrentAccount()?.baseUrl?.takeIf { it.isNotEmpty() }
            ?: getDraftBaseUrl()
    }

    fun saveBaseUrl(baseUrl: String) {
        val current = getCurrentAccount()
        if (current != null) {
            current.baseUrl = baseUrl
            saveAccount(current)
        } else {
            // 没有当前账号时,把 URL 暂存到 draft,登录成功后会被真正的账号覆盖
            saveDraftBaseUrl(baseUrl)
        }
    }

    // ---------------- Draft(草稿态)URL ----------------

    /** 获取草稿态 URL(在"新增账号"流程中暂存) */
    fun getDraftBaseUrl(): String? =
        prefs.getString(KEY_DRAFT_BASE_URL, null)?.takeIf { it.isNotEmpty() }

    /** 暂存草稿态 URL */
    fun saveDraftBaseUrl(baseUrl: String) {
        prefs.edit().putString(KEY_DRAFT_BASE_URL, baseUrl).apply()
    }

    /** 清除草稿态 URL(在登录成功或切换到已有账号时调用) */
    fun clearDraftBaseUrl() {
        prefs.edit().remove(KEY_DRAFT_BASE_URL).apply()
    }

    fun getUsername(): String? = getCurrentAccount()?.username

    fun getPassword(): String? = getCurrentAccount()?.password

    fun saveCredentials(username: String, password: String, baseUrl: String) {
        val token = getToken().orEmpty()
        upsertAccountOnLogin(baseUrl, username, password, token)
    }

    fun hasCredentials(): Boolean {
        val current = getCurrentAccount() ?: return false
        return current.baseUrl.isNotEmpty() &&
                current.username.isNotEmpty() &&
                current.password.isNotEmpty()
    }

    fun clearToken() {
        val current = getCurrentAccount() ?: return
        current.token = ""
        saveAccount(current)
    }

    fun clearCredentials() {
        clearCurrentAccount()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    // ---------------- 内部方法 ----------------

    private fun getAccountsInternal(): List<Account> {
        val json = prefs.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Account>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveAccountsInternal(accounts: List<Account>) {
        prefs.edit().putString(KEY_ACCOUNTS, gson.toJson(accounts)).apply()
    }

    private fun saveCurrentAccountIdInternal(id: String) {
        prefs.edit().putString(KEY_CURRENT_ACCOUNT_ID, id).apply()
    }
}
