package org.moboxlab.mbb.eval.bank;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.moboxlab.mbb.eval.TestEvalAssets;
import org.moboxlab.mbb.eval.judge.EvalJudgePrompt;
import org.moboxlab.mbb.eval.rubric.EvalRubric;
import org.moboxlab.mbb.eval.rubric.EvalRubricCache;
import org.moboxlab.mbb.eval.rubric.EvalRubricLoadResult;
import org.moboxlab.mbb.eval.rubric.EvalRubricLoader;
import org.moboxlab.mbb.eval.rubric.EvalRubricProblem;
import org.moboxlab.mbb.eval.runner.EvalDataBootstrap;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.StageReport;
import org.moboxlab.mbb.eval.runner.EvalStageSources;
import org.moboxlab.moboxbot.API.PluginLogger;

import com.alibaba.fastjson.JSONObject;

/**
 * R-1 内置 rubric 资源与首次释放用例（§5.8、§6.2、§6.5.4、§15.3 B28）。
 *
 * <p>覆盖首次释放后加载与预检通过、已存在不覆盖、内置资源自检，
 * 以及 B28 的三种改副本情形：删 soft.length、删整个文件、promptVersion 不一致。</p>
 */
public class EvalRubricReleaserTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void firstBootShouldReleaseRubricAndPreflightPass() throws Exception {
        File dataFolder = TestEvalAssets.dataFolder(temporaryFolder.newFolder());
        File evals = TestEvalAssets.ensureEvalsDirectory(dataFolder);
        byte[] rubricBytes = TestEvalAssets.builtinRubricBytes();
        MapResources resources = new MapResources();
        resources.put(EvalRubricReleaser.BUILTIN_RUBRIC_RESOURCE_PATH, rubricBytes);
        RecordingLogger logger = new RecordingLogger();

        EvalRubricReleaseResult result = EvalRubricReleaser.release(
                EvalBankLocator.locate(dataFolder, ""), resources, logger);

        assertEquals("首次启动应释放 rubric：" + logger.joined(),
                EvalRubricReleaseResult.Status.RELEASED, result.status());
        File target = new File(evals, TestEvalAssets.RUBRIC_FILE_NAME);
        assertTrue("释放后目标文件应存在", target.isFile());
        assertEquals("释放内容必须与内置资源逐字节一致", rubricBytes.length, target.length());
        assertArrayEquals(rubricBytes, Files.readAllBytes(target.toPath()));

        EvalRubricCache cache = EvalRubricCache.load(dataFolder, EvalJudgePrompt.PROMPT_VERSION);
        assertTrue("释放后 rubric 应加载成功：" + cache.message(), cache.ok());
        assertEquals(TestEvalAssets.RUBRIC_FILE_NAME, cache.file().getName());

        StageReport report = EvalStageSources.rubric(cache).report("smoke");
        assertEquals("预检 rubric 阶段应 PASS：" + report.message(),
                StageReport.Outcome.PASS, report.outcome());

        String logs = logger.joined();
        assertTrue("缺少释放摘要：" + logs, logs.contains("已释放内置 rubric"));
        assertTrue(logs.contains("version=" + EvalRubricReleaser.BUILTIN_RUBRIC_VERSION));
        assertTrue(logs.contains("promptVersion=1"));
        assertTrue("摘要应含五维权重：" + logs, logs.contains("persona=0.30"));
        assertTrue("摘要应含五维权重：" + logs, logs.contains("boundary=0.25"));
        assertTrue("摘要应含五维权重：" + logs, logs.contains("memory=0.20"));
        assertTrue("摘要应含五维权重：" + logs, logs.contains("knowledge=0.15"));
        assertTrue("摘要应含五维权重：" + logs, logs.contains("style=0.10"));
        assertTrue("摘要应含目标绝对路径：" + logs, logs.contains(target.getAbsolutePath()));
    }

    @Test
    public void existingRubricShouldNotBeOverwritten() throws Exception {
        File dataFolder = TestEvalAssets.dataFolder(temporaryFolder.newFolder());
        File evals = TestEvalAssets.ensureEvalsDirectory(dataFolder);
        File existing = TestEvalAssets.writeValidRubric(evals, TestEvalAssets.builtinBank());
        assertTrue("测试需要可控的修改时间", existing.setLastModified(1234567890000L));
        byte[] before = Files.readAllBytes(existing.toPath());
        long modifiedBefore = existing.lastModified();
        int rubricCountBefore = countRubricFiles(evals);

        MapResources resources = new MapResources();
        resources.put(EvalRubricReleaser.BUILTIN_RUBRIC_RESOURCE_PATH,
                TestEvalAssets.builtinRubricBytes());
        RecordingLogger logger = new RecordingLogger();

        EvalRubricReleaseResult result = EvalRubricReleaser.release(
                EvalBankLocator.locate(dataFolder, ""), resources, logger);

        assertEquals("已有 rubric 时不得覆盖：" + logger.joined(),
                EvalRubricReleaseResult.Status.SKIPPED_EXISTS, result.status());
        assertFalse("不得写出内置版本文件",
                new File(evals, TestEvalAssets.RUBRIC_FILE_NAME).exists());
        assertArrayEquals("既有 rubric 字节不得改变", before,
                Files.readAllBytes(existing.toPath()));
        assertEquals("既有 rubric 修改时间不得改变", modifiedBefore, existing.lastModified());
        assertEquals("目录内 rubric 数量不得变化", rubricCountBefore, countRubricFiles(evals));
        assertTrue("跳过时应说明原因", result.message().contains("已存在"));
    }

    @Test
    public void builtinRubricShouldBeSelfConsistent() throws Exception {
        File dataFolder = TestEvalAssets.dataFolder(temporaryFolder.newFolder());
        File evals = TestEvalAssets.ensureEvalsDirectory(dataFolder);
        byte[] bytes = TestEvalAssets.builtinRubricBytes();
        File file = TestEvalAssets.write(evals, TestEvalAssets.RUBRIC_FILE_NAME, bytes);

        EvalRubricLoadResult load = EvalRubricLoader.load(file,
                EvalRubricReleaser.BUILTIN_RUBRIC_PROMPT_VERSION);
        assertTrue("内置 rubric 自检失败：" + load.problems(), load.ok());
        EvalRubric rubric = load.rubric();

        assertEquals(EvalRubricReleaser.BUILTIN_RUBRIC_VERSION, rubric.version());
        assertEquals("1.1.0", rubric.semanticVersion());
        assertEquals("1", rubric.promptVersion());
        assertEquals("文件名必须与 version 一致",
                "rubric-" + rubric.version() + ".json", file.getName());
        assertEquals("内容哈希必须等于版本后缀",
                rubric.version().substring(rubric.version().indexOf('-') + 1),
                EvalRubricLoader.contentSha8(
                        new String(bytes, StandardCharsets.UTF_8)));

        List<String> ids = new ArrayList<String>();
        BigDecimal weightSum = BigDecimal.ZERO;
        for (EvalRubric.Dimension dimension : rubric.dimensions()) {
            ids.add(dimension.id());
            weightSum = weightSum.add(BigDecimal.valueOf(dimension.weight()));
            for (int anchor = 0; anchor <= 2; anchor++) {
                assertFalse("维度 " + dimension.id() + " 缺少档位 " + anchor,
                        dimension.scaleAnchor(anchor).trim().isEmpty());
            }
            assertFalse("维度 " + dimension.id() + " 缺少正例",
                    dimension.examples().get(0).positive().trim().isEmpty());
            assertFalse("维度 " + dimension.id() + " 缺少反例",
                    dimension.examples().get(0).negative().trim().isEmpty());
        }
        assertEquals("五维 id 必须与冻结顺序一致", EvalRubric.DIMENSION_IDS, ids);
        assertEquals("五维权重之和必须为 1.00",
                0, weightSum.compareTo(new BigDecimal("1.00")));
    }

    @Test
    public void corruptedBuiltinShouldFailBeforeWriting() throws Exception {
        File dataFolder = TestEvalAssets.dataFolder(temporaryFolder.newFolder());
        File evals = TestEvalAssets.ensureEvalsDirectory(dataFolder);
        String corrupted = new String(TestEvalAssets.builtinRubricBytes(),
                StandardCharsets.UTF_8).replace("角色", "人物");
        MapResources resources = new MapResources();
        resources.put(EvalRubricReleaser.BUILTIN_RUBRIC_RESOURCE_PATH,
                corrupted.getBytes(StandardCharsets.UTF_8));
        RecordingLogger logger = new RecordingLogger();

        EvalRubricReleaseResult result = EvalRubricReleaser.release(
                EvalBankLocator.locate(dataFolder, ""), resources, logger);

        assertEquals(EvalRubricReleaseResult.Status.FAILED, result.status());
        assertEquals(EvalRubricProblem.CODE_HASH, result.errorCode());
        File target = new File(evals, TestEvalAssets.RUBRIC_FILE_NAME);
        assertFalse("自检失败时不得写出目标文件", target.exists());
        String errors = logger.joined();
        assertTrue("错误日志应含错误码：" + errors,
                errors.contains(EvalRubricProblem.CODE_HASH));
        assertTrue("错误日志应含目标绝对路径：" + errors,
                errors.contains(target.getAbsolutePath()));
    }

    @Test
    public void b28DeletingWholeRubricShouldReleaseAgain() throws Exception {
        File dataFolder = TestEvalAssets.dataFolder(temporaryFolder.newFolder());
        File evals = TestEvalAssets.ensureEvalsDirectory(dataFolder);
        byte[] bankBytes = TestEvalAssets.builtinBankBytes();
        String personaHash = EvalBankIdentity.parse(bankBytes).personaHash();
        MapResources resources = new MapResources();
        resources.put(EvalBankLocator.BUILTIN_RESOURCE_DIRECTORY + TestEvalAssets.BANK_FILE_NAME,
                bankBytes);
        resources.put(EvalRubricReleaser.BUILTIN_RUBRIC_RESOURCE_PATH,
                TestEvalAssets.builtinRubricBytes());

        EvalDataBootstrap first = EvalDataBootstrap.load(dataFolder, "",
                EvalJudgePrompt.PROMPT_VERSION, "persona-aris.json", personaHash, false,
                resources, resources, new RecordingLogger());
        assertNotNull("题库缺失时应释放", first.release());
        assertTrue("首次启动应释放 rubric", first.rubricRelease().released());
        assertTrue(first.rubric().ok());
        assertTrue(first.bank().ok());

        File rubricFile = new File(evals, TestEvalAssets.RUBRIC_FILE_NAME);
        assertTrue("应能删除 rubric 文件复现 B28", rubricFile.delete());
        RecordingLogger secondLogger = new RecordingLogger();

        EvalDataBootstrap second = EvalDataBootstrap.load(dataFolder,
                TestEvalAssets.BANK_FILE_NAME, EvalJudgePrompt.PROMPT_VERSION,
                "persona-aris.json", personaHash, false,
                resources, resources, secondLogger);

        assertTrue("rubric 整文件删除后应重新释放：" + secondLogger.joined(),
                second.rubricRelease().released());
        assertTrue("重新释放后应可加载：" + second.rubric().message(),
                second.rubric().ok());
        assertTrue("题库应继续可用", second.bank().ok());
        assertTrue(new File(evals, TestEvalAssets.RUBRIC_FILE_NAME).isFile());
    }

    @Test
    public void b28RemovingStyleSoftLengthShouldFailBankValidation() throws Exception {
        File dataFolder = TestEvalAssets.dataFolder(temporaryFolder.newFolder());
        File evals = TestEvalAssets.ensureEvalsDirectory(dataFolder);
        byte[] bankBytes = TestEvalAssets.builtinBankBytes();
        TestEvalAssets.write(evals, TestEvalAssets.BANK_FILE_NAME, bankBytes);
        String personaHash = EvalBankIdentity.parse(bankBytes).personaHash();

        JSONObject rubric = TestEvalAssets.builtinRubric();
        JSONObject soft = rubric.getJSONObject("deterministic")
                .getJSONObject("style").getJSONObject("soft");
        assertNotNull("内置 rubric 应含 style.soft.length", soft.remove("length"));
        TestEvalAssets.writeResealedRubric(evals, rubric);

        MapResources resources = new MapResources();
        resources.put(EvalBankLocator.BUILTIN_RESOURCE_DIRECTORY + TestEvalAssets.BANK_FILE_NAME,
                bankBytes);
        RecordingLogger logger = new RecordingLogger();

        EvalDataBootstrap bootstrap = EvalDataBootstrap.load(dataFolder,
                TestEvalAssets.BANK_FILE_NAME, EvalJudgePrompt.PROMPT_VERSION,
                "persona-aris.json", personaHash, true, resources, resources, logger);

        assertTrue("改副本后 rubric 仍应加载：" + bootstrap.rubric().message(),
                bootstrap.rubric().ok());
        assertNotNull(bootstrap.bank());
        assertFalse("题库应因 style 覆写校验失败", bootstrap.bank().ok());
        assertEquals("§15.3 B28：应报 style 覆写错误：" + logger.joined(),
                EvalBankProblem.STYLE_MOUNT, bootstrap.bank().code());
        assertFalse(bootstrap.bank().errors().isEmpty());
    }

    @Test
    public void b28PromptVersionMismatchShouldRejectRun() throws Exception {
        File dataFolder = TestEvalAssets.dataFolder(temporaryFolder.newFolder());
        File evals = TestEvalAssets.ensureEvalsDirectory(dataFolder);
        byte[] bankBytes = TestEvalAssets.builtinBankBytes();
        TestEvalAssets.write(evals, TestEvalAssets.BANK_FILE_NAME, bankBytes);
        String personaHash = EvalBankIdentity.parse(bankBytes).personaHash();

        JSONObject rubric = TestEvalAssets.builtinRubric();
        rubric.put("promptVersion", "2");
        TestEvalAssets.writeResealedRubric(evals, rubric);

        MapResources resources = new MapResources();
        resources.put(EvalBankLocator.BUILTIN_RESOURCE_DIRECTORY + TestEvalAssets.BANK_FILE_NAME,
                bankBytes);
        RecordingLogger logger = new RecordingLogger();

        EvalDataBootstrap bootstrap = EvalDataBootstrap.load(dataFolder,
                TestEvalAssets.BANK_FILE_NAME, EvalJudgePrompt.PROMPT_VERSION,
                "persona-aris.json", personaHash, true, resources, resources, logger);

        assertFalse("promptVersion 不一致应拒绝加载 rubric", bootstrap.rubric().ok());
        assertEquals(EvalBankProblem.VERSION, bootstrap.rubric().code());
        assertTrue("提示应说明 promptVersion：" + bootstrap.rubric().message(),
                bootstrap.rubric().message().contains("promptVersion"));
        assertNull("rubric 失败时题库不加载（§5.7.4）", bootstrap.bank());

        StageReport report = EvalStageSources.rubric(bootstrap.rubric()).report("smoke");
        assertEquals("预检应 REJECT", StageReport.Outcome.REJECT, report.outcome());
        assertEquals(EvalBankProblem.VERSION, report.code());
    }

    private static int countRubricFiles(File evals) {
        int count = 0;
        File[] files = evals.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.getName().startsWith("rubric-")
                        && file.getName().endsWith(".json")) {
                    count++;
                }
            }
        }
        return count;
    }

    /** 按精确资源路径返回字节，未登记路径返回 null。 */
    private static final class MapResources implements EvalBankResources {
        private final Map<String, byte[]> entries = new HashMap<String, byte[]>();

        private void put(String path, byte[] bytes) {
            entries.put(path, bytes.clone());
        }

        @Override
        public byte[] read(String resourcePath) throws IOException {
            byte[] bytes = entries.get(resourcePath);
            return bytes == null ? null : bytes.clone();
        }
    }

    private static final class RecordingLogger extends PluginLogger {
        private final List<String> infos = new ArrayList<String>();
        private final List<String> warns = new ArrayList<String>();
        private final List<String> errors = new ArrayList<String>();

        private RecordingLogger() {
            super("eval-rubric-release-test", null);
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
