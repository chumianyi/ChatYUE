package com.chatyue.chat.license

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.chatyue.chat.databinding.ActivityLicenseBinding

/**
 * 开源许可证列表。
 *
 * 从「关于 → 许可证」进入，列出本项目自身与全部第三方开源组件，
 * 点击任意一项可以查看该组件的完整许可证文本。
 */
class LicenseActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLicenseBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLicenseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }

        val entries = Licenses.all(this)
        binding.licenseList.layoutManager = LinearLayoutManager(this)
        binding.licenseList.adapter = LicenseAdapter(entries) { entry ->
            val index = entries.indexOf(entry)
            if (index >= 0) {
                startActivity(
                    Intent(this, LicenseDetailActivity::class.java)
                        .putExtra(LicenseDetailActivity.EXTRA_INDEX, index),
                )
            }
        }
    }
}
