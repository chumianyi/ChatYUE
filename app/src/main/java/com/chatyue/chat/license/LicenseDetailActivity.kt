package com.chatyue.chat.license

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.chatyue.chat.CompatToast
import com.chatyue.chat.R
import com.chatyue.chat.databinding.ActivityLicenseDetailBinding

/** 单个组件的许可证全文。 */
class LicenseDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INDEX = "license_index"
    }

    private lateinit var binding: ActivityLicenseDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLicenseDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }

        val entries = Licenses.all(this)
        if (entries.isEmpty()) {
            finish()
            return
        }
        val index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, entries.lastIndex)
        val entry = entries[index]

        binding.titleText.text = getString(R.string.licenses_detail_title, entry.name)
        binding.nameText.text = if (entry.self) {
            entry.name + " · " + getString(R.string.licenses_self_suffix)
        } else {
            entry.name
        }
        binding.metaText.text = "v" + entry.version + " · " + entry.license
        binding.holderText.text = entry.holder

        binding.officialButton.setOnClickListener { openOfficial(entry.url) }

        binding.bodyText.text = readAsset(entry.assetPath)
            ?: getString(R.string.licenses_read_failed)
    }

    private fun readAsset(path: String): String? {
        return runCatching {
            assets.open(path).bufferedReader().use { it.readText() }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun openOfficial(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            CompatToast.show(this, url, android.widget.Toast.LENGTH_LONG)
        }
    }
}
