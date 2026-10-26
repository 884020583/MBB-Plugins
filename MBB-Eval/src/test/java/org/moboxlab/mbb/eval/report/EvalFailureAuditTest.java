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
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * H18 失败调用双写审计测试（§13.6、§15.8 H18）。
 *
 * <p>覆盖：日志与 _call 行一致时不报缺行；日志有而 _call 无时列出缺 _call 行；
 * _call 有而日志无时列出缺日志行；只读，不修改库。</p>
 */
public class EvalFailureAuditTest {

    private static final String RUN = "r-20261025230000-full-7k2m";

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private EvalStorageFake storage;
    private EvalStore store;

    @Before
    public void setUp() throws Exception {
        storage = new EvalStorageFake(new File(folder.getRoot(), "eval-audit.db"));
        new EvalSchemaService(storage, null).ensureSchema();
        store = new EvalStore(storage, null);
        insertRun();
    }

    @After
    public void tearDown() {
        if (storage != null) {
            storage.close();
        }
    }

    @Test
    public void matchingLogAndCallRowShouldBeConsistent() {
        insertCall("case-1", 0, 0, "error");
        List<String> log = Arrays.asList(
                "[评测] runId=" + RUN + " caseId=case-1 sampleIndex=0 stage=generate"
                        + " attempt=0 status=error errorType=timeout");

        EvalFailureAudit.AuditResult result = EvalFailureAudit.audit(store, RUN, log);

        assertTrue(result.summary(), result.consistent());
        assertEquals(1, result.logFailureCount());
        assertEquals(1, result.callFailureCount());
        assertTrue(result.missingCallRows().isEmpty());
        assertTrue(result.missingLogRows().isEmpty());
        assertTrue(result.notes().isEmpty());
    }

    @Test
    public void failureLoggedWithoutCallRowShouldBeReported() {
        insertCall("case-1", 0, 0, "error");
        List<String> log = Arrays.asList(
                "[评测] runId=" + RUN + " caseId=case-1 sampleIndex=0 status=error",
                "[评测] runId=" + RUN + " caseId=case-2 sampleIndex=1 status=error"
                        + " errorType=timeout");

        EvalFailureAudit.AuditResult result = EvalFailureAudit.audit(store, RUN, log);

        assertFalse(result.consistent());
        assertEquals(2, result.logFailureCount());
        assertEquals(1, result.callFailureCount());
        assertEquals(1, result.missingCallRows().size());
        assertTrue(result.missingCallRows().get(0).contains("caseId=case-2"));
        assertTrue(result.missingCallRows().get(0).contains("sampleIndex=1"));
        assertTrue(result.missingLogRows().isEmpty());
        assertTrue(result.summary().contains("缺 _call 行"));
    }

    @Test
    public void callRowWithoutFailureLogShouldBeReported() {
        insertCall("case-1", 0, 0, "error");
        insertCall("case-3", 2, 1, "error");
        List<String> log = Arrays.asList(
                "[评测] runId=" + RUN + " caseId=case-1 sampleIndex=0 status=error",
                // 同 run 的成功调用不参与失败审计
                "[评测] runId=" + RUN + " caseId=case-9 sampleIndex=0 status=ok",
                // 其它 run 的失败行不影响本次审计
                "[评测] runId=r-20261025230100-full-bbbb caseId=case-3 sampleIndex=2 status=error");

        EvalFailureAudit.AuditResult result = EvalFailureAudit.audit(store, RUN, log);

        assertFalse(result.consistent());
        assertEquals(1, result.logFailureCount());
        assertEquals(2, result.callFailureCount());
        assertTrue(result.missingCallRows().isEmpty());
        assertEquals(1, result.missingLogRows().size());
        assertTrue(result.missingLogRows().get(0).contains("caseId=case-3"));
        assertTrue(result.summary().contains("缺日志行"));
    }

    @Test
    public void auditFileShouldReadEnglishAndChineseLinesWithoutWriting() throws IOException {
        insertCall("case-1", 0, 0, "error");
        File logFile = new File(folder.getRoot(), "eval.log");
        String content = "[评测] runId=" + RUN + " caseId=case-1 sampleIndex=0"
                + " status=error errorType=timeout\n";
        Files.write(logFile.toPath(), content.getBytes(StandardCharsets.UTF_8));

        EvalFailureAudit.AuditResult result = EvalFailureAudit.auditFile(store, RUN, logFile);

        assertTrue(result.summary(), result.consistent());
        assertEquals(1, result.logFailureCount());
        // 只读：run 行与调用行原样保留
        assertEquals("RUNNING", store.findRun(RUN).getString("status"));
        assertEquals(1, storage.count("SELECT COUNT(*) AS total FROM plugin_mbb_eval_call"
                + " WHERE run_id = ?", RUN));
    }

    private void insertRun() {
        JSONObject row = new JSONObject();
        row.put("run_id", RUN);
        row.put("tier", EvalStore.TIER_FULL);
        row.put("started_at", "2026-10-25 23:00:00");
        assertTrue(store.insertRunning(row));
    }

    private void insertCall(String caseId, int sampleIndex, int attempt, String status) {
        JSONObject row = new JSONObject();
        row.put("run_id", RUN);
        row.put("case_id", caseId);
        row.put("stage", "generate");
        row.put("sample_index", Integer.valueOf(sampleIndex));
        row.put("attempt", Integer.valueOf(attempt));
        row.put("status", status);
        if (!"ok".equals(status)) {
            row.put("error_type", "timeout");
        }
        assertEquals(EvalStore.WriteResult.APPLIED, store.insertCall(row));
    }
}
