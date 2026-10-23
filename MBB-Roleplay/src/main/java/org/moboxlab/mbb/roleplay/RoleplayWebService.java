package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 受限网页读取。
 *
 * 只允许配置中的域名，禁用自动跨域重定向，限制时间和读取字节数。
 */
public class RoleplayWebService {
    private final RoleplayService service;

    public RoleplayWebService(RoleplayService service) {
        this.service = service;
    }

    public JSONObject fetch(String url) throws Exception {
        if (!service.config().webEnable) throw new IllegalArgumentException("网页浏览未启用");
        URL current = new URL(url);
        int redirects = 0;
        while (true) {
            validate(current);
            HttpURLConnection connection = (HttpURLConnection)current.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(Math.max(3,service.config().webTimeoutSecond) * 1000);
            connection.setReadTimeout(Math.max(3,service.config().webTimeoutSecond) * 1000);
            connection.setRequestProperty("User-Agent","MoBoxBot/0.1 (+https://github.com/MossCG/MoBoxBot)");
            connection.setRequestProperty("Accept","text/html,text/plain,application/json,application/xml;q=0.9,*/*;q=0.1");
            int code = connection.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.trim().isEmpty() || redirects++ >= 3) {
                    throw new IllegalArgumentException("网页重定向无效或过多");
                }
                current = new URL(current,location.trim());
                continue;
            }
            if (code < 200 || code >= 300) {
                connection.disconnect();
                throw new IllegalArgumentException("网页返回 HTTP "+code);
            }
            String contentType = service.safe(connection.getContentType()).toLowerCase(Locale.ROOT);
            if (!(contentType.contains("text/") || contentType.contains("json")
                    || contentType.contains("xml") || contentType.isEmpty())) {
                connection.disconnect();
                throw new IllegalArgumentException("不支持的内容类型："+contentType);
            }
            byte[] bytes = readLimited(connection.getInputStream(),service.config().webMaxBytes);
            connection.disconnect();
            String raw = new String(bytes,charset(contentType));
            String text = contentType.contains("html") || raw.contains("<html") ? htmlToText(raw) : raw;
            text = text.replace("\u0000","").replaceAll("[ \\t]+"," ")
                    .replaceAll("\\n{3,}","\n\n").trim();
            if (text.length() > service.config().webMaxChars) {
                text = text.substring(0,service.config().webMaxChars);
            }
            JSONObject result = new JSONObject(true);
            result.put("url",current.toString());
            result.put("contentType",contentType);
            result.put("bytes",bytes.length);
            result.put("text",text);
            return result;
        }
    }

    public boolean isAllowed(String rawUrl) {
        try {
            validate(new URL(rawUrl));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public JSONObject selfTest() {
        JSONObject result = new JSONObject(true);
        boolean schemeRejected = !isAllowed("file:///etc/passwd");
        boolean localhostRejected = !isAllowed("http://127.0.0.1/");
        result.put("nonHttpRejected",schemeRejected);
        result.put("directIpRejected",localhostRejected || isLocalhostAllowed());
        result.put("redirectMode","manual-host-revalidation");
        result.put("status",schemeRejected && (localhostRejected || isLocalhostAllowed()));
        return result;
    }

    private boolean isLocalhostAllowed() {
        for (String item : service.config().webWhitelist.split(",")) {
            String domain = item == null ? "" : item.trim().toLowerCase(Locale.ROOT);
            if ("127.0.0.1".equals(domain) || "localhost".equals(domain)) return true;
        }
        return false;
    }

    private void validate(URL url) {
        String protocol = url.getProtocol() == null ? "" : url.getProtocol().toLowerCase(Locale.ROOT);
        if (!"http".equals(protocol) && !"https".equals(protocol)) {
            throw new IllegalArgumentException("只允许 http/https 地址");
        }
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) throw new IllegalArgumentException("网址缺少主机名");
        for (String item : service.config().webWhitelist.split(",")) {
            String domain = item == null ? "" : item.trim().toLowerCase(Locale.ROOT);
            if (domain.isEmpty()) continue;
            if (host.equals(domain) || host.endsWith("."+domain)) return;
        }
        throw new IllegalArgumentException("域名不在网页白名单："+host);
    }

    private byte[] readLimited(InputStream input,int maxBytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        try {
            while ((read = input.read(buffer)) > 0) {
                if (output.size() + read > maxBytes) {
                    output.write(buffer,0,maxBytes - output.size());
                    break;
                }
                output.write(buffer,0,read);
            }
        } finally {
            input.close();
        }
        return output.toByteArray();
    }

    private Charset charset(String contentType) {
        String value = service.safe(contentType);
        for (String part : value.split(";")) {
            String item = part.trim();
            if (!item.toLowerCase(Locale.ROOT).startsWith("charset=")) continue;
            String name = item.substring("charset=".length()).trim().replace("\"","");
            try {
                return Charset.forName(name);
            } catch (Exception e) {
                return StandardCharsets.UTF_8;
            }
        }
        return StandardCharsets.UTF_8;
    }

    private String htmlToText(String html) {
        String text = html.replaceAll("(?is)<script.*?</script>"," ")
                .replaceAll("(?is)<style.*?</style>"," ")
                .replaceAll("(?is)<!--.*?-->"," ")
                .replaceAll("(?i)<br\\s*/?>","\n")
                .replaceAll("(?i)</p>","\n")
                .replaceAll("(?is)<[^>]+>"," ");
        return text.replace("&nbsp;"," ").replace("&amp;","&")
                .replace("&lt;","<").replace("&gt;",">")
                .replace("&quot;","\"").replace("&#39;","'");
    }
}
