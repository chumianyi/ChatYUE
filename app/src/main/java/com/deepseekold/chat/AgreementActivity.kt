package com.deepseekold.chat

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.deepseekold.chat.databinding.ActivityAgreementBinding
import com.deepseekold.chat.remote.RemoteConfig

/**
 * 首次启动时的用户协议 / 隐私政策确认页。
 *
 * 条款内容使用 GitHub Pages 上的网页版本（可随时更新），
 * 只允许「同意并继续」，选择「不同意」将直接退出应用。
 */
class AgreementActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAgreementBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAgreementBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.agreementWebView.settings.javaScriptEnabled = false
        binding.agreementWebView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.offlineNotice.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    binding.offlineNotice.visibility = View.VISIBLE
                }
            }
        }
        binding.agreementWebView.loadUrl(RemoteConfig.AGREEMENT_URL)

        binding.acceptButton.setOnClickListener {
            AgreementStore.accept(this)
            startActivity(
                android.content.Intent(this, MainActivity::class.java).addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK,
                ),
            )
            finish()
        }
        binding.declineButton.setOnClickListener {
            CompatToast.show(this, R.string.agreement_declined)
            finishAffinity()
        }
    }

    override fun onDestroy() {
        binding.agreementWebView.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 必须做出选择，禁用返回键退出到聊天页。
        CompatToast.show(this, R.string.agreement_declined)
    }
}
