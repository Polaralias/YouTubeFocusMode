package com.polaralias.audiofocus.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.activity.ComponentActivity

class AdminActivity : ComponentActivity() {
    private lateinit var dpm: DevicePolicyManager
    private lateinit var admin: ComponentName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dpm = getSystemService(DevicePolicyManager::class.java)
        admin = ComponentName(this, YtDeviceAdminReceiver::class.java)
        val enable = Button(this).apply {
            text = "Enable uninstall protection"
            setOnClickListener {
                val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Prevents uninstall until disabled in device admin")
                }
                startActivity(i)
            }
        }
        val disable = Button(this).apply {
            text = "Disable uninstall protection"
            setOnClickListener { dpm.removeActiveAdmin(admin) }
        }
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(enable)
            addView(disable)
        }
        setContentView(root)
    }

    companion object {
        fun isEnabled(ctx: Context): Boolean {
            val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
            val admin = ComponentName(ctx, YtDeviceAdminReceiver::class.java)
            return dpm.isAdminActive(admin)
        }
    }
}
