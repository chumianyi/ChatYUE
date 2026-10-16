package com.chatyue.chat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.view.KeyEvent
import androidx.appcompat.app.AlertDialog
import com.chatyue.chat.remote.RemoteConfig
import com.chatyue.chat.remote.RemoteConfigRepository
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 强制版本更新检测（2.0 全局常驻模块）。
 *
 * 行为约定：
 * - 每次进程启动、前台唤醒（[start]）都会静默触发一次版本校验，无手动开关
 * - 启动瞬间无网络时不报错，自动进入监听状态；网络恢复的一瞬间立即补检
 * - 当前版本低于线上 `minSupportedVersionCode` 时弹出不可关闭的强制升级弹窗，
 *   锁定应用全部操作（返回键、点击外部均无效）
 * - 版本足够新时不做任何打扰，旧版本可正常完整使用
 */
class VersionGuard(activity: Activity) {
    private val activityRef = WeakReference(activity)
    private val checking = AtomicBoolean(false)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var dialog: AlertDialog? = null

    /** 每个前台周期开始时调用。 */
    fun start() {
        val activity = activityRef.get() ?: return
        if (isForced(activity)) {
            // 已处于强制升级锁定态，保持监听即可。
            ensureNetworkListener()
            return
        }
        ensureNetworkListener()
        check()
    }

    /** 离开前台时调用，释放网络监听。 */
    fun stop() {
        releaseNetworkListener()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    private fun check() {
        val activity = activityRef.get() ?: return
        if (isForced(activity)) return
        if (!checking.compareAndSet(false, true)) return

        Thread {
            // 必须使用线上数据判断，避免离线时用内置副本得出错误结论。
            val config = RemoteConfigRepository.loadOnline()
            activity.runOnUiThread {
                checking.set(false)
                if (activity.isFinishing) return@runOnUiThread
                if (config == null) {
                    // 网络不可用 / 请求失败：保持监听，等网络恢复后自动补检。
                    ensureNetworkListener()
                } else {
                    releaseNetworkListener()
                    evaluate(activity, config)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun evaluate(activity: Activity, config: com.chatyue.chat.remote.RemoteConfig) {
        val minimum = config.minSupportedVersionCode
        val current = currentVersionCode(activity)
        if (minimum > 0 && current < minimum) {
            showForceUpdateDialog(activity, config, current, minimum)
        }
    }

    private fun showForceUpdateDialog(
        activity: Activity,
        config: RemoteConfig,
        current: Int,
        minimum: Int,
    ) {
        if (dialog?.isShowing == true) return
        dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.force_update_title))
            .setMessage(
                activity.getString(
                    R.string.force_update_message,
                    current.toString(),
                    config.versionName,
                ),
            )
            .setPositiveButton(R.string.force_update_action) { _, _ ->
                openDownloadPage(activity, config.downloadUrl)
            }
            // 不可取消：没有关闭按钮，点击外部与返回键均无效。
            .setCancelable(false)
            .create()
            .apply {
                setCanceledOnTouchOutside(false)
                setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_BACK }
                show()
            }
    }

    private fun openDownloadPage(activity: Activity, url: String) {
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }
    }

    private fun isForced(activity: Activity): Boolean = dialog?.isShowing == true

    // ---------------- 网络监听 ----------------

    private fun ensureNetworkListener() {
        if (networkCallback != null) return
        val activity = activityRef.get() ?: return
        val manager = activity.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // 网络恢复的一瞬间立刻补全启动时未完成的版本校验。
                activity.runOnUiThread { check() }
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { manager.registerNetworkCallback(request, callback) }
            .onSuccess { networkCallback = callback }
    }

    private fun releaseNetworkListener() {
        val callback = networkCallback ?: return
        val activity = activityRef.get() ?: return
        val manager = activity.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager
        runCatching { manager?.unregisterNetworkCallback(callback) }
        networkCallback = null
    }

    companion object {
        fun currentVersionCode(context: Context): Int {
            return runCatching {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    info.versionCode
                }
            }.getOrDefault(0)
        }
    }
}
