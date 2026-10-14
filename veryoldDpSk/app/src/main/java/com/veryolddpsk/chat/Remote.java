package com.veryolddpsk.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 远程配置：读取 GitHub Pages 上的 start.json，用于「检查更新」与「更新日志」。
 *
 * 条款与版本信息都由网页托管，因此不重新发版也能更新。
 */
public final class Remote {

    /** 一条更新日志。 */
    public static final class Release {
        public String versionName = "";
        public String releaseDate = "";
        public ArrayList<String> items = new ArrayList<String>();
    }

    /** start.json 的整体内容。 */
    public static final class Info {
        public String versionName = "";
        public int versionCode = 0;
        public String releaseDate = "";
        public String downloadUrl = "";
        public String agreementUrl = App.AGREEMENT_URL;
        public String privacyUrl = App.PRIVACY_URL;
        public ArrayList<Release> releases = new ArrayList<Release>();
    }

    private Remote() {
    }

    /** 从网络拉取配置，可能抛异常（调用方需在后台线程执行）。 */
    public static Info fetch() throws Exception {
        return parse(Net.get(App.START_JSON_URL));
    }

    public static Info parse(String text) {
        Info info = new Info();
        Map<String, Object> root = Json.parseObject(text);
        if (root.isEmpty()) {
            return null;
        }
        info.versionName = Json.strOrEmpty(root, "versionName");
        info.versionCode = (int) Json.num(root, "versionCode", 0L);
        info.releaseDate = Json.strOrEmpty(root, "releaseDate");
        info.downloadUrl = Json.strOrEmpty(root, "downloadUrl");
        String agreement = Json.strOrEmpty(root, "agreementUrl");
        if (agreement.length() > 0) {
            info.agreementUrl = agreement;
        }
        String privacy = Json.strOrEmpty(root, "privacyUrl");
        if (privacy.length() > 0) {
            info.privacyUrl = privacy;
        }
        List<Object> changelog = Json.arr(root, "changelog");
        for (int k = 0; k < changelog.size(); k++) {
            Map<String, Object> node = Json.asMap(changelog.get(k));
            Release release = new Release();
            release.versionName = Json.strOrEmpty(node, "versionName");
            release.releaseDate = Json.strOrEmpty(node, "releaseDate");
            List<Object> items = Json.arr(node, "items");
            for (int j = 0; j < items.size(); j++) {
                Object item = items.get(j);
                if (item != null) {
                    release.items.add(String.valueOf(item));
                }
            }
            info.releases.add(release);
        }
        return info;
    }

    /** 拼装更新日志的纯文本展示内容。 */
    public static String formatChangelog(Info info) {
        if (info == null || info.releases.isEmpty()) {
            return null;
        }
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < info.releases.size(); k++) {
            Release release = info.releases.get(k);
            b.append("v").append(release.versionName);
            if (release.releaseDate.length() > 0) {
                b.append("    ").append(release.releaseDate);
            }
            b.append('\n');
            for (int j = 0; j < release.items.size(); j++) {
                b.append("• ").append(release.items.get(j)).append('\n');
            }
            if (k < info.releases.size() - 1) {
                b.append('\n');
            }
        }
        return b.toString();
    }
}
