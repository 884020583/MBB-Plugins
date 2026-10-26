package org.moboxlab.mbb.eval.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.alibaba.fastjson.JSONObject;

import org.moboxlab.moboxbot.API.Storage.StorageService;

/**
 * L3 只读旁证的真实数据源（E-4：规格 §7.5、§11.4；验收 §15.5 E11）。
 *
 * <p>只执行一条 SELECT：按 {@code plugin_mbb_ai_usage} 的 profile 与日期区间取出逐日聚合值。
 * 表与列名沿用 MBB-AI 的统计表；SQL 文本必须含 {@code plugin_}、不得出现 {@code bot_}，
 * 且只允许 SELECT（见 {@link #sqlViolation(String)}）。本类没有任何写方法。</p>
 *
 * <p>规格 §11.4 建议「所有 SQL 只出现在 store/ 的 EvalSchemaService 与 EvalStore」，
 * 本类是按 E-4 授权的一处显式例外：它只读 MBB-AI 表、不参与评测库 DDL，且把边界校验
 * 内聚在 SQL 常量旁边，便于审查。</p>
 */
public final class EvalAiUsageStorageSource implements EvalAiUsageSource {

    /**
     * 逐日聚合查询：同一 profile、同一 statDate 下的多个 model/action 行合并。
     * 只读；排序保证输出稳定。
     */
    public static final String SQL_DAILY_USAGE = "SELECT "
            + "`statDate` AS `statDate`, `profile` AS `profile`, "
            + "SUM(`requests`) AS `requests`, SUM(`successes`) AS `successes`, "
            + "SUM(`failures`) AS `failures`, SUM(`promptTokens`) AS `promptTokens`, "
            + "SUM(`completionTokens`) AS `completionTokens`, "
            + "SUM(`totalTokens`) AS `totalTokens`, "
            + "SUM(`totalLatencyMs`) AS `totalLatencyMs`, "
            + "SUM(`cachedHits`) AS `cachedHits` "
            + "FROM `plugin_mbb_ai_usage` "
            + "WHERE `profile` = ? AND `statDate` >= ? AND `statDate` <= ? "
            + "GROUP BY `statDate`, `profile` "
            + "ORDER BY `statDate` ASC";

    private static final String[] FORBIDDEN_WRITE_KEYWORDS = {
        "insert", "update", "delete", "replace", "drop", "alter", "create",
        "truncate", "attach", "detach", "pragma", "vacuum"
    };

    private final StorageService storage;

    public EvalAiUsageStorageSource(StorageService storage) {
        if (storage == null) {
            throw new IllegalArgumentException("L3 旁证依赖存储服务，不能为空");
        }
        this.storage = storage;
    }

    /**
     * 校验一条旁证 SQL 是否满足只读边界（§11.4）。
     *
     * @param sql SQL 文本，可为 null
     * @return 空串表示通过；否则为中文违规原因
     */
    public static String sqlViolation(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return "SQL 不能为空";
        }
        String lower = sql.toLowerCase(Locale.ROOT);
        if (!lower.trim().startsWith("select")) {
            return "只读旁证只允许 SELECT：" + sql;
        }
        if (!lower.contains("plugin_")) {
            return "SQL 必须含 plugin_ 前缀：" + sql;
        }
        if (lower.contains("bot_")) {
            return "SQL 不得出现 bot_ 子串：" + sql;
        }
        for (String keyword : FORBIDDEN_WRITE_KEYWORDS) {
            if (lower.contains(keyword)) {
                return "SQL 不得包含写语句关键字 " + keyword + "：" + sql;
            }
        }
        return "";
    }

    @Override
    public List<EvalAiUsageDay> queryDailyUsage(String profile, String startDate, String endDate) {
        if (profile == null || profile.trim().isEmpty()) {
            throw new IllegalArgumentException("profile 不能为空");
        }
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("日期区间不能为空");
        }
        List<JSONObject> rows = storage.query(SQL_DAILY_USAGE, profile.trim(), startDate, endDate);
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<EvalAiUsageDay> days = new ArrayList<EvalAiUsageDay>(rows.size());
        for (JSONObject row : rows) {
            if (row == null) {
                continue;
            }
            String statDate = text(row, "statDate");
            String rowProfile = text(row, "profile");
            if (statDate == null || rowProfile == null) {
                continue;
            }
            days.add(new EvalAiUsageDay(statDate, rowProfile,
                    longValue(row, "requests"), longValue(row, "successes"),
                    longValue(row, "failures"), longValue(row, "promptTokens"),
                    longValue(row, "completionTokens"), longValue(row, "totalTokens"),
                    longValue(row, "totalLatencyMs"), longValue(row, "cachedHits")));
        }
        return days;
    }

    private static String text(JSONObject row, String key) {
        try {
            String value = row.getString(key);
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            return trimmed.isEmpty() ? null : trimmed;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long longValue(JSONObject row, String key) {
        try {
            Long value = row.getLong(key);
            return value == null ? 0L : value.longValue();
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
