package org.moboxlab.mbb.eval.runner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.moboxlab.mbb.eval.TestEvalAssets;
import org.moboxlab.mbb.eval.bank.EvalBankCache;
import org.moboxlab.mbb.eval.bank.EvalBankIdentity;
import org.moboxlab.mbb.eval.bank.EvalBankLocator;
import org.moboxlab.mbb.eval.bank.EvalBankProblem;
import org.moboxlab.mbb.eval.bank.EvalBankResources;
import org.moboxlab.mbb.eval.judge.EvalJudgePrompt;
import org.moboxlab.mbb.eval.rubric.EvalRubricCache;
import org.moboxlab.moboxbot.API.PluginLogger;

import com.alibaba.fastjson.JSONObject;

/**
 * B-5 onEnable 装配用例：先 rubric 后题库、缺失时释放并钉版本、失败只记录不抛异常。
 *
 * <p>对应 docs/MBB-Eval.md §5.7.4、§5.8 与 §15.3 B1/B23/B24：成功日志必须带文件名、
 * version、bank_hash、用例数与 smoke 数；目录缺失/旧文件必须给绝对路径与可执行提示。</p>
 */
public class EvalDataBootstrapTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void missingBankShouldBeReleasedAndPinned() throws Exception {
        File dataFolder = TestEvalAssets.dataFolder(temporaryFolder.newFolder());
        File evals = TestEvalAssets.ensureEvalsDirectory(dataFolder);
        JSONObject bankJson = TestEvalAssets.builtinBank();
        byte[] bankBytes = TestEvalAssets.builtinBankBytes();
        TestEvalAssets.writeValidRubric(evals, bankJson);
        String personaHash = EvalBankIdentity.parse(bankBytes).personaHash();
        RecordingLogger logger = new RecordingLogger();

        EvalDataBootstrap bootstrap = EvalDataBootstrap.load(dataFolder, "",
                EvalJudgePrompt.PROMPT_VERSION, "persona-aris.json", personaHash, false,
                new StaticResources(bankBytes), logger);

        assertTrue(logger.joined(), bootstrap.bank().ok());
        assertNotNull("缺失时应释放内置题库", bootstrap.release());
        assertTrue("释放应成功", bootstrap.release().released());
        assertEquals("首次释放后应钉成内置版本文件名",
                TestEvalAssets.BANK_FILE_NAME, bootstrap.pinFileName());
        assertTrue("释放后文件应存在",
                new File(evals, TestEvalAssets.BANK_FILE_NAME).isFile());
        assertEquals("释放后仍应使用内置版本文件名",
                TestEvalAssets.BANK_FILE_NAME, bootstrap.bank().fileName());
        assertEquals(EvalBankCache.class, bootstrap.bank().getClass());

        String logs = logger.joined();
        assertTrue("日志缺少题库摘要：" + logs, logs.contains("[评测]"));
        assertTrue(logs.contains("version=1.0.0"));
        assertTrue(logs.contains("bank_hash=" + bootstrap.bank().bankHash()));
        assertTrue(logs.contains("40 条用例"));
        assertTrue(logs.contains("smoke 12 条"));
        assertTrue("日志应含绝对路径：" + logs,
                logs.contains(bootstrap.bank().absolutePath()));
        assertEquals("rubric 应加载成功", true, bootstrap.rubric().ok());
        assertEquals("persona 匹配时不应有告警", false,
                bootstrap.bank().binding().warning());
    }

    @Test
    public void missingEvalsDirectoryShouldWarnAndKeepAbsolutePath() throws Exception {
        File dataFolder = TestEvalAssets.dataFolder(temporaryFolder.newFolder());
        File evals = TestEvalAssets.evalsDirectory(dataFolder);
        RecordingLogger logger = new RecordingLogger();

        EvalDataBootstrap bootstrap = EvalDataBootstrap.load(dataFolder,
                TestEvalAssets.BANK_FILE_NAME, EvalJudgePrompt.PROMPT_VERSION, null, null, false,
                null, logger);

        assertFalse("rubric 缺失应失败", bootstrap.rubric().ok());
        assertFalse("题库缺失应失败", bootstrap.bank().ok());
        assertEquals(EvalBankLocator.CODE_DATA_NOT_FOUND, bootstrap.bank().code());
        String logs = logger.joined();
        assertTrue("启动日志必须给出实际查找路径：" + logs, logs.contains(evals.getAbsolutePath()));
        assertTrue("启动日志应含目录级错误码：" + logs,
                logs.contains(EvalBankLocator.CODE_DATA_NOT_FOUND));
        assertTrue("失败不应抛异常：仍返回装配结果", bootstrap != null);
    }

    @Test
    public void rubricMissingShouldStopBankLoading() throws Exception {
        File dataFolder = TestEvalAssets.dataFolder(temporaryFolder.newFolder());
        File evals = TestEvalAssets.ensureEvalsDirectory(dataFolder);
        TestEvalAssets.write(evals, TestEvalAssets.BANK_FILE_NAME,
                TestEvalAssets.builtinBankBytes());
        RecordingLogger logger = new RecordingLogger();

        EvalDataBootstrap bootstrap = EvalDataBootstrap.load(dataFolder,
                TestEvalAssets.BANK_FILE_NAME, EvalJudgePrompt.PROMPT_VERSION, null, null, false,
                null, logger);

        assertFalse("rubric 缺失应拒绝", bootstrap.rubric().ok());
        assertEquals(EvalBankProblem.READ, bootstrap.rubric().code());
        assertTrue("rubric 提示应含 evals 目录绝对路径：" + bootstrap.rubric().message(),
                bootstrap.rubric().message().contains(evals.getAbsolutePath()));
        assertNull("rubric 失败时题库不加载（§5.7.4）", bootstrap.bank());
        assertTrue("日志应含 rubric 缺失：" + logger.joined(),
                logger.joined().contains("rubric"));
    }

    @Test
    public void legacyBankFileNameShouldFailWithDeprecatedHint() throws Exception {
        File dataFolder = TestEvalAssets.dataFolder(temporaryFolder.newFolder());
        File evals = TestEvalAssets.ensureEvalsDirectory(dataFolder);
        TestEvalAssets.writeValidRubric(evals, TestEvalAssets.builtinBank());
        TestEvalAssets.write(evals, "roleplay-evals.json", TestEvalAssets.builtinBankBytes());
        RecordingLogger logger = new RecordingLogger();

        EvalDataBootstrap bootstrap = EvalDataBootstrap.load(dataFolder, "roleplay-evals.json",
                EvalJudgePrompt.PROMPT_VERSION, null, null, false, null, logger);

        assertFalse(bootstrap.bank().ok());
        assertEquals(EvalBankProblem.READ, bootstrap.bank().code());
        assertTrue("必须提示旧文件已废弃：" + bootstrap.bank().message(),
                bootstrap.bank().message().contains("旧文件已废弃"));
        assertNull("旧文件不应触发释放", bootstrap.release());
        assertTrue("日志应回显旧文件提示：" + logger.joined(),
                logger.joined().contains("旧文件已废弃"));
    }

    @Test
    public void bankValidationFailureShouldLogFirstFiveOnly() throws Exception {
        File dataFolder = TestEvalAssets.dataFolder(temporaryFolder.newFolder());
        File evals = TestEvalAssets.ensureEvalsDirectory(dataFolder);
        JSONObject bank = TestEvalAssets.builtinBank();
        TestEvalAssets.writeValidRubric(evals, bank);
        bank.remove("hash");
        for (int i = 0; i < 7; i++) {
            bank.getJSONArray("cases").getJSONObject(i).put("id", "bad-" + i);
        }
        TestEvalAssets.write(evals, TestEvalAssets.BANK_FILE_NAME, bank.toJSONString());
        RecordingLogger logger = new RecordingLogger();

        EvalDataBootstrap bootstrap = EvalDataBootstrap.load(dataFolder,
                TestEvalAssets.BANK_FILE_NAME, EvalJudgePrompt.PROMPT_VERSION, null, null, false,
                null, logger);

        assertFalse(bootstrap.bank().ok());
        assertEquals(5, bootstrap.bank().errors().size());
        String logs = logger.joined();
        assertTrue("日志应含首条错误：" + logs, logs.contains("cases[0].id"));
        assertTrue("日志应说明仅回显前 5 条：" + logs, logs.contains("仅回显前 5 条"));
        assertFalse("日志不得出现第 6 条错误：" + logs, logs.contains("cases[5].id"));
        assertTrue("失败日志至少 WARN：" + logger.warns,
                !logger.warns.isEmpty());
    }

    private static final class StaticResources implements EvalBankResources {
        private final byte[] bytes;

        private StaticResources(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public byte[] read(String resourcePath) throws IOException {
            if (!resourcePath.startsWith(EvalBankLocator.BUILTIN_RESOURCE_DIRECTORY)) {
                return null;
            }
            return bytes;
        }
    }

    private static final class RecordingLogger extends PluginLogger {
        private final List<String> infos = new ArrayList<String>();
        private final List<String> warns = new ArrayList<String>();
        private final List<String> errors = new ArrayList<String>();

        private RecordingLogger() {
            super("eval-bootstrap-test", null);
        }

        @Override
        public void sendInfo(String message) {
            infos.add(message);
        }

        @Override
        public void sendWarn(String message) {
            warns.add(message);
        }

        @Override
        public void sendError(String message) {
            errors.add(message);
        }

        private String joined() {
            StringBuilder text = new StringBuilder();
            for (String message : infos) {
                text.append(message).append('\n');
            }
            for (String message : warns) {
                text.append(message).append('\n');
            }
            for (String message : errors) {
                text.append(message).append('\n');
            }
            return text.toString();
        }
    }
}