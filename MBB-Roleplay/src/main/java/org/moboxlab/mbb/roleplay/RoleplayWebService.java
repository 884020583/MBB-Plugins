package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 受限网页读取与联网搜索。
 *
 * 公网域名走白名单；内网地址只有 owner 在最近上下文明确发起时才允许访问。
 */
public class RoleplayWebService {
    private static final Pattern DDG_RESULT = Pattern.compile(
            "(?is)<a[^>]+class=\"[^\"]*result__a[^\"]*\"[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>");
    private static final Pattern DDG_RESULT_HREF_FIRST = Pattern.compile(
            "(?is)<a[^>]+href=\"([^\"]+)\"[^>]+class=\"[^\"]*result__a[^\"]*\"[^>]*>(.*?)</a>");
    private static final Pattern BING_RESULT = Pattern.compile(
            "(?is)<h2[^>]*>\\s*<a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>");
    private static final Pattern TITLE_TAG = Pattern.compile("(?is)<[^>]+>");
    private static final Pattern UDDG_TARGET = Pattern.compile("[?&]uddg=([^&]+)");

    private final RoleplayService service;
    private final Map<String,CacheEntry> cache = new LinkedHashMap<String,CacheEntry>(16,0.75f,true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String,CacheEntry> eldest) {
            return size() > 100;
        }
    };

    public RoleplayWebService(RoleplayService service) {
        this.service = service;
    }

    public JSONObject fetch(String url) throws Exception {
        return fetch(url,false);
    }

    public JSONObject fetch(String url,boolean allowInternal) throws Exception {
        if (!service.config().webEnable) throw new IllegalArgumentException("网页浏览未启用");
        String key = "fetch|"+(allowInternal ? "1" : "0")+"|"+service.safe(url);
        JSONObject cached = cached(key);
        if (cached != null) return cached;
        Document document = fetchDocument(url,allowInternal);
        String text = document.contentType.contains("html") || document.raw.contains("<html")
                ? htmlToText(document.raw) : document.raw;
        text = normalizeText(text);
        JSONObject result = new JSONObject(true);
        result.put("url",document.url);
        result.put("contentType",document.contentType);
        result.put("bytes",document.bytes);
        result.put("text",text);
        result.put("trust","external-untrusted");
        result.put("cached",false);
        putCache(key,result);
        return result;
    }

    public JSONArray search(String query,boolean allowInternal) throws Exception {
        String text = service.safe(query).trim();
        if (text.isEmpty()) throw new IllegalArgumentException("搜索词不能为空");
        String template = service.safe(service.config().webSearchUrl);
        if (!template.contains("{query}")) {
            throw new IllegalArgumentException("webSearchUrl 缺少 {query} 占位符");
        }
        String url = template.replace("{query}",URLEncoder.encode(text,"UTF-8"));
        String key = "search|"+(allowInternal ? "1" : "0")+"|"+url;
        JSONObject cached = cached(key);
        if (cached != null) return cached.getJSONArray("results");
        Document document = fetchDocument(url,allowInternal);
        JSONArray results = parseSearchResults(document.raw);
        JSONObject wrapper = new JSONObject(true);
        wrapper.put("results",results);
        wrapper.put("searchUrl",document.url);
        wrapper.put("cached",false);
        putCache(key,wrapper);
        return results;
    }

    public boolean isAllowed(String rawUrl,boolean allowInternal) {
        try {
            validate(new URL(rawUrl),allowInternal);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isInternal(String rawUrl) {
        try {
            URL url = new URL(rawUrl);
            return isInternalHost(url.getHost());
        } catch (Exception e) {
            return false;
        }
    }

    public JSONObject selfTest() {
        JSONObject result = new JSONObject(true);
        boolean schemeRejected = !isAllowed("file:///etc/passwd",false);
        boolean internalRejected = !isAllowed("http://127.0.0.1/",false);
        boolean internalOwnerAllowed = isAllowed("http://127.0.0.1/",true);
        result.put("nonHttpRejected",schemeRejected);
        result.put("internalWithoutOwnerRejected",internalRejected);
        result.put("internalWithOwnerAllowed",internalOwnerAllowed);
        result.put("redirectMode","manual-host-revalidation");
        result.put("status",schemeRejected && internalRejected && internalOwnerAllowed);
        return result;
    }

    private Document fetchDocument(String rawUrl,boolean allowInternal) throws Exception {
        URL current = new URL(rawUrl);
        int redirects = 0;
        while (true) {
            validate(current,allowInternal);
            HttpURLConnection connection = (HttpURLConnection)current.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(Math.max(3,service.config().webTimeoutSecond) * 1000);
            connection.setReadTimeout(Math.max(3,service.config().webTimeoutSecond) * 1000);
            connection.setRequestProperty("User-Agent","MoBoxBot/0.1 (+https://github.com/MossCG/MoBoxBot)");
            connection.setRequestProperty("Accept",
                    "text/html,text/plain,application/json,application/xml;q=0.9,*/*;q=0.1");
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
            Document document = new Document();
            document.url = current.toString();
            document.contentType = contentType;
            document.bytes = bytes.length;
            document.raw = new String(bytes,charset(contentType));
            return document;
        }
    }

    private void validate(URL url,boolean allowInternal) {
        String protocol = url.getProtocol() == null ? "" : url.getProtocol().toLowerCase(Locale.ROOT);
        if (!"http".equals(protocol) && !"https".equals(protocol)) {
            throw new IllegalArgumentException("只允许 http/https 地址");
        }
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) throw new IllegalArgumentException("网址缺少主机名");
        boolean internal = isInternalHost(host);
        if (internal) {
            if (!allowInternal) {
                throw new IllegalArgumentException("内网地址需要 owner 在最近上下文中明确发起访问");
            }
            return;
        }
        for (String item : service.safe(service.config().webWhitelist).split(",")) {
            String domain = item == null ? "" : item.trim().toLowerCase(Locale.ROOT);
            if (domain.isEmpty()) continue;
            if (host.equals(domain) || host.endsWith("."+domain)) return;
        }
        throw new IllegalArgumentException("域名不在网页白名单："+host);
    }

    private boolean isInternalHost(String host) {
        String value = service.safe(host).trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return false;
        if ("localhost".equals(value) || value.endsWith(".localhost")) return true;
        try {
            InetAddress[] addresses = InetAddress.getAllByName(value);
            if (addresses == null) return false;
            for (InetAddress address : addresses) {
                if (isInternalAddress(address)) return true;
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    private boolean isInternalAddress(InetAddress address) {
        if (address == null) return false;
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes == null) return false;
        if (bytes.length == 16) {
            return (bytes[0] & 0xfe) == 0xfc;
        }
        if (bytes.length != 4) return false;
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        if (first == 0 || first == 10 || first == 127) return true;
        if (first == 100 && second >= 64 && second <= 127) return true;
        if (first == 169 && second == 254) return true;
        if (first == 172 && second >= 16 && second <= 31) return true;
        return first == 192 && second == 168;
    }

    JSONArray parseSearchResults(String html) {
        JSONArray results = new JSONArray();
        String raw = service.safe(html);
        extractResults(DDG_RESULT.matcher(raw),results);
        extractResults(DDG_RESULT_HREF_FIRST.matcher(raw),results);
        if (results.isEmpty()) extractResults(BING_RESULT.matcher(raw),results);
        int max = Math.max(1,service.config().webSearchMaxResults);
        if (results.size() > max) {
            JSONArray limited = new JSONArray();
            for (int i = 0; i < max; i++) limited.add(results.get(i));
            return limited;
        }
        return results;
    }

    private void extractResults(Matcher matcher,JSONArray results) {
        while (matcher.find() && results.size() < service.config().webSearchMaxResults) {
            String url = decodeSearchUrl(matcher.group(1));
            String title = decodeHtml(TITLE_TAG.matcher(service.safe(matcher.group(2)))
                    .replaceAll(" ")).replaceAll("\\s+"," ").trim();
            if (url.isEmpty() || title.isEmpty()) continue;
            boolean duplicate = false;
            for (Object object : results) {
                if (object instanceof JSONObject
                        && url.equals(((JSONObject)object).getString("url"))) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) continue;
            JSONObject item = new JSONObject(true);
            item.put("title",title);
            item.put("url",url);
            results.add(item);
        }
    }

    private String decodeSearchUrl(String value) {
        String url = decodeHtml(service.safe(value)).trim();
        if (url.startsWith("//")) url = "https:"+url;
        Matcher matcher = UDDG_TARGET.matcher(url);
        if (matcher.find()) {
            try {
                url = URLDecoder.decode(matcher.group(1),"UTF-8");
            } catch (Exception ignored) {
            }
        }
        return url;
    }

    private String decodeHtml(String html) {
        return html.replace("&amp;","&").replace("&lt;","<").replace("&gt;",">")
                .replace("&quot;","\"").replace("&#39;","'");
    }

    private String normalizeText(String text) {
        String result = service.safe(text).replace("\u0000","")
                .replaceAll("[ \\t]+"," ")
                .replaceAll("\\n{3,}","\n\n").trim();
        if (result.length() > service.config().webMaxChars) {
            result = result.substring(0,service.config().webMaxChars);
        }
        return result;
    }

    private String htmlToText(String html) {
        String text = html.replaceAll("(?is)<script.*?</script>"," ")
                .replaceAll("(?is)<style.*?</style>"," ")
                .replaceAll("(?is)<!--.*?-->"," ")
                .replaceAll("(?i)<br\\s*/?>","\n")
                .replaceAll("(?i)</p>","\n")
                .replaceAll("(?is)<[^>]+>"," ");
        return decodeHtml(text).replace("&nbsp;"," ");
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

    private JSONObject cached(String key) {
        if (service.config().webCacheSecond <= 0) return null;
        synchronized (cache) {
            CacheEntry entry = cache.get(key);
            if (entry == null) return null;
            if (entry.expireAt < System.currentTimeMillis()) {
                cache.remove(key);
                return null;
            }
            JSONObject copy = new JSONObject(true);
            copy.putAll(entry.value);
            copy.put("cached",true);
            return copy;
        }
    }

    private void putCache(String key,JSONObject value) {
        if (service.config().webCacheSecond <= 0 || value == null) return;
        JSONObject copy = new JSONObject(true);
        copy.putAll(value);
        synchronized (cache) {
            cache.put(key,new CacheEntry(
                    System.currentTimeMillis()+service.config().webCacheSecond * 1000L,copy));
        }
    }

    private static class Document {
        private String url = "";
        private String contentType = "";
        private int bytes;
        private String raw = "";
    }

    private static class CacheEntry {
        private final long expireAt;
        private final JSONObject value;

        private CacheEntry(long expireAt,JSONObject value) {
            this.expireAt = expireAt;
            this.value = value;
        }
    }
}
