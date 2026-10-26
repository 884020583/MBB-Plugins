package org.moboxlab.mbb.eval.bank;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.moboxlab.mbb.eval.TestEvalAssets;
import org.moboxlab.mbb.eval.store.EvalStore;

import com.alibaba.fastjson.JSONObject;

/**
 * B-5 题库缓存用例：onEnable 一次性加载后的摘要字段、错误截断与稳定顺序、目录/旧文件提示。
 *
 * <p>摘要里的 bank_hash 必须来自缓存内容（与 {@link EvalHashUtil#bankHash(byte[])} 一致），
 * 不允许写死常量；两次独立加载的可见错误必须逐字相同（§15.3 B29）。</p>
 */
public class EvalBankCacheTest {

    private static final List<String> DIMENSIONS = Arrays.asList(
            "persona", "style", "knowledge", "boundary", "memory");
    private static final List<String> HARD_IDS = Arrays.asList(
            "no-markdown", "no-dash", "no-cs-tone", "no-honorific", "no-paired-quote");
    private static final List<String> SOFT_IDS = Arrays.asList(
            "length", "self-third-person", "catchphrase-low-freq", "sentence-length-variance");

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void loadedBankShouldExposeSummaryFieldsFromContent() throws Exception {
        File dataFolder = TestEvalAssets.dataFolder(temporaryFolder.newFolder());
        File evals = TestEvalAssets.ensureEvalsDirectory(dataFolder);
        byte[] bytes = TestEvalAssets.builtinBankBytes();
        File bankFile = TestEvalAssets.write(evals, TestEvalAssets.BANK_FILE_NAME, bytes);

        EvalBankCache cache = EvalBankCache.load(
                EvalBankLocator.locate(dataFolder, TestEvalAssets.BANK_FILE_NAME),
                options(), null, null, false);

        assertTrue(failureText(cache), cache.ok());
        assertEquals(TestEvalAssets.BANK_FILE_NAME, cache.fileName());
        assertEquals("1.0.0", cache.version());
        assertEquals("bank_hash 必须来自缓存内容", EvalHashUtil.bankHash(bytes), cache.bankHash());
        assertEquals("bank_hash 为 12 位小写十六进制", 12, cache.bankHash().length());
        assertEquals(cache.bankHash(), cache.bankHash().toLowerCase());
        // 结构性断言：按缓存内容统计，题库增删用例时不会因写死数字误报。
        int expectedCases = cache.json().getJSONArray("cases").size();
        int expectedSmoke = 0;
        for (int i = 0; i < expectedCases; i++) {
            if (EvalStore.TIER_SMOKE.equals(
                    cache.json().getJSONArray("cases").getJSONObject(i).getString("tier"))) {
                expectedSmoke++;
            }
        }
        assertEquals("caseCount 与题库内容一致", expectedCases, cache.caseCount());
        assertEquals("smokeCount 与题库内容一致", expectedSmoke, cache.smokeCount());
        assertEquals(bankFile.getAbsolutePath(), cache.absolutePath());
        assertEquals("原始字节应原样缓存", bytes.length, cache.raw().length);
        assertNotNull("JSON 对象应缓存", cache.json());
        assertNotNull("校验结果应缓存", cache.check());
        assertNotNull("身份信息应缓存", cache.identity());

        String summary = cache.summary();
        for (String fragment : new String[] {
                TestEvalAssets.BANK_FILE_NAME, "version=1.0.0", "bank_hash=" + cache.bankHash(),
                expectedCases + " 条用例", "smoke " + expectedSmoke + " 条",
                bankFile.getAbsolutePath() }) {
            assertTrue("摘要缺少字段 " + fragment + "：" + summary, summary.contains(fragment));
        }
        assertTrue("成功时不应有错误码：" + cache.code(), cache.code().isEmpty());
        assertTrue("成功时错误清单为空：" + cache.errors(), cache.errors().isEmpty());
    }

    @Test
    public void directoryMissingShouldReportAbsoluteSearchPath() throws Exception {
        File dataFolder = TestEvalAssets.dataFolder(temporaryFolder.newFolder());
        File evals = TestEvalAssets.evalsDirectory(dataFolder);

        EvalBankCache cache = EvalBankCache.load(
                EvalBankLocator.locate(dataFolder, TestEvalAssets.BANK_FILE_NAME),
                options(), null, null, false);

        assertFalse("目录缺失必须加载失败", cache.ok());
        assertEquals(EvalBankLocator.CODE_DATA_NOT_FOUND, cache.code());
        assertTrue("消息应含实际查找路径：" + cache.message(),
                cache.message().contains(evals.getAbsolutePath()));
        assertTrue("提示应给出修复方法：" + cache.hint(),
                cache.hint().contains("evals") && cache.hint().contains("重启"));
        assertNull("失败时不应有 JSON 对象", cache.json());
    }

    @Test
    public void missingBankFileShouldReportAbsolutePath() throws Exception {
        File dataFolder = TestEvalAssets.dataFolder(temporaryFolder.newFolder());
        File evals = TestEvalAssets.ensureEvalsDirectory(dataFolder);
        File expected = new File(evals, TestEvalAssets.BANK_FILE_NAME);

        EvalBankCache cache = EvalBankCache.load(
                EvalBankLocator.locate(dataFolder, TestEvalAssets.BANK_FILE_NAME),
                options(), null, null, false);

        assertFalse(cache.ok());
        assertEquals(EvalBankProblem.READ, cache.code());
        assertTrue("消息应含题库绝对路径：" + cache.message(),
                cache.message().contains(expected.getAbsolutePath()));
        assertTrue("提示应含绝对路径：" + cache.hint(), cache.hint().contains(expected.getAbsolutePath()));
    }

    @Test
    public void legacyBankFileShouldFailWithDeprecatedHint() throws Exception {
        File dataFolder = TestEvalAssets.dataFolder(temporaryFolder.newFolder());
        File evals = TestEvalAssets.ensureEvalsDirectory(dataFolder);
        byte[] bytes = TestEvalAssets.builtinBankBytes();
        TestEvalAssets.write(evals, EvalLegacyFileGuard.LEGACY_FILE_NAME, bytes);

        EvalBankCache cache = EvalBankCache.load(
                EvalBankLocator.locate(dataFolder, EvalLegacyFileGuard.LEGACY_FILE_NAME),
                options(), null, null, false);

        assertFalse("旧文件必须拒绝加载", cache.ok());
        assertEquals("旧文件对外按 E_BANK_READ 呈现", EvalBankProblem.READ, cache.code());
        String text = cache.message() + " " + cache.hint();
        assertTrue("必须给出旧文件已废弃提示：" + text, text.contains("旧文件已废弃"));
        assertTrue("必须给出新文件名：" + text, text.contains("bank-<version>.json"));
        assertTrue("必须含实际路径：" + text,
                text.contains(new File(evals, EvalLegacyFileGuard.LEGACY_FILE_NAME).getAbsolutePath()));
    }

    @Test
    public void validationErrorsShouldTruncateToFiveAndStayStable() throws Exception {
        File dataFolder = TestEvalAssets.dataFolder(temporaryFolder.newFolder());
        File evals = TestEvalAssets.ensureEvalsDirectory(dataFolder);
        JSONObject bank = TestEvalAssets.builtinBank();
        bank.remove("hash");
        for (int i = 0; i < 7; i++) {
            bank.getJSONArray("cases").getJSONObject(i).put("id", "bad-" + i);
        }
        TestEvalAssets.write(evals, TestEvalAssets.BANK_FILE_NAME, bank.toJSONString());

        EvalBankLocation location = EvalBankLocator.locate(dataFolder, TestEvalAssets.BANK_FILE_NAME);
        EvalBankCache first = EvalBankCache.load(location, options(), null, null, false);
        EvalBankCache second = EvalBankCache.load(location, options(), null, null, false);

        assertFalse("7 处错误应整份拒绝", first.ok());
        assertEquals(EvalBankProblem.ID, first.code());
        assertEquals("最多回显 5 条", 5, first.errors().size());
        assertTrue("首条应是 cases[0]：" + first.errors().get(0),
                first.errors().get(0).contains("cases[0].id"));
        assertTrue("第 5 条应是 cases[4]：" + first.errors().get(4),
                first.errors().get(4).contains("cases[4].id"));
        for (String error : first.errors()) {
            assertTrue("错误行必须带封闭错误码：" + error, error.startsWith(EvalBankProblem.ID));
        }
        assertTrue("消息应给错误总数：" + first.message(), first.message().contains("7"));
        assertEquals("两次加载的可见错误必须完全一致", first.errors(), second.errors());
        assertTrue("失败摘要应说明仅回显前 5 条",
                first.summary().contains("仅回显前 5 条"));
    }

    private static EvalBankValidator.Options options() {
        return EvalBankValidator.Options.defaults()
                .expectedFileName(TestEvalAssets.BANK_FILE_NAME)
                .dimensionIds(DIMENSIONS)
                .styleCatalog(HARD_IDS, SOFT_IDS);
    }

    private static String failureText(EvalBankCache cache) {
        return cache.code() + "：" + cache.message() + "；" + cache.hint();
    }
}
