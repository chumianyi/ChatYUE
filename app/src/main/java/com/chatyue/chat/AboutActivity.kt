package com.chatyue.chat

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.chatyue.chat.databinding.ActivityAboutBinding
import com.chatyue.chat.license.LicenseActivity
import com.chatyue.chat.remote.RemoteConfig

/**
 * 关于页面：标注原项目、本项目地址与 GPLv3 开源许可。
 */
class AboutActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.versionText.text = getString(R.string.about_version, appVersion())

        binding.originRow.setOnClickListener {
            openUrl(getString(R.string.about_origin_url))
        }
        binding.projectRow.setOnClickListener {
            openUrl(getString(R.string.about_project_url))
        }
        binding.licenseRow.setOnClickListener {
            openUrl(getString(R.string.about_license_url))
        }
        binding.agreementButton.setOnClickListener {
            openUrl(RemoteConfig.AGREEMENT_URL)
        }
        binding.privacyButton.setOnClickListener {
            openUrl(RemoteConfig.PRIVACY_URL)
        }
        // 最下方：查看本项目与全部第三方组件的许可证
        binding.licensesButton.setOnClickListener {
            startActivity(Intent(this, LicenseActivity::class.java))
        }
    }

    private fun appVersion(): String {
        return runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        }.getOrDefault("1.0.0").ifBlank { "1.0.0" }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            CompatToast.show(this, url, android.widget.Toast.LENGTH_LONG)
        }
    }
}
