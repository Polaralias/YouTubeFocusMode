package com.polaralias.ytfocus

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.polaralias.ytfocus.admin.AdminActivity
import com.polaralias.ytfocus.databinding.ActivityMainBinding
import com.polaralias.ytfocus.DiagnosticsActivity
import com.polaralias.ytfocus.service.OverlayService
import com.polaralias.ytfocus.util.Logx
import com.polaralias.ytfocus.util.PermissionStatus
import com.polaralias.ytfocus.util.SafeServiceStarter

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        Logx.d("MainActivity.onCreate start savedInstanceState=${savedInstanceState != null}")
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupButtons()
        if (!hasLoggedRunbook) {
            logRunbook()
            hasLoggedRunbook = true
        }
        updateStatusPanel()
        Logx.d("MainActivity.onCreate end")
    }

    override fun onStart() {
        Logx.d("MainActivity.onStart")
        super.onStart()
    }

    override fun onResume() {
        Logx.d("MainActivity.onResume")
        super.onResume()
        updateStatusPanel()
    }

    override fun onPause() {
        Logx.d("MainActivity.onPause")
        super.onPause()
    }

    override fun onStop() {
        Logx.d("MainActivity.onStop")
        super.onStop()
    }

    override fun onDestroy() {
        Logx.d("MainActivity.onDestroy")
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_DIAGNOSTICS, 0, getString(R.string.menu_diagnostics))
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == MENU_DIAGNOSTICS) {
            Logx.d("MainActivity.onOptionsItemSelected diagnostics")
            startActivity(Intent(this, DiagnosticsActivity::class.java))
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

    private fun setupButtons() {
        binding.buttonRequestOverlay.setOnClickListener {
            Logx.d("MainActivity.requestOverlay before permission=${Settings.canDrawOverlays(this)}")
            requestOverlayPermission()
            Logx.d("MainActivity.requestOverlay after permission=${Settings.canDrawOverlays(this)}")
            updateStatusPanel()
        }
        binding.buttonNotificationSettings.setOnClickListener {
            Logx.d("MainActivity.openNotificationSettings start")
            openNotificationListenerSettings()
            Logx.d("MainActivity.openNotificationSettings end")
        }
        binding.buttonUsageAccessSettings.setOnClickListener {
            Logx.d("MainActivity.openUsageAccess start")
            openUsageAccessSettings()
            Logx.d("MainActivity.openUsageAccess end")
        }
        binding.buttonAccessibilitySettings.setOnClickListener {
            Logx.d("MainActivity.openAccessibility start")
            openAccessibilitySettings()
            Logx.d("MainActivity.openAccessibility end")
        }
        binding.buttonStartOverlay.setOnClickListener {
            Logx.d("MainActivity.startOverlay start")
            startOverlayService()
            Logx.d("MainActivity.startOverlay end")
        }
        binding.buttonAdminSettings.setOnClickListener {
            Logx.d("MainActivity.openAdminSettings")
            startActivity(Intent(this, AdminActivity::class.java))
        }
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
        }
    }

    private fun openNotificationListenerSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        startActivity(intent)
    }

    private fun openUsageAccessSettings() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        startActivity(intent)
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun startOverlayService() {
        val intent = Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_SHOW)
        SafeServiceStarter.startFg(this, intent)
    }

    private fun updateStatusPanel() {
        val snapshot = PermissionStatus.snapshot(this)
        binding.statusOverlay.text = getString(R.string.status_overlay, snapshot.overlay)
        binding.statusNotification.text = getString(R.string.status_notification_listener, snapshot.notificationListener)
        binding.statusUsage.text = getString(R.string.status_usage_access, snapshot.usageAccess)
        binding.statusAccessibility.text = getString(R.string.status_accessibility, snapshot.accessibility)
        binding.statusSdk.text = getString(R.string.status_sdk, snapshot.sdkLevel)
        Logx.d("MainActivity.updateStatusPanel overlay=${snapshot.overlay} notification=${snapshot.notificationListener} usage=${snapshot.usageAccess} accessibility=${snapshot.accessibility} sdk=${snapshot.sdkLevel} build=${Build.VERSION.SDK_INT} overlays=${Settings.canDrawOverlays(this)}")
    }

    private fun logRunbook() {
        Logx.i("adb install -r app/build/outputs/apk/debug/app-debug.apk")
        Logx.i("adb logcat -v time -s AudioFocus ActivityManager PackageManager")
        Logx.i("adb logcat -v time | grep AudioFocus")
        Logx.i("adb shell run-as com.polaralias.audiofocus cat files/crash/latest_crash.txt")
    }

    companion object {
        private var hasLoggedRunbook = false
        private const val MENU_DIAGNOSTICS = 100
    }
}
