package org.moboxlab.mbb.eval.store;

import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.PluginLogger;
import org.moboxlab.moboxbot.API.Storage.StorageService;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * run_id 生成与终态 CAS（规格 §4.3、§4.4）。
 *
 * <p>本切片提供可被 F-2 复用的骨架：生成 run_id（本地校验格式并先查库避开已占用值）、
 * 写入 status='RUNNING' 的运行行、按 run_id / RUNNING / 最近一次查询运行行，以及把 RUNNING 行
 * 原子改写为 COMPLETED / FAILED / ABORTED。样例与报表归 F-2；终态 CAS 返回 -1 时记 WARN，
 * 由调用方下一轮重试，返回 0 按幂等成功处理。全部 SQL 必须含 plugin_ 且不得含 bot_（§11.4）。</p>
 */
public final class EvalStore {

    /** 运行中状态；终态 CAS 只改写该状态的行。 */
    public static final String STATUS_RUNNING = "RUNNING";
    /** 完成状态。 */
    public static final String STATUS_COMPLETED = "COMPLETED";
    /** 失败状态。 */
    public static final String STATUS_FAILED = "FAILED";
    /** 中止状态。 */
    public static final String STATUS_ABORTED = "ABORTED";

    /** ABORTED 的原因码，取值集合封闭（§13.1.3）。 */
    public static final String ABORT_REASON_USER = "user-abort";
    /** 预算触顶。 */
    public static final String ABORT_REASON_BUDGET = "budget";
    /** 墙钟超限。 */
    public static final String ABORT_REASON_DEADLINE = "deadline";
    /** 插件卸载。 */
    public static final String ABORT_REASON_SHUTDOWN = "shutdown";

    /** smoke 档。 */
    public static final String TIER_SMOKE = "smoke";
    /** full 档。 */
    public static final String TIER_FULL = "full";

    /** run_id 形状：r-<yyyyMMddHHmmss>-<smoke|full>-<4 位小写字母或数字>。 */
    private static final Pattern RUN_ID_PATTERN =
            Pattern.compile("r-\\d{14}-(smoke|full)-[a-z0-9]{4}");
    /** 业务键允许字符与长度上限（§4.3）。 */
    private static final Pattern RUN_ID_ALLOWED = Pattern.compile("[A-Za-z0-9_-]{1,48}");
    /** run_id 随机后缀字符集。 */
    private static final String RANDOM_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    /** 生成 run_id 的最大尝试次数：同秒重复概率极低，同时避免数据库异常时死循环。 */
    private static final int RUN_ID_MAX_ATTEMPTS = 8;

    /** 时间列格式：TEXT、可按字典序比较，init 回收与最近 N 排序都依赖它（§4.3）。 */
    private static final String TIME_FORMAT = "yyyy-MM-dd HH:mm:ss";
    /** 内存中最多保留的降级警告条数，避免长跑无限增长。 */
    private static final int WARN_LIMIT = 200;

    private static final String SQL_RUN_ID_EXISTS =
            "SELECT run_id FROM plugin_mbb_eval_run WHERE run_id = ?";

    /**
     * 受理时的完整建行（§4.4）：一次写齐九键快照、config_snapshot_json、baseline_id_at_run、
     * calls_budget 与 started_at；status 固定 'RUNNING'，report_path 保持 null。
     *
     * <p>列顺序只影响参数下标：前五个参数固定为 run_id、tier、started_at、calls_budget、
     * eval_plugin_version，供 6 参兼容重载复用；其余快照列缺省写 null，不伪造。</p>
     */
    private static final String SQL_INSERT_RUN = "INSERT INTO plugin_mbb_eval_run"
            + " (run_id, tier, started_at, status, calls_budget, eval_plugin_version,"
            + " bank_file, bank_version, bank_hash, persona_id, persona_version, persona_hash,"
            + " rubric_version, eval_api_version, roleplay_plugin_version, java_version, os,"
            + " eval_profile, judge_profile, judge_model, generation_model, config_fingerprint,"
            + " config_snapshot_json, sample_k, baseline_id_at_run)"
            + " VALUES (?, ?, ?, 'RUNNING', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /** calls_budget 缺省值（§4.2）。 */
    private static final int DEFAULT_CALLS_BUDGET = 100;
    /** sample_k 缺省值（§4.2）。 */
    private static final int DEFAULT_SAMPLE_K = 1;

    private static final String SQL_FIND_RUN =
            "SELECT * FROM plugin_mbb_eval_run WHERE run_id = ?";

    private static final String SQL_FIND_RUNNING = "SELECT * FROM plugin_mbb_eval_run"
            + " WHERE status = 'RUNNING' ORDER BY started_at DESC, run_id DESC LIMIT 1";

    private static final String SQL_FIND_LATEST = "SELECT * FROM plugin_mbb_eval_run"
            + " ORDER BY started_at DESC, run_id DESC LIMIT 1";

    private static final String SQL_CAS_COMPLETED = "UPDATE plugin_mbb_eval_run"
            + " SET status = 'COMPLETED', ended_at = ?, duration_ms = ?, report_path = ?"
            + " WHERE run_id = ? AND status = 'RUNNING'";
    private static final String SQL_CAS_FAILED = "UPDATE plugin_mbb_eval_run"
            + " SET status = 'FAILED', ended_at = ?, duration_ms = ?, error_summary = ?"
            + " WHERE run_id = ? AND status = 'RUNNING'";
    private static final String SQL_CAS_ABORTED = "UPDATE plugin_mbb_eval_run"
            + " SET status = 'ABORTED', ended_at = ?, duration_ms = ?, error_summary = ?"
            + " WHERE run_id = ? AND status = 'RUNNING'";

    /**
     * 用例结果插入：主键由门面按「run_id + 业务键」派生（§4.2 O7），(run_id, case_id) 唯一索引兜底。
     *
     * <p>走 update 而不是 insert：主键是 TEXT，insert 返回的 last_insert_rowid 对这类表没有意义，
     * 0 无法区分「已存在」与「取键失败」（§4.1）。</p>
     */
    private static final String SQL_INSERT_CASE_RESULT = "INSERT INTO "
            + EvalSchemaService.TABLE_CASE_RESULT
            + " (case_result_id, run_id, case_id, dimension, status, score, max_score, label, scale,"
            + " k_used, sample_count, cached_count, tokens_prompt, tokens_completion, tokens_total,"
            + " duration_ms, evidence, detail_json, created_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SQL_FIND_CASE_RESULT = "SELECT * FROM "
            + EvalSchemaService.TABLE_CASE_RESULT + " WHERE run_id = ? AND case_id = ?";

    private static final String SQL_FIND_CASE_RESULTS = "SELECT * FROM "
            + EvalSchemaService.TABLE_CASE_RESULT + " WHERE run_id = ? ORDER BY case_id";

    private static final String SQL_COUNT_CASE_RESULTS = "SELECT COUNT(*) AS total FROM "
            + EvalSchemaService.TABLE_CASE_RESULT + " WHERE run_id = ?";

    /** 单次调用插入：call_id 由 run_id + case_id + stage + 序号派生，每次尝试天然独立（§4.4）。 */
    private static final String SQL_INSERT_CALL = "INSERT INTO " + EvalSchemaService.TABLE_CALL
            + " (call_id, run_id, case_id, stage, sample_index, attempt, status, error_type, retryable,"
            + " cached, profile, model, usage_prompt, usage_completion, usage_total, usage_cached,"
            + " elapsed_ms, finish_reason, response_chars, created_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SQL_FIND_CALL = "SELECT * FROM " + EvalSchemaService.TABLE_CALL
            + " WHERE call_id = ?";

    private static final String SQL_FIND_CALLS = "SELECT * FROM " + EvalSchemaService.TABLE_CALL
            + " WHERE run_id = ? ORDER BY created_at, call_id";

    private static final String SQL_COUNT_CALLS = "SELECT COUNT(*) AS total FROM "
            + EvalSchemaService.TABLE_CALL + " WHERE run_id = ?";

    /** 每次 AI 调用前把 calls_used 原子加一（§4.4、§9.5）；只依赖 run_id，由调用方保证在跑。 */
    private static final String SQL_INCREMENT_CALLS_USED = "UPDATE plugin_mbb_eval_run"
            + " SET calls_used = calls_used + 1 WHERE run_id = ?";

    /** 基线插入：active 由调用方给定，默认 1；唯一索引 idx_eval_baseline_active 兜底。 */
    private static final String SQL_INSERT_BASELINE = "INSERT INTO "
            + EvalSchemaService.TABLE_BASELINE
            + " (baseline_id, run_id, active, dimension_scores_json, total_score, valid_case_count,"
            + " perf_ref_json, bank_hash, rubric_version, persona_hash, config_fingerprint, eval_profile,"
            + " judge_profile, judge_model, generation_model, eval_api_version, created_at, created_by,"
            + " reason, superseded_by)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SQL_FIND_ACTIVE_BASELINE =
            "SELECT * FROM " + EvalSchemaService.TABLE_BASELINE + " WHERE active = 1 LIMIT 1";

    private static final String SQL_FIND_BASELINE =
            "SELECT * FROM " + EvalSchemaService.TABLE_BASELINE + " WHERE baseline_id = ?";

    private static final String SQL_FIND_BASELINES = "SELECT * FROM "
            + EvalSchemaService.TABLE_BASELINE + " ORDER BY created_at DESC, baseline_id DESC LIMIT ?";

    /** 切换第 ② 步：旧 active 行退役并记录被谁取代（§8.1）。 */
    private static final String SQL_SUPERSEDE_ACTIVE = "UPDATE " + EvalSchemaService.TABLE_BASELINE
            + " SET active = 0, superseded_by = ? WHERE active = 1";

    /** 切换第 ③ 步：把已写好 active=0 的新行激活。 */
    private static final String SQL_ACTIVATE_BASELINE = "UPDATE " + EvalSchemaService.TABLE_BASELINE
            + " SET active = 1 WHERE baseline_id = ? AND active = 0";

    private static final String SQL_BASELINE_BY_RUN =
            "SELECT baseline_id FROM " + EvalSchemaService.TABLE_BASELINE + " WHERE run_id = ? LIMIT 1";

    /** 保留窗口之外的终态行（最新 N 条以外的旧行），最新在前；RUNNING 不在删除范围。 */
    private static final String SQL_TERMINAL_RUNS_BEYOND = "SELECT * FROM plugin_mbb_eval_run"
            + " WHERE status IN ('COMPLETED', 'FAILED', 'ABORTED')"
            + " ORDER BY started_at DESC, run_id DESC LIMIT -1 OFFSET ?";

    private static final String SQL_STALE_RUNNING = "SELECT * FROM plugin_mbb_eval_run"
            + " WHERE status = 'RUNNING' AND started_at < ? ORDER BY started_at, run_id";

    private static final String SQL_RECENT_RUNS = "SELECT * FROM plugin_mbb_eval_run"
            + " ORDER BY started_at DESC, run_id DESC LIMIT ?";

    private static final String SQL_DELETE_CASE_RESULTS =
            "DELETE FROM " + EvalSchemaService.TABLE_CASE_RESULT + " WHERE run_id = ?";

    private static final String SQL_DELETE_CALLS =
            "DELETE FROM " + EvalSchemaService.TABLE_CALL + " WHERE run_id = ?";

    /** 只删终态行，且被任何基线引用的 run 永不删除（§4.5）。 */
    private static final String SQL_DELETE_RUN = "DELETE FROM plugin_mbb_eval_run"
            + " WHERE run_id = ? AND status IN ('COMPLETED', 'FAILED', 'ABORTED')"
            + " AND NOT EXISTS (SELECT 1 FROM " + EvalSchemaService.TABLE_BASELINE
            + " WHERE run_id = ?)";

    private final StorageService storage;
    private final PluginLogger logger;
    private final Random random;
    /** 本次运行期间累计的降级警告；测试据此断言 -1 / 部分行 / 非法 JSON 都被显式记录。 */
    private final List<String> warnings = Collections.synchronizedList(new ArrayList<String>());

    public EvalStore(StorageService storage, PluginLogger logger) {
        if (storage == null) {
            throw new IllegalArgumentException("存储服务不可用，无法创建 EvalStore");
        }
        this.storage = storage;
        this.logger = logger;
        this.random = new Random();
        for (String sql : sqlStatements()) {
            if (!EvalSchemaService.isPluginScopedSql(sql)) {
                warn("SQL 越界（必须含 plugin_ 且不得含 bot_）：" + sql);
            }
        }
    }

    /**
     * 生成一个未被占用的 run_id。
     *
     * <p>生成后先做 §4.3 的本地格式校验，再查库确认没有同值行；已存在则重新生成。
     * INSERT 返回主键冲突（并发写入同一 run_id）时，调用方（F-2）必须重新调用本方法
     * 生成新的 run_id，不得覆盖既有行。</p>
     *
     * @param tier {@code smoke} 或 {@code full}
     * @return 形如 {@code r-202610252300-full-7k2m} 的 run_id
     * @throws IllegalArgumentException 档位非法
     * @throws IllegalStateException    生成结果不满足 §4.3 的字符与长度约束（编码错误，调用方应中止 run），
     *                                  或连续 {@value #RUN_ID_MAX_ATTEMPTS} 次都命中已占用的 run_id
     */
    public String generateRunId(String tier) {
        if (!TIER_SMOKE.equals(tier) && !TIER_FULL.equals(tier)) {
            throw new IllegalArgumentException("未知评测档位：" + tier);
        }
        for (int attempt = 0; attempt < RUN_ID_MAX_ATTEMPTS; attempt++) {
            String stamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new java.util.Date());
            String runId = "r-" + stamp + "-" + tier + "-" + randomSuffix(4);
            if (!isValidRunId(runId)) {
                throw new IllegalStateException("生成的 run_id 不满足格式约束：" + runId);
            }
            if (!runIdExists(runId)) {
                return runId;
            }
            warn("run_id 已被占用，重新生成：" + runId);
        }
        throw new IllegalStateException("连续 " + RUN_ID_MAX_ATTEMPTS + " 次生成的 run_id 都已存在");
    }

    /**
     * 查库判断 run_id 是否已被占用。
     *
     * <p>查询失败或返回空集时按未占用处理并记 WARN：INSERT 的主键约束仍会兜底，
     * 不能因为一次读失败就阻断评测。</p>
     */
    public boolean runIdExists(String runId) {
        if (runId == null || runId.trim().isEmpty()) {
            return false;
        }
        try {
            List<JSONObject> rows = storage.query(SQL_RUN_ID_EXISTS, runId);
            return rows != null && !rows.isEmpty();
        } catch (RuntimeException e) {
            warn("查询 run_id 是否已存在失败，按未占用处理：" + e.getMessage());
            return false;
        }
    }

    /**
     * 写入一条 status='RUNNING' 的完整运行行（§4.4、§8.2）。
     *
     * <p>一次写齐受理时已确定的列：run_id、tier、started_at、九键快照（bank_hash、
     * rubric_version、persona_hash、config_fingerprint、eval_profile、judge_profile、
     * judge_model、generation_model、eval_api_version）、config_snapshot_json、
     * baseline_id_at_run、calls_budget 与同组快照列（bank_file/bank_version/persona_id/
     * persona_version 等）。status 固定写 'RUNNING'，report_path 留 null。</p>
     *
     * <p>缺省：calls_budget=100、sample_k=1（§4.2）；其余缺失列写 null，由基线比较判
     * 「数据缺失」，不得用假值冒充快照。主键冲突时返回 false，由调用方重新生成 run_id
     * 后重试，不得覆盖既有行（§4.3）；其余写库异常同样返回 false 并记 WARN。</p>
     *
     * @param row 建行数据，至少包含 run_id、tier、started_at
     * @return 写入成功返回 true
     * @throws IllegalArgumentException row 为空、run_id 不满足格式，或档位不是 smoke/full
     */
    public boolean insertRunning(JSONObject row) {
        if (row == null) {
            throw new IllegalArgumentException("建行数据不能为空");
        }
        String runId = row.getString("run_id");
        String tier = row.getString("tier");
        if (!isValidRunId(runId)) {
            throw new IllegalArgumentException("run_id 不满足格式约束：" + runId);
        }
        if (!TIER_SMOKE.equals(tier) && !TIER_FULL.equals(tier)) {
            throw new IllegalArgumentException("未知评测档位：" + tier);
        }
        int callsBudget = row.containsKey("calls_budget")
                ? row.getIntValue("calls_budget") : DEFAULT_CALLS_BUDGET;
        int sampleK = row.containsKey("sample_k") ? row.getIntValue("sample_k") : DEFAULT_SAMPLE_K;
        try {
            int result = storage.update(SQL_INSERT_RUN,
                    runId, tier, row.getString("started_at"),
                    Integer.valueOf(callsBudget), row.getString("eval_plugin_version"),
                    row.getString("bank_file"), row.getString("bank_version"),
                    row.getString("bank_hash"), row.getString("persona_id"),
                    row.getString("persona_version"), row.getString("persona_hash"),
                    row.getString("rubric_version"), row.getString("eval_api_version"),
                    row.getString("roleplay_plugin_version"), row.getString("java_version"),
                    row.getString("os"), row.getString("eval_profile"),
                    row.getString("judge_profile"), row.getString("judge_model"),
                    row.getString("generation_model"), row.getString("config_fingerprint"),
                    row.getString("config_snapshot_json"), Integer.valueOf(sampleK),
                    row.getString("baseline_id_at_run"));
            if (result <= 0) {
                warn("写入 RUNNING 行未生效（run_id=" + runId + "，update=" + result + "）");
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            warn("写入 RUNNING 行异常（run_id=" + runId + "）：" + e.getMessage());
            return false;
        }
    }

    /**
     * 最小建行的兼容重载：只写受理即可确定的列，快照列留 null 等后续切片补齐。
     *
     * <p>F-2 起生产调用走 {@link #insertRunning(JSONObject)}；本重载保留给既有调用方与用例，
     * 参数次序与语义不变。</p>
     *
     * @param pluginVersion 写入 eval_plugin_version 的插件版本，可为 null
     * @return 写入成功返回 true
     * @throws IllegalArgumentException run_id 不满足格式，或档位不是 smoke/full
     */
    public boolean insertRunning(String runId, String tier, String startedAt, int callsBudget,
                                 String pluginVersion) {
        JSONObject row = new JSONObject();
        row.put("run_id", runId);
        row.put("tier", tier);
        row.put("started_at", startedAt);
        row.put("calls_budget", Integer.valueOf(callsBudget));
        row.put("eval_plugin_version", pluginVersion);
        return insertRunning(row);
    }

    /** 按 run_id 查询运行行；不存在或查询异常返回 null（异常已记 WARN）。 */
    public JSONObject findRun(String runId) {
        if (runId == null || runId.trim().isEmpty()) {
            return null;
        }
        return queryOne(SQL_FIND_RUN, runId);
    }

    /**
     * 查询尚未收尾的 RUNNING 行。
     *
     * <p>插件重启后内存中的 active 为空，但库里可能留着插件崩溃时的 RUNNING 行。受理前用它
     * 保证同一时刻只有一个 run；回收动作属 F 区 init。</p>
     */
    public JSONObject findRunningRun() {
        return queryOne(SQL_FIND_RUNNING);
    }

    /** 查询最近一次运行行（按 started_at 倒序）；没有任何记录返回 null。 */
    public JSONObject findLatestRun() {
        return queryOne(SQL_FIND_LATEST);
    }

    /**
     * 写入一条用例结果行（§4.4）。
     *
     * <p>唯一索引 (run_id, case_id) 兜底：同一 run 的同一 case 二次插入返回 {@link WriteResult#NO_MATCH}
     * 并记 WARN，旧行保持原样，调用方按「该 case 已有结果」跳过。缺 case_result_id 时按
     * {@link #deriveCaseResultId} 派生。</p>
     *
     * @throws IllegalArgumentException 缺少 run_id / case_id / dimension / status
     */
    public WriteResult insertCaseResult(JSONObject row) {
        if (row == null) {
            throw new IllegalArgumentException("用例结果行不能为空");
        }
        String runId = requireText(row, "run_id", "用例结果");
        String caseId = requireText(row, "case_id", "用例结果");
        String dimension = requireText(row, "dimension", "用例结果");
        String status = requireText(row, "status", "用例结果");
        String caseResultId = optionalText(row, "case_result_id");
        if (caseResultId == null) {
            caseResultId = deriveCaseResultId(runId, caseId);
        }
        String createdAt = optionalText(row, "created_at");
        return write("用例结果插入（case_result_id=" + caseResultId + "）", SQL_INSERT_CASE_RESULT,
                caseResultId, runId, caseId, dimension, status,
                row.getDouble("score"), Double.valueOf(defaultDouble(row, "max_score", 100.0D)),
                optionalText(row, "label"), optionalText(row, "scale"),
                row.getInteger("k_used"), row.getInteger("sample_count"), row.getInteger("cached_count"),
                row.getInteger("tokens_prompt"), row.getInteger("tokens_completion"),
                row.getInteger("tokens_total"), row.getLong("duration_ms"),
                optionalText(row, "evidence"), optionalText(row, "detail_json"),
                createdAt == null ? nowText() : createdAt);
    }

    /** 按 (run_id, case_id) 查询用例结果行；没有或查询异常返回 null（异常已记 WARN）。 */
    public JSONObject findCaseResult(String runId, String caseId) {
        if (isBlank(runId) || isBlank(caseId)) {
            return null;
        }
        return queryOne(SQL_FIND_CASE_RESULT, runId, caseId);
    }

    /**
     * 查询某次 run 的全部用例结果行，并与 COUNT(*) 比对识别部分行（ST8）。
     *
     * <p>部分行时 {@link RowSet#isComplete()} 为 false：调用方必须把缺失 case 标成「数据缺失」，
     * 不得按 0 分计入维度统计（§15.6 F12）。</p>
     */
    public RowSet findCaseResults(String runId) {
        if (isBlank(runId)) {
            return RowSet.empty();
        }
        return queryRows("用例结果查询", SQL_FIND_CASE_RESULTS, SQL_COUNT_CASE_RESULTS, runId);
    }

    /**
     * 写入一条单次调用行（§4.4：每次尝试即落库，失败与 cached 调用都要留行）。
     *
     * <p>缺 call_id 时按 {@link #deriveCallId} 派生；同一次尝试重复写入返回
     * {@link WriteResult#NO_MATCH}，不覆盖既有调用记录。</p>
     *
     * @throws IllegalArgumentException 缺少 run_id / stage / status
     */
    public WriteResult insertCall(JSONObject row) {
        if (row == null) {
            throw new IllegalArgumentException("调用行不能为空");
        }
        String runId = requireText(row, "run_id", "调用行");
        String stage = requireText(row, "stage", "调用行");
        String status = requireText(row, "status", "调用行");
        String caseId = optionalText(row, "case_id");
        Integer sampleIndex = row.getInteger("sample_index");
        Integer attempt = row.getInteger("attempt");
        String callId = optionalText(row, "call_id");
        if (callId == null) {
            callId = deriveCallId(runId, caseId, stage, sampleIndex, attempt);
        }
        String createdAt = optionalText(row, "created_at");
        return write("调用行插入（call_id=" + callId + "）", SQL_INSERT_CALL,
                callId, runId, caseId, stage, sampleIndex, attempt, status,
                optionalText(row, "error_type"), row.getInteger("retryable"), row.getInteger("cached"),
                optionalText(row, "profile"), optionalText(row, "model"),
                row.getInteger("usage_prompt"), row.getInteger("usage_completion"),
                row.getInteger("usage_total"), row.getInteger("usage_cached"),
                row.getLong("elapsed_ms"), optionalText(row, "finish_reason"),
                row.getInteger("response_chars"), createdAt == null ? nowText() : createdAt);
    }

    /**
     * 把某次运行的 calls_used 原子加一（§4.4、§9.5）。
     *
     * <p>每次 AI 调用前执行，重试与缓存重采都各记一次。规格给的语句只带 run_id；
     * 终态之后不应再发起调用，因此不额外加 status 条件。update 语义沿用
     * {@link #write}：-1 记 WARN（效果未知）、0 表示 run_id 不存在。</p>
     *
     * @throws IllegalArgumentException run_id 为空
     */
    public WriteResult incrementCallsUsed(String runId) {
        if (isBlank(runId)) {
            throw new IllegalArgumentException("run_id 不能为空");
        }
        return write("calls_used 自增（run_id=" + runId + "）", SQL_INCREMENT_CALLS_USED, runId);
    }

    /** 按 call_id 查询单次调用行；没有或查询异常返回 null（异常已记 WARN）。 */
    public JSONObject findCall(String callId) {
        if (isBlank(callId)) {
            return null;
        }
        return queryOne(SQL_FIND_CALL, callId);
    }

    /**
     * 查询某次 run 的全部调用行，并与 COUNT(*) 比对识别部分行。
     *
     * <p>失败调用（status=error）与 cached 调用（cached=1）都是正常行，不做过滤。</p>
     */
    public RowSet findCalls(String runId) {
        if (isBlank(runId)) {
            return RowSet.empty();
        }
        return queryRows("调用行查询", SQL_FIND_CALLS, SQL_COUNT_CALLS, runId);
    }

    /**
     * 写入一条基线行（§8.1）。
     *
     * <p>active=1 的行同时只能有一条（部分唯一索引 idx_eval_baseline_active）：第二条返回失败，
     * 旧基线保持 active。切换流程无事务，分三步：① 新行以 active=0 插入；②
     * {@link #supersedeActiveBaseline}；③ {@link #activateBaseline}。任一步失败时调用方核对
     * active=1 行数，按「保留最近 created_at」修复后重跑，整个过程可重跑。</p>
     *
     * @throws IllegalArgumentException 缺少 baseline_id / run_id，或 active 不是 0/1
     */
    public WriteResult insertBaseline(JSONObject row) {
        if (row == null) {
            throw new IllegalArgumentException("基线行不能为空");
        }
        String baselineId = requireText(row, "baseline_id", "基线行");
        String runId = requireText(row, "run_id", "基线行");
        Integer active = row.getInteger("active");
        if (active == null) {
            active = Integer.valueOf(1);
        }
        if (active.intValue() != 0 && active.intValue() != 1) {
            throw new IllegalArgumentException("基线 active 只能是 0 或 1：" + active);
        }
        String createdAt = optionalText(row, "created_at");
        return write("基线插入（baseline_id=" + baselineId + "）", SQL_INSERT_BASELINE,
                baselineId, runId, active,
                optionalText(row, "dimension_scores_json"), row.getDouble("total_score"),
                row.getInteger("valid_case_count"), optionalText(row, "perf_ref_json"),
                optionalText(row, "bank_hash"), optionalText(row, "rubric_version"),
                optionalText(row, "persona_hash"), optionalText(row, "config_fingerprint"),
                optionalText(row, "eval_profile"), optionalText(row, "judge_profile"),
                optionalText(row, "judge_model"), optionalText(row, "generation_model"),
                optionalText(row, "eval_api_version"),
                createdAt == null ? nowText() : createdAt,
                optionalText(row, "created_by"), optionalText(row, "reason"),
                optionalText(row, "superseded_by"));
    }

    /** 当前生效基线（active=1）；没有或查询异常返回 null（异常已记 WARN）。 */
    public JSONObject findActiveBaseline() {
        return queryOne(SQL_FIND_ACTIVE_BASELINE);
    }

    /** 按 baseline_id 查询基线行；没有或查询异常返回 null。 */
    public JSONObject findBaseline(String baselineId) {
        if (isBlank(baselineId)) {
            return null;
        }
        return queryOne(SQL_FIND_BASELINE, baselineId);
    }

    /**
     * 历史基线列表，按 created_at 倒序。
     *
     * <p>limit 是展示用截断，不做完整行数校验；基线行永不清理（§4.5）。</p>
     *
     * @throws IllegalArgumentException limit 不是正数
     */
    public List<JSONObject> findBaselines(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("查询条数必须为正数：" + limit);
        }
        return queryList("基线查询", SQL_FIND_BASELINES, Integer.valueOf(limit));
    }

    /**
     * 基线切换第 ② 步：把当前 active=1 行退役为 active=0 并写 superseded_by（§8.1）。
     *
     * @return APPLIED 表示旧 active 行已退役；NO_MATCH 表示当前没有 active 行（可能已切换过），
     * 调用方核对 active=1 行数后决定是否重跑；NOT_EXECUTED 表示语句未执行
     */
    public WriteResult supersedeActiveBaseline(String newBaselineId) {
        if (isBlank(newBaselineId)) {
            throw new IllegalArgumentException("新 baseline_id 不能为空");
        }
        return write("基线退役（superseded_by=" + newBaselineId + "）",
                SQL_SUPERSEDE_ACTIVE, newBaselineId);
    }

    /**
     * 基线切换第 ③ 步：把已写好 active=0 的新行置为 active=1（§8.1）。
     *
     * @return APPLIED 表示已激活；NO_MATCH 表示目标行不存在或已是 active
     */
    public WriteResult activateBaseline(String baselineId) {
        if (isBlank(baselineId)) {
            throw new IllegalArgumentException("baseline_id 不能为空");
        }
        return write("基线激活（baseline_id=" + baselineId + "）", SQL_ACTIVATE_BASELINE, baselineId);
    }

    /**
     * run 是否被任何基线行引用（§4.5 豁免依据）。
     *
     * <p>查询失败按「已引用」保守处理：宁可少删，不可删掉基线锚定的 run。</p>
     */
    public boolean isRunReferencedByBaseline(String runId) {
        if (isBlank(runId)) {
            return false;
        }
        try {
            JSONObject row = storage.queryOne(SQL_BASELINE_BY_RUN, runId);
            return row != null && !row.isEmpty();
        } catch (RuntimeException e) {
            warn("查询基线引用失败，按已引用保守处理（run_id=" + runId + "）：" + e.getMessage());
            return true;
        }
    }

    /** 最近 limit 条运行行（含 RUNNING），按 started_at 倒序；仅供展示与诊断。 */
    public List<JSONObject> findRecentRuns(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("查询条数必须为正数：" + limit);
        }
        return queryList("最近运行行查询", SQL_RECENT_RUNS, Integer.valueOf(limit));
    }

    /**
     * 清理候选：保留窗口之外的终态行，最新在前（§4.5）。
     *
     * <p>只算终态行（RUNNING 不在删除范围）；是否被基线引用由 {@link #isRunReferencedByBaseline}
     * 与 {@link #deleteRun} 兜底，本方法不替调用方豁免，便于 F-3 记录跳过原因。</p>
     *
     * @param keepRuns 保留的最近终态行数（evalKeepRuns）
     * @throws IllegalArgumentException keepRuns 为负数
     */
    public List<JSONObject> findTerminalRunsBeyond(int keepRuns) {
        if (keepRuns < 0) {
            throw new IllegalArgumentException("保留条数不能为负数：" + keepRuns);
        }
        return queryList("终态运行行查询", SQL_TERMINAL_RUNS_BEYOND, Integer.valueOf(keepRuns));
    }

    /**
     * 早于阈值的 RUNNING 行（§4.5 init 回收候选）。
     *
     * <p>只提供查询：置 ABORTED 复用 {@link #casAborted}，回收策略与 35 分钟阈值归 F-2/F-3。</p>
     *
     * @throws IllegalArgumentException 阈值文本为空
     */
    public List<JSONObject> findRunningRunsStartedBefore(String startedBefore) {
        if (isBlank(startedBefore)) {
            throw new IllegalArgumentException("时间阈值不能为空");
        }
        return queryList("早期 RUNNING 行查询", SQL_STALE_RUNNING, startedBefore);
    }

    /**
     * 删除某 run 的全部用例结果行（§4.5 删除顺序的第一步）。
     *
     * @return APPLIED 表示有行被删；NO_MATCH 表示 0 行（调用方按「中止本轮清理并记 WARN」处理）；
     * NOT_EXECUTED 表示语句未执行
     */
    public WriteResult deleteCaseResults(String runId) {
        if (isBlank(runId)) {
            throw new IllegalArgumentException("run_id 不能为空");
        }
        return write("用例结果删除（run_id=" + runId + "）", SQL_DELETE_CASE_RESULTS, runId);
    }

    /** 删除某 run 的全部调用行（§4.5 删除顺序的第一步）；返回值语义同 {@link #deleteCaseResults}。 */
    public WriteResult deleteCalls(String runId) {
        if (isBlank(runId)) {
            throw new IllegalArgumentException("run_id 不能为空");
        }
        return write("调用行删除（run_id=" + runId + "）", SQL_DELETE_CALLS, runId);
    }

    /**
     * 删除一条终态 run 行（§4.5 删除顺序的第二步）。
     *
     * <p>只删 COMPLETED / FAILED / ABORTED；被任何基线引用的 run、仍 RUNNING 的行都返回
     * NO_MATCH 且不删除。删除后调用方须复核行已不存在，避免半清理状态。</p>
     */
    public WriteResult deleteRun(String runId) {
        if (isBlank(runId)) {
            throw new IllegalArgumentException("run_id 不能为空");
        }
        if (isRunReferencedByBaseline(runId)) {
            warn("运行行被基线引用，拒绝删除（run_id=" + runId + "）");
            return WriteResult.NO_MATCH;
        }
        int result;
        try {
            result = storage.update(SQL_DELETE_RUN, runId, runId);
        } catch (RuntimeException e) {
            warn("运行行删除异常（run_id=" + runId + "）：" + e.getMessage());
            return WriteResult.NOT_EXECUTED;
        }
        if (result < 0) {
            warn("运行行删除未执行（run_id=" + runId + "，update=" + result + "）");
            return WriteResult.NOT_EXECUTED;
        }
        if (result == 0) {
            warn("运行行未删除（不存在、仍 RUNNING 或已被基线引用，run_id=" + runId + "）");
            return WriteResult.NO_MATCH;
        }
        return WriteResult.APPLIED;
    }

    /**
     * 解析 case 行的 detail_json（§4.4、ST9）。
     *
     * <p>非法 JSON 或空值按空对象降级并记 WARN，行本身仍可读；调用方照常生成报表，
     * 只是该 case 没有样本明细。</p>
     */
    public JSONObject parseDetailJson(JSONObject caseRow) {
        String text = caseRow == null ? null : caseRow.getString("detail_json");
        if (text == null || text.trim().isEmpty()) {
            return new JSONObject();
        }
        try {
            JSONObject parsed = JSONObject.parseObject(text);
            return parsed == null ? new JSONObject() : parsed;
        } catch (RuntimeException e) {
            String runId = caseRow == null ? null : caseRow.getString("run_id");
            String caseId = caseRow == null ? null : caseRow.getString("case_id");
            warn("detail_json 非法，按空对象降级（run_id=" + runId + "，case_id=" + caseId + "）："
                    + e.getMessage());
            return new JSONObject();
        }
    }

    /** 用例结果主键派生规则（§4.2 O7）：run_id + 业务键；调用方可在写入前用同一规则预生成。 */
    public static String deriveCaseResultId(String runId, String caseId) {
        return runId + ":case:" + caseId;
    }

    /** 调用主键派生规则（§4.2 O7）：run_id + case_id + stage + 样本序号 + 尝试序号，每次尝试独立。 */
    public static String deriveCallId(String runId, String caseId, String stage,
                                      Integer sampleIndex, Integer attempt) {
        return runId + ":call:" + (caseId == null ? "-" : caseId) + ":" + stage + ":"
                + (sampleIndex == null ? "-" : sampleIndex) + ":" + (attempt == null ? "-" : attempt);
    }

    private JSONObject queryOne(String sql, Object... params) {
        try {
            return storage.queryOne(sql, params);
        } catch (RuntimeException e) {
            warn("查询失败：" + e.getMessage());
            return null;
        }
    }

    /** 当前时间的运行行文本格式（{@code yyyy-MM-dd HH:mm:ss}）。 */
    public static String nowText() {
        return formatTime(System.currentTimeMillis());
    }

    /** 把毫秒时间戳格式化为运行行文本格式。 */
    public static String formatTime(long millis) {
        return new SimpleDateFormat(TIME_FORMAT, Locale.CHINA).format(new Date(millis));
    }

    /** 校验 run_id 是否满足 §4.3 的形状与字符约束。 */
    public static boolean isValidRunId(String runId) {
        if (runId == null || runId.isEmpty() || runId.length() > 48) {
            return false;
        }
        if (!RUN_ID_ALLOWED.matcher(runId).matches()) {
            return false;
        }
        return RUN_ID_PATTERN.matcher(runId).matches();
    }

    private String randomSuffix(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(RANDOM_ALPHABET.charAt(random.nextInt(RANDOM_ALPHABET.length())));
        }
        return builder.toString();
    }

    /**
     * 把 RUNNING 行改写为 COMPLETED。
     *
     * @return update() 返回值：-1 表示语句未执行（已记 WARN，调用方下一轮重试），
     * 0 表示没有 RUNNING 行（按幂等成功处理，不重复生成报表），1 表示本次完成改写
     */
    public int casCompleted(String runId, String endedAt, long durationMs, String reportPath) {
        return update("COMPLETED", SQL_CAS_COMPLETED, runId, endedAt, durationMs, reportPath);
    }

    /**
     * 把 RUNNING 行改写为 FAILED。
     *
     * @return 返回值语义同 {@link #casCompleted}
     */
    public int casFailed(String runId, String endedAt, long durationMs, String errorSummary) {
        return update("FAILED", SQL_CAS_FAILED, runId, endedAt, durationMs, errorSummary);
    }

    /**
     * 把 RUNNING 行改写为 ABORTED。
     *
     * @return 返回值语义同 {@link #casCompleted}
     */
    public int casAborted(String runId, String endedAt, long durationMs, String errorSummary) {
        return update("ABORTED", SQL_CAS_ABORTED, runId, endedAt, durationMs, errorSummary);
    }

    private int update(String targetStatus, String sql, String runId, String endedAt,
                       long durationMs, String text) {
        if (runId == null || runId.trim().isEmpty()) {
            throw new IllegalArgumentException("run_id 不能为空");
        }
        int result;
        try {
            result = storage.update(sql, endedAt, Long.valueOf(durationMs), text, runId);
        } catch (RuntimeException e) {
            warn("终态 CAS 异常（" + targetStatus + "，run_id=" + runId + "）：" + e.getMessage());
            return -1;
        }
        if (result == -1) {
            warn("终态 CAS 未执行（" + targetStatus + "，run_id=" + runId + "），下一轮重试");
        }
        return result;
    }

    /** update / insert 返回值的三态收敛（§4.1）。 */
    public enum WriteResult {
        /** 返回正数：语句已执行并命中行。 */
        APPLIED,
        /** 返回 0：没有写入或没有匹配行；INSERT 时多为唯一键 / 主键冲突（§4.4 按「已有结果」跳过）。 */
        NO_MATCH,
        /**
         * 返回 -1：语句未执行，效果未知；调用方下一轮重试，不得当作成功。
         *
         * <p>SQLite 的唯一键 / 主键冲突会被驱动上报成语句失败，因此 INSERT 冲突既可能是
         * {@link #NO_MATCH} 也可能是本值；两者都表示「未写入」，调用方一律按已有行跳过。</p>
         */
        NOT_EXECUTED;
    }

    /**
     * 查询结果集与其应有行数。
     *
     * <p>主程序把 query 异常吞成「已读到的部分行」，只看行数无法发现缺行；本类额外执行
     * {@code COUNT(*)}，两者不一致即判部分行（ST8）。</p>
     */
    public static final class RowSet {
        private static final RowSet EMPTY =
                new RowSet(Collections.<JSONObject>emptyList(), 0, false);

        private final List<JSONObject> rows;
        private final int expectedCount;
        private final boolean queryFailed;

        RowSet(List<JSONObject> rows, int expectedCount, boolean queryFailed) {
            this.rows = Collections.unmodifiableList(new ArrayList<JSONObject>(rows));
            this.expectedCount = expectedCount;
            this.queryFailed = queryFailed;
        }

        static RowSet empty() {
            return EMPTY;
        }

        /** 实际读到的行（只读）。 */
        public List<JSONObject> rows() {
            return rows;
        }

        public int size() {
            return rows.size();
        }

        /** 库中应有的行数；计数失败为 -1。 */
        public int expectedCount() {
            return expectedCount;
        }

        /** query 或计数本身失败（已记 WARN）。 */
        public boolean isQueryFailed() {
            return queryFailed;
        }

        /**
         * 行数完整且查询未失败。
         *
         * <p>为 false 时调用方必须把缺失项标成「数据缺失」，不得按 0 分计入维度统计。</p>
         */
        public boolean isComplete() {
            return !queryFailed && expectedCount >= 0 && rows.size() == expectedCount;
        }
    }

    /** 本类可能执行的 SQL（只读，供 A8 自检）。 */
    public static List<String> sqlStatements() {
        return Collections.unmodifiableList(new ArrayList<String>(Arrays.asList(
                SQL_RUN_ID_EXISTS, SQL_INSERT_RUN, SQL_FIND_RUN, SQL_FIND_RUNNING, SQL_FIND_LATEST,
                SQL_CAS_COMPLETED, SQL_CAS_FAILED, SQL_CAS_ABORTED,
                SQL_INSERT_CASE_RESULT, SQL_FIND_CASE_RESULT, SQL_FIND_CASE_RESULTS,
                SQL_COUNT_CASE_RESULTS, SQL_INSERT_CALL, SQL_FIND_CALL, SQL_FIND_CALLS,
                SQL_COUNT_CALLS, SQL_INCREMENT_CALLS_USED, SQL_INSERT_BASELINE,
                SQL_FIND_ACTIVE_BASELINE, SQL_FIND_BASELINE,
                SQL_FIND_BASELINES, SQL_SUPERSEDE_ACTIVE, SQL_ACTIVATE_BASELINE, SQL_BASELINE_BY_RUN,
                SQL_TERMINAL_RUNS_BEYOND, SQL_STALE_RUNNING, SQL_RECENT_RUNS, SQL_DELETE_CASE_RESULTS,
                SQL_DELETE_CALLS, SQL_DELETE_RUN)));
    }

    /** 把 update / insert 的 -1 / 0 / 正数收敛成三态并记录警告。 */
    private WriteResult write(String label, String sql, Object... params) {
        int result;
        try {
            result = storage.update(sql, params);
        } catch (RuntimeException e) {
            warn(label + "异常：" + e.getMessage());
            return WriteResult.NOT_EXECUTED;
        }
        if (result < 0) {
            warn(label + "未执行（update=" + result + "），效果未知，下一轮重试");
            return WriteResult.NOT_EXECUTED;
        }
        if (result == 0) {
            warn(label + "未写入任何行（update=0），按已有行跳过，不覆盖旧行");
            return WriteResult.NO_MATCH;
        }
        return WriteResult.APPLIED;
    }

    /** 查询行集并核对 COUNT(*)，识别部分行（ST8）。 */
    private RowSet queryRows(String label, String sql, String countSql, Object... params) {
        List<JSONObject> rows;
        try {
            rows = storage.query(sql, params);
        } catch (RuntimeException e) {
            warn(label + "异常：" + e.getMessage());
            return new RowSet(Collections.<JSONObject>emptyList(), -1, true);
        }
        if (rows == null) {
            rows = new ArrayList<JSONObject>();
        }
        int expected = countRows(label, countSql, params);
        if (expected < 0) {
            return new RowSet(rows, -1, true);
        }
        if (rows.size() != expected) {
            warn(label + "只读到 " + rows.size() + " / " + expected + " 行（部分行），"
                    + "缺失项按数据缺失处理，不计 0 分");
        }
        return new RowSet(rows, expected, false);
    }

    /** 查询列表（不做完整行数校验）；异常或 null 返回空列表并记 WARN。 */
    private List<JSONObject> queryList(String label, String sql, Object... params) {
        try {
            List<JSONObject> rows = storage.query(sql, params);
            return rows == null ? new ArrayList<JSONObject>() : rows;
        } catch (RuntimeException e) {
            warn(label + "异常：" + e.getMessage());
            return new ArrayList<JSONObject>();
        }
    }

    private int countRows(String label, String countSql, Object... params) {
        try {
            JSONObject row = storage.queryOne(countSql, params);
            if (row == null) {
                warn(label + "计数为空，无法判断是否部分行");
                return -1;
            }
            Long total = row.getLong("total");
            if (total == null) {
                warn(label + "计数结果缺少 total 列，无法判断是否部分行");
                return -1;
            }
            return total.intValue();
        } catch (RuntimeException e) {
            warn(label + "计数失败：" + e.getMessage());
            return -1;
        }
    }

    private static String requireText(JSONObject row, String key, String label) {
        String value = optionalText(row, key);
        if (value == null) {
            throw new IllegalArgumentException(label + "缺少必填列：" + key);
        }
        return value;
    }

    private static String optionalText(JSONObject row, String key) {
        String value = row.getString(key);
        return value == null || value.isEmpty() ? null : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static double defaultDouble(JSONObject row, String key, double fallback) {
        Double value = row.getDouble(key);
        return value == null ? fallback : value.doubleValue();
    }

    /** 本次会话累计的降级警告（只读副本，供报告与测试断言）。 */
    public List<String> warnings() {
        synchronized (warnings) {
            return Collections.unmodifiableList(new ArrayList<String>(warnings));
        }
    }

    private void warn(String message) {
        synchronized (warnings) {
            if (warnings.size() >= WARN_LIMIT) {
                warnings.remove(0);
            }
            warnings.add(message);
        }
        if (logger != null) {
            logger.sendWarn("[MBB-Eval] " + message);
        }
    }
}
