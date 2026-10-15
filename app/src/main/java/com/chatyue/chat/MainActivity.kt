package com.chatyue.chat

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModelProvider
import com.chatyue.chat.attachment.ImageGalleryActivity
import com.chatyue.chat.attachment.MediaImageStore
import com.chatyue.chat.ui.AttachmentUi
import com.chatyue.chat.ui.ChatScreen
import com.chatyue.chat.ui.ChatYUETheme
import java.util.concurrent.Executors

/**
 * 主界面：Jetpack Compose + Miuix（小米 HyperOS 设计语言）实现。
 *
 * 业务逻辑（固定接口 / 流式解析 / 上下文压缩）仍复用 ChatViewModel 与本地仓库，
 * 界面部分全部改为 Compose。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: ChatViewModel
    private lateinit var repository: LocalChatRepository

    /** onCreate 可能因「未同意协议」提前返回，用它避免 onDestroy 访问未初始化的字段。 */
    private var initialized = false

    private val attachmentState = mutableStateOf(AttachmentUi())
    private val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "deepseek-io").apply { isDaemon = true }
    }

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val uri = data.data ?: return@registerForActivityResult
        val name = data.getStringExtra(ImageGalleryActivity.EXTRA_IMAGE_NAME)
            ?: getString(R.string.attachment_image_label)
        attachmentState.value = AttachmentUi(imageUri = uri, imageName = name)
        // 需求：上传图片时自动提示模型只支持文本。
        CompatToast.show(this, R.string.image_text_only_notice, android.widget.Toast.LENGTH_LONG)
    }

    private val textFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        CompatToast.show(this, R.string.attachment_reading)
        ioExecutor.execute {
            val name = MediaImageStore.displayName(this, uri)
            val content = MediaImageStore.readText(this, uri)
            runOnUiThread {
                when {
                    content == null -> CompatToast.show(this, R.string.attachment_too_large)
                    content.isEmpty() -> CompatToast.show(this, R.string.attachment_read_failed)
                    else -> attachmentState.value = AttachmentUi(
                        textName = name,
                        textContent = content,
                    )
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 首次启动必须先同意用户协议与隐私政策。
        if (!AgreementStore.isAccepted(this)) {
            startActivity(Intent(this, AgreementActivity::class.java))
            finish()
            return
        }

        viewModel = ViewModelProvider(this)[ChatViewModel::class.java]
        repository = viewModel.repository

        setContent {
            ChatYUETheme {
                ChatScreen(
                    viewModel = viewModel,
                    repository = repository,
                    attachment = attachmentState.value,
                    onPickImage = {
                        galleryLauncher.launch(Intent(this, ImageGalleryActivity::class.java))
                    },
                    onPickText = { textFileLauncher.launch(arrayOf("text/plain")) },
                    onClearAttachment = { attachmentState.value = AttachmentUi() },
                    onOpenActivity = { startActivity(Intent(this, it)) },
                )
            }
        }

        initialized = true
    }

    override fun onDestroy() {
        // initialized 为 false 说明本次 onCreate 直接跳去了协议页，此时还没创建 ViewModel。
        if (initialized && !isChangingConfigurations) {
            viewModel.cancelGeneration()
        }
        ioExecutor.shutdownNow()
        super.onDestroy()
    }
}
