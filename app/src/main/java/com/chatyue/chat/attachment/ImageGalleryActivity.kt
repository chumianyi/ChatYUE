package com.chatyue.chat.attachment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chatyue.chat.AttachmentBlockBuilder
import com.chatyue.chat.CompatToast
import com.chatyue.chat.R
import com.chatyue.chat.databinding.ActivityImageGalleryBinding
import com.chatyue.chat.databinding.ItemGalleryImageBinding
import java.util.concurrent.Executors

/**
 * 设备相册选择页（2.0 起支持多选）。
 *
 * 授权媒体权限后浏览设备中的全部图片，点击切换选中态，
 * 底部「完成」把选中的图片按选择顺序回传给输入区。
 */
class ImageGalleryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityImageGalleryBinding
    private lateinit var adapter: GalleryAdapter
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "deepseek-gallery").apply { isDaemon = true }
    }

    /** 按选择顺序记录已选图片；用 LinkedHashSet 去重且保序。 */
    private val selectedImages = LinkedHashSet<MediaImage>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            loadImages()
        } else {
            CompatToast.show(this, R.string.media_permission_denied)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backButton.setOnClickListener { finish() }
        binding.confirmSelectionButton.setOnClickListener { confirmSelection() }

        adapter = GalleryAdapter(::onImageToggled)
        binding.imageGrid.layoutManager = GridLayoutManager(this, 3)
        binding.imageGrid.adapter = adapter

        if (hasMediaPermission()) {
            loadImages()
        } else {
            permissionLauncher.launch(requiredPermission())
        }
        updateSelectionUi()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    /** 点击图片：切换选中态；达到上限后给出提示且不再多选。 */
    private fun onImageToggled(image: MediaImage) {
        if (selectedImages.contains(image)) {
            selectedImages.remove(image)
            adapter.setSelected(selectedImages, image)
            updateSelectionUi()
            return
        }
        if (selectedImages.size >= AttachmentBlockBuilder.MAX_IMAGE_ATTACHMENTS) {
            CompatToast.show(
                this,
                getString(
                    R.string.gallery_pick_limit,
                    AttachmentBlockBuilder.MAX_IMAGE_ATTACHMENTS,
                ),
                android.widget.Toast.LENGTH_LONG,
            )
            return
        }
        selectedImages.add(image)
        adapter.setSelected(selectedImages, image)
        updateSelectionUi()
    }

    /** 点击「完成」：按选择顺序回传图片；兼容单图时的旧字段。 */
    private fun confirmSelection() {
        if (selectedImages.isEmpty()) {
            CompatToast.show(this, R.string.gallery_pick_none)
            return
        }
        val ordered = selectedImages.toList()
        val data = android.content.Intent().apply {
            data = ordered.first().uri
            putExtra(EXTRA_IMAGE_NAME, ordered.first().name)
            putParcelableArrayListExtra(
                EXTRA_IMAGE_URIS,
                ArrayList(ordered.map { it.uri }),
            )
            putStringArrayListExtra(
                EXTRA_IMAGE_NAMES,
                ArrayList(ordered.map { it.name }),
            )
        }
        setResult(RESULT_OK, data)
        finish()
    }

    private fun loadImages() {
        executor.execute {
            val images = MediaImageStore.queryAll(this)
            runOnUiThread {
                adapter.submit(images)
                val empty = images.isEmpty()
                binding.emptyNotice.visibility = if (empty) View.VISIBLE else View.GONE
                binding.imageGrid.visibility = if (empty) View.GONE else View.VISIBLE
            }
        }
    }

    /** 刷新底部条：已选数量文案 + 完成按钮可用性。 */
    private fun updateSelectionUi() {
        val count = selectedImages.size
        val limit = AttachmentBlockBuilder.MAX_IMAGE_ATTACHMENTS
        binding.selectedCountLabel.text = when (count) {
            0 -> getString(R.string.gallery_pick_hint)
            1 -> getString(R.string.gallery_selected_one)
            else -> getString(R.string.gallery_selected_many, count, limit)
        }
        binding.confirmSelectionButton.isEnabled = count > 0
    }

    private fun hasMediaPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, requiredPermission()) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun requiredPermission(): String {
        return if (android.os.Build.VERSION.SDK_INT >= 33) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    companion object {
        const val EXTRA_IMAGE_NAME = "extra_image_name"

        /** 多选回传：图片 Uri 列表（Parcelable）。 */
        const val EXTRA_IMAGE_URIS = "extra_image_uris"

        /** 多选回传：图片文件名列表，与 [EXTRA_IMAGE_URIS] 一一对应。 */
        const val EXTRA_IMAGE_NAMES = "extra_image_names"
    }

    private class GalleryAdapter(
        private val onClick: (MediaImage) -> Unit,
    ) : RecyclerView.Adapter<GalleryAdapter.ViewHolder>() {
        private val items = mutableListOf<MediaImage>()
        private val selected = mutableSetOf<MediaImage>()

        fun submit(newItems: List<MediaImage>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        fun setSelected(newSelected: Set<MediaImage>, toggled: MediaImage) {
            selected.clear()
            selected.addAll(newSelected)
            // 只刷新被点击的那一格：相册是全屏网格，整列表刷新会闪且重置滚动位置。
            val position = items.indexOf(toggled)
            if (position >= 0) notifyItemChanged(position)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemGalleryImageBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position], selected.contains(items[position]), onClick)
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(
            private val binding: ItemGalleryImageBinding,
        ) : RecyclerView.ViewHolder(binding.root) {
            fun bind(image: MediaImage, isSelected: Boolean, onClick: (MediaImage) -> Unit) {
                ImageLoader.load(
                    context = binding.root.context,
                    uri = image.uri,
                    targetSizePx = THUMBNAIL_SIZE_PX,
                    imageView = binding.thumbnail,
                )
                binding.selectionCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
                binding.selectionScrim.visibility = if (isSelected) View.VISIBLE else View.GONE
                binding.root.setOnClickListener { onClick(image) }
            }
        }

        private companion object {
            const val THUMBNAIL_SIZE_PX = 240
        }
    }
}
