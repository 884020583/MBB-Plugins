package org.moboxlab.mbb.eval.report;

import com.alibaba.fastjson.JSONObject;
import org.moboxlab.mbb.eval.store.EvalStore;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * H18 失败调用双写审计（规格 §13.6、§15.8 H18）。
 *
 * <p>控制台日志里的关键行形如
 * {@code [评测] runId=<runId> caseId=<caseId> sampleIndex=<n>}；每次失败调用还必须同时
 * 在 {@code plugin_mbb_eval_call} 留一行。本类按 run_id 把日志中的失败调用与
 * {@code _call} 行按「caseId + sampleIndex」配对，输出两类清单：</p>
 * <ul>
 *   <li>缺 _call 行：日志记了失败，库里没有对应失败行；</li>
 *   <li>缺日志行：库里有失败行，日志没有对应记录。</li>
 * </ul>
 *
 * <p>只读：不写库、不改报表、不删除日志。日志由调用方以文本行或文件传入，便于
 * 控制台抓取与离线审计复用。</p>
 */
public final class EvalFailureAudit {

    private static final Pattern RUN_ID = Pattern.compile("runId=([^\\s]+)");
    private static final Pattern CASE_ID = Pattern.compile("caseId=([^\\s]+)");
    private static final Pattern SAMPLE_INDEX = Pattern.compile("sampleIndex=([0-9]+)");
    private static final Pattern STAGE = Pattern.compile("stage=([^\\s]+)");
    private static final Pattern ATTEMPT = Pattern.compile("attempt=([0-9]+)");
    private static final Pattern STATUS = Pattern.compile("status=([A-Za-z]+)");

    private EvalFailureAudit() {
    }

    /**
     * 审计一段日志文本中的失败调用与库中 _call 行是否一致。
     *
     * @param store    评测库门面；为 null 时按数据缺失返回
     * @param runId    run 标识
     * @param logLines 日志文本行；null 视为没有日志
     * @return 审计结果，永不返回 null
     */
    public static AuditResult audit(EvalStore store, String runId, List<String> logLines) {
        String safeRunId = runId == null ? "" : runId.trim();
        List<String> lines = logLines == null ? new ArrayList<String>() : logLines;
        if (store == null) {
            return AuditResult.missing(safeRunId, "评测存储不可用，无法核对 _call 行");
        }
        JSONObject run = store.findRun(safeRunId);
        if (run == null) {
            return AuditResult.missing(safeRunId, "库中无该 run 的行，无法核对 _call 行");
        }

        List<LoggedFailure> logged = parseFailures(lines, safeRunId);
        EvalStore.RowSet callSet = store.findCalls(safeRunId);
        List<CallFailure> calls = collectCallFailures(callSet.rows());

        List<String> missingCallRows = new ArrayList<String>();
        for (LoggedFailure failure : logged) {
            if (!hasCall(calls, failure.caseId, failure.sampleIndex)) {
                missingCallRows.add(failure.describe());
            }
        }
        List<String> missingLogRows = new ArrayList<String>();
        for (CallFailure call : calls) {
            if (!hasLog(logged, call.caseId, call.sampleIndex)) {
                missingLogRows.add(call.describe());
            }
        }

        List<String> notes = new ArrayList<String>();
        if (!callSet.isComplete()) {
            notes.add("调用行读取不完整（读到 " + callSet.size() + "/"
                    + (callSet.expectedCount() < 0 ? "?" : callSet.expectedCount())
                    + "），审计结果仅供参考");
        }
        if (callSet.isQueryFailed()) {
            notes.add("调用行查询失败，审计结果仅供参考");
        }
        return new AuditResult(safeRunId, true, callSet.isComplete(), logged.size(), calls.size(),
                missingCallRows, missingLogRows, notes);
    }

    /** 从 UTF-8 日志文件读取并审计；读文件失败直接抛出，由调用方记录。 */
    public static AuditResult auditFile(EvalStore store, String runId, File logFile)
            throws IOException {
        if (logFile == null) {
            return audit(store, runId, null);
        }
        List<String> lines = Files.readAllLines(logFile.toPath(), StandardCharsets.UTF_8);
        return audit(store, runId, lines);
    }

    /** 解析形如 {@code [评测] runId=... caseId=... sampleIndex=...} 的失败行。 */
    private static List<LoggedFailure> parseFailures(List<String> lines, String runId) {
        List<LoggedFailure> failures = new ArrayList<LoggedFailure>();
        for (String line : lines) {
            if (line == null || line.isEmpty() || !isFailureLine(line)) {
                continue;
            }
            Matcher runMatcher = RUN_ID.matcher(line);
            Matcher caseMatcher = CASE_ID.matcher(line);
            if (!runMatcher.find() || !caseMatcher.find()) {
                continue;
            }
            if (!runId.equals(runMatcher.group(1))) {
                continue;
            }
            Matcher sampleMatcher = SAMPLE_INDEX.matcher(line);
            int sampleIndex = sampleMatcher.find() ? Integer.parseInt(sampleMatcher.group(1)) : -1;
            Matcher stageMatcher = STAGE.matcher(line);
            Matcher attemptMatcher = ATTEMPT.matcher(line);
            failures.add(new LoggedFailure(caseMatcher.group(1), sampleIndex,
                    stageMatcher.find() ? stageMatcher.group(1) : null,
                    attemptMatcher.find() ? Integer.valueOf(attemptMatcher.group(1)) : null));
        }
        return failures;
    }

    /**
     * 判断一行日志是否为失败调用。
     *
     * <p>优先看 {@code status=}：error / invalid 等非 ok 即失败；没有 status 时接受
     * 「失败」或 errorType / error_type 关键字，避免把成功行误判成失败。</p>
     */
    private static boolean isFailureLine(String line) {
        Matcher status = STATUS.matcher(line);
        if (status.find()) {
            return !"ok".equalsIgnoreCase(status.group(1));
        }
        String lower = line.toLowerCase();
        return line.contains("失败") || lower.contains("errortype=")
                || lower.contains("error_type=");
    }

    private static List<CallFailure> collectCallFailures(List<JSONObject> rows) {
        List<CallFailure> failures = new ArrayList<CallFailure>();
        for (JSONObject row : rows) {
            String status = row.getString("status");
            if (status == null || "ok".equalsIgnoreCase(status)) {
                continue;
            }
            Integer sampleIndex = row.getInteger("sample_index");
            failures.add(new CallFailure(row.getString("case_id"),
                    sampleIndex == null ? -1 : sampleIndex.intValue(),
                    row.getString("stage"), row.getInteger("attempt")));
        }
        return failures;
    }

    private static boolean hasCall(List<CallFailure> calls, String caseId, int sampleIndex) {
        for (CallFailure call : calls) {
            if (sameCase(call.caseId, caseId) && call.sampleIndex == sampleIndex) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLog(List<LoggedFailure> logged, String caseId, int sampleIndex) {
        for (LoggedFailure failure : logged) {
            if (sameCase(failure.caseId, caseId) && failure.sampleIndex == sampleIndex) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameCase(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    /** 日志侧一条失败调用。 */
    private static final class LoggedFailure {
        private final String caseId;
        private final int sampleIndex;
        private final String stage;
        private final Integer attempt;

        private LoggedFailure(String caseId, int sampleIndex, String stage, Integer attempt) {
            this.caseId = caseId;
            this.sampleIndex = sampleIndex;
            this.stage = stage;
            this.attempt = attempt;
        }

        private String describe() {
            return "caseId=" + value(caseId) + " sampleIndex=" + sampleIndex
                    + " stage=" + value(stage) + " attempt=" + (attempt == null ? "-" : attempt);
        }
    }

    /** _call 侧一条失败调用。 */
    private static final class CallFailure {
        private final String caseId;
        private final int sampleIndex;
        private final String stage;
        private final Integer attempt;

        private CallFailure(String caseId, int sampleIndex, String stage, Integer attempt) {
            this.caseId = caseId;
            this.sampleIndex = sampleIndex;
            this.stage = stage;
            this.attempt = attempt;
        }

        private String describe() {
            return "caseId=" + value(caseId) + " sampleIndex=" + sampleIndex
                    + " stage=" + value(stage) + " attempt=" + (attempt == null ? "-" : attempt);
        }
    }

    private static String value(String text) {
        return text == null || text.isEmpty() ? "-" : text;
    }

    /** 审计结果：两类缺行清单与总体一致性。 */
    public static final class AuditResult {

        private final String runId;
        private final boolean runFound;
        private final boolean callsComplete;
        private final int logFailureCount;
        private final int callFailureCount;
        private final List<String> missingCallRows;
        private final List<String> missingLogRows;
        private final List<String> notes;

        private AuditResult(String runId, boolean runFound, boolean callsComplete,
                            int logFailureCount, int callFailureCount,
                            List<String> missingCallRows, List<String> missingLogRows,
                            List<String> notes) {
            this.runId = runId;
            this.runFound = runFound;
            this.callsComplete = callsComplete;
            this.logFailureCount = logFailureCount;
            this.callFailureCount = callFailureCount;
            this.missingCallRows = new ArrayList<String>(missingCallRows);
            this.missingLogRows = new ArrayList<String>(missingLogRows);
            this.notes = new ArrayList<String>(notes);
        }

        static AuditResult missing(String runId, String note) {
            List<String> notes = new ArrayList<String>();
            notes.add(note);
            return new AuditResult(runId, false, false, 0, 0,
                    new ArrayList<String>(), new ArrayList<String>(), notes);
        }

        public String runId() {
            return runId;
        }

        public boolean runFound() {
            return runFound;
        }

        /** _call 行是否读全；false 时一致性结论只作参考。 */
        public boolean callsComplete() {
            return callsComplete;
        }

        public int logFailureCount() {
            return logFailureCount;
        }

        public int callFailureCount() {
            return callFailureCount;
        }

        /** 日志有、_call 无的失败调用（H18 缺行）。 */
        public List<String> missingCallRows() {
            return new ArrayList<String>(missingCallRows);
        }

        /** _call 有、日志无的失败调用（H18 多行 / 日志漏写）。 */
        public List<String> missingLogRows() {
            return new ArrayList<String>(missingLogRows);
        }

        public List<String> notes() {
            return new ArrayList<String>(notes);
        }

        /** 双写一致：run 存在、调用行读全、两类清单都为空。 */
        public boolean consistent() {
            return runFound && callsComplete && missingCallRows.isEmpty()
                    && missingLogRows.isEmpty();
        }

        /** 中文摘要，供诊断输出；无 emoji。 */
        public String summary() {
            if (!runFound) {
                return "runId=" + value(runId) + " 审计不可用：" + (notes.isEmpty() ? "-" : notes.get(0));
            }
            StringBuilder sb = new StringBuilder();
            sb.append("runId=").append(runId)
                    .append(" 日志失败 ").append(logFailureCount)
                    .append(" 条，_call 失败 ").append(callFailureCount)
                    .append(" 条，一致=").append(consistent() ? "是" : "否");
            if (!missingCallRows.isEmpty()) {
                sb.append("；缺 _call 行：").append(missingCallRows.size()).append(" 条");
            }
            if (!missingLogRows.isEmpty()) {
                sb.append("；缺日志行：").append(missingLogRows.size()).append(" 条");
            }
            return sb.toString();
        }
    }
}
