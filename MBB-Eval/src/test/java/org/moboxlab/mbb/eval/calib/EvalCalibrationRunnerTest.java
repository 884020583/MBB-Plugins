package org.moboxlab.mbb.eval.calib;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import org.moboxlab.mbb.eval.judge.EvalJudgeResult;
import org.moboxlab.moboxbot.API.PluginLogger;

/**
 * 校准自证内核测试（规格 §6.7.4）。
 */
public class EvalCalibrationRunnerTest {

    private static final String RUN_ID = "run-calib-test";

    @Test
    public void allSamplesLabelZeroShouldPass() {
        FakePort port = new FakePort();
        EvalCalibrationResult result = new EvalCalibrationRunner(port, null).run(RUN_ID);
        assertTrue(result.passed());
        assertTrue(result.personaPassed());
        assertTrue(result.memoryPassed());
        assertTrue(result.boundaryPassed());
        assertEquals(0, result.evidenceFailures());
        assertEquals(3, result.evidenceTotal());
        assertEquals(0.0d, result.evidenceFailureRate(), 0.0001d);
        assertTrue(result.warnings().isEmpty());
        assertEquals(3, port.calls());
    }

    @Test
    public void personaLabelTwoShouldFailPersonaGate() {
        FakePort port = new FakePort().with(EvalCalibrationSample.PERSONA_OOC.caseId(),
                ok(EvalCalibrationSample.PERSONA_OOC, 2, EvalCalibrationSample.PERSONA_OOC.reply()));
        EvalCalibrationResult result = new EvalCalibrationRunner(port, null).run(RUN_ID);
        assertFalse(result.passed());
        assertFalse(result.personaPassed());
        assertTrue(result.memoryPassed());
        assertTrue(result.boundaryPassed());
        assertTrue(result.warnings().get(0).contains("人设"));
        assertTrue(result.warnings().get(0).contains("label=2"));
    }

    @Test
    public void memoryLabelOneShouldFailMemoryGate() {
        FakePort port = new FakePort().with(EvalCalibrationSample.MEMORY_REVERSED.caseId(),
                ok(EvalCalibrationSample.MEMORY_REVERSED, 1, EvalCalibrationSample.MEMORY_REVERSED.reply()));
        EvalCalibrationResult result = new EvalCalibrationRunner(port, null).run(RUN_ID);
        assertFalse(result.passed());
        assertFalse(result.memoryPassed());
        assertTrue(result.personaPassed());
        assertTrue(result.warnings().get(0).contains("记忆"));
        assertTrue(result.warnings().get(0).contains("label=1"));
    }

    @Test
    public void boundaryLabelTwoWithoutVetoShouldFail() {
        FakePort port = new FakePort().with(EvalCalibrationSample.BOUNDARY_YES.caseId(),
                ok(EvalCalibrationSample.BOUNDARY_YES, 2, EvalCalibrationSample.BOUNDARY_YES.reply()));
        EvalCalibrationResult result = new EvalCalibrationRunner(port, null).run(RUN_ID);
        assertFalse(result.passed());
        assertFalse(result.boundaryVetoed());
        assertFalse(result.boundaryPassed());
        assertTrue(result.warnings().get(0).contains("边界"));
        assertTrue(result.warnings().get(0).contains("label=2"));
    }

    @Test
    public void boundaryVetoShouldPassEvenWithLabelTwo() {
        FakePort port = new FakePort().with(EvalCalibrationSample.BOUNDARY_YES.caseId(),
                ok(EvalCalibrationSample.BOUNDARY_YES, 2, EvalCalibrationSample.BOUNDARY_YES.reply()));
        EvalCalibrationResult failed = new EvalCalibrationRunner(port, null).run(RUN_ID);
        assertFalse(failed.boundaryPassed());

        EvalCalibrationResult vetoed =
                new EvalCalibrationResult(failed.runId(), failed.samples(), true);
        assertTrue(vetoed.boundaryVetoed());
        assertTrue(vetoed.boundaryPassed());
        assertTrue(vetoed.passed());
        assertTrue(vetoed.toJson().getBooleanValue("boundaryVetoed"));
        JSONArray samples = vetoed.toJson().getJSONArray("samples");
        assertEquals(3, samples.size());
        boolean boundaryGatePassed = false;
        for (int i = 0; i < samples.size(); i++) {
            JSONObject item = samples.getJSONObject(i);
            if (EvalCalibrationSample.CASE_ID_BOUNDARY.equals(item.getString("caseId"))) {
                boundaryGatePassed = item.getBooleanValue("gatePassed");
            }
        }
        assertTrue(boundaryGatePassed);
    }

    @Test
    public void invalidJudgeResultShouldFailGateAndWarn() {
        FakePort port = new FakePort().with(EvalCalibrationSample.MEMORY_REVERSED.caseId(),
                EvalJudgeResult.invalid(EvalCalibrationSample.MEMORY_REVERSED.dimensionId(),
                        EvalJudgeResult.KIND_PARSE, 1, "not-json", "模型返回不是 JSON", "sid", 0));
        EvalCalibrationResult result = new EvalCalibrationRunner(port, null).run(RUN_ID);
        assertFalse(result.passed());
        assertFalse(result.memoryPassed());
        assertEquals(2, result.evidenceTotal());
        String joined = joinWarnings(result);
        assertTrue(joined.contains("记忆"));
        assertTrue(joined.contains("invalid"));
        assertTrue(joined.contains("parse"));
        assertEquals("invalid", result.samples().get(1).state());
        assertEquals(-1, result.samples().get(1).label());
    }

    @Test
    public void thrownJudgeExceptionShouldConvergeWithoutThrowing() {
        FakePort port = new FakePort().throwingOn(EvalCalibrationSample.PERSONA_OOC.caseId());
        RecordingLogger logger = new RecordingLogger();
        EvalCalibrationResult result = new EvalCalibrationRunner(port, logger).run(RUN_ID);
        assertNotNull(result);
        assertFalse(result.passed());
        assertFalse(result.personaPassed());
        assertTrue(joinWarnings(result).contains("异常"));
        assertEquals(3, port.calls());
        assertFalse(logger.errors.isEmpty());
        assertTrue(logger.errors.get(0).contains("IllegalStateException"));
    }

    @Test
    public void nullJudgeResultShouldConverge() {
        FakePort port = new FakePort().nullOn(EvalCalibrationSample.BOUNDARY_YES.caseId());
        RecordingLogger logger = new RecordingLogger();
        EvalCalibrationResult result = new EvalCalibrationRunner(port, logger).run(RUN_ID);
        assertNotNull(result);
        assertFalse(result.boundaryPassed());
        assertTrue(joinWarnings(result).contains("null"));
        assertFalse(logger.errors.isEmpty());
    }

    @Test
    public void evidenceWithPunctuationAndWhitespaceShouldStayLocatable() {
        String noisyEvidence = "您好 ， 我是 智能助手 ！";
        FakePort port = new FakePort().with(EvalCalibrationSample.PERSONA_OOC.caseId(),
                ok(EvalCalibrationSample.PERSONA_OOC, 0, noisyEvidence));
        EvalCalibrationResult result = new EvalCalibrationRunner(port, null).run(RUN_ID);
        assertTrue(result.samples().get(0).evidenceLocatable());
        assertEquals(0, result.evidenceFailures());
        assertFalse(result.rubricSelfEvidenceWarning());
        assertTrue(result.passed());
    }

    @Test
    public void allEvidenceUnlocatableShouldReportSelfEvidenceWarning() {
        FakePort port = new FakePort()
                .with(EvalCalibrationSample.PERSONA_OOC.caseId(),
                        ok(EvalCalibrationSample.PERSONA_OOC, 0, "回复里没有这一句"))
                .with(EvalCalibrationSample.MEMORY_REVERSED.caseId(),
                        ok(EvalCalibrationSample.MEMORY_REVERSED, 0, "回复里没有这一句"))
                .with(EvalCalibrationSample.BOUNDARY_YES.caseId(),
                        ok(EvalCalibrationSample.BOUNDARY_YES, 0, "回复里没有这一句"));
        EvalCalibrationResult result = new EvalCalibrationRunner(port, null).run(RUN_ID);
        assertEquals(3, result.evidenceFailures());
        assertEquals(3, result.evidenceTotal());
        assertEquals(1.0d, result.evidenceFailureRate(), 0.0001d);
        assertTrue(result.rubricSelfEvidenceWarning());
        assertFalse(result.passed());
        String joined = joinWarnings(result);
        assertTrue(joined.contains("不自证"));
        assertTrue(joined.contains("100.0%"));
        assertTrue(result.warnings().size() <= EvalCalibrationResult.MAX_WARNINGS);
    }

    @Test
    public void toJsonShouldContainAllCalibrationFieldsAndParse() {
        EvalCalibrationResult result =
                new EvalCalibrationRunner(new FakePort(), null).run(RUN_ID);
        JSONObject parsed = JSON.parseObject(result.toJson().toJSONString());
        assertEquals(RUN_ID, parsed.getString("runId"));
        assertTrue(parsed.getBooleanValue("passed"));
        assertTrue(parsed.getBooleanValue("personaPassed"));
        assertTrue(parsed.getBooleanValue("memoryPassed"));
        assertTrue(parsed.getBooleanValue("boundaryPassed"));
        assertFalse(parsed.getBooleanValue("boundaryVetoed"));
        assertEquals(0.0d, parsed.getDoubleValue("evidenceFailureRate"), 0.0001d);
        assertEquals(0, parsed.getIntValue("evidenceFailures"));
        assertEquals(3, parsed.getIntValue("evidenceTotal"));
        assertFalse(parsed.getBooleanValue("rubricSelfEvidenceWarning"));
        assertFalse(parsed.getBooleanValue("judgeJitterWarning"));
        assertEquals(3, parsed.getJSONArray("samples").size());
        JSONObject first = parsed.getJSONArray("samples").getJSONObject(0);
        String[] keys = {"caseId", "dimensionId", "state", "label", "scale", "evidence",
                "evidenceLocatable", "gatePassed", "reason"};
        for (String key : keys) {
            assertTrue("缺少字段 " + key, first.containsKey(key));
        }
        assertEquals(EvalCalibrationSample.CASE_ID_PERSONA, first.getString("caseId"));
        assertTrue(parsed.getJSONArray("warnings").isEmpty());
    }

    @Test
    public void sameRunnerTwiceShouldNotShareState() {
        FakePort port = new FakePort().with(EvalCalibrationSample.PERSONA_OOC.caseId(),
                ok(EvalCalibrationSample.PERSONA_OOC, 2, EvalCalibrationSample.PERSONA_OOC.reply()));
        EvalCalibrationRunner runner = new EvalCalibrationRunner(port, null);
        EvalCalibrationResult first = runner.run(RUN_ID);
        assertFalse(first.passed());
        assertFalse(first.warnings().isEmpty());

        port.with(EvalCalibrationSample.PERSONA_OOC.caseId(),
                ok(EvalCalibrationSample.PERSONA_OOC, 0, EvalCalibrationSample.PERSONA_OOC.reply()));
        EvalCalibrationResult second = runner.run(RUN_ID + "-2");
        assertTrue(second.passed());
        assertTrue(second.warnings().isEmpty());
        assertEquals(RUN_ID + "-2", second.runId());

        assertFalse(first.passed());
        assertFalse(first.warnings().isEmpty());
        assertEquals(2, first.samples().get(0).label());
        assertEquals(0, second.samples().get(0).label());
        assertEquals(6, port.calls());
    }

    private static EvalJudgeResult ok(EvalCalibrationSample sample, int label, String evidence) {
        return EvalJudgeResult.ok(sample.dimensionId(), label, evidence, 1, "{}", "sid", 0);
    }

    private static String joinWarnings(EvalCalibrationResult result) {
        StringBuilder builder = new StringBuilder();
        for (String warning : result.warnings()) {
            builder.append(warning).append('\n');
        }
        return builder.toString();
    }

    private static final class FakePort implements EvalCalibrationJudgePort {

        private final Map<String, EvalJudgeResult> results = new HashMap<String, EvalJudgeResult>();
        private final Set<String> throwing = new HashSet<String>();
        private final Set<String> returningNull = new HashSet<String>();
        private int calls;

        FakePort with(String caseId, EvalJudgeResult result) {
            results.put(caseId, result);
            return this;
        }

        FakePort throwingOn(String caseId) {
            throwing.add(caseId);
            return this;
        }

        FakePort nullOn(String caseId) {
            returningNull.add(caseId);
            return this;
        }

        int calls() {
            return calls;
        }

        @Override
        public EvalJudgeResult judge(EvalCalibrationSample sample) {
            calls++;
            if (throwing.contains(sample.caseId())) {
                throw new IllegalStateException("假 judge 故障");
            }
            if (returningNull.contains(sample.caseId())) {
                return null;
            }
            EvalJudgeResult result = results.get(sample.caseId());
            if (result == null) {
                return EvalJudgeResult.ok(sample.dimensionId(), 0, sample.reply(), 1, "{}", "sid", 0);
            }
            return result;
        }
    }

    private static final class RecordingLogger extends PluginLogger {

        private final List<String> infos = new ArrayList<String>();
        private final List<String> warns = new ArrayList<String>();
        private final List<String> errors = new ArrayList<String>();

        RecordingLogger() {
            super("test", null);
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
    }
}
