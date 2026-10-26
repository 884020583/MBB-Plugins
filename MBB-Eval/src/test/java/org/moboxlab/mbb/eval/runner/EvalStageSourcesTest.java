package org.moboxlab.mbb.eval.runner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.moboxlab.mbb.eval.bank.EvalBankLocator;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.StageReport;
import org.moboxlab.moboxbot.API.Plugin;
import org.moboxlab.moboxbot.API.PluginManager;

/**
 * 预检剩余阶段的真实来源：MBB-AI 在线（§9.5 第六项、§10.1）与数据目录可写
 * （§9.5 第七项、§13.1），并覆盖缺失 / 未启用 / 不可写三条拒绝路径。
 */
public class EvalStageSourcesTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void missingAiPluginShouldRejectWithInstallHint() {
        StageReport missing = EvalStageSources.aiReport(null);
        assertEquals(StageReport.Outcome.REJECT, missing.outcome());
        assertTrue("必须指出 MBB-AI 未安装：" + missing.message(),
                missing.message().contains("MBB-AI"));
        assertTrue("修复建议必须提到 aiHost：" + missing.hint(),
                missing.hint().contains("aiHost"));
    }

    @Test
    public void unavailablePluginManagerShouldReject() {
        StageReport report = EvalStageSources.ai((PluginManager) null).report("smoke");
        assertEquals(StageReport.Outcome.REJECT, report.outcome());
        assertTrue(report.message().contains("服务器未就绪"));
    }

    @Test
    public void disabledAiPluginShouldReject() {
        StageReport report = EvalStageSources.aiReport(new StubPlugin(false));
        assertEquals(StageReport.Outcome.REJECT, report.outcome());
        assertTrue(report.message().contains("未启用"));
        assertTrue(report.hint().contains("aiHost"));
    }

    @Test
    public void enabledAiPluginShouldPass() {
        StageReport report = EvalStageSources.aiReport(new StubPlugin(true));
        assertEquals(StageReport.Outcome.PASS, report.outcome());
    }

    @Test
    public void missingEvalsDirectoryShouldRejectWithAbsolutePath() {
        File evals = new File(temporaryFolder.getRoot(), "plugins/MBB-Roleplay/evals");
        File reports = new File(temporaryFolder.getRoot(), "plugins/MBB-Eval/reports");
        StageReport report = EvalStageSources.dataDir(evals, reports).report("smoke");
        assertEquals(StageReport.Outcome.REJECT, report.outcome());
        assertEquals(EvalBankLocator.CODE_DATA_NOT_FOUND, report.code());
        assertTrue("必须给绝对路径：" + report.message(),
                report.message().contains(evals.getAbsolutePath()));
    }

    @Test
    public void unwritableReportsDirectoryShouldRejectWithAbsolutePath() throws Exception {
        File evals = temporaryFolder.newFolder("evals");
        // 父路径是普通文件，reports 子目录必然建不出来（等价于不可写）。
        File blocker = temporaryFolder.newFile("blocked");
        File reports = new File(blocker, "reports");
        StageReport report = EvalStageSources.dataDir(evals, reports).report("smoke");
        assertEquals(StageReport.Outcome.REJECT, report.outcome());
        assertTrue("必须指出不可写：" + report.message(),
                report.message().contains("不可写"));
        assertTrue("必须给绝对路径：" + report.message(),
                report.message().contains(reports.getAbsolutePath()));
    }

    @Test
    public void writableDataDirectoryShouldPassAndCreateReports() throws Exception {
        File evals = temporaryFolder.newFolder("evals-ok");
        File reports = new File(temporaryFolder.getRoot(), "reports-ok");
        StageReport report = EvalStageSources.dataDir(evals, reports).report("smoke");
        assertEquals(StageReport.Outcome.PASS, report.outcome());
        assertTrue("预检应能建出 reports 子目录", reports.isDirectory());
    }

    /** 最小插件桩：只关心 enabled 状态。 */
    private static final class StubPlugin extends Plugin {
        private final boolean enabled;

        private StubPlugin(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }
    }
}
