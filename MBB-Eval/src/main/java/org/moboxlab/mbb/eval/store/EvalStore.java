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

    private static final String SQL_RUN_ID_EXISTS =
            "SELECT run_id FROM plugin_mbb_eval_run WHERE run_id = ?";

    /**
     * 受理时的最小建行：只写受理即可确定的列。
     *
     * <p>§4.4 要求的九键快照、config_snapshot_json、baseline_id_at_run 依赖题库 / persona /
     * rubric 与配置指纹，属 F-2（§16.2「run 生命周期：建行 RUNNING…」）的建行内容；A-3 只
     * 需要一条能参与单飞互斥与终态 CAS 的 RUNNING 行，F-2 接入时替换本语句。</p>
     */
    private static final String SQL_INSERT_RUN = "INSERT INTO plugin_mbb_eval_run"
            + " (run_id, tier, started_at, status, calls_budget, eval_plugin_version)"
            + " VALUES (?, ?, ?, 'RUNNING', ?, ?)";

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

    private final StorageService storage;
    private final PluginLogger logger;
    private final Random random;

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
     * 写入一条 status='RUNNING' 的运行行（§4.4）。
     *
     * <p>主键冲突时返回 false，由调用方重新生成 run_id 后重试，不得覆盖既有行（§4.3）；
     * 其余写库异常同样返回 false 并记 WARN。</p>
     *
     * @param pluginVersion 写入 eval_plugin_version 的插件版本，可为 null
     * @return 写入成功返回 true
     * @throws IllegalArgumentException run_id 不满足格式，或档位不是 smoke/full
     */
    public boolean insertRunning(String runId, String tier, String startedAt, int callsBudget,
                                 String pluginVersion) {
        if (!isValidRunId(runId)) {
            throw new IllegalArgumentException("run_id 不满足格式约束：" + runId);
        }
        if (!TIER_SMOKE.equals(tier) && !TIER_FULL.equals(tier)) {
            throw new IllegalArgumentException("未知评测档位：" + tier);
        }
        try {
            int result = storage.update(SQL_INSERT_RUN, runId, tier, startedAt,
                    Integer.valueOf(callsBudget), pluginVersion);
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

    private JSONObject queryOne(String sql, Object... params) {
        try {
            return storage.queryOne(sql, params);
        } catch (RuntimeException e) {
            warn("查询运行行失败：" + e.getMessage());
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

    /** 本类可能执行的 SQL（只读，供 A8 自检）。 */
    public static List<String> sqlStatements() {
        return Collections.unmodifiableList(new ArrayList<String>(Arrays.asList(
                SQL_RUN_ID_EXISTS, SQL_INSERT_RUN, SQL_FIND_RUN, SQL_FIND_RUNNING, SQL_FIND_LATEST,
                SQL_CAS_COMPLETED, SQL_CAS_FAILED, SQL_CAS_ABORTED)));
    }

    private void warn(String message) {
        if (logger != null) {
            logger.sendWarn("[MBB-Eval] " + message);
        }
    }
}
