package com.ccg.screenblocker.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 权限工具：
 * - 悬浮窗权限（特殊权限，必须跳转系统设置）
 * - 通知权限（API 33+）
 * - 小米/HyperOS 后台相关权限（无公开 API，仅提供手动检查清单）
 */
object PermissionHelper {

    fun canDrawOverlays(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /**
     * 创建跳转到悬浮窗权限设置的 Intent。
     * Android 11+ 行为：跳转系统设置顶层页，需在返回应用后再次校验。
     */
    fun createOverlayPermissionIntent(packageName: String): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun shouldRequestPostNotifications(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun canPostNotifications(context: Context): Boolean {
        return if (shouldRequestPostNotifications()) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * 判断是否为小米/Redmi/POCO 设备。
     * 用于决定是否展示厂商专属的手动权限清单。
     */
    fun isXiaomiFamilyDevice(): Boolean {
        val brand = Build.BRAND.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        return brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") ||
            manufacturer.contains("xiaomi")
    }

    /**
     * 小米/HyperOS 后台运行所需手动检查清单。
     * 这些权限没有稳定公开 API 检查，只能让用户对照清单手动开启。
     */
    fun getXiaomiManualChecklist(): List<String> = listOf(
        "在「应用详情」中开启「后台弹出界面」",
        "在「应用详情」中开启「自启动」",
        "在「应用详情」→「省电策略」选择「无限制」",
        "在最近任务卡片中长按本应用，选择「锁定」"
    )

    /**
     * 创建跳转到应用详情页的 Intent（通用）。
     */
    fun createAppSettingsIntent(packageName: String): Intent {
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * 检查指定的无障碍服务是否已在系统设置中启用。
     * 用于判断 trusted overlay 通道是否可用（屏蔽不被单手模式带飞的关键）。
     */
    fun isAccessibilityServiceEnabled(context: Context, serviceClassName: String): Boolean {
        val pkg = context.packageName
        val expected1 = "$pkg/$serviceClassName"
        val expected2 = "$pkg/.${serviceClassName.substringAfterLast('.')}"
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected1, true) || it.equals(expected2, true) }
    }

    /** 跳转到系统的"无障碍"设置页 */
    fun createAccessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
