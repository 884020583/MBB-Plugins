package org.moboxlab.mbb.eval.gateway;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code eval-generate} 的入参模型（规格 §10.2）。
 *
 * <p>只承载数据与本地校验，不自己取服务、不自己发调用：调用统一经
 * {@link RoleplayEvalGateway}。必填缺失、格式不符、数值越界按 §10.2 规则 4 返回
 * {@code invalid-param}；{@code history} 超限按规则 5 返回 {@code history-too-long}，
 * 两种都在本地拦下，不发起 MBB-AI 调用。</p>
 *
 * <p>{@code temperature} / {@code maxTokens} / {@code timeoutSeconds} 按 §10.2 的区间钳制，
 * 不因越界拒绝；{@code sessionId} 由本类按 §10.5 编码，调用方不得传入。</p>
 */
public final class EvalGenerateRequest {

    /** history 条数上限（§10.2）。 */
    public static final int MAX_HISTORY_MESSAGES = 40;
    /** history 正文总字符上限（§10.2）。 */
    public static final int MAX_HISTORY_CHARS = 30000;
    /** 同题采样序号上限（§10.2）。 */
    public static final int MAX_SAMPLE_INDEX = 99;
    /** 重试序号上限（§10.2）。 */
    public static final int MAX_ATTEMPT = 2;
    /** sessionId 总长上限（§10.5）。 */
    public static final int MAX_SESSION_ID_LENGTH = 160;
    /** 运行标识与用例标识的字符集与长度（§10.2）。 */
    public static final String ID_PATTERN_TEXT = "[A-Za-z0-9_-]{1,48}";

    private static final Pattern ID_PATTERN = Pattern.compile(ID_PATTERN_TEXT);
    private static final Set<String> RELATIONSHIPS = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("teacher", "friend", "otherRolePeer")));
    private static final Set<String> ADDRESSED = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("direct", "thread", "ambient")));
    private static final Set<String> REASONING_EFFORTS = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("none", "low", "medium", "high")));

    /** 本地校验失败：错误类型（{@code invalid-param} 或 {@code history-too-long}）与可读原因。 */
    public static final class Problem {
        private final String errorType;
        private final String message;

        public Problem(String errorType, String message) {
            this.errorType = errorType;
            this.message = message;
        }

        public String errorType() {
            return errorType;
        }

        public String message() {
            return message;
        }
    }

    /** 当轮发言（§10.2 {@code message}）。 */
    public static final class Message {
        private final long userID;
        private final String userName;
        private final String text;

        public Message(long userID, String userName, String text) {
            this.userID = userID;
            this.userName = userName;
            this.text = text;
        }

        public long userID() {
            return userID;
        }

        public String userName() {
            return userName;
        }

        public String text() {
            return text;
        }
    }

    /** 历史发言（§10.2 {@code history[]}），{@code speaker} 只接受 self / peer / otherRole。 */
    public static final class HistoryMessage {
        private final String speaker;
        private final long userID;
        private final String userName;
        private final String text;

        public HistoryMessage(String speaker, long userID, String userName, String text) {
            this.speaker = speaker;
            this.userID = userID;
            this.userName = userName;
            this.text = text;
        }

        public String speaker() {
            return speaker;
        }

        public long userID() {
            return userID;
        }

        public String userName() {
            return userName;
        }

        public String text() {
            return text;
        }
    }

    private final String runId;
    private final String caseId;
    private int sampleIndex;
    private int attempt;
    private List<HistoryMessage> history = Collections.emptyList();
    private Message message;
    private String relationship;
    private String addressed;
    private String profile;
    private Double temperature;
    private Integer maxTokens;
    private String reasoningEffort;
    private Integer timeoutSeconds;

    public EvalGenerateRequest(String runId, String caseId) {
        this.runId = runId;
        this.caseId = caseId;
    }

    public EvalGenerateRequest sampleIndex(int value) {
        this.sampleIndex = value;
        return this;
    }

    public EvalGenerateRequest attempt(int value) {
        this.attempt = value;
        return this;
    }

    public EvalGenerateRequest history(List<HistoryMessage> value) {
        this.history = value == null ? Collections.<HistoryMessage>emptyList() : new ArrayList<HistoryMessage>(value);
        return this;
    }

    public EvalGenerateRequest message(Message value) {
        this.message = value;
        return this;
    }

    public EvalGenerateRequest relationship(String value) {
        this.relationship = value;
        return this;
    }

    public EvalGenerateRequest addressed(String value) {
        this.addressed = value;
        return this;
    }

    public EvalGenerateRequest profile(String value) {
        this.profile = value;
        return this;
    }

    /** temperature 按 §10.2 钳制到 0..2；不传则使用 profile 默认值。 */
    public EvalGenerateRequest temperature(double value) {
        this.temperature = Double.valueOf(Math.max(0.0d, Math.min(2.0d, value)));
        return this;
    }

    /** maxTokens 按 §10.2 钳制到 300..4000。 */
    public EvalGenerateRequest maxTokens(int value) {
        this.maxTokens = Integer.valueOf(Math.max(300, Math.min(4000, value)));
        return this;
    }

    public EvalGenerateRequest reasoningEffort(String value) {
        this.reasoningEffort = value;
        return this;
    }

    /** timeoutSeconds 按 §10.2 钳制到 20..300。 */
    public EvalGenerateRequest timeoutSeconds(int value) {
        this.timeoutSeconds = Integer.valueOf(Math.max(20, Math.min(300, value)));
        return this;
    }

    public String runId() {
        return runId;
    }

    public String caseId() {
        return caseId;
    }

    public int sampleIndex() {
        return sampleIndex;
    }

    public int attempt() {
        return attempt;
    }

    public List<HistoryMessage> history() {
        return history;
    }

    public Message message() {
        return message;
    }

    public String relationship() {
        return relationship;
    }

    public String addressed() {
        return addressed;
    }

    public String profile() {
        return profile;
    }

    public Double temperature() {
        return temperature;
    }

    public Integer maxTokens() {
        return maxTokens;
    }

    public String reasoningEffort() {
        return reasoningEffort;
    }

    public Integer timeoutSeconds() {
        return timeoutSeconds;
    }

    /**
     * 按 §10.5 编码 sessionId：{@code roleplay-eval-<runId>-<caseId>-<sampleIndex>-<attempt>}。
     * 跨 run、跨采样、跨重试都不同，用于绕开 MBB-AI 本地缓存。
     */
    public String sessionId() {
        return encodeSessionId(runId, caseId, sampleIndex, attempt);
    }

    /** 静态编码入口，便于判分侧按 §6.3 的四要素各自独立拼接。 */
    public static String encodeSessionId(String runId, String caseId, int sampleIndex, int attempt) {
        return "roleplay-eval-" + runId + "-" + caseId + "-" + sampleIndex + "-" + attempt;
    }

    /**
     * 本地校验（§10.2 入参判定规则）。
     *
     * @return 第一条失败原因；通过返回 {@code null}
     */
    public Problem validate() {
        Problem idProblem = validateId("runId", runId);
        if (idProblem != null) {
            return idProblem;
        }
        idProblem = validateId("caseId", caseId);
        if (idProblem != null) {
            return idProblem;
        }
        String sessionId = sessionId();
        if (sessionId.length() > MAX_SESSION_ID_LENGTH) {
            return invalidParam("sessionId 长度 " + sessionId.length() + " 超过上限 "
                    + MAX_SESSION_ID_LENGTH + "（runId / caseId 各不超过 48）");
        }
        if (sampleIndex < 0 || sampleIndex > MAX_SAMPLE_INDEX) {
            return invalidParam("sampleIndex 越界：" + sampleIndex + "，取值 0.." + MAX_SAMPLE_INDEX);
        }
        if (attempt < 0 || attempt > MAX_ATTEMPT) {
            return invalidParam("attempt 越界：" + attempt + "，取值 0.." + MAX_ATTEMPT);
        }
        if (message == null) {
            return invalidParam("缺少必填字段 message");
        }
        if (message.text() == null || message.text().isEmpty()) {
            return invalidParam("message.text 不得为空字符串");
        }
        int historyChars = 0;
        for (int i = 0; i < history.size(); i++) {
            HistoryMessage item = history.get(i);
            if (item == null) {
                return invalidParam("history[" + i + "] 为空");
            }
            if (!"self".equals(item.speaker()) && !"peer".equals(item.speaker())
                    && !"otherRole".equals(item.speaker())) {
                return invalidParam("history[" + i + "].speaker 非法：" + item.speaker()
                        + "，只接受 self / peer / otherRole");
            }
            if (item.text() == null) {
                return invalidParam("history[" + i + "].text 为 null");
            }
            historyChars += item.text().length();
        }
        if (history.size() > MAX_HISTORY_MESSAGES) {
            return historyTooLong("history 超过 " + MAX_HISTORY_MESSAGES + " 条（实际 " + history.size()
                    + " 条），按 §10.2 不得静默截断");
        }
        if (historyChars > MAX_HISTORY_CHARS) {
            return historyTooLong("history 超过 " + MAX_HISTORY_CHARS + " 字符（实际 " + historyChars
                    + " 字符），按 §10.2 不得静默截断");
        }
        if (relationship != null && !RELATIONSHIPS.contains(relationship)) {
            return invalidParam("relationship 非法：" + relationship
                    + "，只接受 teacher / friend / otherRolePeer");
        }
        if (addressed != null && !ADDRESSED.contains(addressed)) {
            return invalidParam("addressed 非法：" + addressed + "，只接受 direct / thread / ambient");
        }
        if (reasoningEffort != null && !REASONING_EFFORTS.contains(reasoningEffort)) {
            return invalidParam("reasoningEffort 非法：" + reasoningEffort
                    + "，只接受 none / low / medium / high");
        }
        return null;
    }

    /**
     * 拼装公开动作入参。
     *
     * <p>不写 {@code sessionId}、{@code selfID} 与 persona：按 §10.2 判定规则 1-3，
     * 这三项由 Roleplay 侧决定，调用方传了也不得生效，因此不传。</p>
     */
    public JSONObject toParams() {
        JSONObject params = new JSONObject(true);
        params.put("runId", runId);
        params.put("caseId", caseId);
        params.put("sampleIndex", Integer.valueOf(sampleIndex));
        params.put("attempt", Integer.valueOf(attempt));
        if (!history.isEmpty()) {
            JSONArray array = new JSONArray();
            for (HistoryMessage item : history) {
                if (item == null) {
                    continue;
                }
                JSONObject row = new JSONObject(true);
                row.put("speaker", item.speaker());
                row.put("userID", Long.valueOf(item.userID()));
                row.put("userName", item.userName());
                row.put("text", item.text());
                array.add(row);
            }
            params.put("history", array);
        }
        if (message != null) {
            JSONObject row = new JSONObject(true);
            row.put("userID", Long.valueOf(message.userID()));
            row.put("userName", message.userName());
            row.put("text", message.text());
            params.put("message", row);
        }
        if (relationship != null) {
            params.put("relationship", relationship);
        }
        if (addressed != null) {
            params.put("addressed", addressed);
        }
        if (profile != null && !profile.isEmpty()) {
            params.put("profile", profile);
        }
        if (temperature != null) {
            params.put("temperature", temperature);
        }
        if (maxTokens != null) {
            params.put("maxTokens", maxTokens);
        }
        if (reasoningEffort != null) {
            params.put("reasoningEffort", reasoningEffort);
        }
        if (timeoutSeconds != null) {
            params.put("timeoutSeconds", timeoutSeconds);
        }
        return params;
    }

    private static Problem validateId(String field, String value) {
        if (value == null || value.isEmpty()) {
            return invalidParam("缺少必填字段 " + field);
        }
        if (!ID_PATTERN.matcher(value).matches()) {
            return invalidParam(field + " 格式不合法：" + value + "，必须匹配 " + ID_PATTERN_TEXT);
        }
        return null;
    }

    private static Problem invalidParam(String message) {
        return new Problem(EvalGenerateResult.ERROR_INVALID_PARAM, message);
    }

    private static Problem historyTooLong(String message) {
        return new Problem(EvalGenerateResult.ERROR_HISTORY_TOO_LONG, message);
    }
}
