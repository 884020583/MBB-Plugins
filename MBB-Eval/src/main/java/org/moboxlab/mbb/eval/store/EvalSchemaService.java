package org.moboxlab.mbb.eval.store;

import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.PluginLogger;
import org.moboxlab.moboxbot.API.Storage.StorageService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 建表、索引、补列与 SQL 边界自检（规格 §4.2、§4.6、§11.4）。
 *
 * <p>列定义只有一份登记表 {@link #TABLE_COLUMNS}：建表 DDL、补列 DDL 与期望列清单全部由它派生，
 * 后续加列只需在登记表改一处，避免 §4.6 要求的多处同步漏改。</p>
 *
 * <p>幂等：没有 schema 版本号，每次插件启用都全量执行一遍 CREATE TABLE / INDEX IF NOT EXISTS。
 * 失败处理：任何 DDL（建表、补列、建索引）返回 -1 或抛异常都记 ERROR 并让 {@link #ensureSchema()}
 * 返回 false，由主类禁用评测写路径；绝不 DROP、绝不重建。补列先用 PRAGMA table_info 读现有列，
 * 判重后再 ALTER TABLE ADD COLUMN，PRAGMA 失败或返回空结果时不做盲改。</p>
 */
public final class EvalSchemaService {

    /** 评测运行表。 */
    public static final String TABLE_RUN = "plugin_mbb_eval_run";
    /** 用例结果表。 */
    public static final String TABLE_CASE_RESULT = "plugin_mbb_eval_case_result";
    /** 单次调用表。 */
    public static final String TABLE_CALL = "plugin_mbb_eval_call";
    /** 基线表。 */
    public static final String TABLE_BASELINE = "plugin_mbb_eval_baseline";

    /** 单列定义；建表与补列两种形态都由同一份字段派生。 */
    private static final class Column {
        private final String name;
        private final String type;
        private final boolean notNull;
        private final String defaultValue;
        private final boolean primaryKey;

        private Column(String name, String type, boolean notNull, String defaultValue,
                       boolean primaryKey) {
            this.name = name;
            this.type = type;
            this.notNull = notNull;
            this.defaultValue = defaultValue;
            this.primaryKey = primaryKey;
        }

        /** CREATE TABLE 中的列定义，与规格 §4.2 的列、类型、默认值一致（仅排版压成单行）。 */
        private String createDefinition() {
            StringBuilder builder = new StringBuilder(name).append(' ').append(type);
            if (primaryKey) {
                builder.append(" PRIMARY KEY");
            }
            if (notNull) {
                builder.append(" NOT NULL");
            }
            if (defaultValue != null) {
                builder.append(" DEFAULT ").append(defaultValue);
            }
            return builder.toString();
        }

        /**
         * ALTER TABLE ADD COLUMN 中的列定义。
         *
         * <p>SQLite 要求新增的 NOT NULL 列必须带常量默认值，冻结 DDL 里部分 NOT NULL 列没有 DEFAULT，
         * 迁移老库只能用 '' 补齐；新库仍以 CREATE TABLE 为准。可空列补列时不加默认值。</p>
         */
        private String alterDefinition() {
            StringBuilder builder = new StringBuilder(name).append(' ').append(type);
            if (notNull) {
                builder.append(" NOT NULL DEFAULT ").append(defaultValue == null ? "''" : defaultValue);
            } else if (defaultValue != null) {
                builder.append(" DEFAULT ").append(defaultValue);
            }
            return builder.toString();
        }
    }

    /** 四张表的有序列定义，唯一事实源。 */
    private static final Map<String, List<Column>> TABLE_COLUMNS;
    /** 各表主键列；主键无法通过 ALTER TABLE 补列。 */
    private static final Map<String, String> PRIMARY_KEY_COLUMNS;
    /** 建表语句。 */
    private static final List<String> CREATE_TABLE_SQL;
    /** 建索引语句。 */
    private static final List<String> CREATE_INDEX_SQL;
    /** 各表非主键列的补列定义。 */
    private static final Map<String, Map<String, String>> COLUMN_DEFINITIONS;

    static {
        Map<String, List<Column>> tables = new LinkedHashMap<String, List<Column>>();
        tables.put(TABLE_RUN, Arrays.asList(
                column("run_id", "TEXT", false, null, true),
                column("tier", "TEXT", true, null, false),
                column("started_at", "TEXT", true, null, false),
                column("ended_at", "TEXT", false, null, false),
                column("duration_ms", "INTEGER", false, null, false),
                column("status", "TEXT", true, null, false),
                column("bank_file", "TEXT", false, null, false),
                column("bank_version", "TEXT", false, null, false),
                column("bank_hash", "TEXT", false, null, false),
                column("persona_id", "TEXT", false, null, false),
                column("persona_version", "TEXT", false, null, false),
                column("persona_hash", "TEXT", false, null, false),
                column("rubric_version", "TEXT", false, null, false),
                column("eval_api_version", "TEXT", false, null, false),
                column("eval_plugin_version", "TEXT", false, null, false),
                column("roleplay_plugin_version", "TEXT", false, null, false),
                column("java_version", "TEXT", false, null, false),
                column("os", "TEXT", false, null, false),
                column("eval_profile", "TEXT", false, null, false),
                column("judge_profile", "TEXT", false, null, false),
                column("judge_model", "TEXT", false, null, false),
                column("generation_model", "TEXT", false, null, false),
                column("config_fingerprint", "TEXT", false, null, false),
                column("config_snapshot_json", "TEXT", false, null, false),
                column("sample_k", "INTEGER", false, "1", false),
                column("calls_used", "INTEGER", false, "0", false),
                column("calls_budget", "INTEGER", false, "100", false),
                column("calibration_json", "TEXT", false, null, false),
                column("baseline_id_at_run", "TEXT", false, null, false),
                column("report_path", "TEXT", false, null, false),
                column("error_summary", "TEXT", false, null, false)));
        tables.put(TABLE_CASE_RESULT, Arrays.asList(
                column("case_result_id", "TEXT", false, null, true),
                column("run_id", "TEXT", true, null, false),
                column("case_id", "TEXT", true, null, false),
                column("dimension", "TEXT", true, null, false),
                column("status", "TEXT", true, null, false),
                column("score", "REAL", false, null, false),
                column("max_score", "REAL", false, "100", false),
                column("label", "TEXT", false, null, false),
                column("scale", "TEXT", false, null, false),
                column("k_used", "INTEGER", false, null, false),
                column("sample_count", "INTEGER", false, null, false),
                column("cached_count", "INTEGER", false, null, false),
                column("tokens_prompt", "INTEGER", false, null, false),
                column("tokens_completion", "INTEGER", false, null, false),
                column("tokens_total", "INTEGER", false, null, false),
                column("duration_ms", "INTEGER", false, null, false),
                column("evidence", "TEXT", false, null, false),
                column("detail_json", "TEXT", false, null, false),
                column("created_at", "TEXT", false, null, false)));
        tables.put(TABLE_CALL, Arrays.asList(
                column("call_id", "TEXT", false, null, true),
                column("run_id", "TEXT", true, null, false),
                column("case_id", "TEXT", false, null, false),
                column("stage", "TEXT", true, null, false),
                column("sample_index", "INTEGER", false, null, false),
                column("attempt", "INTEGER", false, null, false),
                column("status", "TEXT", true, null, false),
                column("error_type", "TEXT", false, null, false),
                column("retryable", "INTEGER", false, null, false),
                column("cached", "INTEGER", false, null, false),
                column("profile", "TEXT", false, null, false),
                column("model", "TEXT", false, null, false),
                column("usage_prompt", "INTEGER", false, null, false),
                column("usage_completion", "INTEGER", false, null, false),
                column("usage_total", "INTEGER", false, null, false),
                column("usage_cached", "INTEGER", false, null, false),
                column("elapsed_ms", "INTEGER", false, null, false),
                column("finish_reason", "TEXT", false, null, false),
                column("response_chars", "INTEGER", false, null, false),
                column("created_at", "TEXT", false, null, false)));
        tables.put(TABLE_BASELINE, Arrays.asList(
                column("baseline_id", "TEXT", false, null, true),
                column("run_id", "TEXT", true, null, false),
                column("active", "INTEGER", true, "1", false),
                column("dimension_scores_json", "TEXT", false, null, false),
                column("total_score", "REAL", false, null, false),
                column("valid_case_count", "INTEGER", false, null, false),
                column("perf_ref_json", "TEXT", false, null, false),
                column("bank_hash", "TEXT", false, null, false),
                column("rubric_version", "TEXT", false, null, false),
                column("persona_hash", "TEXT", false, null, false),
                column("config_fingerprint", "TEXT", false, null, false),
                column("eval_profile", "TEXT", false, null, false),
                column("judge_profile", "TEXT", false, null, false),
                column("judge_model", "TEXT", false, null, false),
                column("generation_model", "TEXT", false, null, false),
                column("eval_api_version", "TEXT", false, null, false),
                column("created_at", "TEXT", false, null, false),
                column("created_by", "TEXT", false, null, false),
                column("reason", "TEXT", false, null, false),
                column("superseded_by", "TEXT", false, null, false)));
        TABLE_COLUMNS = Collections.unmodifiableMap(tables);

        Map<String, String> primaryKeys = new LinkedHashMap<String, String>();
        List<String> createTables = new ArrayList<String>();
        Map<String, Map<String, String>> definitions = new LinkedHashMap<String, Map<String, String>>();
        for (Map.Entry<String, List<Column>> table : TABLE_COLUMNS.entrySet()) {
            StringBuilder ddl = new StringBuilder("CREATE TABLE IF NOT EXISTS ")
                    .append(table.getKey()).append(" (");
            Map<String, String> alterDefinitions = new LinkedHashMap<String, String>();
            for (Column column : table.getValue()) {
                if (column.primaryKey) {
                    primaryKeys.put(table.getKey(), column.name);
                } else {
                    alterDefinitions.put(column.name, column.alterDefinition());
                }
                ddl.append(column.createDefinition()).append(',');
            }
            ddl.setLength(ddl.length() - 1);
            createTables.add(ddl.append(')').toString());
            definitions.put(table.getKey(), Collections.unmodifiableMap(alterDefinitions));
        }
        CREATE_TABLE_SQL = Collections.unmodifiableList(createTables);
        COLUMN_DEFINITIONS = Collections.unmodifiableMap(definitions);
        PRIMARY_KEY_COLUMNS = Collections.unmodifiableMap(primaryKeys);

        List<String> indexes = new ArrayList<String>();
        indexes.add("CREATE INDEX IF NOT EXISTS idx_eval_run_started ON "
                + TABLE_RUN + "(started_at)");
        indexes.add("CREATE INDEX IF NOT EXISTS idx_eval_run_status ON "
                + TABLE_RUN + "(status)");
        indexes.add("CREATE UNIQUE INDEX IF NOT EXISTS idx_eval_case_uniq ON "
                + TABLE_CASE_RESULT + "(run_id, case_id)");
        indexes.add("CREATE INDEX IF NOT EXISTS idx_eval_case_dim ON "
                + TABLE_CASE_RESULT + "(run_id, dimension)");
        indexes.add("CREATE INDEX IF NOT EXISTS idx_eval_call_run ON "
                + TABLE_CALL + "(run_id)");
        indexes.add("CREATE INDEX IF NOT EXISTS idx_eval_call_stage ON "
                + TABLE_CALL + "(run_id, stage)");
        indexes.add("CREATE UNIQUE INDEX IF NOT EXISTS idx_eval_baseline_active ON "
                + TABLE_BASELINE + "(active) WHERE active = 1");
        CREATE_INDEX_SQL = Collections.unmodifiableList(indexes);
    }

    private static Column column(String name, String type, boolean notNull,
                                 String defaultValue, boolean primaryKey) {
        return new Column(name, type, notNull, defaultValue, primaryKey);
    }

    private final StorageService storage;
    private final PluginLogger logger;
    private final List<String> warnings = new ArrayList<String>();

    public EvalSchemaService(StorageService storage, PluginLogger logger) {
        this.storage = storage;
        this.logger = logger;
        if (storage == null) {
            warn("存储服务不可用，无法建表");
        }
        for (String sql : allSql()) {
            if (!isPluginScopedSql(sql)) {
                warn("SQL 越界（必须含 plugin_ 且不得含 bot_）：" + sql);
            }
        }
    }

    /** 四张表名。 */
    public static List<String> tableNames() {
        return Collections.unmodifiableList(Arrays.asList(TABLE_RUN, TABLE_CASE_RESULT,
                TABLE_CALL, TABLE_BASELINE));
    }

    /** 建表语句（只读，供测试与自检）。 */
    public static List<String> createTableSql() {
        return CREATE_TABLE_SQL;
    }

    /** 建索引语句（只读，供测试与自检）。 */
    public static List<String> createIndexSql() {
        return CREATE_INDEX_SQL;
    }

    /** 各表非主键列的补列定义（只读，供测试与自检）。 */
    public static Map<String, Map<String, String>> expectedColumns() {
        return COLUMN_DEFINITIONS;
    }

    /** 指定表的主键列；未知表返回 {@code null}。 */
    public static String primaryKeyColumn(String table) {
        return PRIMARY_KEY_COLUMNS.get(table);
    }

    /**
     * 执行一次幂等建表、补列与建索引。
     *
     * @return true 表示四表七索引就绪；false 表示有 DDL 未成功（插件应禁用评测写路径）
     */
    public boolean ensureSchema() {
        warnings.clear();
        if (storage == null) {
            warn("存储服务不可用，跳过建表");
            return false;
        }
        for (String sql : CREATE_TABLE_SQL) {
            if (!executeDdl(sql, "建表")) {
                return false;
            }
        }
        for (String table : tableNames()) {
            if (!ensureColumns(table)) {
                return false;
            }
        }
        for (String sql : CREATE_INDEX_SQL) {
            if (!executeDdl(sql, "建索引")) {
                return false;
            }
        }
        return true;
    }

    private boolean executeDdl(String sql, String action) {
        int result;
        try {
            result = storage.update(sql);
        } catch (RuntimeException e) {
            error(action + "异常：" + e.getMessage() + "；" + sql);
            return false;
        }
        if (result == -1) {
            error(action + "失败（返回 -1）：" + sql);
            return false;
        }
        return true;
    }

    private boolean ensureColumns(String table) {
        Map<String, String> expected = COLUMN_DEFINITIONS.get(table);
        if (expected == null) {
            return true;
        }
        Set<String> existing = readColumns(table);
        if (existing == null) {
            // 读失败或表结构为空：只告警，不做盲改（§4.6）
            return true;
        }
        if (!existing.containsAll(expected.keySet())) {
            // StorageService 的 query 失败会被吞成部分行，缺列可能只是读到一半；
            // 重读一次并合并结果，仍缺的才当成真的缺列，避免对已存在列发 ALTER
            Set<String> reread = readColumns(table);
            if (reread == null) {
                warn("表结构读取不完整，跳过补列（不做盲改）：" + table);
                return true;
            }
            existing.addAll(reread);
        }
        String primaryKey = PRIMARY_KEY_COLUMNS.get(table);
        // §4.6：旧版本读新库（多余列）只记 WARN，不报错、不改表
        for (String existingColumn : existing) {
            if (!expected.containsKey(existingColumn) && !existingColumn.equals(primaryKey)) {
                warn("表 " + table + " 存在规格外列 " + existingColumn + "，只记 WARN 不报错");
            }
        }
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            if (existing.contains(entry.getKey())) {
                continue;
            }
            if (entry.getKey().equals(primaryKey)) {
                // 主键列无法用 ALTER TABLE 补，缺主键说明表已被外部破坏，只告警不盲改
                warn("表 " + table + " 缺少主键列 " + primaryKey + "，无法补列");
                continue;
            }
            warn("表 " + table + " 缺少列 " + entry.getKey() + "，尝试补列");
            String sql = "ALTER TABLE " + table + " ADD COLUMN " + entry.getValue();
            if (!executeDdl(sql, "补列")) {
                // §4.6：DDL 失败必须记 ERROR 并禁用评测写路径，不得继续写入不完整的表
                return false;
            }
        }
        return true;
    }

    /** 读 PRAGMA table_info；读失败或结果为空时返回 null，由调用方只告警。 */
    private Set<String> readColumns(String table) {
        List<JSONObject> rows;
        try {
            rows = storage.query("PRAGMA table_info(" + table + ")");
        } catch (RuntimeException e) {
            warn("读取表结构失败，跳过补列：" + table + "，" + e.getMessage());
            return null;
        }
        if (rows == null || rows.isEmpty()) {
            warn("表结构为空，跳过补列（不做盲改）：" + table);
            return null;
        }
        Set<String> existing = new LinkedHashSet<String>();
        for (JSONObject row : rows) {
            if (row == null) {
                continue;
            }
            String name = row.getString("name");
            if (name != null && !name.trim().isEmpty()) {
                existing.add(name);
            }
        }
        if (existing.isEmpty()) {
            warn("表结构缺少列名，跳过补列（不做盲改）：" + table);
            return null;
        }
        return existing;
    }

    /** 全部 SQL，用于边界自检；与真正可能执行的语句一一对应。 */
    public static List<String> allSql() {
        List<String> all = new ArrayList<String>();
        all.addAll(CREATE_TABLE_SQL);
        all.addAll(CREATE_INDEX_SQL);
        for (Map.Entry<String, Map<String, String>> table : COLUMN_DEFINITIONS.entrySet()) {
            for (Map.Entry<String, String> column : table.getValue().entrySet()) {
                all.add("ALTER TABLE " + table.getKey() + " ADD COLUMN " + column.getValue());
            }
        }
        return all;
    }

    /** 插件 SQL 边界：必须含 plugin_，且不得含 bot_。 */
    public static boolean isPluginScopedSql(String sql) {
        if (sql == null) {
            return false;
        }
        String lower = sql.toLowerCase();
        return lower.contains("plugin_") && !lower.contains("bot_");
    }

    /** 本次 ensureSchema 的告警与错误。 */
    public List<String> warnings() {
        return Collections.unmodifiableList(warnings);
    }

    private void warn(String message) {
        warnings.add(message);
        if (logger != null) {
            logger.sendWarn("[MBB-Eval] " + message);
        }
    }

    private void error(String message) {
        warnings.add(message);
        if (logger != null) {
            logger.sendError("[MBB-Eval] " + message);
        }
    }
}
