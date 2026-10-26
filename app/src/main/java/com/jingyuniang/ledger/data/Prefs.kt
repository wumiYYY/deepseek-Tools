package com.jingyuniang.ledger.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.prefStore: DataStore<Preferences> by preferencesDataStore(name = "whale_settings")

/** 默认接口：DeepSeek 官方（OpenAI 兼容）。换服务商只改 Settings.apiBase。 */
const val DEFAULT_API_BASE = "https://api.deepseek.com"

data class Settings(
    val ledgerId: Long = 0,
    val aiEnabled: Boolean = true,
    val apiKey: String = "",
    val model: String = "deepseek-chat",
    /** 接口地址：任何 **OpenAI 兼容** 的 /chat/completions 端点都行（DeepSeek/Kimi/智谱/通义/本地 Ollama…） */
    val apiBase: String = DEFAULT_API_BASE,
    /** 高置信度自动入账 */
    val autoBook: Boolean = true,
    val autoBookThreshold: Int = 80,
    val captureNotify: Boolean = true,
    val captureSms: Boolean = true,
    val captureAccess: Boolean = true,
    val floatingBall: Boolean = false,
    val darkMode: Int = 0,
    val reminderEnabled: Boolean = true,
    val reminderHour: Int = 21,
    val budgetAlert: Boolean = true,
    val onboardingDone: Boolean = false,
    val lastBackup: Long = 0,
    val selectedTab: Int = 0,
    /** 通知栏常驻「记一笔」 */
    val quickNotify: Boolean = false,
    /** 自动记账总开关（关掉=完全不读通知/短信，省电） */
    val captureMaster: Boolean = true,
    /** 应用锁 4 位密码，空 = 未启用 */
    val lockPin: String = ""
)

class Prefs(private val ctx: Context) {

    private object K {
        val ledgerId = longPreferencesKey("ledger_id")
        val aiEnabled = booleanPreferencesKey("ai_enabled")
        val apiKey = stringPreferencesKey("api_key")
        val model = stringPreferencesKey("model")
        val apiBase = stringPreferencesKey("api_base")
        val autoBook = booleanPreferencesKey("auto_book")
        val threshold = intPreferencesKey("auto_book_threshold")
        val captureNotify = booleanPreferencesKey("capture_notify")
        val captureSms = booleanPreferencesKey("capture_sms")
        val captureAccess = booleanPreferencesKey("capture_access")
        val floatingBall = booleanPreferencesKey("floating_ball")
        val darkMode = intPreferencesKey("dark_mode")
        val reminder = booleanPreferencesKey("reminder_enabled")
        val reminderHour = intPreferencesKey("reminder_hour")
        val budgetAlert = booleanPreferencesKey("budget_alert")
        val onboarding = booleanPreferencesKey("onboarding_done")
        val lastBackup = longPreferencesKey("last_backup")
        val selectedTab = intPreferencesKey("selected_tab")
        val quickNotify = booleanPreferencesKey("quick_notify")
        val captureMaster = booleanPreferencesKey("capture_master")
        val lockPin = stringPreferencesKey("lock_pin")
    }

    val flow: Flow<Settings> = ctx.prefStore.data.map { p ->
        Settings(
            ledgerId = p[K.ledgerId] ?: 0,
            aiEnabled = p[K.aiEnabled] ?: true,
            apiKey = p[K.apiKey] ?: "",
            model = p[K.model] ?: "deepseek-chat",
            apiBase = p[K.apiBase] ?: DEFAULT_API_BASE,
            autoBook = p[K.autoBook] ?: true,
            autoBookThreshold = p[K.threshold] ?: 80,
            captureNotify = p[K.captureNotify] ?: true,
            captureSms = p[K.captureSms] ?: true,
            captureAccess = p[K.captureAccess] ?: true,
            floatingBall = p[K.floatingBall] ?: false,
            darkMode = p[K.darkMode] ?: 0,
            reminderEnabled = p[K.reminder] ?: true,
            reminderHour = p[K.reminderHour] ?: 21,
            budgetAlert = p[K.budgetAlert] ?: true,
            onboardingDone = p[K.onboarding] ?: false,
            lastBackup = p[K.lastBackup] ?: 0,
            selectedTab = p[K.selectedTab] ?: 0,
            quickNotify = p[K.quickNotify] ?: false,
            captureMaster = p[K.captureMaster] ?: true,
            lockPin = p[K.lockPin] ?: ""
        )
    }

    suspend fun setLedgerId(v: Long) = put(K.ledgerId, v)
    suspend fun setAiEnabled(v: Boolean) = put(K.aiEnabled, v)
    suspend fun setApiKey(v: String) = put(K.apiKey, v)
    suspend fun setModel(v: String) = put(K.model, v)
    suspend fun setApiBase(v: String) = put(K.apiBase, v)
    suspend fun setAutoBook(v: Boolean) = put(K.autoBook, v)
    suspend fun setThreshold(v: Int) = put(K.threshold, v)
    suspend fun setCaptureNotify(v: Boolean) = put(K.captureNotify, v)
    suspend fun setCaptureSms(v: Boolean) = put(K.captureSms, v)
    suspend fun setCaptureAccess(v: Boolean) = put(K.captureAccess, v)
    suspend fun setFloatingBall(v: Boolean) = put(K.floatingBall, v)
    suspend fun setDarkMode(v: Int) = put(K.darkMode, v)
    suspend fun setReminderEnabled(v: Boolean) = put(K.reminder, v)
    suspend fun setReminderHour(v: Int) = put(K.reminderHour, v)
    suspend fun setBudgetAlert(v: Boolean) = put(K.budgetAlert, v)
    suspend fun setOnboardingDone(v: Boolean) = put(K.onboarding, v)
    suspend fun setLastBackup(v: Long) = put(K.lastBackup, v)
    suspend fun setSelectedTab(v: Int) = put(K.selectedTab, v)
    suspend fun setQuickNotify(v: Boolean) = put(K.quickNotify, v)
    suspend fun setCaptureMaster(v: Boolean) = put(K.captureMaster, v)
    suspend fun setLockPin(v: String) = put(K.lockPin, v)

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        ctx.prefStore.edit { it[key] = value }
    }
}
