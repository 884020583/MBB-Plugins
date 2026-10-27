package org.moboxlab.mbb.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.Test;
import org.moboxlab.mbb.eval.config.EvalConfig;
import org.moboxlab.moboxbot.API.Plugin;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

/**
 * 构建产物用例（A-5：构建产物；对应 A1–A5 与 A6 的追加回归）。
 *
 * <p>断言的是打包后的 {@code out/MBB-Eval.jar} 而不是 build 目录：A2 要求 JAR 根有
 * {@code plugin.json}（明确不是仅 {@code classes/plugin.json}），A4 要求 JAR 内没有
 * {@code fastjson} 与主程序类。资源读取优先走 JAR，JAR 不存在时回落到 {@code out/build/classes}，
 * 便于只跑构建脚本的场景与单测调试场景。</p>
 */
public class EvalManifestTest {

    @Test
    public void jarShouldContainRootManifestAndResources() throws Exception {
        File jar = locateJar();
        ZipFile zip = new ZipFile(jar);
        try {
            assertNotNull("JAR 根目录应有 plugin.json：" + jar, zip.getEntry("plugin.json"));
            assertNotNull("JAR 根目录应有 config.yml", zip.getEntry("config.yml"));
            assertEquals("JAR 内不应出现测试类", 0, countEntriesWithSuffix(zip, "Test.class"));
            assertEquals("JAR 不应打包 fastjson", 0, countEntriesStartingWith(zip, "com/alibaba/fastjson/"));
            assertEquals("JAR 不应打包主程序类", 0, countEntriesStartingWith(zip, "org/moboxlab/moboxbot/"));
            assertEquals("JAR 不应打包 MBB-Eval 之外的插件类", 0,
                    countEntriesStartingWith(zip, "org/moboxlab/mbb/roleplay/"));
        } finally {
            zip.close();
        }
    }

    @Test
    public void pluginJsonShouldMatchSpec() throws Exception {
        JSONObject json = JSON.parseObject(readResource("plugin.json"));
        assertEquals("MBB-Eval", json.getString("name"));
        assertEquals("0.3", json.getString("apiVersion"));
        assertEquals("org.moboxlab.mbb.eval.EvalPlugin", json.getString("main"));
        String version = json.getString("version");
        assertTrue("版本应为 V大版本.小版本.小更新.小修正.四位时间戳：" + version,
                version != null && version.matches("V\\d+\\.\\d+\\.\\d+\\.\\d+\\.\\d{4}"));
        assertNotNull("depend 必须是数组", json.getJSONArray("depend"));
        assertEquals("depend 应为空数组", 0, json.getJSONArray("depend").size());
        assertTrue("softDepend 应含 MBB-Roleplay", json.getJSONArray("softDepend").contains("MBB-Roleplay"));
        assertTrue("softDepend 应含 MBB-AI", json.getJSONArray("softDepend").contains("MBB-AI"));
        assertTrue("provides 应含 eval", json.getJSONArray("provides").contains("eval"));
    }

    @Test
    public void pluginClassShouldExtendApiPlugin() throws Exception {
        Class<?> pluginClass = Class.forName("org.moboxlab.mbb.eval.EvalPlugin");
        assertTrue("主类必须继承 API.Plugin", Plugin.class.isAssignableFrom(pluginClass));
    }

    @Test
    public void configTemplateShouldMatchEvalConfigDefaults() throws Exception {
        Map<String, String> template = parseConfigTemplate(readResource("config.yml"));
        assertEquals("模板键数应与 §12.1 一致", 14, template.size());
        Map<String, String> defaults = EvalConfig.defaults();
        assertEquals(defaults.keySet(), template.keySet());
        for (Map.Entry<String, String> entry : defaults.entrySet()) {
            assertEquals("模板默认值应与 EvalConfig 一致：" + entry.getKey(),
                    entry.getValue(), template.get(entry.getKey()));
        }
    }

    private static int countEntriesStartingWith(ZipFile zip, String prefix) {
        int count = 0;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            if (entries.nextElement().getName().startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    private static int countEntriesWithSuffix(ZipFile zip, String suffix) {
        int count = 0;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            if (entries.nextElement().getName().endsWith(suffix)) {
                count++;
            }
        }
        return count;
    }

    /** 定位构建产物 JAR：test-classes 的同级 out 目录。 */
    private static File locateJar() throws Exception {
        File testClasses = new File(EvalManifestTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        File outDir = testClasses.getParentFile().getParentFile();
        File jar = new File(outDir, "MBB-Eval.jar");
        assertTrue("找不到构建产物，请先执行 build.ps1（-Test 会先打包再跑测试）："
                + jar.getAbsolutePath(), jar.isFile());
        return jar;
    }

    /**
     * 读取打包资源：优先从 JAR 读，回落到 build 目录的 classes。
     *
     * <p>不直接用 classpath：测试 classpath 把主程序 JAR 放在最前，主程序根目录也可能有
     * 同名 config.yml，会读取到别人的资源。</p>
     */
    private static String readResource(String name) throws Exception {
        File testClasses = new File(EvalManifestTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        File outDir = testClasses.getParentFile().getParentFile();
        File jar = new File(outDir, "MBB-Eval.jar");
        if (jar.isFile()) {
            ZipFile zip = new ZipFile(jar);
            try {
                ZipEntry entry = zip.getEntry(name);
                assertNotNull("JAR 内缺少资源：" + name, entry);
                return readAll(zip.getInputStream(entry));
            } finally {
                zip.close();
            }
        }
        File classesDir = new File(testClasses.getParentFile(), "classes");
        File file = new File(classesDir, name);
        assertTrue("找不到资源：" + file.getAbsolutePath(), file.isFile());
        return readAll(new FileInputStream(file));
    }

    private static String readAll(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    private static Map<String, String> parseConfigTemplate(String text) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (String line : text.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            assertTrue("配置模板行格式应为 key: value：" + line, colon > 0);
            String key = trimmed.substring(0, colon).trim();
            String value = trimmed.substring(colon + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            values.put(key, value);
        }
        return values;
    }
}
