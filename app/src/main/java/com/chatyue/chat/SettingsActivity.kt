package com.chatyue.chat

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.chatyue.chat.databinding.ActivitySettingsBinding
import com.chatyue.chat.license.LicenseActivity
import com.chatyue.chat.remote.RemoteConfigRepository

/**
 * 设置页（2.0 新增）。
 *
 * 功能：界面尺寸档位、启动动画开关、全局背景入口、默认推理模式、数据管理入口、
 * 检查更新、关于与开源许可证。
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.versionText.text = getString(R.string.about_version, appVersion())

        binding.uiScaleRow.setOnClickListener { showUiScaleDialog() }
        refreshUiScaleSummary()

        binding.splashSwitch.isChecked = AppConfig.isSplashEnabled(this)
        binding.splashSwitch.setOnCheckedChangeListener { _, checked ->
            AppConfig.setSplashEnabled(this, checked)
        }

        binding.backgroundRow.setOnClickListener {
            startActivity(Intent(this, BackgroundActivity::class.java))
        }
        refreshBackgroundSummary()

        binding.modeRow.setOnClickListener { showModeDialog() }
        refreshModeSummary()

        binding.dataRow.setOnClickListener {
            startActivity(Intent(this, DataManagerActivity::class.java))
        }

        binding.updateRow.setOnClickListener {
            CompatToast.show(this, R.string.update_checking)
            checkUpdateManually()
        }

        binding.aboutRow.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        binding.licensesRow.setOnClickListener {
            startActivity(Intent(this, LicenseActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshBackgroundSummary()
    }

    private fun showUiScaleDialog() {
        val scales = AppConfig.UiScale.entries.toTypedArray()
        val labels = scales.map { "${it.label}  ·  ${it.summary}" }.toTypedArray()
        val current = AppConfig.uiScale(this)
        val checked = scales.indexOf(current).coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.settings_ui_scale)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                if (scales[which] != current) {
                    AppConfig.setUiScale(this, scales[which])
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshUiScaleSummary() {
        binding.uiScaleSummary.text = AppConfig.uiScale(this).label
    }

    private fun showModeDialog() {
        val modes = com.chatyue.chat.backend.ChatMode.entries.toTypedArray()
        val labels = modes.map { mode ->
            "${com.chatyue.chat.backend.ModelCatalog.displayName(mode)}  ·  " +
                com.chatyue.chat.backend.ModelCatalog.description(mode)
        }.toTypedArray()
        val current = AppConfig.chatMode(this)
        val checked = modes.indexOf(current).coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.settings_mode)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                AppConfig.setChatMode(this, modes[which])
                dialog.dismiss()
                refreshModeSummary()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshModeSummary() {
        val mode = AppConfig.chatMode(this)
        binding.modeSummary.text = getString(
            R.string.settings_mode_summary_format,
            com.chatyue.chat.backend.ModelCatalog.displayName(mode),
        )
    }

    private fun refreshBackgroundSummary() {
        val mode = AppearanceStore.mode(this)
        binding.backgroundSummary.text = when (mode) {
            AppearanceStore.Mode.IMAGE -> getString(R.string.settings_background_summary_image)
            else -> AppearanceStore.preset(this).label
        }
    }

    private fun checkUpdateManually() {
        Thread {
            val result = runCatching { RemoteConfigRepository.load(this) }
            runOnUiThread {
                result
                    .onSuccess { config ->
                        if (config.versionCode > currentVersionCode()) {
                            androidx.appcompat.app.AlertDialog.Builder(this)
                                .setTitle(
                                    getString(R.string.update_found_title, config.versionName),
                                )
                                .setMessage(
                                    getString(
                                        R.string.update_found_message,
                                        currentVersionName(),
                                        config.versionName,
                                    ),
                                )
                                .setPositiveButton(R.string.update_download) { _, _ ->
                                    openUrl(config.downloadUrl)
                                }
                                .setNegativeButton(R.string.update_later, null)
                                .show()
                        } else {
                            CompatToast.show(
                                this,
                                getString(R.string.update_latest, currentVersionName()),
                                android.widget.Toast.LENGTH_LONG,
                            )
                        }
                    }
                    .onFailure {
                        CompatToast.show(
                            this,
                            getString(R.string.update_check_failed, "网络异常"),
                            android.widget.Toast.LENGTH_LONG,
                        )
                    }
            }
        }.start()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (e: android.content.ActivityNotFoundException) {
            CompatToast.show(this, url, android.widget.Toast.LENGTH_LONG)
        }
    }

    private fun currentVersionCode(): Long =
        runCatching {
            packageManager.getPackageInfo(packageName, 0).let {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    it.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    it.versionCode.toLong()
                }
            }
        }.getOrDefault(0L)

    private fun currentVersionName(): String = appVersion()

    private fun appVersion(): String =
        runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        }.getOrDefault("1.0.0").ifBlank { "1.0.0" }
}
