package com.polaralias.audiofocus

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ShareCompat
import com.polaralias.audiofocus.databinding.ActivityDiagnosticsBinding
import com.polaralias.audiofocus.util.Logx
import java.io.File

class DiagnosticsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDiagnosticsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        Logx.d("DiagnosticsActivity.onCreate")
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.buttonOpenCrash.setOnClickListener {
            Logx.d("DiagnosticsActivity.openCrash click")
            openCrash()
        }
        binding.buttonShareCrash.setOnClickListener {
            Logx.d("DiagnosticsActivity.shareCrash click")
            shareCrash()
        }
        openCrash()
    }

    private fun openCrash() {
        val file = crashFile()
        if (file.exists()) {
            val text = file.readText()
            binding.crashContent.text = text
            Logx.d("DiagnosticsActivity.openCrash size=${text.length}")
        } else {
            binding.crashContent.text = getString(R.string.no_crash_file)
            Toast.makeText(this, R.string.no_crash_file, Toast.LENGTH_SHORT).show()
            Logx.d("DiagnosticsActivity.openCrash missing")
        }
    }

    private fun shareCrash() {
        val file = crashFile()
        if (!file.exists()) {
            Toast.makeText(this, R.string.no_crash_file, Toast.LENGTH_SHORT).show()
            Logx.d("DiagnosticsActivity.shareCrash missing")
            return
        }
        val text = file.readText()
        val intent = ShareCompat.IntentBuilder(this)
            .setType("text/plain")
            .setText(text)
            .setChooserTitle(R.string.share_crash_title)
            .createChooserIntent()
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        Logx.d("DiagnosticsActivity.shareCrash sent length=${text.length}")
    }

    private fun crashFile(): File {
        val dir = File(filesDir, "crash")
        return File(dir, "latest_crash.txt")
    }
}
