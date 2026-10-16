package com.chatyue.chat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.chatyue.chat.databinding.ActivityDataBinding

/**
 * 数据管理页（2.0 新增）。
 *
 * - 导出：全部对话、个性化配置、全局背景、模型偏好打成备份包，
 *   写入公共目录 Download/ChatYUE/chatyue_backup.zip
 * - 导入：选择备份包，确认覆盖后一键恢复，完成后自动重启应用
 */
class DataManagerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDataBinding
    private var busy = false

    private val openBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) confirmImport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDataBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }

        binding.exportRow.setOnClickListener { doExport() }
        binding.importRow.setOnClickListener {
            if (busy) return@setOnClickListener
            openBackupLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
        }
    }

    private fun doExport() {
        if (busy) return
        busy = true
        binding.progress.visibility = View.VISIBLE
        Thread {
            val result = DataBackup.export(this)
            runOnUiThread {
                binding.progress.visibility = View.GONE
                busy = false
                result
                    .onSuccess { path ->
                        AlertDialog.Builder(this)
                            .setTitle(R.string.data_export)
                            .setMessage(getString(R.string.data_export_ok, path))
                            .setPositiveButton(R.string.ok, null)
                            .show()
                    }
                    .onFailure { error ->
                        AlertDialog.Builder(this)
                            .setTitle(R.string.data_export)
                            .setMessage(
                                getString(
                                    R.string.data_export_failed,
                                    error.message.orEmpty().ifBlank { "未知错误" },
                                ),
                            )
                            .setPositiveButton(R.string.ok, null)
                            .show()
                    }
            }
        }.start()
    }

    private fun confirmImport(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle(R.string.data_import_confirm_title)
            .setMessage(R.string.data_import_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.data_import_confirm_action) { _, _ -> doImport(uri) }
            .show()
    }

    private fun doImport(uri: Uri) {
        if (busy) return
        busy = true
        binding.progress.visibility = View.VISIBLE
        Thread {
            val importResult = DataBackup.import(this, uri)
            val applyResult = importResult.getOrNull()?.let { DataBackup.applyRestore(this) }
            runOnUiThread {
                binding.progress.visibility = View.GONE
                busy = false
                val failure = importResult.exceptionOrNull() ?: applyResult?.exceptionOrNull()
                if (failure == null) {
                    // 覆盖完成后重启应用，让新数据与配置完整生效。
                    val restart = Intent(this, SplashActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                    startActivity(restart)
                    Process.killProcess(Process.myPid())
                } else {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.data_import)
                        .setMessage(
                            getString(
                                R.string.data_import_failed,
                                failure.message.orEmpty().ifBlank { "未知错误" },
                            ),
                        )
                        .setPositiveButton(R.string.ok, null)
                        .show()
                }
            }
        }.start()
    }
}
