package org.moboxlab.mbb.eval.store;

import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.PluginLogger;
import org.moboxlab.moboxbot.API.Storage.StorageService;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * run_id 生成与终态 CAS（规格 §4.3、§4.4）。
 *
 * <p>本切片只提供可被 F-2 复用的骨架：生成 run_id（本地校验格式并先查库避开已占用值），
 * 以及把 RUNNING 行原子改写为 COMPLETED / FAILED / ABORTED。行的插入、样例与报表
 * 归 F-2；终态 CAS 返回 -1 时记 WARN，由调用方下一轮重试，返回 0 按幂等成功处理。
 * 全部 SQL 必须含 plugin_ 且不得含 bot_（§11.4）。</p>
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

    private static final String SQL_RUN_ID_EXISTS =
            "SELECT run_id FROM plugin_mbb_eval_run WHERE run_id = ?";

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
                SQL_RUN_ID_EXISTS, SQL_CAS_COMPLETED, SQL_CAS_FAILED, SQL_CAS_ABORTED)));
    }

    private void warn(String message) {
        if (logger != null) {
            logger.sendWarn("[MBB-Eval] " + message);
        }
    }
}
