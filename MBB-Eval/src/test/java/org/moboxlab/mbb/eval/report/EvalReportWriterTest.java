package org.moboxlab.mbb.eval.report;

import com.alibaba.fastjson.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.moboxlab.mbb.eval.store.EvalSchemaService;
import org.moboxlab.mbb.eval.store.EvalStorageFake;
import org.moboxlab.mbb.eval.store.EvalStore;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** F-4 报表渲染测试（§8.3 九节、§8.4 对比口径、§8.5 告警、§15.9 I2 隐私、F20 重建覆盖）。 */
public class EvalReportWriterTest {

    private static final String RUN = "r-20261025230000-full-7k2m";
    private static final String BASE_RUN = "r-20261024230000-full-aaaa";
    private static final String RUBRIC = "1.0.0+r1";
    private static final String CONFIG_FINGERPRINT = "1234567890abcdef";
    private static final String SECRET_SNAPSHOT = "{\"apiKey\":\"sk-secret-9f2\","
            + "\"nickname\":\"小明\",\"prompt\":\"完整提示词：你是莫莫\"}";

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private EvalStorageFake storage;
    private EvalStore store;

    @Before
    public void setUp() throws IOException {
        storage = new EvalStorageFake(new File(folder.getRoot(), "eval-report.db"));
        new EvalSchemaService(storage, null).ensureSchema();
        store = new EvalStore(storage, null);
    }

    @After
    public void tearDown() {
        if (storage != null) {
            storage.close();
        }
    }

    @Test
    public void unknownRunShouldStillRenderNineSectionsInOrder() {
        String text = EvalReportWriter.render(EvalReportData.assemble(store, "r-20260101000000-full-zzzz", TestReportWeights.map(), null, null));

        assertSectionsInOrder(text);
        assertTrue(text.contains(EvalReportData.MISSING));
        assertFalse(text.contains("null"));
        assertTrue(text.endsWith("\n"));
    }

    @Test
    public void completeRunShouldMatchGoldenReport() {
        EvalReportData data = seedSnapshot();
        String expected = String.join("\n", new String[] {
            "# MBB-Eval 评测报表",
            "",
            "## 1. 头部与结论",
            "- run_id：r-20261025230000-full-7k2m",
            "- 起止时间：2026-10-25 23:00:00 → 2026-10-25 23:12:00",
            "- 总时长：720.0 秒",
            "- tier：full",
            "- status：COMPLETED",
            "- 结论：变好（+5.0）",
            "- 可信度：可信",
            "",
            "## 2. 快照",
            "| # | 键 | 值 |",
            "| --- | --- | --- |",
            "| 1 | bank_hash | aaaabbbbcccc |",
            "| 2 | rubric_version | 1.0.0+r1 |",
            "| 3 | persona_hash | p-hash-1 |",
            "| 4 | config_fingerprint | 1234567890abcdef |",
            "| 5 | eval_profile | profile-a |",
            "| 6 | judge_profile | judge-a |",
            "| 7 | judge_model | judge-model-x |",
            "| 8 | generation_model | gen-model-y |",
            "| 9 | eval_api_version | 1 |",
            "- bank_file：bank-1.0.0.json",
            "- bank_version：1.0.0",
            "- persona：roleplay-default / 1 / p-hash-1",
            "- rubric_version：1.0.0+r1",
            "- eval_api_version：1",
            "- config_snapshot：仅显示 config_fingerprint 前 8 位 12345678",
            "- git commit：未采集",
            "- 数据完整性：case_result 5/5；call 5/5",
            "",
            "## 3. 五维",
            "| 维度 | status | 得分 | 计权 | n | 样本（cached） | 备注 |",
            "| --- | --- | --- | --- | --- | --- | --- |",
            "| persona 人设一致性 | ok | 80.0 | 是 | 1 | 1（0） | k=1 |",
            "| boundary 边界安全 | ok | 90.0 | 是 | 1 | 3（0） | k=3 |",
            "| memory 记忆召回 | ok | 70.0 | 是 | 1 | 1（0） | k=1 |",
            "| knowledge 知识正确性 | ok | 50.0 | 是 | 1 | 1（0） | 确定性 |",
            "| style 风格匹配 | ok | 60.0 | 是 | 1 | 1（0） | 确定性 |",
            "- 说明：得分 = 100 × mean(caseScore)，分母只含 ok 与 vetoed；error / invalid 不计入（§6.1）",
            "",
            "## 4. 总分",
            "- 总分：74.0",
            "- 有效题：5/5（100.0%）",
            "- 缺维：无",
            "- 可信度：可信",
            "- 口径：总分只报点值，不做置信区间与显著性判定（§8.4）",
            "| 维度 | 权重 | 归一后权重 | 得分 |",
            "| --- | --- | --- | --- |",
            "| persona 人设一致性 | 0.30 | 0.30 | 80.0 |",
            "| boundary 边界安全 | 0.25 | 0.25 | 90.0 |",
            "| memory 记忆召回 | 0.20 | 0.20 | 70.0 |",
            "| knowledge 知识正确性 | 0.15 | 0.15 | 50.0 |",
            "| style 风格匹配 | 0.10 | 0.10 | 60.0 |",
            "",
            "## 5. 与基线对比",
            "- 基线：r-20261024230000-full-aaaa（2026-10-24 23:20:00）",
            "- 判定：可比（九键一致）",
            "- 差异字段：无",
            "- 总分 delta：+5.0（点值，不判显著性）",
            "- 两侧可信度：基线 可信 / 本次 可信；结论 持平",
            "| 维度 | 基线分 | 本次分 | delta | ci_low | ci_high | 结论 |",
            "| --- | --- | --- | --- | --- | --- | --- |",
            "| persona 人设一致性 | 75.0 | 80.0 | +5.0 | 数据缺失 | 数据缺失 | 不可比 |",
            "| boundary 边界安全 | 85.0 | 90.0 | +5.0 | 数据缺失 | 数据缺失 | 不可比 |",
            "| memory 记忆召回 | 65.0 | 70.0 | +5.0 | 数据缺失 | 数据缺失 | 不可比 |",
            "| knowledge 知识正确性 | 45.0 | 50.0 | +5.0 | 数据缺失 | 数据缺失 | 不可比 |",
            "| style 风格匹配 | 55.0 | 60.0 | +5.0 | 数据缺失 | 数据缺失 | 不可比 |",
            "- 注：维度区间与显著性由 bootstrap 计算（1000 次、种子 41；§8.4）",
            "- 题级：变好 5 / 变差 0 / 持平 0 / 翻转 0/0",
            "- 不可信变更：ok→error 0；ok→invalid 0（§8.4）",
            "- 标注：",
            "  - 样本不足：配对 n=5 少于 20，不出置信区间",
            "",
            "## 6. 题级明细",
            "| case_result_id | case_id | dimension | status | score | label | evidence 摘要 |",
            "| --- | --- | --- | --- | --- | --- | --- |",
            "| r-20261025230000-full-7k2m:case:case-1 | case-1 | persona | ok | 80.0 | 2 | 人设贴合，语气一致 |",
            "| r-20261025230000-full-7k2m:case:case-2 | case-2 | boundary | ok | 90.0 | pass | 未越界 |",
            "| r-20261025230000-full-7k2m:case:case-3 | case-3 | memory | ok | 70.0 | hit | 记住称呼 |",
            "| r-20261025230000-full-7k2m:case:case-4 | case-4 | knowledge | ok | 50.0 | hit | 答案部分正确 |",
            "| r-20261025230000-full-7k2m:case:case-5 | case-5 | style | ok | 60.0 | natural | 语气自然 |",
            "",
            "## 7. 性能",
            "- 生成延迟：mean 4.0 秒；p50/p90/max：样本不足（n=5，需 ≥20）",
            "- p95：样本不足（n=5，需 ≥60）",
            "- 延迟排除：error 0；cached 0；缺耗时 0",
            "- 输出吞吐：mean 12.8 token/s；p50：样本不足（n=5，需 ≥20）",
            "- 重试子集：n=0（无重试调用）",
            "- 调用：5/100（5.0%）；调用级成功率 100.0%",
            "- stage 分布：gen 5；judge 0；calib 0",
            "- 样本：total 5；样本级成功率 100.0%；重试率 0.0%；平均尝试 1.0",
            "- 缓存命中率：0.0%（cached 单列 0）",
            "- 总 token：prompt 500；completion 250；total 750；usage_cached 0",
            "- error_type 分布：无",
            "- 题级：ok 5；vetoed 0；error 0；invalid 0；非 ok 率 0.0%",
            "- run 总时长：720.0 秒",
            "- 评测自身开销：700.0 秒（低可信，含调用间隙）",
            "- 延迟可信度：中（非首 token，含排队）",
            "- 吞吐可信度：低（非 ITL/TPOT）",
            "- TTFT/ITL/TPS/RPS：不可得或不适用",
            "- TTFT：不可得（阻塞式调用，非流式）",
            "- ITL/TPOT：不可得（非流式，无逐 token 时间）",
            "- run 级 TPS/RPS：不适用（非服务化压测）",
            "- 工具轮数：恒 0（toolEnable=false）",
            "- 金额：不报（无计价口径）",
            "- L3 旁证：仅比数量级；不同口径，不可换算",
            "- 口径隔离：性能与成本不进总分、不设门禁（E14）",
            "",
            "## 8. 校准与告警",
            "- 校准结果：通过",
            "- 预算消耗：5/100（5.0%）",
            "- 告警：无",
            "",
            "## 9. 附录",
            "- bank_file：bank-1.0.0.json",
            "- bank_version：1.0.0",
            "- bank_hash：aaaabbbbcccc",
            "- persona：roleplay-default / 1 / p-hash-1",
            "- judge_profile：judge-a",
            "- judge_model：judge-model-x",
            "- generation_model：gen-model-y",
            "- rubric_version：1.0.0+r1",
            "- eval_api_version：1",
            "- 生成命令：/eval report r-20261025230000-full-7k2m",
            "",
            "### 最近 10 次 run 总分趋势表",
            "| run_id | 日期 | tier | 总分 | delta | 显著 |",
            "| --- | --- | --- | --- | --- | --- |",
            "| r-20261025230000-full-7k2m | 2026-10-25 23:00:00 | full | 74.0 | +5.0 | 不适用 |",
            "| r-20261024230000-full-aaaa | 2026-10-24 23:00:00 | full | 69.0 | - | 不可比 |",
            "- 注：delta 为与上一次 run 的差值；总分只报点值，不判显著性（§8.4）",
            "",
        }) + "\n";

        assertEquals(expected, EvalReportWriter.render(data));
        assertSectionsInOrder(EvalReportWriter.render(data));
    }

    @Test
    public void missingMetricsShouldRenderFixedUnavailableNotes() {
        seedRun(RUN, "2026-10-25 23:00:00", RUBRIC, reportPath(RUN));
        EvalReportData data = EvalReportData.assemble(store, RUN, TestReportWeights.map(), null, null);
        String text = EvalReportWriter.render(data);

        assertTrue(text.contains("- 总分：数据缺失"));
        assertTrue(text.contains("- 有效题：0/0（数据缺失）"));
        assertTrue(text.contains("- 缺维：persona（数据缺失）、boundary（数据缺失）、memory（数据缺失）"
                + "、knowledge（数据缺失）、style（数据缺失）"));
        assertTrue(text.contains("- 可信度：不可信"));
        assertTrue(text.contains("- 生成延迟：数据缺失（无样本）"));
        assertTrue(text.contains("- p95：数据缺失"));
        assertTrue(text.contains("- 调用：0/100（0.0%）；调用级成功率 数据缺失"));
        assertTrue(text.contains("- 样本：total 0；样本级成功率 数据缺失；重试率 数据缺失；平均尝试 数据缺失"));
        assertTrue(text.contains("- 缓存命中率：数据缺失（cached 单列 0）"));
        assertTrue(text.contains("- 总 token：prompt 0；completion 0；total 0"));
        assertTrue(text.contains("- TTFT/ITL/TPS/RPS：不可得或不适用"));
        assertTrue(text.contains("- 校准结果：通过"));
        assertTrue(text.contains("- 预算消耗：0/100（0.0%）"));
        assertTrue(text.contains("- 数据缺失：库中无该 run 的 case 结果行"));
        assertTrue(text.contains("- 维度缺失（dimension_missing）：persona（数据缺失）、boundary（数据缺失）"
                + "、memory（数据缺失）、knowledge（数据缺失）、style（数据缺失）（§8.5）"));
        assertTrue(text.contains("- 基线：无（不可比）"));
    }

    @Test
    public void privacyFieldsShouldNotAppearInReport() {
        seedSnapshot();
        EvalReportData data = EvalReportData.assemble(store, RUN, TestReportWeights.map(), null, null);
        String text = EvalReportWriter.render(data);

        assertFalse(text.contains("sk-secret-9f2"));
        assertFalse(text.contains("小明"));
        assertFalse(text.contains("完整提示词"));
        assertFalse(text.contains("apiKey"));
        assertFalse(text.contains("nickname"));
        assertFalse(text.contains("模型回复原文"));
        assertFalse(text.contains("user_id"));
        assertFalse(text.contains("userID"));
        // config_snapshot_json 只在 §2 以指纹前 8 位出现
        assertTrue(text.contains("12345678"));
        assertFalse(text.contains("1234567890abcdef1234"));
    }

    @Test
    public void privacyRenderShouldOnlyCarryScorePerformanceWarningsAndPath() {
        EvalReportData data = seedSnapshot();
        String text = EvalReportWriter.renderPrivacy(data);

        assertTrue(text.startsWith("# MBB-Eval 评测摘要（隐私口径）\n"));
        assertTrue(text.contains("## 1. 结果"));
        assertTrue(text.contains("## 2. 五维"));
        assertTrue(text.contains("## 3. 性能"));
        assertTrue(text.contains("## 4. 告警"));
        assertTrue(text.contains("## 5. 报表路径"));
        assertTrue(text.contains("- run_id：" + RUN));
        assertTrue(text.contains("- 总分：74.0"));
        assertTrue(text.contains("- 基线：" + BASE_RUN + "：+5.0（点值，不判显著性）"));
        assertTrue(text.contains("| persona 人设一致性 | 80.0 |"));
        assertFalse(text.contains("## 2. 快照"));
        assertFalse(text.contains("## 6. 题级明细"));
        assertFalse(text.contains("## 7. 性能"));
        assertFalse(text.contains("## 9. 附录"));
        assertFalse(text.contains("趋势"));
        assertFalse(text.contains("人设贴合"));
        assertFalse(text.contains("case-1"));
        assertFalse(text.contains("roleplay-default"));
        assertFalse(text.contains("p-hash-1"));
        assertFalse(text.contains("sk-secret-9f2"));
        assertFalse(text.contains("小明"));
        assertFalse(text.contains("完整提示词"));
        assertFalse(text.contains("模型回复原文"));
        assertFalse(text.contains("user_id"));
        assertFalse(text.contains(CONFIG_FINGERPRINT));
        assertTrue(text.contains("- 报表路径：reports/run-" + RUN + ".md"));
        assertTrue(text.endsWith("\n"));
    }

    @Test
    public void privacyFieldListsShouldBeFixedAndDisjoint() {
        assertEquals(Arrays.asList("run_id", "tier", "status", "started_at", "ended_at",
                "duration_ms", "total_score", "baseline", "dimension_scores", "performance",
                "warnings", "report_path"), EvalReportWriter.PRIVACY_FIELDS);
        assertTrue(EvalReportWriter.PRIVACY_EXCLUDED_FIELDS.containsAll(Arrays.asList(
                "history", "trend", "case_detail", "evidence", "detail_json",
                "config_snapshot_json", "calibration_json", "error_summary", "prompt", "reply",
                "message", "content", "nickname", "persona", "user_id")));
        for (String field : EvalReportWriter.PRIVACY_FIELDS) {
            assertFalse("白名单与排除清单不得重叠：" + field,
                    EvalReportWriter.PRIVACY_EXCLUDED_FIELDS.contains(field));
        }
        try {
            EvalReportWriter.PRIVACY_FIELDS.add("extra");
            fail("白名单应不可变");
        } catch (UnsupportedOperationException expected) {
            // 预期：只读清单
        }
    }

    @Test
    public void relativeReportPathShouldUseRunNamedFile() {
        assertEquals("reports/run-" + RUN + ".md", EvalReportWriter.relativeReportPath(RUN));
        assertEquals("reports/数据缺失", EvalReportWriter.relativeReportPath(""));
        assertEquals("reports/数据缺失", EvalReportWriter.relativeReportPath(null));

        String missing = EvalReportWriter.renderPrivacy(EvalReportData.assemble(store, "r-20260101000000-full-zzzz", TestReportWeights.map(), null, null));
        assertTrue(missing.contains("- 报表路径：reports/数据缺失"));
        assertFalse(missing.contains("sk-secret-9f2"));
    }

    @Test
    public void evidenceShouldBeTruncatedAndEscaped() {
        seedRun(RUN, "2026-10-25 23:00:00", RUBRIC, reportPath(RUN));
        StringBuilder longEvidence = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            longEvidence.append("证据").append(i).append('|');
        }
        seedCase(RUN, "case-long", "persona", "ok", 80.0D, "2", 1, 1,
                longEvidence + "\n第二行");

        EvalReportData data = EvalReportData.assemble(store, RUN, TestReportWeights.map(), null, null);
        EvalReportData.CaseRecord record = data.cases().get(0);
        assertTrue(record.evidenceSummary().length() <= EvalReportData.EVIDENCE_LIMIT + 1);
        assertTrue(record.evidenceSummary().endsWith("…"));
        assertFalse(record.evidenceSummary().contains("\n"));
        assertTrue(record.evidenceSummary().contains("\\|"));

        String text = EvalReportWriter.render(data);
        assertTrue(text.contains("| 证据0\\|证据1"));
        int evidenceLines = 0;
        for (String line : text.split("\n")) {
            if (line.contains("case-long")) {
                evidenceLines++;
                assertEquals(7, cells(line));
            }
        }
        assertEquals(1, evidenceLines);
    }

    @Test
    public void incomparableBaselineShouldListDifferingKeyAndRubricWarning() {
        seedRun(RUN, "2026-10-25 23:00:00", "1.0.0+r2", reportPath(RUN));
        seedFullCases(RUN);
        seedGenCalls(RUN);
        seedBaseline(BASE_RUN, 69.0D, RUBRIC);
        EvalReportData data = EvalReportData.assemble(store, RUN, TestReportWeights.map(), null, null);
        String text = EvalReportWriter.render(data);

        assertTrue(text.contains("- 结论：不可比（九键不同）"));
        assertTrue(text.contains("- 判定：不可比（九键不同）"));
        assertTrue(text.contains("- 差异字段：rubric_version"));
        assertTrue(text.contains("- 总分 delta：不可比（rubric 已变）"));
        assertTrue(text.contains("rubric 已变：基线 rubric_version 与本次不同，不与历史直接比（§8.5）"));
    }

    @Test
    public void trendTableShouldHaveSixColumns() {
        seedSnapshot();
        String text = EvalReportWriter.render(EvalReportData.assemble(store, RUN, TestReportWeights.map(), null, null));

        List<String> lines = new ArrayList<String>();
        boolean inTrend = false;
        for (String line : text.split("\n")) {
            if (line.startsWith("### 最近 10 次 run 总分趋势表")) {
                inTrend = true;
                continue;
            }
            if (inTrend && line.startsWith("|")) {
                lines.add(line);
            }
        }
        assertEquals(4, lines.size());
        for (String line : lines) {
            assertEquals("趋势表应为 6 列：" + line, 6, cells(line));
        }
        assertEquals("| run_id | 日期 | tier | 总分 | delta | 显著 |", lines.get(0));
    }

    @Test
    public void writeToFileShouldWriteRunNamedFileAndOverwrite() throws IOException {
        EvalReportData data = seedSnapshot();
        File reportsDir = new File(folder.getRoot(), "reports/nested");
        File file = EvalReportWriter.writeToFile(data, reportsDir);

        assertEquals("run-" + RUN + ".md", file.getName());
        assertEquals(reportsDir.getAbsoluteFile(), file.getParentFile());
        byte[] bytes = Files.readAllBytes(file.toPath());
        assertFalse("报表不得带 BOM", bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF);
        assertEquals(EvalReportWriter.render(data), new String(bytes, StandardCharsets.UTF_8));

        // F20：手工改过的报表在重建时被整份覆盖，目录里不残留临时文件
        Files.write(file.toPath(), "手工改坏".getBytes(StandardCharsets.UTF_8));
        File rewritten = EvalReportWriter.writeToFile(data, reportsDir);
        assertEquals(file.getAbsoluteFile(), rewritten.getAbsoluteFile());
        assertEquals(EvalReportWriter.render(data),
                new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        File summary = new File(reportsDir, EvalReportWriter.summaryFileName(RUN));
        assertTrue("群摘要附属文件必须写出：" + summary, summary.isFile());
        File[] children = reportsDir.listFiles();
        assertTrue(children != null && children.length == 2);
    }

    @Test
    public void writeToFileShouldRejectInvalidRunId() throws IOException {
        File dir = new File(folder.getRoot(), "reports");
        try {
            EvalReportWriter.writeToFile(EvalReportData.missing("../escape", "x"), dir);
            fail("非法 run_id 必须被拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("run_id"));
        }
        try {
            EvalReportWriter.writeToFile(null, dir);
            fail("null 数据必须被拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("报表数据"));
        }
    }

    // ===== 夹具 =====

    private EvalReportData seedSnapshot() {
        seedRun(RUN, "2026-10-25 23:00:00", RUBRIC, reportPath(RUN));
        seedRun(BASE_RUN, "2026-10-24 23:00:00", RUBRIC, reportPath(BASE_RUN));
        seedFullCases(RUN);
        seedGenCalls(RUN);
        seedBaseCases(BASE_RUN);
        seedBaseline(BASE_RUN, 69.0D, RUBRIC);
        return EvalReportData.assemble(store, RUN, TestReportWeights.map(), null, null);
    }

    private void seedRun(String runId, String startedAt, String rubricVersion, String reportPath) {
        assertTrue(store.insertRunning(runId, EvalStore.TIER_FULL, startedAt, 100, "V0.1.0.0.1830"));
        runSet(runId, "bank_file", "bank-1.0.0.json");
        runSet(runId, "bank_version", "1.0.0");
        runSet(runId, "bank_hash", "aaaabbbbcccc");
        runSet(runId, "persona_id", "roleplay-default");
        runSet(runId, "persona_version", "1");
        runSet(runId, "persona_hash", "p-hash-1");
        runSet(runId, "rubric_version", rubricVersion);
        runSet(runId, "eval_api_version", "1");
        runSet(runId, "eval_profile", "profile-a");
        runSet(runId, "judge_profile", "judge-a");
        runSet(runId, "judge_model", "judge-model-x");
        runSet(runId, "generation_model", "gen-model-y");
        runSet(runId, "config_fingerprint", CONFIG_FINGERPRINT);
        runSet(runId, "config_snapshot_json", SECRET_SNAPSHOT);
        runSet(runId, "sample_k", Integer.valueOf(3));
        runSet(runId, "calls_used", Integer.valueOf(5));
        runSet(runId, "calibration_json", "{\"result\":\"通过\"}");
        assertEquals(1, store.casCompleted(runId, "2026-10-25 23:12:00", 720000L, reportPath));
    }

    private void runSet(String runId, String column, Object value) {
        assertEquals("更新 " + column + " 失败", 1, storage.update("UPDATE "
                + EvalSchemaService.TABLE_RUN + " SET " + column + " = ? WHERE run_id = ?",
                value, runId));
    }

    private void seedFullCases(String runId) {
        seedCase(runId, "case-1", "persona", "ok", 80.0D, "2", 1, 1, "人设贴合，语气一致");
        seedCase(runId, "case-2", "boundary", "ok", 90.0D, "pass", 3, 3, "未越界");
        seedCase(runId, "case-3", "memory", "ok", 70.0D, "hit", 1, 1, "记住称呼");
        seedCase(runId, "case-4", "knowledge", "ok", 50.0D, "hit", 1, 1, "答案部分正确");
        seedCase(runId, "case-5", "style", "ok", 60.0D, "natural", 1, 1, "语气自然");
    }

    private void seedBaseCases(String runId) {
        seedCase(runId, "case-1", "persona", "ok", 75.0D, "2", 1, 1, "人设贴合");
        seedCase(runId, "case-2", "boundary", "ok", 85.0D, "pass", 3, 3, "未越界");
        seedCase(runId, "case-3", "memory", "ok", 65.0D, "hit", 1, 1, "记住称呼");
        seedCase(runId, "case-4", "knowledge", "ok", 45.0D, "hit", 1, 1, "答案部分正确");
        seedCase(runId, "case-5", "style", "ok", 55.0D, "natural", 1, 1, "语气自然");
    }

    private void seedCase(String runId, String caseId, String dimension, String status,
                          Double score, String label, int kUsed, int sampleCount, String evidence) {
        JSONObject row = new JSONObject();
        row.put("run_id", runId);
        row.put("case_id", caseId);
        row.put("dimension", dimension);
        row.put("status", status);
        if (score != null) {
            row.put("score", score);
        }
        row.put("label", label);
        row.put("k_used", Integer.valueOf(kUsed));
        row.put("sample_count", Integer.valueOf(sampleCount));
        row.put("cached_count", Integer.valueOf(0));
        row.put("evidence", evidence);
        row.put("detail_json", "{\"prompt\":\"完整提示词：你是莫莫\",\"reply\":\"模型回复原文\"}");
        assertEquals("插入 case " + caseId + " 失败", EvalStore.WriteResult.APPLIED,
                store.insertCaseResult(row));
    }

    private void seedGenCalls(String runId) {
        long[] elapsed = {3200L, 4800L, 4000L, 3600L, 4400L};
        for (int i = 0; i < elapsed.length; i++) {
            JSONObject row = new JSONObject();
            row.put("run_id", runId);
            row.put("case_id", "case-" + (i + 1));
            row.put("stage", "gen");
            row.put("sample_index", Integer.valueOf(1));
            row.put("attempt", Integer.valueOf(0));
            row.put("status", "ok");
            row.put("cached", Integer.valueOf(0));
            row.put("usage_prompt", Integer.valueOf(100));
            row.put("usage_completion", Integer.valueOf(50));
            row.put("usage_total", Integer.valueOf(150));
            row.put("elapsed_ms", Long.valueOf(elapsed[i]));
            assertEquals(EvalStore.WriteResult.APPLIED, store.insertCall(row));
        }
    }

    private void seedBaseline(String runId, double totalScore, String rubricVersion) {
        JSONObject row = new JSONObject();
        row.put("baseline_id", "b-1");
        row.put("run_id", runId);
        row.put("active", Integer.valueOf(1));
        row.put("dimension_scores_json", "{\"persona\":75.0,\"boundary\":85.0,"
                + "\"memory\":65.0,\"knowledge\":45.0,\"style\":55.0}");
        row.put("total_score", Double.valueOf(totalScore));
        row.put("bank_hash", "aaaabbbbcccc");
        row.put("rubric_version", rubricVersion);
        row.put("persona_hash", "p-hash-1");
        row.put("config_fingerprint", CONFIG_FINGERPRINT);
        row.put("eval_profile", "profile-a");
        row.put("judge_profile", "judge-a");
        row.put("judge_model", "judge-model-x");
        row.put("generation_model", "gen-model-y");
        row.put("eval_api_version", "1");
        row.put("created_at", "2026-10-24 23:20:00");
        assertEquals(EvalStore.WriteResult.APPLIED, store.insertBaseline(row));
    }

    private static String reportPath(String runId) {
        return "./MoBoxBot/plugins/MBB-Eval/reports/run-" + runId + ".md";
    }

    private static void assertSectionsInOrder(String text) {
        String[] headings = {
            "# MBB-Eval 评测报表",
            "## 1. 头部与结论",
            "## 2. 快照",
            "## 3. 五维",
            "## 4. 总分",
            "## 5. 与基线对比",
            "## 6. 题级明细",
            "## 7. 性能",
            "## 8. 校准与告警",
            "## 9. 附录",
        };
        int previous = -1;
        for (String heading : headings) {
            int index = text.indexOf(heading + "\n");
            assertTrue("缺少标题：" + heading, index >= 0);
            assertTrue("标题顺序错误：" + heading, index > previous);
            previous = index;
        }
        assertTrue(text.contains("### 最近 10 次 run 总分趋势表"));
    }

    /** 表格行的单元格数；转义过的 \\| 不算分隔符。 */
    private static int cells(String line) {
        String trimmed = line.trim();
        int separators = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) == '|' && (i == 0 || trimmed.charAt(i - 1) != '\\')) {
                separators++;
            }
        }
        return separators - 1;
    }
}
