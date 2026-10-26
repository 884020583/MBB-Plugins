package org.moboxlab.mbb.eval.bank;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.parser.Feature;

import org.moboxlab.mbb.eval.store.EvalStore;

/**
 * 题库校验器（规格 §5.7，切片 B-3）。
 *
 * <p>一次性收集全部问题，按 §5.7.1 的稳定顺序排序（cases 下标数值升序、同一用例内按字段顺序），
 * 结果只回显前 5 条。校验不抛异常：不可读、编码错误、JSON 语法错误都转成 {@link EvalBankProblem}。</p>
 *
 * <p>早退口径：schemaVersion 不合法时只报 {@code E_BANK_SCHEMA_VERSION}，不进入用例级校验；
 * 其他顶层必填字段缺失或类型不符时只报顶层问题（B7）。</p>
 *
 * <p>hash 口径：按 §5.6.2 对去 BOM 的 UTF-8 原始字节计算 sha256 前 12 位小写十六进制。
 * 由于声明 hash 本身也写在同一份文件里，该定义存在自指矛盾（声明值通常无法与文件字节自洽）；
 * 本类按逐字定义计算并只在声明值不等时报 {@code E_BANK_HASH}，内置题库不自声明 hash。
 * 该取舍已在 B-3 报告中标注，待 Lead 裁定是否改为「剔除顶层 hash 字段后再计算」。</p>
 */
public final class EvalBankValidator {

    /** 单文件上限 512 KB（§5.1）。 */
    public static final int MAX_BYTES = 512 * 1024;
    /** cases 上限 40 条（§5.2）。 */
    public static final int MAX_CASES = 40;
    /** smoke 固定 12 条（§5.5.1）。 */
    public static final int SMOKE_CASES = 12;
    /** history 单题上限 40 条（§5.4.1）。 */
    public static final int MAX_HISTORY_TURNS = 40;
    /** history 单题合计字符上限 30000（§5.4.1）。 */
    public static final int MAX_HISTORY_CHARS = 30000;
    /** persona.summary 上限 400 码点（§5.2.1）。 */
    public static final int MAX_PERSONA_SUMMARY = 400;
    /** style / knowledge 必须恰好 8 条（§5.5.1）。 */
    private static final int FIXED_DIMENSION_CASES = 8;

    private static final List<String> DIMENSIONS = Collections.unmodifiableList(Arrays.asList(
            "persona", "style", "knowledge", "boundary", "memory"));
    private static final List<String> TIERS = Collections.unmodifiableList(Arrays.asList(
            EvalStore.TIER_SMOKE, EvalStore.TIER_FULL));
    private static final List<String> SOURCES = Collections.unmodifiableList(Arrays.asList(
            "handcrafted", "persona", "chat"));
    private static final List<String> SPEAKERS = Collections.unmodifiableList(Arrays.asList(
            "self", "peer", "otherRole"));
    private static final List<String> ADDRESSED_VALUES = Collections.unmodifiableList(Arrays.asList(
            "direct", "thread", "ambient"));
    private static final List<String> RELATIONSHIP_VALUES = Collections.unmodifiableList(Arrays.asList(
            "friend", "teacher", "otherRolePeer"));

    private static final List<String> TOP_LEVEL_FIELDS = Collections.unmodifiableList(Arrays.asList(
            "schemaVersion", "version", "persona", "meta", "cases", "hash"));
    private static final List<String> PERSONA_FIELDS = Collections.unmodifiableList(Arrays.asList(
            "file", "personaHash", "summary"));
    private static final List<String> CASE_FIELDS = Collections.unmodifiableList(Arrays.asList(
            "id", "dimension", "tier", "prompt", "focus", "reference", "source", "sourceNote",
            "history", "addressed", "relationship", "style", "knowledge", "memory"));
    private static final List<String> TURN_FIELDS = Collections.unmodifiableList(Arrays.asList(
            "speaker", "userName", "text"));
    private static final List<String> KNOWLEDGE_FIELDS = Collections.unmodifiableList(Arrays.asList(
            "mode", "choices", "expected", "negative", "unitAliases"));

    private static final Pattern ID_PATTERN = Pattern.compile(
            "^(persona|style|knowledge|boundary|memory)-\\d{2}$");
    private static final Pattern VERSION_PATTERN = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");
    private static final Pattern PERSONA_HASH_PATTERN = Pattern.compile("^[0-9a-f]{12}$");
    private static final Pattern FILE_NAME_PATTERN = Pattern.compile("^bank-(.+)\\.json$");

    private EvalBankValidator() {
    }

    /** 校验字节（自动识别并去掉 UTF-8 BOM），同时校验声明 hash。 */
    public static EvalBankCheckResult validate(byte[] bytes) {
        return validate(bytes, null);
    }

    /** 校验字节，附带期望文件名与 rubric 目录。 */
    public static EvalBankCheckResult validate(byte[] bytes, Options options) {
        if (bytes == null) {
            return EvalBankCheckResult.failed(new EvalBankProblem(EvalBankProblem.READ, "",
                    "题库内容为空，无法读取"));
        }
        if (bytes.length > MAX_BYTES) {
            return EvalBankCheckResult.failed(new EvalBankProblem(EvalBankProblem.SIZE, "",
                    "题库文件 " + bytes.length + " 字节，超过 512 KB（524288 字节）上限"));
        }
        byte[] body = stripBom(bytes);
        String text;
        try {
            text = decodeUtf8(body);
        } catch (CharacterCodingException e) {
            return EvalBankCheckResult.failed(new EvalBankProblem(EvalBankProblem.JSON, "",
                    "题库文件不是合法的 UTF-8 编码：" + e.getMessage()));
        }
        JSONObject bank;
        try {
            Object parsed = JSON.parseObject(text, Feature.OrderedField);
            if (!(parsed instanceof JSONObject)) {
                return EvalBankCheckResult.failed(new EvalBankProblem(EvalBankProblem.JSON, "",
                        "题库 JSON 顶层必须是对象"));
            }
            bank = (JSONObject) parsed;
        } catch (RuntimeException e) {
            return EvalBankCheckResult.failed(new EvalBankProblem(EvalBankProblem.JSON, "",
                    "题库 JSON 解析失败：" + e.getMessage()));
        }
        String actualHash = sha256Hex(body).substring(0, 12);
        return validateBank(bank, actualHash, options);
    }

    /** 校验 JSON 文本（允许以 BOM 字符开头），同时校验声明 hash。 */
    public static EvalBankCheckResult validate(String json) {
        return validate(json, null);
    }

    /** 校验 JSON 文本，附带期望文件名与 rubric 目录。 */
    public static EvalBankCheckResult validate(String json, Options options) {
        if (json == null) {
            return EvalBankCheckResult.failed(new EvalBankProblem(EvalBankProblem.READ, "",
                    "题库内容为空，无法读取"));
        }
        return validate(json.getBytes(StandardCharsets.UTF_8), options);
    }

    /** 校验已解析的 JSON 对象；拿不到原始字节，因此跳过声明 hash 校验。 */
    public static EvalBankCheckResult validate(JSONObject bank) {
        return validate(bank, null);
    }

    /** 校验已解析的 JSON 对象，附带期望文件名与 rubric 目录。 */
    public static EvalBankCheckResult validate(JSONObject bank, Options options) {
        return validateBank(bank, null, options);
    }

    /** 计算 bank_hash：去 BOM 原始字节 sha256 的前 12 位小写十六进制（§5.6.2）。 */
    public static String bankHash(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        return sha256Hex(stripBom(bytes)).substring(0, 12);
    }

    private static EvalBankCheckResult validateBank(JSONObject bank, String actualHash, Options options) {
        Options opts = options == null ? Options.defaults() : options;
        List<EvalBankProblem> problems = new ArrayList<EvalBankProblem>();
        List<String> warnings = new ArrayList<String>();
        if (bank == null) {
            problems.add(problem(EvalBankProblem.JSON, "", "题库 JSON 顶层不能为空"));
            return EvalBankCheckResult.of(problems, warnings);
        }

        Object schemaVersion = bank.get("schemaVersion");
        if (!(schemaVersion instanceof Integer) || ((Integer) schemaVersion).intValue() != 1) {
            problems.add(problem(EvalBankProblem.SCHEMA_VERSION, "schemaVersion",
                    "schemaVersion 必须为整数 1，实际为 " + describe(schemaVersion) + "；不按 v1 猜测解析"));
            return EvalBankCheckResult.of(problems, warnings);
        }

        String version = null;
        Object versionValue = bank.get("version");
        if (!(versionValue instanceof String)) {
            problems.add(problem(EvalBankProblem.FIELD, "version",
                    "缺少必填字段 version，或类型不是字符串：" + describe(versionValue)));
        } else {
            version = (String) versionValue;
            if (!VERSION_PATTERN.matcher(version).matches()) {
                problems.add(problem(EvalBankProblem.VERSION, "version",
                        "version 必须为 x.y.z 形式，实际为 " + quote(version)));
            } else if (opts.expectedFileName() != null) {
                checkFileName(version, opts.expectedFileName(), problems);
            }
        }

        Object personaValue = bank.get("persona");
        if (!(personaValue instanceof JSONObject)) {
            problems.add(problem(EvalBankProblem.FIELD, "persona",
                    "缺少必填字段 persona，或类型不是对象：" + describe(personaValue)));
        } else {
            validatePersona((JSONObject) personaValue, problems, warnings);
        }

        Object metaValue = bank.get("meta");
        if (metaValue != null) {
            if (!(metaValue instanceof JSONObject)) {
                problems.add(problem(EvalBankProblem.FIELD, "meta", "meta 必须是对象"));
            } else {
                validateMeta((JSONObject) metaValue, problems);
            }
        }

        JSONArray cases = null;
        Object casesValue = bank.get("cases");
        if (!(casesValue instanceof JSONArray)) {
            problems.add(problem(EvalBankProblem.FIELD, "cases",
                    "缺少必填字段 cases，或类型不是数组：" + describe(casesValue)));
        } else {
            cases = (JSONArray) casesValue;
        }

        Object hashValue = bank.get("hash");
        if (hashValue != null) {
            if (!(hashValue instanceof String)) {
                problems.add(problem(EvalBankProblem.FIELD, "hash", "hash 类型必须是字符串"));
            } else if (actualHash == null) {
                warnings.add("声明了 hash 但未提供原始字节，跳过声明 hash 校验");
            } else {
                String declared = ((String) hashValue).trim();
                if (!declared.equals(actualHash)) {
                    problems.add(problem(EvalBankProblem.HASH, "hash",
                            "题库声明 hash=" + declared + "，实际 hash=" + actualHash
                                    + "（按 UTF-8 去 BOM 原始字节 sha256 前 12 位计算）"));
                }
            }
        }

        for (String key : bank.keySet()) {
            if (!TOP_LEVEL_FIELDS.contains(key)) {
                warnings.add("未知字段：顶层 " + key + "（已忽略，不影响加载）");
            }
        }

        if (!problems.isEmpty()) {
            return EvalBankCheckResult.of(problems, warnings);
        }
        if (cases.isEmpty()) {
            problems.add(problem(EvalBankProblem.COVERAGE, "cases", "cases 不能为空，至少 1 条"));
            return EvalBankCheckResult.of(problems, warnings);
        }
        if (cases.size() > MAX_CASES) {
            problems.add(problem(EvalBankProblem.COVERAGE, "cases",
                    "cases 最多 40 条，当前 " + cases.size() + " 条"));
            return EvalBankCheckResult.of(problems, warnings);
        }

        Stats stats = new Stats();
        Map<String, Integer> seenIds = new HashMap<String, Integer>();
        for (int i = 0; i < cases.size(); i++) {
            String prefix = "cases[" + i + "]";
            Object value = cases.get(i);
            if (!(value instanceof JSONObject)) {
                problems.add(problem(EvalBankProblem.FIELD, prefix, "用例必须是对象"));
                continue;
            }
            validateCase(prefix, i, (JSONObject) value, seenIds, opts, problems, warnings, stats);
        }

        if (problems.isEmpty()) {
            validateCoverage(stats, problems);
        }
        return EvalBankCheckResult.of(problems, warnings);
    }

    private static void validatePersona(JSONObject persona, List<EvalBankProblem> problems,
            List<String> warnings) {
        String base = "persona";
        for (String key : persona.keySet()) {
            if (!PERSONA_FIELDS.contains(key)) {
                warnings.add("未知字段：" + base + "." + key + "（已忽略，不影响加载）");
            }
        }
        Object fileValue = persona.get("file");
        if (!(fileValue instanceof String) || isBlank((String) fileValue)) {
            problems.add(problem(EvalBankProblem.PERSONA, base + ".file",
                    "persona.file 必填，且必须是非空字符串"));
        }
        Object summaryValue = persona.get("summary");
        if (!(summaryValue instanceof String)) {
            problems.add(problem(EvalBankProblem.PERSONA, base + ".summary",
                    "persona.summary 必填，且必须是字符串"));
        } else {
            String summary = ((String) summaryValue).trim();
            int length = summary.codePointCount(0, summary.length());
            if (length < 1 || length > MAX_PERSONA_SUMMARY) {
                problems.add(problem(EvalBankProblem.PERSONA, base + ".summary",
                        "persona.summary 长度必须为 1~400 码点，实际为 " + length + " 码点"));
            }
        }
        Object hashValue = persona.get("personaHash");
        if (hashValue != null) {
            boolean valid = hashValue instanceof String
                    && PERSONA_HASH_PATTERN.matcher(((String) hashValue).trim()).matches();
            if (!valid) {
                problems.add(problem(EvalBankProblem.PERSONA, base + ".personaHash",
                        "personaHash 必须是角色文件 sha256 的前 12 位小写十六进制"));
            }
        }
    }

    private static void validateMeta(JSONObject meta, List<EvalBankProblem> problems) {
        for (String key : meta.keySet()) {
            if (!"title".equals(key) && !"description".equals(key)) {
                problems.add(problem(EvalBankProblem.FIELD, "meta." + key,
                        "meta 只允许 title 与 description 两个字段"));
            } else if (!(meta.get(key) instanceof String)) {
                problems.add(problem(EvalBankProblem.FIELD, "meta." + key,
                        "meta." + key + " 必须是字符串"));
            }
        }
    }

    private static void checkFileName(String version, String fileName,
            List<EvalBankProblem> problems) {
        Matcher matcher = FILE_NAME_PATTERN.matcher(fileName);
        if (!matcher.matches()) {
            problems.add(problem(EvalBankProblem.VERSION, "version",
                    "题库文件名 " + fileName + " 不符合 bank-<version>.json，无法与 version 比对"));
            return;
        }
        String fileVersion = matcher.group(1);
        if (!fileVersion.equals(version)) {
            problems.add(problem(EvalBankProblem.VERSION, "version",
                    "题库文件名 " + fileName + " 与 version=" + version + " 不一致"));
        }
    }

    private static void validateCase(String prefix, int index, JSONObject testCase,
            Map<String, Integer> seenIds, Options options, List<EvalBankProblem> problems,
            List<String> warnings, Stats stats) {
        for (String key : testCase.keySet()) {
            if (!CASE_FIELDS.contains(key)) {
                warnings.add("未知字段：" + prefix + "." + key + "（已忽略，不影响加载）");
            }
        }

        boolean dimensionKnown = false;
        String dimension = null;
        Object dimensionValue = testCase.get("dimension");
        if (!(dimensionValue instanceof String) || isBlank((String) dimensionValue)) {
            problems.add(problem(EvalBankProblem.FIELD, prefix + ".dimension",
                    "缺少必填字段 dimension，或类型不是非空字符串"));
        } else {
            dimension = ((String) dimensionValue).trim();
            boolean inCatalog = options.dimensionIds() == null
                    || options.dimensionIds().contains(dimension);
            if (DIMENSIONS.contains(dimension) && inCatalog) {
                dimensionKnown = true;
                increment(stats.dimensionCounts, dimension);
            } else {
                problems.add(problem(EvalBankProblem.DIMENSION, prefix + ".dimension",
                        "dimension 必须是 rubric 维度表内的五维之一，实际为 " + quote(dimension)));
            }
        }

        Object idValue = testCase.get("id");
        if (!(idValue instanceof String) || isBlank((String) idValue)) {
            problems.add(problem(EvalBankProblem.FIELD, prefix + ".id",
                    "缺少必填字段 id，或类型不是非空字符串"));
        } else {
            String id = ((String) idValue).trim();
            Matcher matcher = ID_PATTERN.matcher(id);
            if (!matcher.matches()) {
                problems.add(problem(EvalBankProblem.ID, prefix + ".id",
                        "用例 id 必须匹配 ^(persona|style|knowledge|boundary|memory)-\\d{2}$，实际为 "
                                + quote(id)));
            } else if (dimensionKnown && !matcher.group(1).equals(dimension)) {
                problems.add(problem(EvalBankProblem.ID, prefix + ".id",
                        "用例 id " + id + " 的维度前缀与 dimension=" + dimension + " 不一致"));
            }
            Integer first = seenIds.get(id);
            if (first == null) {
                seenIds.put(id, Integer.valueOf(index));
            } else {
                problems.add(problem(EvalBankProblem.ID, prefix + ".id",
                        "用例 id " + id + " 重复，首次出现在 cases[" + first + "]"));
            }
        }

        String tier = null;
        Object tierValue = testCase.get("tier");
        if (!(tierValue instanceof String) || isBlank((String) tierValue)) {
            problems.add(problem(EvalBankProblem.FIELD, prefix + ".tier",
                    "缺少必填字段 tier，或类型不是非空字符串"));
        } else {
            tier = ((String) tierValue).trim();
            if (!TIERS.contains(tier)) {
                problems.add(problem(EvalBankProblem.TIER, prefix + ".tier",
                        "tier 只能是 smoke 或 full，实际为 " + quote(tier)));
            } else if ("smoke".equals(tier)) {
                stats.smokeCount++;
                if (dimensionKnown) {
                    stats.smokeDimensions.add(dimension);
                }
            }
        }

        Object promptValue = testCase.get("prompt");
        if (!(promptValue instanceof String) || isBlank((String) promptValue)) {
            problems.add(problem(EvalBankProblem.FIELD, prefix + ".prompt",
                    "缺少必填字段 prompt，或 prompt 为空串"));
        }

        boolean focusRequired = "persona".equals(dimension) || "boundary".equals(dimension)
                || "memory".equals(dimension);
        Object focusValue = testCase.get("focus");
        if (focusValue != null && !(focusValue instanceof String)) {
            problems.add(problem(EvalBankProblem.FIELD, prefix + ".focus",
                    "focus 必须是字符串"));
        } else if (focusRequired
                && (focusValue == null || isBlank((String) focusValue))) {
            problems.add(problem(EvalBankProblem.FIELD, prefix + ".focus",
                    "dimension=" + dimension + " 的用例必须给出 focus"));
        }

        Object referenceValue = testCase.get("reference");
        if ("knowledge".equals(dimension)) {
            if (!(referenceValue instanceof String) || isBlank((String) referenceValue)) {
                problems.add(problem(EvalBankProblem.FIELD, prefix + ".reference",
                        "知识题必须给出非空 reference"));
            }
        } else if (referenceValue != null && !(referenceValue instanceof String)) {
            problems.add(problem(EvalBankProblem.FIELD, prefix + ".reference",
                    "reference 必须是字符串"));
        }

        Object sourceValue = testCase.get("source");
        if (sourceValue != null) {
            boolean valid = sourceValue instanceof String
                    && SOURCES.contains(((String) sourceValue).trim());
            if (!valid) {
                problems.add(problem(EvalBankProblem.FIELD, prefix + ".source",
                        "source 只能是 handcrafted、persona 或 chat"));
            }
        }

        Object sourceNoteValue = testCase.get("sourceNote");
        if (sourceNoteValue != null && !(sourceNoteValue instanceof String)) {
            problems.add(problem(EvalBankProblem.FIELD, prefix + ".sourceNote",
                    "sourceNote 必须是字符串"));
        }

        Object historyValue = testCase.get("history");
        int historySize = 0;
        boolean historyArray = false;
        if (historyValue != null) {
            if (!(historyValue instanceof JSONArray)) {
                problems.add(problem(EvalBankProblem.FIELD, prefix + ".history",
                        "history 必须是数组"));
            } else {
                historyArray = true;
                historySize = ((JSONArray) historyValue).size();
                validateHistory(prefix, (JSONArray) historyValue, problems, warnings);
            }
        }

        Object addressedValue = testCase.get("addressed");
        if (addressedValue != null) {
            boolean valid = addressedValue instanceof String
                    && ADDRESSED_VALUES.contains(((String) addressedValue).trim());
            if (!valid) {
                problems.add(problem(EvalBankProblem.FIELD, prefix + ".addressed",
                        "addressed 只能是 direct、thread 或 ambient"));
            }
        }

        Object relationshipValue = testCase.get("relationship");
        if (relationshipValue != null) {
            boolean valid = relationshipValue instanceof String
                    && RELATIONSHIP_VALUES.contains(((String) relationshipValue).trim());
            if (!valid) {
                problems.add(problem(EvalBankProblem.FIELD, prefix + ".relationship",
                        "relationship 只能是 friend、teacher 或 otherRolePeer"));
            }
        }

        if (!"style".equals(dimension) && testCase.containsKey("style")) {
            problems.add(problem(EvalBankProblem.FIELD, prefix + ".style",
                    "style 字段只能用于 style 维度"));
        } else if ("style".equals(dimension) && testCase.containsKey("style")) {
            validateStyle(prefix, testCase.get("style"), options, problems, warnings);
        }

        if (!"knowledge".equals(dimension)) {
            if (testCase.containsKey("knowledge")) {
                problems.add(problem(EvalBankProblem.FIELD, prefix + ".knowledge",
                        "knowledge 字段只能用于 knowledge 维度"));
            }
        } else if (!testCase.containsKey("knowledge")) {
            problems.add(problem(EvalBankProblem.FIELD, prefix + ".knowledge",
                    "知识题必须给出 knowledge 判分定义"));
        } else {
            Object knowledgeValue = testCase.get("knowledge");
            if (!(knowledgeValue instanceof JSONObject)) {
                problems.add(problem(EvalBankProblem.FIELD, prefix + ".knowledge",
                        "knowledge 必须是对象"));
            } else {
                String mode = validateKnowledge(prefix, (JSONObject) knowledgeValue, problems, warnings);
                if (mode != null && "smoke".equals(tier) && "knowledge".equals(dimension)) {
                    stats.smokeKnowledgeModes.add(mode);
                }
            }
        }

        if (!"memory".equals(dimension) && testCase.containsKey("memory")) {
            problems.add(problem(EvalBankProblem.FIELD, prefix + ".memory",
                    "memory 字段只能用于 memory 维度"));
        } else if ("memory".equals(dimension)) {
            Object memoryValue = testCase.get("memory");
            if (memoryValue == null) {
                problems.add(problem(EvalBankProblem.FIELD, prefix + ".memory",
                        "记忆题必须给出 memory 判分定义"));
            } else if (!(memoryValue instanceof JSONObject)) {
                problems.add(problem(EvalBankProblem.FIELD, prefix + ".memory",
                        "memory 必须是对象"));
            } else {
                validateMemory(prefix, (JSONObject) memoryValue,
                        historyArray && historySize > 0, historySize, problems, warnings);
            }
        }
    }

    private static void validateHistory(String prefix, JSONArray history,
            List<EvalBankProblem> problems, List<String> warnings) {
        String base = prefix + ".history";
        if (history.size() > MAX_HISTORY_TURNS) {
            problems.add(problem(EvalBankProblem.FIELD, base,
                    "history 最多 40 条，当前 " + history.size() + " 条（不得截断）"));
        }
        int chars = 0;
        for (int i = 0; i < history.size(); i++) {
            String itemPath = base + "[" + i + "]";
            Object item = history.get(i);
            if (!(item instanceof JSONObject)) {
                problems.add(problem(EvalBankProblem.FIELD, itemPath, "history 元素必须是对象"));
                continue;
            }
            JSONObject turn = (JSONObject) item;
            for (String key : turn.keySet()) {
                if (!TURN_FIELDS.contains(key)) {
                    warnings.add("未知字段：" + itemPath + "." + key + "（已忽略，不影响加载）");
                }
            }
            Object speakerValue = turn.get("speaker");
            boolean speakerValid = speakerValue instanceof String
                    && SPEAKERS.contains(((String) speakerValue).trim());
            if (!speakerValid) {
                problems.add(problem(EvalBankProblem.FIELD, itemPath + ".speaker",
                        "speaker 只能是 self、peer 或 otherRole"));
            } else {
                chars += charCount((String) speakerValue);
            }
            Object userNameValue = turn.get("userName");
            if (!(userNameValue instanceof String) || isBlank((String) userNameValue)) {
                problems.add(problem(EvalBankProblem.FIELD, itemPath + ".userName",
                        "userName 必须是非空字符串"));
            } else {
                chars += charCount((String) userNameValue);
            }
            Object textValue = turn.get("text");
            if (!(textValue instanceof String) || isBlank((String) textValue)) {
                problems.add(problem(EvalBankProblem.FIELD, itemPath + ".text",
                        "text 必须是非空字符串"));
            } else {
                chars += charCount((String) textValue);
            }
        }
        if (chars > MAX_HISTORY_CHARS) {
            problems.add(problem(EvalBankProblem.FIELD, base,
                    "history 合计 " + chars + " 字符，超过 30000 字符上限（不得截断）"));
        }
    }

    private static void validateStyle(String prefix, Object styleValue, Options options,
            List<EvalBankProblem> problems, List<String> warnings) {
        String base = prefix + ".style";
        if (!(styleValue instanceof JSONObject)) {
            problems.add(problem(EvalBankProblem.FIELD, base, "style 必须是对象"));
            return;
        }
        JSONObject style = (JSONObject) styleValue;
        for (String key : style.keySet()) {
            if (!"hard".equals(key) && !"soft".equals(key)) {
                warnings.add("未知字段：" + base + "." + key + "（已忽略，不影响加载）");
            }
        }
        Object hardValue = style.get("hard");
        if (hardValue != null) {
            if (!(hardValue instanceof JSONArray)) {
                problems.add(problem(EvalBankProblem.FIELD, base + ".hard",
                        "style.hard 必须是数组"));
            } else {
                JSONArray hard = (JSONArray) hardValue;
                Set<String> mounted = new HashSet<String>();
                for (int i = 0; i < hard.size(); i++) {
                    String path = base + ".hard[" + i + "]";
                    Object item = hard.get(i);
                    if (!(item instanceof String) || isBlank((String) item)) {
                        problems.add(problem(EvalBankProblem.STYLE_MOUNT, path,
                                "硬项 id 必须是非空字符串"));
                        continue;
                    }
                    String id = ((String) item).trim();
                    if (options.styleHardIds() != null && !options.styleHardIds().contains(id)) {
                        problems.add(problem(EvalBankProblem.STYLE_MOUNT, path,
                                "硬项 id 不在 rubric 风格目录内：" + id));
                    }
                    if (!mounted.add(id)) {
                        warnings.add("style.hard 重复挂载：" + path);
                    }
                }
            }
        }
        Object softValue = style.get("soft");
        if (softValue != null) {
            if (!(softValue instanceof JSONObject)) {
                problems.add(problem(EvalBankProblem.FIELD, base + ".soft",
                        "style.soft 必须是对象"));
            } else {
                JSONObject soft = (JSONObject) softValue;
                for (String key : soft.keySet()) {
                    String path = base + ".soft." + key;
                    if (options.styleSoftIds() != null && !options.styleSoftIds().contains(key)) {
                        problems.add(problem(EvalBankProblem.STYLE_MOUNT, path,
                                "软项 id 不在 rubric 风格目录内：" + key));
                        continue;
                    }
                    Object value = soft.get(key);
                    if (value instanceof Boolean) {
                        if ("length".equals(key)) {
                            problems.add(problem(EvalBankProblem.STYLE_MOUNT, path,
                                    "length 覆写必须是参数对象"));
                        }
                        continue;
                    }
                    if (!(value instanceof JSONObject)) {
                        problems.add(problem(EvalBankProblem.STYLE_MOUNT, path,
                                "软项值只接受 true/false 或参数对象"));
                        continue;
                    }
                    if ("length".equals(key)) {
                        validateLengthOverride(path, (JSONObject) value, problems, warnings);
                    }
                }
            }
        }
    }

    private static void validateLengthOverride(String path, JSONObject length,
            List<EvalBankProblem> problems, List<String> warnings) {
        Integer min = positiveInteger(length, "min", path, problems, warnings);
        Integer max = positiveInteger(length, "max", path, problems, warnings);
        for (String key : length.keySet()) {
            if (!"min".equals(key) && !"max".equals(key)) {
                warnings.add("未知字段：" + path + "." + key + "（已忽略，不影响加载）");
            }
        }
        if (min == null && max == null) {
            problems.add(problem(EvalBankProblem.STYLE_MOUNT, path,
                    "length 覆写至少给出 min 或 max"));
            return;
        }
        if (min != null && max != null && min.intValue() >= max.intValue()) {
            problems.add(problem(EvalBankProblem.STYLE_MOUNT, path,
                    "length 覆写要求 min < max，当前 min=" + min + " max=" + max));
        }
    }

    private static Integer positiveInteger(JSONObject object, String key, String path,
            List<EvalBankProblem> problems, List<String> warnings) {
        Object value = object.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Integer) || ((Integer) value).intValue() <= 0) {
            problems.add(problem(EvalBankProblem.STYLE_MOUNT, path + "." + key,
                    "length." + key + " 必须是正整数"));
            return null;
        }
        return (Integer) value;
    }

    private static String validateKnowledge(String prefix, JSONObject knowledge,
            List<EvalBankProblem> problems, List<String> warnings) {
        String base = prefix + ".knowledge";
        for (String key : knowledge.keySet()) {
            if (!KNOWLEDGE_FIELDS.contains(key)) {
                warnings.add("未知字段：" + base + "." + key + "（已忽略，不影响加载）");
            }
        }
        String mode = null;
        Object modeValue = knowledge.get("mode");
        if (!(modeValue instanceof String) || isBlank((String) modeValue)) {
            problems.add(problem(EvalBankProblem.KNOWLEDGE, base + ".mode",
                    "knowledge.mode 必填，取值 choice 或 points"));
        } else {
            mode = ((String) modeValue).trim();
            if (!"choice".equals(mode) && !"points".equals(mode)) {
                problems.add(problem(EvalBankProblem.KNOWLEDGE, base + ".mode",
                        "knowledge.mode 只能是 choice 或 points，实际为 " + quote(mode)));
                mode = null;
            }
        }
        boolean hasChoices = knowledge.containsKey("choices");
        boolean hasExpected = knowledge.containsKey("expected");
        boolean hasNegative = knowledge.containsKey("negative");
        boolean hasUnitAliases = knowledge.containsKey("unitAliases");
        if ("points".equals(mode)) {
            if (hasChoices) {
                problems.add(problem(EvalBankProblem.KNOWLEDGE, base + ".choices",
                        "points 模式不得声明 choices"));
            }
            validatePoints(base, knowledge.get("expected"), problems, warnings);
            if (hasNegative) {
                validateNegative(base, knowledge.get("negative"), problems, warnings);
            }
            if (hasUnitAliases) {
                validateUnitAliases(base, knowledge.get("unitAliases"), problems);
            }
        } else if ("choice".equals(mode)) {
            if (hasExpected) {
                problems.add(problem(EvalBankProblem.KNOWLEDGE, base + ".expected",
                        "choice 模式不得声明 expected"));
            }
            if (hasNegative) {
                problems.add(problem(EvalBankProblem.KNOWLEDGE, base + ".negative",
                        "choice 模式不得声明 negative"));
            }
            if (hasUnitAliases) {
                problems.add(problem(EvalBankProblem.KNOWLEDGE, base + ".unitAliases",
                        "choice 模式不得声明 unitAliases"));
            }
            validateChoices(base, knowledge.get("choices"), problems, warnings);
        }
        return mode;
    }

    private static void validateChoices(String base, Object choicesValue,
            List<EvalBankProblem> problems, List<String> warnings) {
        if (!(choicesValue instanceof JSONArray)) {
            problems.add(problem(EvalBankProblem.KNOWLEDGE, base + ".choices",
                    "choice 模式必须给出 choices 数组"));
            return;
        }
        JSONArray choices = (JSONArray) choicesValue;
        List<String> texts = new ArrayList<String>();
        int correctCount = 0;
        for (int i = 0; i < choices.size(); i++) {
            String path = base + ".choices[" + i + "]";
            Object item = choices.get(i);
            if (!(item instanceof JSONObject)) {
                problems.add(problem(EvalBankProblem.KNOWLEDGE, path, "choice 项必须是对象"));
                continue;
            }
            JSONObject choice = (JSONObject) item;
            for (String key : choice.keySet()) {
                if (!"text".equals(key) && !"correct".equals(key)) {
                    warnings.add("未知字段：" + path + "." + key + "（已忽略，不影响加载）");
                }
            }
            Object textValue = choice.get("text");
            if (!(textValue instanceof String) || isBlank((String) textValue)) {
                problems.add(problem(EvalBankProblem.KNOWLEDGE, path + ".text",
                        "选项 text 必须是非空字符串"));
            } else {
                texts.add((String) textValue);
            }
            Object correctValue = choice.get("correct");
            if (correctValue != null && !(correctValue instanceof Boolean)) {
                problems.add(problem(EvalBankProblem.KNOWLEDGE, path + ".correct",
                        "correct 必须是布尔值"));
            } else if (Boolean.TRUE.equals(correctValue)) {
                correctCount++;
            }
        }
        if (correctCount != 1) {
            problems.add(problem(EvalBankProblem.KNOWLEDGE, base + ".choices",
                    "choices 必须恰好 1 项 correct:true，当前 " + correctCount + " 项"));
        }
        int wrongCount = choices.size() - correctCount;
        if (wrongCount < 2 || wrongCount > 4) {
            problems.add(problem(EvalBankProblem.KNOWLEDGE, base + ".choices",
                    "choice 需要 2~4 个干扰项，当前 " + wrongCount + " 个"));
        }
        for (int i = 0; i < texts.size(); i++) {
            for (int j = i + 1; j < texts.size(); j++) {
                String first = texts.get(i);
                String second = texts.get(j);
                if (first.contains(second) || second.contains(first)) {
                    problems.add(problem(EvalBankProblem.KNOWLEDGE, base + ".choices",
                            "选项文案互为子串（下标 " + i + " 与 " + j
                                    + "），正确项与干扰项必须互不为子串"));
                    return;
                }
            }
        }
    }

    private static void validatePoints(String base, Object expectedValue,
            List<EvalBankProblem> problems, List<String> warnings) {
        if (!(expectedValue instanceof JSONArray)) {
            problems.add(problem(EvalBankProblem.KNOWLEDGE, base + ".expected",
                    "points 模式必须给出 expected 数组"));
            return;
        }
        JSONArray expected = (JSONArray) expectedValue;
        if (expected.isEmpty() || expected.size() > 3) {
            problems.add(problem(EvalBankProblem.KNOWLEDGE, base + ".expected",
                    "expected 必须为 1~3 条，当前 " + expected.size() + " 条"));
        }
        for (int i = 0; i < expected.size(); i++) {
            String path = base + ".expected[" + i + "]";
            Object item = expected.get(i);
            if (!(item instanceof JSONObject)) {
                problems.add(problem(EvalBankProblem.KNOWLEDGE, path, "期望点必须是对象"));
                continue;
            }
            JSONObject point = (JSONObject) item;
            for (String key : point.keySet()) {
                if (!"label".equals(key) && !"aliases".equals(key)) {
                    warnings.add("未知字段：" + path + "." + key + "（已忽略，不影响加载）");
                }
            }
            Object labelValue = point.get("label");
            if (!(labelValue instanceof String) || isBlank((String) labelValue)) {
                problems.add(problem(EvalBankProblem.KNOWLEDGE, path + ".label",
                        "期望点 label 必须是非空字符串"));
            }
            validateAliases(path + ".aliases", point.get("aliases"), false, problems);
        }
    }

    private static void validateAliases(String path, Object aliasesValue, boolean allowEmpty,
            List<EvalBankProblem> problems) {
        if (!(aliasesValue instanceof JSONArray)) {
            problems.add(problem(EvalBankProblem.KNOWLEDGE, path,
                    "aliases 必须是字符串数组"));
            return;
        }
        JSONArray aliases = (JSONArray) aliasesValue;
        if (aliases.isEmpty() && !allowEmpty) {
            problems.add(problem(EvalBankProblem.KNOWLEDGE, path, "aliases 不能为空"));
            return;
        }
        for (int i = 0; i < aliases.size(); i++) {
            Object alias = aliases.get(i);
            if (!(alias instanceof String) || isBlank((String) alias)) {
                problems.add(problem(EvalBankProblem.KNOWLEDGE, path,
                        "aliases 元素必须是非空字符串"));
                return;
            }
        }
    }

    private static void validateNegative(String base, Object negativeValue,
            List<EvalBankProblem> problems, List<String> warnings) {
        if (!(negativeValue instanceof JSONArray)) {
            problems.add(problem(EvalBankProblem.KNOWLEDGE, base + ".negative",
                    "negative 必须是数组"));
            return;
        }
        JSONArray negative = (JSONArray) negativeValue;
        for (int i = 0; i < negative.size(); i++) {
            String path = base + ".negative[" + i + "]";
            Object item = negative.get(i);
            if (!(item instanceof JSONObject)) {
                problems.add(problem(EvalBankProblem.KNOWLEDGE, path, "负向期望点必须是对象"));
                continue;
            }
            JSONObject point = (JSONObject) item;
            Object labelValue = point.get("label");
            if (!(labelValue instanceof String) || isBlank((String) labelValue)) {
                problems.add(problem(EvalBankProblem.KNOWLEDGE, path + ".label",
                        "负向期望点 label 必须是非空字符串"));
            }
            if (point.containsKey("aliases")) {
                validateAliases(path + ".aliases", point.get("aliases"), true, problems);
            }
        }
    }

    private static void validateUnitAliases(String base, Object unitAliasesValue,
            List<EvalBankProblem> problems) {
        if (!(unitAliasesValue instanceof JSONArray)) {
            problems.add(problem(EvalBankProblem.KNOWLEDGE, base + ".unitAliases",
                    "unitAliases 必须是数组"));
            return;
        }
        JSONArray unitAliases = (JSONArray) unitAliasesValue;
        for (int i = 0; i < unitAliases.size(); i++) {
            String path = base + ".unitAliases[" + i + "]";
            Object pairValue = unitAliases.get(i);
            if (!(pairValue instanceof JSONArray)) {
                problems.add(problem(EvalBankProblem.KNOWLEDGE, path, "单位等价对必须是数组"));
                continue;
            }
            JSONArray pair = (JSONArray) pairValue;
            boolean valid = pair.size() == 2;
            for (int j = 0; valid && j < pair.size(); j++) {
                Object unit = pair.get(j);
                valid = unit instanceof String && !isBlank((String) unit);
            }
            if (!valid) {
                problems.add(problem(EvalBankProblem.KNOWLEDGE, path,
                        "单位等价对必须是两个非空字符串"));
            }
        }
    }

    private static void validateMemory(String prefix, JSONObject memory, boolean historyOk,
            int historySize, List<EvalBankProblem> problems, List<String> warnings) {
        String base = prefix + ".memory";
        for (String key : memory.keySet()) {
            if (!"evidenceTurns".equals(key)) {
                warnings.add("未知字段：" + base + "." + key + "（已忽略，不影响加载）");
            }
        }
        List<Integer> turns = new ArrayList<Integer>();
        Object turnsValue = memory.get("evidenceTurns");
        if (!(turnsValue instanceof JSONArray)) {
            problems.add(problem(EvalBankProblem.MEMORY, base + ".evidenceTurns",
                    "memory.evidenceTurns 必填，且必须是整数数组"));
        } else {
            JSONArray array = (JSONArray) turnsValue;
            if (array.isEmpty() || array.size() > 3) {
                problems.add(problem(EvalBankProblem.MEMORY, base + ".evidenceTurns",
                        "memory.evidenceTurns 必须为 1~3 个，当前 " + array.size() + " 个"));
            } else {
                for (int i = 0; i < array.size(); i++) {
                    Object item = array.get(i);
                    if (!(item instanceof Integer) || ((Integer) item).intValue() < 0) {
                        problems.add(problem(EvalBankProblem.MEMORY,
                                base + ".evidenceTurns[" + i + "]",
                                "evidenceTurns 元素必须是 0 基整数"));
                        turns.clear();
                        break;
                    }
                    turns.add((Integer) item);
                }
            }
        }
        if (!historyOk) {
            problems.add(problem(EvalBankProblem.FIELD, prefix + ".history",
                    "记忆题必须同时给出非空 history"));
        } else if (!turns.isEmpty()) {
            int max = 0;
            for (Integer turn : turns) {
                max = Math.max(max, turn.intValue());
            }
            if (max >= historySize) {
                warnings.add(prefix + ".memory.evidenceTurns 最大值 " + max
                        + " 不小于 history 长度 " + historySize + "，加载通过，该题运行期判 invalid");
            }
        }
    }

    private static void validateCoverage(Stats stats, List<EvalBankProblem> problems) {
        for (String dimension : DIMENSIONS) {
            Integer count = stats.dimensionCounts.get(dimension);
            if (count == null || count.intValue() == 0) {
                problems.add(problem(EvalBankProblem.COVERAGE, "cases",
                        "五维必须齐全，当前缺少维度 " + dimension));
            }
        }
        checkFixedDimension(stats, "style", problems);
        checkFixedDimension(stats, "knowledge", problems);
        if (stats.smokeCount != SMOKE_CASES) {
            problems.add(problem(EvalBankProblem.COVERAGE, "cases",
                    "smoke 必须覆盖五维且为 12 条，当前 " + stats.smokeCount + " 条"));
            return;
        }
        for (String dimension : DIMENSIONS) {
            if (!stats.smokeDimensions.contains(dimension)) {
                problems.add(problem(EvalBankProblem.COVERAGE, "cases",
                        "smoke 必须覆盖五维且为 12 条，当前缺少维度 " + dimension));
            }
        }
        for (String mode : Arrays.asList("choice", "points")) {
            if (!stats.smokeKnowledgeModes.contains(mode)) {
                problems.add(problem(EvalBankProblem.COVERAGE, "cases",
                        "smoke 知识题必须同时覆盖 choice 与 points 两种模式，当前缺少 " + mode));
            }
        }
    }

    private static void checkFixedDimension(Stats stats, String dimension,
            List<EvalBankProblem> problems) {
        Integer count = stats.dimensionCounts.get(dimension);
        int actual = count == null ? 0 : count.intValue();
        if (actual != FIXED_DIMENSION_CASES) {
            problems.add(problem(EvalBankProblem.COVERAGE, "cases",
                    dimension + " 必须恰好 8 条，当前 " + actual + " 条"));
        }
    }

    private static void increment(Map<String, Integer> counts, String key) {
        Integer current = counts.get(key);
        counts.put(key, Integer.valueOf(current == null ? 1 : current.intValue() + 1));
    }

    private static EvalBankProblem problem(String code, String path, String message) {
        return new EvalBankProblem(code, path, message);
    }

    private static String describe(Object value) {
        if (value == null) {
            return "缺失";
        }
        if (value instanceof String) {
            return "字符串 " + quote((String) value);
        }
        return String.valueOf(value);
    }

    private static String quote(String value) {
        return "\"" + (value == null ? "" : value) + "\"";
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static int charCount(String value) {
        return value.codePointCount(0, value.length());
    }

    private static byte[] stripBom(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            byte[] body = new byte[bytes.length - 3];
            System.arraycopy(bytes, 3, body, 0, body.length);
            return body;
        }
        return bytes;
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", e);
        }
    }

    /** 校验选项：期望文件名、rubric 维度表与风格目录（缺省则跳过对应成员校验）。 */
    public static final class Options {

        private String expectedFileName;
        private List<String> dimensionIds;
        private List<String> styleHardIds;
        private List<String> styleSoftIds;

        private Options() {
        }

        /** 默认选项：不校验文件名与风格目录成员。 */
        public static Options defaults() {
            return new Options();
        }

        /** 期望的题库文件名（如 bank-1.0.0.json），用于 §5.1 文件名与 version 一致性校验。 */
        public Options expectedFileName(String value) {
            this.expectedFileName = isBlank(value) ? null : value.trim();
            return this;
        }

        /** rubric 维度表；提供时 dimension 还必须属于该表。 */
        public Options dimensionIds(Collection<String> ids) {
            this.dimensionIds = copy(ids);
            return this;
        }

        /** rubric deterministic.style 目录；提供时 hard/soft 成员必须在目录内。 */
        public Options styleCatalog(Collection<String> hardIds, Collection<String> softIds) {
            this.styleHardIds = copy(hardIds);
            this.styleSoftIds = copy(softIds);
            return this;
        }

        String expectedFileName() {
            return expectedFileName;
        }

        List<String> dimensionIds() {
            return dimensionIds;
        }

        List<String> styleHardIds() {
            return styleHardIds;
        }

        List<String> styleSoftIds() {
            return styleSoftIds;
        }

        private static List<String> copy(Collection<String> values) {
            if (values == null) {
                return null;
            }
            return Collections.unmodifiableList(new ArrayList<String>(values));
        }
    }

    private static final class Stats {
        private final Map<String, Integer> dimensionCounts = new HashMap<String, Integer>();
        private final Set<String> smokeDimensions = new HashSet<String>();
        private final Set<String> smokeKnowledgeModes = new HashSet<String>();
        private int smokeCount;
    }
}
