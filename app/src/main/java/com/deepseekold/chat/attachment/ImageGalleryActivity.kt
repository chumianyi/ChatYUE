package com.deepseekold.chat.attachment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.deepseekold.chat.CompatToast
import com.deepseekold.chat.R
import com.deepseekold.chat.databinding.ActivityImageGalleryBinding
import com.deepseekold.chat.databinding.ItemGalleryImageBinding
import java.util.concurrent.Executors

/**
 * 设备相册选择页：授权媒体权限后即可浏览设备中的全部图片，点选一张即返回。
 */
class ImageGalleryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityImageGalleryBinding
    private lateinit var adapter: GalleryAdapter
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "deepseek-gallery").apply { isDaemon = true }
    }

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

        adapter = GalleryAdapter(::onImageSelected)
        binding.imageGrid.layoutManager = GridLayoutManager(this, 3)
        binding.imageGrid.adapter = adapter

        if (hasMediaPermission()) {
            loadImages()
        } else {
            permissionLauncher.launch(requiredPermission())
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun onImageSelected(image: MediaImage) {
        val data = android.content.Intent().apply {
            data = image.uri
            putExtra(EXTRA_IMAGE_NAME, image.name)
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
    }

    private class GalleryAdapter(
        private val onClick: (MediaImage) -> Unit,
    ) : RecyclerView.Adapter<GalleryAdapter.ViewHolder>() {
        private val items = mutableListOf<MediaImage>()

        fun submit(newItems: List<MediaImage>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
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
            holder.bind(items[position], onClick)
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(
            private val binding: ItemGalleryImageBinding,
        ) : RecyclerView.ViewHolder(binding.root) {
            fun bind(image: MediaImage, onClick: (MediaImage) -> Unit) {
                ImageLoader.load(
                    context = binding.root.context,
                    uri = image.uri,
                    targetSizePx = THUMBNAIL_SIZE_PX,
                    imageView = binding.thumbnail,
                )
                binding.root.setOnClickListener { onClick(image) }
            }
        }

        private companion object {
            const val THUMBNAIL_SIZE_PX = 240
        }
    }
}
