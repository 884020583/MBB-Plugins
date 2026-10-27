package org.moboxlab.mbb.eval.rubric;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.parser.Feature;

/**
 * rubric 文件加载与版本校验，对应 docs/MBB-Eval.md §6.2。
 *
 * <p>路径口径见 §5.1：由插件数据目录的父目录推出 {@code ./MoBoxBot/plugins}，
 * 再拼 {@code MBB-Roleplay/evals/rubric-<version>.json}，不新增配置键。</p>
 *
 * <p>版本口径见 §6.2.1：{@code <version>} = 语义版本 + 内容 sha256 前 8 位，
 * 文件名与内容 version 必须逐字一致。校验失败返回问题清单，不抛异常；
 * 加载顺序固定 rubric → bank（§5.7），rubric 失败时 bank 不加载。</p>
 *
 * <p>内容哈希的实现口径：version 字段自身含哈希，若把 version 计入哈希则任何文件都无法自洽，
 * 因此按「去掉顶层 version 后的 canonical JSON」计算 sha256 前 8 位（小写十六进制）。
 * canonical JSON 保留字段顺序，数字去掉无意义的尾零（0.30 与 0.3 等价），
 * 与独立工具（如 Node 的 JSON.stringify）可复算；改动锚点、权重、词表等任一内容都会改变该值。</p>
 */
public final class EvalRubricLoader {

    private static final Pattern SEMANTIC_VERSION = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");
    private static final Pattern CONTENT_HASH = Pattern.compile("^[0-9a-f]{8}$");
    private static final BigDecimal WEIGHT_SUM = new BigDecimal("1.00");
    private static final int DIMENSION_COUNT = 5;

    private static final Set<String> TOP_LEVEL_FIELDS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "version", "promptVersion", "dimensions", "prechecks", "budget", "deterministic")));
    private static final Set<String> DIMENSION_FIELDS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "id", "weight", "scale", "examples")));
    private static final Set<String> EXAMPLE_FIELDS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "positive", "negative")));

    private EvalRubricLoader() {
    }

    /** 按 §5.1 推导 rubric 文件路径，不做存在性检查。 */
    public static File resolveFile(File dataFolder, String version) {
        if (dataFolder == null) {
            throw new IllegalArgumentException("插件数据目录不能为空");
        }
        if (version == null || version.isEmpty()) {
            throw new IllegalArgumentException("rubric 版本不能为空");
        }
        File pluginsRoot = dataFolder.getParentFile();
        if (pluginsRoot == null) {
            throw new IllegalArgumentException("插件数据目录缺少父目录：" + dataFolder.getPath());
        }
        File evalsDir = new File(new File(pluginsRoot, "MBB-Roleplay"), "evals");
        return new File(evalsDir, "rubric-" + version + ".json");
    }

    /** 计算 rubric 内容 sha256 前 8 位小写十六进制；version 字段本身不参与计算。 */
    public static String contentSha8(String jsonText) {
        if (jsonText == null || jsonText.isEmpty()) {
            throw new IllegalArgumentException("rubric 文本不能为空");
        }
        JSONObject root;
        try {
            root = JSON.parseObject(stripBom(jsonText), Feature.OrderedField);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("rubric 不是合法 JSON：" + e.getMessage(), e);
        }
        if (root == null) {
            throw new IllegalArgumentException("rubric 顶层必须是 JSON 对象");
        }
        return contentSha8(root);
    }

    /** 加载并校验 rubric；{@code expectedPromptVersion} 为代码支持的 prompt 结构版本。 */
    public static EvalRubricLoadResult load(File file, String expectedPromptVersion) {
        if (expectedPromptVersion == null || expectedPromptVersion.isEmpty()) {
            throw new IllegalArgumentException("代码支持的 promptVersion 不能为空");
        }
        List<EvalRubricProblem> problems = new ArrayList<EvalRubricProblem>();
        List<String> warnings = new ArrayList<String>();

        if (file == null) {
            problems.add(new EvalRubricProblem(EvalRubricProblem.CODE_READ, "", "rubric 文件不能为空"));
            return EvalRubricLoadResult.failed(problems, warnings);
        }
        if (!file.isFile()) {
            problems.add(new EvalRubricProblem(EvalRubricProblem.CODE_READ, "",
                    "rubric 文件不存在或不是普通文件：" + file.getPath()));
            return EvalRubricLoadResult.failed(problems, warnings);
        }

        String text;
        try {
            text = decodeUtf8(Files.readAllBytes(file.toPath()));
        } catch (CharacterCodingException e) {
            problems.add(new EvalRubricProblem(EvalRubricProblem.CODE_READ, "",
                    "rubric 文件不是合法 UTF-8：" + file.getPath()));
            return EvalRubricLoadResult.failed(problems, warnings);
        } catch (IOException e) {
            problems.add(new EvalRubricProblem(EvalRubricProblem.CODE_READ, "",
                    "读取 rubric 文件失败：" + file.getPath() + "，原因：" + e.getMessage()));
            return EvalRubricLoadResult.failed(problems, warnings);
        }

        JSONObject root;
        try {
            root = JSON.parseObject(text, Feature.OrderedField);
        } catch (RuntimeException e) {
            problems.add(new EvalRubricProblem(EvalRubricProblem.CODE_JSON, "",
                    "rubric 不是合法 JSON：" + e.getMessage()));
            return EvalRubricLoadResult.failed(problems, warnings);
        }
        if (root == null) {
            problems.add(new EvalRubricProblem(EvalRubricProblem.CODE_JSON, "",
                    "rubric 顶层必须是 JSON 对象"));
            return EvalRubricLoadResult.failed(problems, warnings);
        }

        for (String key : root.keySet()) {
            if (!TOP_LEVEL_FIELDS.contains(key)) {
                warnings.add("未知字段：" + key + "，已忽略");
            }
        }

        String version = requireString(root, "version", "version", problems);
        String semanticVersion = null;
        String declaredHash = null;
        if (version != null) {
            int dash = version.lastIndexOf('-');
            if (dash < 0) {
                problems.add(new EvalRubricProblem(EvalRubricProblem.CODE_VERSION, "version",
                        "缺少内容哈希后缀，应为 \"语义版本-8位小写十六进制\"：" + version));
            } else {
                String semanticPart = version.substring(0, dash);
                String hashPart = version.substring(dash + 1);
                if (!SEMANTIC_VERSION.matcher(semanticPart).matches()) {
                    problems.add(new EvalRubricProblem(EvalRubricProblem.CODE_VERSION, "version",
                            "语义版本非法，应为 主版本.次版本.修订号：" + semanticPart));
                } else {
                    semanticVersion = semanticPart;
                }
                if (!CONTENT_HASH.matcher(hashPart).matches()) {
                    problems.add(new EvalRubricProblem(EvalRubricProblem.CODE_HASH, "version",
                            "内容哈希必须是 8 位小写十六进制：" + hashPart));
                } else {
                    declaredHash = hashPart;
                }
            }
            String expectedName = "rubric-" + version + ".json";
            if (!expectedName.equals(file.getName())) {
                problems.add(new EvalRubricProblem(EvalRubricProblem.CODE_VERSION, "version",
                        "文件名与内容 version 不一致：期望 " + expectedName + "，实际 " + file.getName()));
            }
        }

        String computedHash = contentSha8(root);
        if (declaredHash != null && !declaredHash.equals(computedHash)) {
            problems.add(new EvalRubricProblem(EvalRubricProblem.CODE_HASH, "version",
                    "内容 sha256 前 8 位为 " + computedHash + "，与 version 后缀 " + declaredHash
                            + " 不一致；内容改动后必须同步更新 version 与文件名"));
        }

        String promptVersion = requireString(root, "promptVersion", "promptVersion", problems);
        if (promptVersion != null && !promptVersion.equals(expectedPromptVersion)) {
            problems.add(new EvalRubricProblem(EvalRubricProblem.CODE_PROMPT_VERSION, "promptVersion",
                    "promptVersion 不匹配：文件为 " + promptVersion + "，代码支持 " + expectedPromptVersion
                            + "，拒绝跑"));
        }

        List<EvalRubric.Dimension> dimensions = readDimensions(root, problems, warnings);
        JSONObject deterministic = requireObject(root, "deterministic", problems);
        JSONObject prechecks = requireObject(root, "prechecks", problems);
        JSONObject budget = requireObject(root, "budget", problems);

        if (!problems.isEmpty()) {
            return EvalRubricLoadResult.failed(problems, warnings);
        }
        EvalRubric rubric = new EvalRubric(version, semanticVersion, computedHash, promptVersion,
                dimensions, deterministic, prechecks, budget, warnings);
        return EvalRubricLoadResult.ok(rubric, warnings);
    }

    private static List<EvalRubric.Dimension> readDimensions(JSONObject root,
            List<EvalRubricProblem> problems, List<String> warnings) {
        List<EvalRubric.Dimension> dimensions = new ArrayList<EvalRubric.Dimension>();
        Object raw = root.get("dimensions");
        if (!(raw instanceof JSONArray)) {
            problems.add(new EvalRubricProblem(EvalRubricProblem.CODE_FIELD, "dimensions",
                    "必须是数组"));
            return dimensions;
        }
        JSONArray array = (JSONArray) raw;
        if (array.size() != DIMENSION_COUNT) {
            problems.add(new EvalRubricProblem(EvalRubricProblem.CODE_DIMENSION, "dimensions",
                    "必须恰好 " + DIMENSION_COUNT + " 条，实际 " + array.size() + " 条"));
        }

        Set<String> seenIds = new LinkedHashSet<String>();
        BigDecimal weightSum = BigDecimal.ZERO;
        boolean weightsComplete = true;

        for (int i = 0; i < array.size(); i++) {
            String path = "dimensions[" + i + "]";
            Object item = array.get(i);
            if (!(item instanceof JSONObject)) {
                problems.add(new EvalRubricProblem(EvalRubricProblem.CODE_FIELD, path,
                        "必须是对象"));
                weightsComplete = false;
                continue;
            }
            JSONObject dimension = (JSONObject) item;
            for (String key : dimension.keySet()) {
                if (!DIMENSION_FIELDS.contains(key)) {
                    warnings.add("未知维度字段：" + path + "." + key + "，已忽略");
                }
            }

            boolean dimensionOk = true;
            String id = requireString(dimension, "id", path + ".id", problems);
            if (id == null) {
                dimensionOk = false;
            } else if (!EvalRubric.DIMENSION_IDS.contains(id)) {
                problems.add(new EvalRubricProblem(EvalRubricProblem.CODE_DIMENSION, path + ".id",
                        "维度 id 必须在五维封闭集合内 " + EvalRubric.DIMENSION_IDS + "，实际 " + id));
                dimensionOk = false;
            } else if (!seenIds.add(id)) {
                problems.add(new EvalRubricProblem(EvalRubricProblem.CODE_DIMENSION, path + ".id",
                        "维度 id 重复：" + id));
                dimensionOk = false;
            }

            Object rawWeight = dimension.get("weight");
            double weight = 0D;
            if (!(rawWeight instanceof Number)) {
                problems.add(new EvalRubricProblem(EvalRubricProblem.CODE_FIELD, path + ".weight",
                        "必须是数字"));
                dimensionOk = false;
                weightsComplete = false;
            } else {
                weight = ((Number) rawWeight).doubleValue();
                weightSum = weightSum.add(new BigDecimal(rawWeight.toString()));
            }

            Map<String, String> scale = new LinkedHashMap<String, String>();
            Object rawScale = dimension.get("scale");
            if (!(rawScale instanceof JSONObject)) {
                problems.add(new EvalRubricProblem(EvalRubricProblem.CODE_FIELD, path + ".scale",
                        "必须是对象"));
                dimensionOk = false;
            } else {
                JSONObject scaleObject = (JSONObject) rawScale;
                for (int label = 0; label <= 2; label++) {
                    String key = String.valueOf(label);
                    Object anchor = scaleObject.get(key);
                    if (!(anchor instanceof String) || ((String) anchor).trim().isEmpty()) {
                        problems.add(new EvalRubricProblem(EvalRubricProblem.CODE_FIELD,
                                path + ".scale." + key, "缺少 " + label + " 档锚点文本或不是非空字符串"));
                        dimensionOk = false;
                    } else {
                        scale.put(key, (String) anchor);
                    }
                }
            }

            List<EvalRubric.Example> examples = new ArrayList<EvalRubric.Example>();
            Object rawExamples = dimension.get("examples");
            if (!(rawExamples instanceof JSONArray) || ((JSONArray) rawExamples).isEmpty()) {
                problems.add(new EvalRubricProblem(EvalRubricProblem.CODE_FIELD,
                        path + ".examples", "必须是非空数组"));
                dimensionOk = false;
            } else {
                JSONArray exampleArray = (JSONArray) rawExamples;
                for (int j = 0; j < exampleArray.size(); j++) {
                    String examplePath = path + ".examples[" + j + "]";
                    Object rawExample = exampleArray.get(j);
                    if (!(rawExample instanceof JSONObject)) {
                        problems.add(new EvalRubricProblem(EvalRubricProblem.CODE_FIELD,
                                examplePath, "必须是对象"));
                        dimensionOk = false;
                        continue;
                    }
                    JSONObject exampleObject = (JSONObject) rawExample;
                    for (String key : exampleObject.keySet()) {
                        if (!EXAMPLE_FIELDS.contains(key)) {
                            warnings.add("未知正反例字段：" + examplePath + "." + key + "，已忽略");
                        }
                    }
                    String positive = requireString(exampleObject, "positive", examplePath + ".positive",
                            problems);
                    String negative = requireString(exampleObject, "negative", examplePath + ".negative",
                            problems);
                    if (positive == null || negative == null) {
                        dimensionOk = false;
                    } else {
                        examples.add(new EvalRubric.Example(positive, negative));
                    }
                }
            }

            if (dimensionOk) {
                dimensions.add(new EvalRubric.Dimension(id, weight, scale, examples));
            }
        }

        if (weightsComplete && weightSum.compareTo(WEIGHT_SUM) != 0) {
            problems.add(new EvalRubricProblem(EvalRubricProblem.CODE_WEIGHT, "dimensions",
                    "五维权重之和必须为 1.00，实际 " + weightSum.toPlainString()));
        }
        return dimensions;
    }

    private static String requireString(JSONObject object, String key, String path,
            List<EvalRubricProblem> problems) {
        Object value = object.get(key);
        if (!(value instanceof String)) {
            problems.add(new EvalRubricProblem(EvalRubricProblem.CODE_FIELD, path,
                    "必须是非空字符串"));
            return null;
        }
        String text = ((String) value).trim();
        if (text.isEmpty()) {
            problems.add(new EvalRubricProblem(EvalRubricProblem.CODE_FIELD, path,
                    "必须是非空字符串"));
            return null;
        }
        return text;
    }

    private static JSONObject requireObject(JSONObject root, String key,
            List<EvalRubricProblem> problems) {
        Object value = root.get(key);
        if (!(value instanceof JSONObject)) {
            problems.add(new EvalRubricProblem(EvalRubricProblem.CODE_FIELD, key,
                    "必须是对象"));
            return null;
        }
        return (JSONObject) value;
    }

    private static String contentSha8(JSONObject root) {
        JSONObject canonical = new JSONObject(true);
        for (String key : root.keySet()) {
            if (!"version".equals(key)) {
                canonical.put(key, normalizeJson(root.get(key)));
            }
        }
        String canonicalText = JSON.toJSONString(canonical);
        return sha256Hex8(canonicalText.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 归一化 JSON 值：数字去掉无意义尾零，保证 0.30 与 0.3 得到同一 canonical 形式，
     * 便于用其他语言独立复算版本哈希。
     */
    private static Object normalizeJson(Object value) {
        if (value instanceof BigDecimal) {
            BigDecimal decimal = (BigDecimal) value;
            return new BigDecimal(decimal.stripTrailingZeros().toPlainString());
        }
        if (value instanceof JSONObject) {
            JSONObject source = (JSONObject) value;
            JSONObject copy = new JSONObject(true);
            for (String key : source.keySet()) {
                copy.put(key, normalizeJson(source.get(key)));
            }
            return copy;
        }
        if (value instanceof JSONArray) {
            JSONArray source = (JSONArray) value;
            JSONArray copy = new JSONArray();
            for (int i = 0; i < source.size(); i++) {
                copy.add(normalizeJson(source.get(i)));
            }
            return copy;
        }
        return value;
    }

    private static String sha256Hex8(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                hex.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
                hex.append(Character.forDigit(digest[i] & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JRE 不支持 SHA-256", e);
        }
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return stripBom(decoder.decode(ByteBuffer.wrap(bytes)).toString());
    }

    private static String stripBom(String text) {
        if (text != null && !text.isEmpty() && text.charAt(0) == '\uFEFF') {
            return text.substring(1);
        }
        return text;
    }
}
