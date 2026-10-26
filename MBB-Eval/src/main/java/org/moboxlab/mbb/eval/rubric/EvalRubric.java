package org.moboxlab.mbb.eval.rubric;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.parser.Feature;

/**
 * 判分规约（rubric）的不可变内存模型，对应 docs/MBB-Eval.md §6.2.2 字段表。
 *
 * <p>只承载加载与校验后的数据，不做判分计算；权重、锚点、正反例与确定性词表全部来自
 * rubric 文件，不得在代码里硬编码（§6.1 判定规则 3、§6.6）。实例只能由
 * {@link EvalRubricLoader} 在校验通过后创建。</p>
 */
public final class EvalRubric {

    /** §6.1 的维度封闭集合，顺序即报表与权重表的展示顺序。 */
    public static final List<String> DIMENSION_IDS = Collections.unmodifiableList(Arrays.asList(
            "persona", "boundary", "memory", "knowledge", "style"));

    private final String version;
    private final String semanticVersion;
    private final String contentHash;
    private final String promptVersion;
    private final List<Dimension> dimensions;
    private final JSONObject deterministic;
    private final JSONObject prechecks;
    private final JSONObject budget;
    private final List<String> warnings;

    EvalRubric(String version, String semanticVersion, String contentHash, String promptVersion,
            List<Dimension> dimensions, JSONObject deterministic, JSONObject prechecks, JSONObject budget,
            List<String> warnings) {
        this.version = version;
        this.semanticVersion = semanticVersion;
        this.contentHash = contentHash;
        this.promptVersion = promptVersion;
        this.dimensions = Collections.unmodifiableList(new ArrayList<Dimension>(dimensions));
        this.deterministic = copy(deterministic);
        this.prechecks = copy(prechecks);
        this.budget = copy(budget);
        this.warnings = Collections.unmodifiableList(new ArrayList<String>(warnings));
    }

    /** 完整版本号，形如 1.0.0-3f2a91cd；写入 run 快照 rubric_version（§8.2）。 */
    public String version() {
        return version;
    }

    /** 语义版本部分，形如 1.0.0。 */
    public String semanticVersion() {
        return semanticVersion;
    }

    /** 内容 sha256 前 8 位小写十六进制。 */
    public String contentHash() {
        return contentHash;
    }

    /** judge prompt 结构版本；与代码支持版本不匹配时拒绝跑（§6.2.3）。 */
    public String promptVersion() {
        return promptVersion;
    }

    /** 五条维度，顺序与文件一致。 */
    public List<Dimension> dimensions() {
        return dimensions;
    }

    /** 按 id 取维度；不存在时返回 null（校验通过后不会发生）。 */
    public Dimension dimension(String id) {
        if (id == null) {
            return null;
        }
        for (Dimension dimension : dimensions) {
            if (id.equals(dimension.id())) {
                return dimension;
            }
        }
        return null;
    }

    /** 确定性判定段（§6.4、§6.5）的只读副本，供 D-1 判分实现使用。 */
    public JSONObject deterministic() {
        return copy(deterministic);
    }

    /** 硬项 / 软项预检段（§6.3.5）的只读副本；字段级 schema 见 §17.1 O1。 */
    public JSONObject prechecks() {
        return copy(prechecks);
    }

    /** 预算段（§6.8）的只读副本；字段级 schema 见 §17.1 O1。 */
    public JSONObject budget() {
        return copy(budget);
    }

    /** 加载期告警，例如未知字段；无告警时为空列表。 */
    public List<String> warnings() {
        return warnings;
    }

    private static JSONObject copy(JSONObject source) {
        if (source == null) {
            return null;
        }
        return JSON.parseObject(JSON.toJSONString(source), Feature.OrderedField);
    }

    /** 单条维度：id、权重、三档锚点与正反例。 */
    public static final class Dimension {

        private final String id;
        private final double weight;
        private final Map<String, String> scale;
        private final List<Example> examples;

        Dimension(String id, double weight, Map<String, String> scale, List<Example> examples) {
            this.id = id;
            this.weight = weight;
            this.scale = Collections.unmodifiableMap(new LinkedHashMap<String, String>(scale));
            this.examples = Collections.unmodifiableList(new ArrayList<Example>(examples));
        }

        public String id() {
            return id;
        }

        public double weight() {
            return weight;
        }

        /** 档位锚点文本；label 仅允许 0、1、2。 */
        public String scaleAnchor(int label) {
            if (label < 0 || label > 2) {
                throw new IllegalArgumentException("档位只能是 0、1、2：" + label);
            }
            return scale.get(String.valueOf(label));
        }

        /** 三档锚点映射，key 为 "0" / "1" / "2"。 */
        public Map<String, String> scale() {
            return scale;
        }

        /** 正反例，至少一条。 */
        public List<Example> examples() {
            return examples;
        }
    }

    /** 一条正反例，均为纯文本，不得含分数（§6.2.2）。 */
    public static final class Example {

        private final String positive;
        private final String negative;

        Example(String positive, String negative) {
            this.positive = positive;
            this.negative = negative;
        }

        public String positive() {
            return positive;
        }

        public String negative() {
            return negative;
        }
    }
}
