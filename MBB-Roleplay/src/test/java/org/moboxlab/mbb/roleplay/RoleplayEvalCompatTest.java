package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * evalApiEnable 正式键、旧键 evalEnable 兼容与一次性废弃 WARN 的测试（规格 #50 I-5）。
 *
 * <p>锁定四件事：开关在配置层按 {@code evalApiEnable && evalEnable} 收口；新键缺省为 true；
 * 旧键显式存在时同一进程只提示一次；迁移表与配置模板都已登记正式键并标注旧键废弃。</p>
 */
public class RoleplayEvalCompatTest {

    @Test
    public void 评测开关按真值表取AND() {
        assertTrue("true/true 应放行", allowed(true, true));
        assertFalse("true/false 应关闭", allowed(true, false));
        assertFalse("false/true 应关闭", allowed(false, true));
        assertFalse("false/false 应关闭", allowed(false, false));
    }

    @Test
    public void 缺键时评测接口默认开启() {
        RoleplayConfig config = new RoleplayConfig();
        assertTrue("evalApiEnable 缺省应为 true", config.evalApiEnable);
        assertTrue("evalEnable 缺省应为 true", config.evalEnable);
        assertTrue("缺键时总开关应为 true", config.evalApiAllowed());
        assertTrue("配置尚未加载时退回插件配置，缺键按 true",
                RoleplayEvalGateway.evalApiEnabled(null, null));
    }

    @Test
    public void 旧键显式存在时只WARN一次() {
        RoleplayConfig.resetLegacyEvalEnableWarning();
        try {
            assertTrue("首次加载应提示一次", RoleplayConfig.warnLegacyEvalEnableOnce(true));
            for (int i = 0; i < 8; i++) {
                assertFalse("同一进程后续加载不得重复提示",
                        RoleplayConfig.warnLegacyEvalEnableOnce(true));
            }
        } finally {
            RoleplayConfig.resetLegacyEvalEnableWarning();
        }
    }

    @Test
    public void 旧键缺省不消耗一次性WARN() {
        RoleplayConfig.resetLegacyEvalEnableWarning();
        try {
            assertFalse("缺键时不得提示", RoleplayConfig.warnLegacyEvalEnableOnce(false));
            assertTrue("缺键不消耗额度，后续显式存在时仍应提示一次",
                    RoleplayConfig.warnLegacyEvalEnableOnce(true));
        } finally {
            RoleplayConfig.resetLegacyEvalEnableWarning();
        }
    }

    @Test
    public void 迁移表登记evalApiEnable并标记旧键废弃() throws Exception {
        Field defaultsField = RoleplayConfigMigrator.class.getDeclaredField("DEFAULTS");
        defaultsField.setAccessible(true);
        List<?> defaults = (List<?>) defaultsField.get(null);

        String apiComment = null;
        String legacyComment = null;
        for (Object entry : defaults) {
            Class<?> entryType = entry.getClass();
            Field keyField = entryType.getDeclaredField("key");
            keyField.setAccessible(true);
            Field commentField = entryType.getDeclaredField("comment");
            commentField.setAccessible(true);
            String key = String.valueOf(keyField.get(entry));
            String comment = String.valueOf(commentField.get(entry));
            if ("evalApiEnable".equals(key)) {
                apiComment = comment;
            } else if ("evalEnable".equals(key)) {
                legacyComment = comment;
            }
        }

        assertNotNull("迁移表缺少 evalApiEnable 默认条目", apiComment);
        assertTrue("evalApiEnable 注释应说明 MBB-Eval 调用面",
                apiComment.contains("MBB-Eval") && apiComment.contains("eval-generate"));
        assertNotNull("迁移表仍应保留 evalEnable 旧键读取", legacyComment);
        assertTrue("evalEnable 条目应标注已废弃", legacyComment.contains("已废弃"));
    }

    @Test
    public void 配置模板包含evalApiEnable与废弃说明() throws Exception {
        // 按 RoleplayConfig 的类源定位构建产物，避免类路径上其他插件同名 config.yml 抢先命中
        File classesDir = new File(
                RoleplayConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        File templateFile = new File(classesDir, "config.yml");
        assertTrue("构建产物中应有 config.yml 模板：" + templateFile.getAbsolutePath(), templateFile.isFile());
        String template = new String(Files.readAllBytes(templateFile.toPath()), StandardCharsets.UTF_8);
        assertTrue("模板缺少 evalApiEnable 正式键", template.contains("evalApiEnable: true"));
        assertTrue("模板应标注旧键废弃与等价关系",
                template.contains("已废弃，等价于 evalApiEnable"));
    }

    @Test
    public void 能力清单按配置层AND收口() {
        RoleplayConfig disabled = new RoleplayConfig();
        disabled.evalApiEnable = true;
        disabled.evalEnable = false;
        JSONObject off = RoleplayEvalGateway.buildCapabilities(null, disabled, null);
        assertFalse("旧键为 false 时能力清单应关闭", off.getBooleanValue("evalApiEnable"));
        JSONArray offActions = off.getJSONArray("actions");
        assertEquals("关闭时只保留 capabilities", 1, offActions.size());
        assertEquals("capabilities", offActions.getString(0));

        RoleplayConfig enabled = new RoleplayConfig();
        enabled.evalApiEnable = true;
        enabled.evalEnable = true;
        JSONObject on = RoleplayEvalGateway.buildCapabilities(null, enabled, null);
        assertTrue("两个键都为 true 时应开启", on.getBooleanValue("evalApiEnable"));
        JSONArray onActions = on.getJSONArray("actions");
        assertEquals("开启时保留两个动作", 2, onActions.size());
        assertEquals("eval-generate", onActions.getString(1));
    }

    private static boolean allowed(boolean apiEnable, boolean legacyEnable) {
        RoleplayConfig config = new RoleplayConfig();
        config.evalApiEnable = apiEnable;
        config.evalEnable = legacyEnable;
        return config.evalApiAllowed();
    }
}
