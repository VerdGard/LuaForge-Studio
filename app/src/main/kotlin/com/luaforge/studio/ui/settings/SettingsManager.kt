package com.luaforge.studio.ui.settings

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.getSystemService
import androidx.core.os.LocaleListCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.luaforge.studio.ui.theme.ThemeType
import com.luaforge.studio.utils.IconManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

// DataStore 实例
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

// 定义所有存储键
private object PreferencesKeys {
    val THEME_TYPE = stringPreferencesKey("theme_type")
    val DARK_MODE = stringPreferencesKey("dark_mode")
    val FONT_SIZE_SCALE = floatPreferencesKey("font_size_scale")
    val SHAPE_SIZE_INDEX = intPreferencesKey("shape_size_index")
    val FONT_FAMILY_TYPE = stringPreferencesKey("font_family_type")
    val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    val EDITOR_FONT_TYPE = stringPreferencesKey("editor_font_type")
    val CUSTOM_FONT_PATH = stringPreferencesKey("custom_font_path")
    val ENABLE_TAB_HISTORY = booleanPreferencesKey("enable_tab_history")
    val INDENT_GUIDE_ENABLED = booleanPreferencesKey("indentGuideEnabled")
    val PROJECT_STORAGE_PATH = stringPreferencesKey("project_storage_path")

    // 语法高亮颜色
    val CLASS_NAME_COLOR = intPreferencesKey("syntax_class_name_color")
    val LOCAL_VAR_COLOR = intPreferencesKey("syntax_local_var_color")
    val KEYWORD_COLOR = intPreferencesKey("syntax_keyword_color")
    val FUNCTION_NAME_COLOR = intPreferencesKey("syntax_function_color")
    val LITERAL_COLOR = intPreferencesKey("syntax_literal_color")
    val COMMENT_COLOR = intPreferencesKey("syntax_comment_color")
    val SELECTED_LINE_COLOR = intPreferencesKey("selected_line_color")

    val SELECTED_APP_ICON = stringPreferencesKey("selected_app_icon")

    // 补全大小写敏感设置项
    val COMPLETION_CASE_SENSITIVE = booleanPreferencesKey("completion_case_sensitive")

    // 排序方式和置顶项目列表
    val SORT_ORDER = stringPreferencesKey("sort_order")
    val PINNED_PROJECTS = stringPreferencesKey("pinned_projects")

    // 智能排序开关
    val SMART_SORTING_ENABLED = booleanPreferencesKey("smart_sorting_enabled")

    // Toast 位置
    val TOAST_POSITION = stringPreferencesKey("toast_position")
    // Toast 边框开关
    val TOAST_BORDER_ENABLED = booleanPreferencesKey("toast_border_enabled")

    val EDITOR_WORD_WRAP = booleanPreferencesKey("editor_word_wrap")
    // 三方控件支持:允许项目 libs/*.dex 里的自定义控件
    val THIRD_PARTY_WIDGET_SUPPORT = booleanPreferencesKey("third_party_widget_support")
    // 项目间自动换行独立:开=每项目独立换行状态;关=全局共享
    val EDITOR_WORD_WRAP_INDEPENDENT = booleanPreferencesKey("editor_word_wrap_independent")
    val EDITOR_WORD_WRAP_PROJECTS = stringPreferencesKey("editor_word_wrap_projects")

    // 语言设置（使用 DataStore，不再用 SharedPreferences）
    val LANGUAGE_TAG = stringPreferencesKey("language_tag")

    // 【新增】十六进制颜色高亮开关
    val HEX_COLOR_HIGHLIGHT_ENABLED = booleanPreferencesKey("hex_color_highlight_enabled")

    // 【新增】滑动手势开关
    val ENABLE_SWIPE_GESTURE = booleanPreferencesKey("enable_swipe_gesture")

    // 【新增】MCP 服务设置
    val MCP_ENABLED = booleanPreferencesKey("mcp_enabled")
    val MCP_PORT = intPreferencesKey("mcp_port")
    val MCP_REQUIRE_TOKEN = booleanPreferencesKey("mcp_require_token")
    val MCP_TOKEN = stringPreferencesKey("mcp_token")

    // 【新增】网络请求拦截设置
    val NETWORK_INTERCEPT_ENABLED = booleanPreferencesKey("network_intercept_enabled")
    val NETWORK_ALLOWED_HOSTS = stringPreferencesKey("network_allowed_hosts")
    val NETWORK_BLOCKED_HOSTS = stringPreferencesKey("network_blocked_hosts")

    // 【新增】防火墙（越级写入 + 自我守护）
    val CROSS_PROJECT_WRITE_GUARD = booleanPreferencesKey("cross_project_write_guard")
    val SELF_GUARD = booleanPreferencesKey("self_guard")
    val CROSS_WRITE_COUNTS = stringPreferencesKey("cross_write_counts")
    val SELF_GUARD_COUNTS = stringPreferencesKey("self_guard_counts")

    // 【新增】符号自动补全开关:底部符号栏点击左括号/引号时自动补全配对并居中光标
    val SYMBOL_AUTO_PAIR = booleanPreferencesKey("symbol_auto_pair")

    // 【新增】打包目标架构:ARM32 / ARM64 / UNIVERSAL
    val ABI_TARGET = stringPreferencesKey("abi_target")
}

/** 防火墙拦截类型（分计）。 */
enum class FirewallKind { CROSS_WRITE, SELF_GUARD }

// 排序方式枚举
enum class SortOrder {
    NAME_ASC,           // 名称 A-Z
    NAME_DESC,          // 名称 Z-A
    DATE_MODIFIED_NEWEST, // 修改时间 最新
    DATE_MODIFIED_OLDEST  // 修改时间 最早
}

// Toast 位置枚举
enum class ToastPosition {
    TOP, BOTTOM
}

/**
 * 打包目标架构。
 * - [UNIVERSAL] 通用版:同时包含 armeabi-v7a 与 arm64-v8a,兼容所有设备(体积最大)
 * - [ARM64]     仅 64 位:只保留 arm64-v8a
 * - [ARM32]     仅 32 位:只保留 armeabi-v7a
 */
enum class AbiTarget {
    UNIVERSAL, ARM64, ARM32
}

object SettingsManager {

    // 当前设置状态
    var currentSettings by mutableStateOf(SettingsData())

    // 设置变化监听器列表
    private val listeners = mutableListOf<(SettingsData) -> Unit>()

    /**
     * 获取固定项目存储路径（外部存储根目录）
     */
    private fun getFixedProjectStoragePath(): String {
        val baseDir = Environment.getExternalStorageDirectory()
        return File(baseDir, "LuaForge-Studio/project").absolutePath
    }

    // 注册设置变化监听器
    fun addListener(listener: (SettingsData) -> Unit) {
        listeners.add(listener)
    }

    // 移除设置变化监听器
    fun removeListener(listener: (SettingsData) -> Unit) {
        listeners.remove(listener)
    }

    // 更新设置并通知所有监听器
    fun updateSettings(newSettings: SettingsData) {
        currentSettings = newSettings
        notifyListeners()
    }

    // 通知所有监听器
    private fun notifyListeners() {
        listeners.forEach { listener ->
            listener(currentSettings)
        }
    }

    // 从 DataStore 异步加载设置
    suspend fun loadSavedSettings(context: Context) {
        val preferences = context.dataStore.data.first()

        val themeType = ThemeType.valueOf(
            preferences[PreferencesKeys.THEME_TYPE] ?: "GREEN"
        )
        val darkMode = DarkMode.valueOf(
            preferences[PreferencesKeys.DARK_MODE] ?: "FOLLOW_SYSTEM"
        )
        val fontSizeScale = preferences[PreferencesKeys.FONT_SIZE_SCALE] ?: 1.0f
        val shapeSizeIndex = preferences[PreferencesKeys.SHAPE_SIZE_INDEX] ?: 2
        val fontFamilyType = FontFamilyType.valueOf(
            preferences[PreferencesKeys.FONT_FAMILY_TYPE] ?: "DEFAULT"
        )
        val dynamicColor = preferences[PreferencesKeys.DYNAMIC_COLOR] ?: false
        val editorFontType = EditorFontType.valueOf(
            preferences[PreferencesKeys.EDITOR_FONT_TYPE] ?: "JETBRAINS_MONO"
        )
        val customFontPath = preferences[PreferencesKeys.CUSTOM_FONT_PATH] ?: ""
        val enableTabHistory = preferences[PreferencesKeys.ENABLE_TAB_HISTORY] ?: false
        val indentGuideEnabled = preferences[PreferencesKeys.INDENT_GUIDE_ENABLED] ?: true

        val fixedPath = getFixedProjectStoragePath()

        val classNameColor = preferences[PreferencesKeys.CLASS_NAME_COLOR] ?: 0xFF6E81D9.toInt()
        val localVariableColor = preferences[PreferencesKeys.LOCAL_VAR_COLOR] ?: 0xFFAAAA88.toInt()
        val keywordColor = preferences[PreferencesKeys.KEYWORD_COLOR] ?: 0xFFFF565E.toInt()
        val functionNameColor =
            preferences[PreferencesKeys.FUNCTION_NAME_COLOR] ?: 0xFF2196F3.toInt()
        val literalColor = preferences[PreferencesKeys.LITERAL_COLOR] ?: 0xFF008080.toInt()
        val commentColor = preferences[PreferencesKeys.COMMENT_COLOR] ?: 0xFFA7A8A8.toInt()
        val selectedLineColor =
            preferences[PreferencesKeys.SELECTED_LINE_COLOR] ?: 0x33000000

        // 加载补全大小写敏感设置项
        val completionCaseSensitive =
            preferences[PreferencesKeys.COMPLETION_CASE_SENSITIVE] ?: false

        val selectedAppIconName = preferences[PreferencesKeys.SELECTED_APP_ICON] ?: "PLAY_STORE"
        val selectedAppIcon = try {
            IconManager.AppIcon.valueOf(selectedAppIconName)
        } catch (_: Exception) {
            IconManager.AppIcon.PLAY_STORE
        }

        // 加载排序方式
        val sortOrderName = preferences[PreferencesKeys.SORT_ORDER] ?: "NAME_ASC"
        val sortOrder = try {
            SortOrder.valueOf(sortOrderName)
        } catch (_: Exception) {
            SortOrder.NAME_ASC
        }

        // 加载置顶项目列表（存储为 JSON 字符串）
        val pinnedProjectsJson = preferences[PreferencesKeys.PINNED_PROJECTS] ?: "[]"
        val pinnedProjects: Set<String> = try {
            val type = object : TypeToken<Set<String>>() {}.type
            Gson().fromJson(pinnedProjectsJson, type)
        } catch (_: Exception) {
            emptySet()
        }

        // 加载智能排序开关
        val smartSortingEnabled = preferences[PreferencesKeys.SMART_SORTING_ENABLED] ?: false

        // 加载 Toast 位置
        val toastPositionName = preferences[PreferencesKeys.TOAST_POSITION] ?: "BOTTOM"
        val toastPosition = try {
            ToastPosition.valueOf(toastPositionName)
        } catch (_: Exception) {
            ToastPosition.BOTTOM
        }

        // 加载 Toast 边框开关
        val toastBorderEnabled = preferences[PreferencesKeys.TOAST_BORDER_ENABLED] ?: false

        val editorWordWrap = preferences[PreferencesKeys.EDITOR_WORD_WRAP] ?: false
        val thirdPartyWidgetSupport = preferences[PreferencesKeys.THIRD_PARTY_WIDGET_SUPPORT] ?: true
        val perProjectWordWrap = preferences[PreferencesKeys.EDITOR_WORD_WRAP_INDEPENDENT] ?: true
        val editorWordWrapByProject: Map<String, Boolean> = try {
            val type = object : TypeToken<Map<String, Boolean>>() {}.type
            Gson().fromJson(preferences[PreferencesKeys.EDITOR_WORD_WRAP_PROJECTS] ?: "{}", type)
                ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }

        // 从 DataStore 加载语言设置（未设置时跟随系统，避免强制切换语言）
        val languageTag = preferences[PreferencesKeys.LANGUAGE_TAG] ?: ""

        // 【新增】加载十六进制颜色高亮开关
        val hexColorHighlightEnabled = preferences[PreferencesKeys.HEX_COLOR_HIGHLIGHT_ENABLED] ?: false

        // 【新增】加载滑动手势开关
        val enableSwipeGesture = preferences[PreferencesKeys.ENABLE_SWIPE_GESTURE] ?: false

        // 【新增】加载 MCP 服务设置
        val mcpEnabled = preferences[PreferencesKeys.MCP_ENABLED] ?: false
        val mcpPort = preferences[PreferencesKeys.MCP_PORT] ?: 8787
        val mcpRequireToken = preferences[PreferencesKeys.MCP_REQUIRE_TOKEN] ?: false
        val mcpToken = preferences[PreferencesKeys.MCP_TOKEN] ?: ""

        // 【新增】加载网络请求拦截设置
        val networkInterceptEnabled = preferences[PreferencesKeys.NETWORK_INTERCEPT_ENABLED] ?: false
        val networkAllowedHosts: Set<String> = try {
            val type = object : TypeToken<Set<String>>() {}.type
            Gson().fromJson(preferences[PreferencesKeys.NETWORK_ALLOWED_HOSTS] ?: "[]", type) ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
        val networkBlockedHosts: Set<String> = try {
            val type = object : TypeToken<Set<String>>() {}.type
            Gson().fromJson(preferences[PreferencesKeys.NETWORK_BLOCKED_HOSTS] ?: "[]", type) ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }

        // 【新增】加载防火墙设置
        val crossProjectWriteGuard = preferences[PreferencesKeys.CROSS_PROJECT_WRITE_GUARD] ?: true
        val selfGuard = preferences[PreferencesKeys.SELF_GUARD] ?: true
        val crossWriteCounts: Map<String, Int> = try {
            val type = object : TypeToken<Map<String, Int>>() {}.type
            Gson().fromJson(preferences[PreferencesKeys.CROSS_WRITE_COUNTS] ?: "{}", type) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
        val selfGuardCounts: Map<String, Int> = try {
            val type = object : TypeToken<Map<String, Int>>() {}.type
            Gson().fromJson(preferences[PreferencesKeys.SELF_GUARD_COUNTS] ?: "{}", type) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }

        // 【新增】符号自动补全(默认开启)
        val symbolAutoPair = preferences[PreferencesKeys.SYMBOL_AUTO_PAIR] ?: true

        // 【新增】打包目标架构(默认通用版)
        val abiTargetName = preferences[PreferencesKeys.ABI_TARGET] ?: "UNIVERSAL"
        val abiTarget = try {
            AbiTarget.valueOf(abiTargetName)
        } catch (_: Exception) {
            AbiTarget.UNIVERSAL
        }

        updateSettings(
            SettingsData(
                themeType = themeType,
                darkMode = darkMode,
                projectStoragePath = fixedPath,
                fontSizeScale = fontSizeScale,
                shapeSizeIndex = shapeSizeIndex,
                fontFamilyType = fontFamilyType,
                dynamicColor = dynamicColor,
                editorFontType = editorFontType,
                customFontPath = customFontPath,
                enableTabHistory = enableTabHistory,
                classNameColor = Color(classNameColor),
                localVariableColor = Color(localVariableColor),
                keywordColor = Color(keywordColor),
                functionNameColor = Color(functionNameColor),
                literalColor = Color(literalColor),
                commentColor = Color(commentColor),
                selectedLineColor = Color(selectedLineColor),
                indentGuideEnabled = indentGuideEnabled,
                selectedAppIcon = selectedAppIcon,
                completionCaseSensitive = completionCaseSensitive,
                sortOrder = sortOrder,
                pinnedProjects = pinnedProjects,
                smartSortingEnabled = smartSortingEnabled,
                toastPosition = toastPosition,
                toastBorderEnabled = toastBorderEnabled,
                editorWordWrap = editorWordWrap,
                thirdPartyWidgetSupport = thirdPartyWidgetSupport,
                perProjectWordWrap = perProjectWordWrap,
                editorWordWrapByProject = editorWordWrapByProject,
                languageTag = languageTag,
                hexColorHighlightEnabled = hexColorHighlightEnabled,
                enableSwipeGesture = enableSwipeGesture,  // 【新增】
                mcpEnabled = mcpEnabled,                  // 【新增】
                mcpPort = mcpPort,                        // 【新增】
                mcpRequireToken = mcpRequireToken,        // 【新增】
                mcpToken = mcpToken,                      // 【新增】
                networkInterceptEnabled = networkInterceptEnabled,  // 【新增】
                networkAllowedHosts = networkAllowedHosts,          // 【新增】
                networkBlockedHosts = networkBlockedHosts,          // 【新增】
                crossProjectWriteGuard = crossProjectWriteGuard,    // 【新增】
                selfGuard = selfGuard,                              // 【新增】
                crossWriteCounts = crossWriteCounts,                // 【新增】
                selfGuardCounts = selfGuardCounts,                  // 【新增】
                symbolAutoPair = symbolAutoPair,                    // 【新增】
                abiTarget = abiTarget                              // 【新增】
            )
        )
    }

    // 异步保存设置到 DataStore
    suspend fun saveSettingsAsync(context: Context) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.THEME_TYPE] = currentSettings.themeType.name
            preferences[PreferencesKeys.DARK_MODE] = currentSettings.darkMode.name
            preferences[PreferencesKeys.FONT_SIZE_SCALE] = currentSettings.fontSizeScale
            preferences[PreferencesKeys.SHAPE_SIZE_INDEX] = currentSettings.shapeSizeIndex
            preferences[PreferencesKeys.FONT_FAMILY_TYPE] = currentSettings.fontFamilyType.name
            preferences[PreferencesKeys.DYNAMIC_COLOR] = currentSettings.dynamicColor
            preferences[PreferencesKeys.EDITOR_FONT_TYPE] = currentSettings.editorFontType.name
            preferences[PreferencesKeys.CUSTOM_FONT_PATH] = currentSettings.customFontPath
            preferences[PreferencesKeys.ENABLE_TAB_HISTORY] = currentSettings.enableTabHistory
            preferences[PreferencesKeys.INDENT_GUIDE_ENABLED] = currentSettings.indentGuideEnabled
            preferences[PreferencesKeys.PROJECT_STORAGE_PATH] = currentSettings.projectStoragePath

            preferences[PreferencesKeys.CLASS_NAME_COLOR] = currentSettings.classNameColor.toArgb()
            preferences[PreferencesKeys.LOCAL_VAR_COLOR] =
                currentSettings.localVariableColor.toArgb()
            preferences[PreferencesKeys.KEYWORD_COLOR] = currentSettings.keywordColor.toArgb()
            preferences[PreferencesKeys.FUNCTION_NAME_COLOR] =
                currentSettings.functionNameColor.toArgb()
            preferences[PreferencesKeys.LITERAL_COLOR] = currentSettings.literalColor.toArgb()
            preferences[PreferencesKeys.COMMENT_COLOR] = currentSettings.commentColor.toArgb()
            preferences[PreferencesKeys.SELECTED_LINE_COLOR] =
                currentSettings.selectedLineColor.toArgb()

            preferences[PreferencesKeys.COMPLETION_CASE_SENSITIVE] =
                currentSettings.completionCaseSensitive

            preferences[PreferencesKeys.SELECTED_APP_ICON] = currentSettings.selectedAppIcon.name

            preferences[PreferencesKeys.SORT_ORDER] = currentSettings.sortOrder.name

            val pinnedJson = Gson().toJson(currentSettings.pinnedProjects)
            preferences[PreferencesKeys.PINNED_PROJECTS] = pinnedJson

            preferences[PreferencesKeys.SMART_SORTING_ENABLED] = currentSettings.smartSortingEnabled

            preferences[PreferencesKeys.TOAST_POSITION] = currentSettings.toastPosition.name

            preferences[PreferencesKeys.TOAST_BORDER_ENABLED] = currentSettings.toastBorderEnabled

            preferences[PreferencesKeys.EDITOR_WORD_WRAP] = currentSettings.editorWordWrap
            preferences[PreferencesKeys.THIRD_PARTY_WIDGET_SUPPORT] = currentSettings.thirdPartyWidgetSupport
            preferences[PreferencesKeys.EDITOR_WORD_WRAP_INDEPENDENT] = currentSettings.perProjectWordWrap
            preferences[PreferencesKeys.EDITOR_WORD_WRAP_PROJECTS] =
                Gson().toJson(currentSettings.editorWordWrapByProject)

            // 保存语言设置到 DataStore
            preferences[PreferencesKeys.LANGUAGE_TAG] = currentSettings.languageTag

            // 【新增】保存十六进制颜色高亮开关
            preferences[PreferencesKeys.HEX_COLOR_HIGHLIGHT_ENABLED] = currentSettings.hexColorHighlightEnabled

            // 【新增】保存滑动手势开关
            preferences[PreferencesKeys.ENABLE_SWIPE_GESTURE] = currentSettings.enableSwipeGesture
       

            // 【新增】保存 MCP 服务设置
            preferences[PreferencesKeys.MCP_ENABLED] = currentSettings.mcpEnabled
            preferences[PreferencesKeys.MCP_PORT] = currentSettings.mcpPort
            preferences[PreferencesKeys.MCP_REQUIRE_TOKEN] = currentSettings.mcpRequireToken
            preferences[PreferencesKeys.MCP_TOKEN] = currentSettings.mcpToken

            // 【新增】保存网络请求拦截设置
            preferences[PreferencesKeys.NETWORK_INTERCEPT_ENABLED] = currentSettings.networkInterceptEnabled
            preferences[PreferencesKeys.NETWORK_ALLOWED_HOSTS] = Gson().toJson(currentSettings.networkAllowedHosts)
            preferences[PreferencesKeys.NETWORK_BLOCKED_HOSTS] = Gson().toJson(currentSettings.networkBlockedHosts)

            // 【新增】保存防火墙设置
            preferences[PreferencesKeys.CROSS_PROJECT_WRITE_GUARD] = currentSettings.crossProjectWriteGuard
            preferences[PreferencesKeys.SELF_GUARD] = currentSettings.selfGuard
            preferences[PreferencesKeys.CROSS_WRITE_COUNTS] = Gson().toJson(currentSettings.crossWriteCounts)
            preferences[PreferencesKeys.SELF_GUARD_COUNTS] = Gson().toJson(currentSettings.selfGuardCounts)

            // 【新增】保存符号自动补全与打包目标架构
            preferences[PreferencesKeys.SYMBOL_AUTO_PAIR] = currentSettings.symbolAutoPair
            preferences[PreferencesKeys.ABI_TARGET] = currentSettings.abiTarget.name
        }
        notifyListeners()
    }

    // 保存设置（在后台协程中执行）
    fun saveSettings(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            saveSettingsAsync(context)
        }
    }

    /** 记录一次防火墙拦截（按项目名分计），立即更新内存态并异步持久化。 */
    fun recordFirewallGuard(kind: FirewallKind, projectName: String, context: Context) {
        currentSettings = when (kind) {
            FirewallKind.CROSS_WRITE -> {
                val m = currentSettings.crossWriteCounts.toMutableMap()
                m[projectName] = (m[projectName] ?: 0) + 1
                currentSettings.copy(crossWriteCounts = m)
            }
            FirewallKind.SELF_GUARD -> {
                val m = currentSettings.selfGuardCounts.toMutableMap()
                m[projectName] = (m[projectName] ?: 0) + 1
                currentSettings.copy(selfGuardCounts = m)
            }
        }
        notifyListeners()
        saveSettings(context)
    }

    /** 防火墙拦截全局累计（两开关合计）。 */
    fun firewallGuardTotal(): Int =
        currentSettings.crossWriteCounts.values.sum() + currentSettings.selfGuardCounts.values.sum()

    /** 每项目换行状态的规范化键(去掉结尾分隔符,避免同一路径出现两种写法)。 */
    private fun normalizeProjectPath(projectPath: String): String =
        projectPath.trimEnd('/', '\\')

    /**
     * 读取某项目应使用的自动换行状态。
     * 独立开关关闭或未指定项目时 → 全局 editorWordWrap;
     * 否则取项目级记录,缺失时回退全局值。
     */
    fun getEditorWordWrap(projectPath: String?): Boolean {
        val global = currentSettings.editorWordWrap
        if (!currentSettings.perProjectWordWrap || projectPath.isNullOrBlank()) return global
        return currentSettings.editorWordWrapByProject[normalizeProjectPath(projectPath)] ?: global
    }

    /** 写入某项目的自动换行状态:独立开关开启且路径有效 → 项目级;否则写全局。 */
    fun setEditorWordWrap(context: Context, projectPath: String?, value: Boolean) {
        if (currentSettings.perProjectWordWrap && !projectPath.isNullOrBlank()) {
            val key = normalizeProjectPath(projectPath)
            updateSettings(
                currentSettings.copy(
                    editorWordWrapByProject = currentSettings.editorWordWrapByProject + (key to value)
                )
            )
        } else {
            updateSettings(currentSettings.copy(editorWordWrap = value))
        }
        saveSettings(context)
    }

    /**
     * 确保项目目录存在
     */
    fun ensureProjectDirectoryExists(): Boolean {
        val projectDir = File(currentSettings.projectStoragePath)
        return try {
            if (!projectDir.exists()) {
                val created = projectDir.mkdirs()
                if (!created) {
                    try {
                        Runtime.getRuntime().exec(arrayOf("mkdir", "-p", projectDir.absolutePath))
                        Thread.sleep(200)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            projectDir.exists() && projectDir.canWrite()
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 生成一个随机的 MCP 访问令牌
     */
    fun generateMcpToken(): String =
        java.util.UUID.randomUUID().toString().replace("-", "").take(24)

    /**
     * 设置应用语言（兼容 Android 13+ 和旧版本）
     * 会自动重启 Activity 使语言生效
     */
    fun setAppLanguage(context: Context, languageTag: String) {
        // 更新内存中的设置
        val newSettings = currentSettings.copy(languageTag = languageTag)
        updateSettings(newSettings)

        // 异步保存到 DataStore
        CoroutineScope(Dispatchers.IO).launch {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.LANGUAGE_TAG] = languageTag
            }
        }

        // 设置系统语言
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService<LocaleManager>()
            localeManager?.applicationLocales = LocaleList.forLanguageTags(languageTag)
        } else {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag))
        }

    }

    /**
     * 同步加载语言设置（用于启动时）
     * 从 DataStore 读取，如果失败返回默认值
     */
    fun loadLanguageSync(context: Context): String {
        return try {
            // 尝试从 DataStore 同步读取（使用 runBlocking 或直接访问）
            // 但由于 DataStore 是异步的，这里用 currentSettings 作为回退
            currentSettings.languageTag
        } catch (_: Exception) {
            "zh"
        }
    }
}

data class SettingsData(
    val themeType: ThemeType = ThemeType.GREEN,
    val darkMode: DarkMode = DarkMode.FOLLOW_SYSTEM,
    val projectStoragePath: String = "/storage/emulated/0/LuaForge-Studio/project/",
    val fontSizeScale: Float = 1.0f,
    val shapeSizeIndex: Int = 2,
    val fontFamilyType: FontFamilyType = FontFamilyType.DEFAULT,
    val dynamicColor: Boolean = false,
    val editorFontType: EditorFontType = EditorFontType.JETBRAINS_MONO,
    val customFontPath: String = "",
    val enableTabHistory: Boolean = false,
    val classNameColor: Color = Color(0xFF6E81D9),
    val localVariableColor: Color = Color(0xFFAAAA88),
    val keywordColor: Color = Color(0xFFFF565E),
    val functionNameColor: Color = Color(0xFF2196F3),
    val literalColor: Color = Color(0xFF008080),
    val commentColor: Color = Color(0xFFA7A8A8),
    val selectedLineColor: Color = Color(0x1A000000),
    val indentGuideEnabled: Boolean = true,
    val selectedAppIcon: IconManager.AppIcon = IconManager.AppIcon.PLAY_STORE,
    val completionCaseSensitive: Boolean = false,
    val sortOrder: SortOrder = SortOrder.NAME_ASC,
    val pinnedProjects: Set<String> = emptySet(),
    val smartSortingEnabled: Boolean = false,
    val toastPosition: ToastPosition = ToastPosition.BOTTOM,
    val toastBorderEnabled: Boolean = false,
    val editorWordWrap: Boolean = false,
    /** 三方控件支持:默认开启 */
    val thirdPartyWidgetSupport: Boolean = true,
    /** 项目间自动换行独立:默认开启(每项目独立换行状态) */
    val perProjectWordWrap: Boolean = true,
    /** 项目路径 → 该项目的自动换行状态(仅当 perProjectWordWrap 为 true 时生效) */
    val editorWordWrapByProject: Map<String, Boolean> = emptyMap(),
    val languageTag: String = "zh",
    val hexColorHighlightEnabled: Boolean = false,  // 【新增】十六进制颜色高亮开关
    val enableSwipeGesture: Boolean = false,         // 【新增】滑动手势开关
    val mcpEnabled: Boolean = false,                 // 【新增】MCP 服务开关
    val mcpPort: Int = 8787,                         // 【新增】MCP 服务端口
    val mcpRequireToken: Boolean = false,            // 【新增】MCP 是否要求令牌
    val mcpToken: String = "",                       // 【新增】MCP 访问令牌
    val networkInterceptEnabled: Boolean = false,    // 【新增】网络请求拦截开关(默认关闭)
    val networkAllowedHosts: Set<String> = emptySet(), // 【新增】已允许的请求主机
    val networkBlockedHosts: Set<String> = emptySet(), // 【新增】已拒绝的请求主机
    /** 防火墙：越级写入拦截（默认开启） */
    val crossProjectWriteGuard: Boolean = true,
    /** 防火墙：自我守护（默认开启） */
    val selfGuard: Boolean = true,
    /** 项目名 → 越级写入拦截次数 */
    val crossWriteCounts: Map<String, Int> = emptyMap(),
    /** 项目名 → 自我守护拦截次数 */
    val selfGuardCounts: Map<String, Int> = emptyMap(),
    /** 符号自动补全:底部符号栏点击左括号或引号时自动补全配对并居中光标 */
    val symbolAutoPair: Boolean = true,
    /** 打包目标架构(默认通用版) */
    val abiTarget: AbiTarget = AbiTarget.UNIVERSAL,
)