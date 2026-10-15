package com.veryolddpsk.chat;

/**
 * 全局固定配置。
 *
 * 接口地址、密钥、模型全部写死在代码里，应用内不提供任何查看与修改入口。
 */
public final class App {

    /** 固定接口，不支持修改。 */
    public static final String ENDPOINT =
            "http://103.236.99.177:24512/deepseekold/v1/chat/completions";

    /** 固定密钥，不支持查看。 */
    public static final String API_KEY = "sk-123456789012345678901234567890";

    /** 固定模型，不支持切换。 */
    public static final String MODEL = "deepseek-ai/DeepSeek-R1";

    /** 上下文上限（token）。 */
    public static final long CONTEXT_LIMIT = 128L * 1024L;

    /** 上下文占用达到该百分比时自动压缩。 */
    public static final int COMPRESS_THRESHOLD_PERCENT = 85;

    /** 应用版本。 */
    public static final String VERSION_NAME = "1.0.0";

    /** GitHub Pages 站点，承载更新日志、用户协议与隐私政策。 */
    public static final String PAGES_BASE = "https://chumianyi.github.io/ChatYUE/";
    public static final String START_JSON_URL = PAGES_BASE + "start.json";
    public static final String AGREEMENT_URL = PAGES_BASE + "agreement.html";
    public static final String PRIVACY_URL = PAGES_BASE + "privacy.html";

    public static final String PROJECT_URL = "https://github.com/chumianyi/ChatYUE";
    public static final String ORIGINAL_URL = "https://github.com/benzhoupo/deepseek-lite";
    public static final String LICENSE_URL = "https://www.gnu.org/licenses/gpl-3.0.html";

    private App() {
    }
}
