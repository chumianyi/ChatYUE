package com.chatyue.chat

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import com.chatyue.chat.databinding.ActivityBackgroundBinding

/**
 * 全局背景设置页（2.0 新增）。
 *
 * - 内置 8 款浅色预设，点选即换，立即全局生效
 * - 支持导入本地图片，自动居中裁剪铺满；图片同时写入公共存储 Pictures/ChatYUE
 */
class BackgroundActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBackgroundBinding

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val ok = AppearanceStore.importImage(this, uri)
            if (ok) {
                CompatToast.show(this, R.string.background_image_applied)
            } else {
                CompatToast.show(this, R.string.background_import_failed, android.widget.Toast.LENGTH_LONG)
            }
            refreshPresetSelection()
            refreshPreview()
            refreshImageState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBackgroundBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }

        buildPresetGrid()
        refreshPresetSelection()
        refreshPreview()
        refreshImageState()

        binding.pickImageRow.setOnClickListener {
            pickImageLauncher.launch(arrayOf("image/*"))
        }
        binding.removeImageRow.setOnClickListener {
            AppearanceStore.clearImage(this)
            CompatToast.show(this, R.string.background_removed)
            refreshPresetSelection()
            refreshPreview()
            refreshImageState()
        }
    }

    private fun buildPresetGrid() {
        val grid: GridLayout = binding.presetGrid
        grid.removeAllViews()
        val density = resources.displayMetrics.density
        val margin = (6 * density).toInt()
        AppearanceStore.Preset.entries.forEachIndexed { index, preset ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding((8 * density).toInt())
            }
            val swatch = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (44 * density).toInt(),
                    (44 * density).toInt(),
                )
                setBackgroundColor(preset.color)
                // 选中态使用描边
                foreground = androidx.core.content.ContextCompat.getDrawable(
                    this@BackgroundActivity,
                    R.drawable.bg_preset_ring,
                )
                tag = preset
            }
            val label = TextView(this).apply {
                text = preset.label
                setTextColor(
                    androidx.core.content.ContextCompat.getColor(
                        this@BackgroundActivity,
                        R.color.ds_text_secondary,
                    ),
                )
                textSize = 11f
                gravity = Gravity.CENTER
            }
            cell.addView(swatch)
            cell.addView(label)
            cell.setOnClickListener {
                AppearanceStore.setPreset(this, preset)
                refreshPresetSelection()
                refreshPreview()
                refreshImageState()
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(index % COLUMNS, 1f)
                setMargins(margin, margin, margin, margin)
            }
            grid.addView(cell, params)
        }
    }

    private fun refreshPresetSelection() {
        val current = AppearanceStore.preset(this)
        val imageMode = AppearanceStore.mode(this) == AppearanceStore.Mode.IMAGE
        val grid: GridLayout = binding.presetGrid
        for (i in 0 until grid.childCount) {
            val cell = grid.getChildAt(i) as? LinearLayout ?: continue
            val swatch = cell.getChildAt(0) as? View ?: continue
            val preset = swatch.tag as? AppearanceStore.Preset ?: continue
            val selected = !imageMode && preset == current
            (swatch.foreground as? android.graphics.drawable.Drawable)?.alpha =
                if (selected) 255 else 0
        }
    }

    private fun refreshPreview() {
        binding.backgroundPreview.background = AppearanceStore.backgroundDrawable(this)
    }

    private fun refreshImageState() {
        val has = AppearanceStore.hasImage(this)
        binding.removeImageRow.visibility = if (has) View.VISIBLE else View.GONE
        binding.imageState.text = if (has) {
            getString(R.string.background_image_active)
        } else {
            getString(R.string.background_no_image)
        }
    }

    private companion object {
        const val COLUMNS = 4
    }
}
