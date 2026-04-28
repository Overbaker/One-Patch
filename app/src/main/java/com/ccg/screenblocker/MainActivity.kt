package com.ccg.screenblocker

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.ccg.screenblocker.data.GestureRepository
import com.google.android.material.snackbar.Snackbar
import com.ccg.screenblocker.data.SharedPrefsBlockAreaRepository
import com.ccg.screenblocker.databinding.ActivityMainBinding
import com.ccg.screenblocker.model.BlockArea
import com.ccg.screenblocker.service.BlockerAccessibilityService
import com.ccg.screenblocker.service.OverlayService
import com.ccg.screenblocker.util.DisplayHelper
import com.ccg.screenblocker.util.PermissionHelper

/**
 * 主入口 Activity：
 * - 显示当前已保存的屏蔽区域坐标
 * - 「编辑屏蔽区域」按钮跳转 FullscreenEditorActivity 在真实屏幕上拖拽
 * - 「启用/暂停屏蔽」直接控制 Service
 * - 运行态可见性开关
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val repository by lazy { SharedPrefsBlockAreaRepository(this) }
    private val gestureRepository by lazy { GestureRepository(this) }

    private var displayWidthPx: Int = 0
    private var displayHeightPx: Int = 0
    private var currentArea: BlockArea? = null
    private var isServiceRunning: Boolean = false
    private var serviceBinder: OverlayService.LocalBinder? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? OverlayService.LocalBinder ?: return
            serviceBinder = binder
            isServiceRunning = binder.isRunning()
            binder.setStateListener(object : OverlayService.StateListener {
                override fun onServiceStateChanged(running: Boolean) {
                    runOnUiThread {
                        isServiceRunning = running
                        renderState()
                    }
                }
            })
            renderState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBinder = null
            isServiceRunning = false
            renderState()
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    private val fullscreenEditorLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // 编辑器返回后刷新坐标显示
            loadCurrentArea()
            renderState()
        }

    private val recordGestureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            refreshGestureStatus()
            if (result.resultCode == RESULT_OK) {
                Snackbar.make(binding.root, R.string.hint_gesture_replaced_undo, Snackbar.LENGTH_LONG)
                    .setAction(R.string.hint_gesture_test) {
                        BlockerAccessibilityService.get()?.triggerOneHandedGesture()
                            ?: Toast.makeText(
                                this,
                                R.string.fab_long_press_a11y_required,
                                Toast.LENGTH_SHORT
                            ).show()
                    }
                    .show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val (w, h) = DisplayHelper.getRealDisplaySizePx(this)
        displayWidthPx = w
        displayHeightPx = h

        setupOperationPanel()
        loadCurrentArea()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, OverlayService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onResume() {
        super.onResume()
        loadCurrentArea()
        renderState()
        renderA11yStatus()
        refreshGestureStatus()
    }

    override fun onStop() {
        super.onStop()
        runCatching {
            serviceBinder?.setStateListener(null)
            unbindService(serviceConnection)
        }
        serviceBinder = null
    }

    private fun setupOperationPanel() {
        binding.btnEditFullscreen.setOnClickListener {
            if (!PermissionHelper.canDrawOverlays(this)) {
                Toast.makeText(this, R.string.toast_overlay_perm_required, Toast.LENGTH_SHORT)
                    .show()
                showPermissionGuide()
                return@setOnClickListener
            }
            fullscreenEditorLauncher.launch(
                Intent(this, FullscreenEditorActivity::class.java)
            )
        }

        binding.btnToggle.setOnClickListener {
            if (!PermissionHelper.canDrawOverlays(this)) {
                Toast.makeText(this, R.string.toast_overlay_perm_required, Toast.LENGTH_SHORT)
                    .show()
                showPermissionGuide()
                return@setOnClickListener
            }
            if (isServiceRunning) sendStopCommand() else sendEnableCommand()
        }

        binding.btnReset.setOnClickListener {
            val defaultArea = BlockArea.default(displayWidthPx, displayHeightPx)
            repository.save(defaultArea)
            currentArea = defaultArea
            renderCoordinates()
            Toast.makeText(this, R.string.toast_reset_done, Toast.LENGTH_SHORT).show()
        }

        val settingsPrefs = getSharedPreferences("settings_prefs", MODE_PRIVATE)
        binding.switchRuntimeVisible.isChecked =
            settingsPrefs.getBoolean("runtime_visible", true)
        binding.switchRuntimeVisible.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.edit().putBoolean("runtime_visible", isChecked).apply()
            // 双路径：binder 直调（已 bind 时立即生效）+ Intent 兜底（binding 未就绪时仍能送达）
            serviceBinder?.setRuntimeVisible(isChecked)
            startService(Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_RUNTIME_VISIBLE_SET
                putExtra(OverlayService.EXTRA_VISIBLE, isChecked)
            })
        }

        binding.btnOpenA11ySettings.setOnClickListener {
            runCatching {
                startActivity(PermissionHelper.createAccessibilitySettingsIntent())
                Toast.makeText(this, R.string.perm_a11y_grant, Toast.LENGTH_LONG).show()
            }
        }

        binding.switchAntiTransform.isChecked =
            settingsPrefs.getBoolean("auto_bypass_displaced", true)
        binding.switchAntiTransform.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.edit().putBoolean("auto_bypass_displaced", isChecked).apply()
        }

        binding.btnRecordGesture.setOnClickListener { handleRecordGestureClick() }

        binding.switchFab.isChecked = settingsPrefs.getBoolean("fab_visible", true)
        binding.switchFab.setOnCheckedChangeListener { _, isChecked ->
            // 单一 writer：只在此处写 fab_visible
            settingsPrefs.edit().putBoolean("fab_visible", isChecked).apply()
            if (isServiceRunning) {
                startService(Intent(this, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_FAB_SET_VISIBLE
                    putExtra(OverlayService.EXTRA_VISIBLE, isChecked)
                })
            } else {
                Snackbar.make(
                    binding.root,
                    R.string.hint_fab_appears_when_running,
                    Snackbar.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun renderA11yStatus() {
        val on = PermissionHelper.isAccessibilityServiceEnabled(
            this,
            BlockerAccessibilityService::class.java.name
        )
        binding.tvA11yStatus.setText(
            if (on) R.string.perm_a11y_status_on else R.string.perm_a11y_status_off
        )
        binding.tvA11yStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (on) R.color.status_active else R.color.status_warning
            )
        )
    }

    private fun loadCurrentArea() {
        val area = repository.loadOrDefault(displayWidthPx, displayHeightPx)
            .rescaleIfNeeded(displayWidthPx, displayHeightPx)
        currentArea = area
        renderCoordinates()
    }

    private fun renderCoordinates() {
        val area = currentArea ?: return
        binding.tvCoordinates.text = getString(
            R.string.coordinates_format,
            area.leftPx, area.topPx, area.widthPx, area.heightPx
        )
    }

    private fun renderState() {
        if (!PermissionHelper.canDrawOverlays(this)) {
            showPermissionGuide()
            binding.btnToggle.isEnabled = true
            binding.btnToggle.setText(R.string.action_enable)
            binding.tvStatus.setText(R.string.perm_overlay_desc)
            binding.tvChannelStatus.setText(R.string.channel_status_idle)
            return
        }
        hidePermissionGuide()
        if (isServiceRunning) {
            binding.btnToggle.setText(R.string.action_pause)
            val statusRes = when {
                serviceBinder?.isManualBypass() == true -> R.string.status_paused_manual
                serviceBinder?.isAutoBypass() == true -> R.string.status_paused_auto
                else -> R.string.status_enabled
            }
            binding.tvStatus.setText(statusRes)
            binding.btnToggle.setIconResource(R.drawable.ic_stop)
            // 显示当前真实通道（关键诊断信息）
            val channelRes = when (serviceBinder?.getActiveBackend()) {
                "PHYSICAL_DISPLAY" -> R.string.channel_status_physical_display
                "ACCESSIBILITY" -> R.string.channel_status_a11y
                else -> R.string.channel_status_app
            }
            binding.tvChannelStatus.setText(channelRes)
        } else {
            binding.btnToggle.setText(R.string.action_enable)
            binding.tvStatus.setText(R.string.status_editing_fullscreen)
            binding.btnToggle.setIconResource(R.drawable.ic_play)
            binding.tvChannelStatus.setText(R.string.channel_status_idle)
        }
    }

    private fun sendEnableCommand() {
        Toast.makeText(this, R.string.toast_starting, Toast.LENGTH_SHORT).show()
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_ENABLE
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun sendStopCommand() {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP
        }
        startService(intent)
        Toast.makeText(this, R.string.toast_stopped, Toast.LENGTH_SHORT).show()
    }

    private fun showPermissionGuide() {
        val rootView = binding.permissionGuide.root
        if (rootView.visibility != View.VISIBLE) rootView.visibility = View.VISIBLE
        bindPermissionGuideActions()
    }

    private fun hidePermissionGuide() {
        binding.permissionGuide.root.visibility = View.GONE
    }

    private fun handleRecordGestureClick() {
        if (gestureRepository.has()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_rerecord_title)
                .setMessage(R.string.dialog_rerecord_message)
                .setPositiveButton(R.string.dialog_rerecord_confirm) { _, _ -> launchRecordActivity() }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        } else {
            launchRecordActivity()
        }
    }

    private fun launchRecordActivity() {
        recordGestureLauncher.launch(Intent(this, RecordGestureActivity::class.java))
    }

    private fun refreshGestureStatus() {
        binding.tvGestureStatus.setText(
            if (gestureRepository.has()) R.string.status_gesture_recorded
            else R.string.status_gesture_unrecorded
        )
    }

    private fun bindPermissionGuideActions() {
        findViewById<View?>(R.id.btn_grant_overlay_perm)?.setOnClickListener {
            runCatching {
                startActivity(PermissionHelper.createOverlayPermissionIntent(packageName))
            }.onFailure {
                Toast.makeText(this, R.string.toast_overlay_perm_required, Toast.LENGTH_SHORT)
                    .show()
            }
        }
        findViewById<View?>(R.id.btn_check_overlay_perm)?.setOnClickListener { renderState() }
        findViewById<View?>(R.id.btn_open_app_settings)?.setOnClickListener {
            runCatching {
                startActivity(PermissionHelper.createAppSettingsIntent(packageName))
            }
        }
    }
}
