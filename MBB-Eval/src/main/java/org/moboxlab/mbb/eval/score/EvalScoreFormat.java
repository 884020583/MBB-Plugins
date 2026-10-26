package org.moboxlab.mbb.eval.score;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 分数展示口径（D-4：规格 §6.1、§7.1、§8.3、§8.6）。
 *
 * <p>维度分与总分内部始终用未取整的 double 计算与比较；本类只负责报表与摘要的展示格式，
 * 取整结果不得回流到下一次计算（§6.1 判定规则 4）。统一口径：效果分保留一位小数，
 * 比例类保留两位小数；一律 HALF_UP，避免 double 的银行家舍入差异。</p>
 */
public final class EvalScoreFormat {

    private EvalScoreFormat() {
    }

    /** 一位小数展示，如 33.35 → "33.4"、100 → "100.0"。 */
    public static String oneDecimal(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    /** 两位小数展示，如 0.125 → "0.13"、0.104 → "0.10"。 */
    public static String twoDecimals(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** 一位小数取整值；仅供展示前转换，不得参与后续分数计算。 */
    public static double roundOne(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    /** 两位小数取整值；仅供展示前转换，不得参与后续分数计算。 */
    public static double roundTwo(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /** 比例按百分比展示，保留一位小数，如 0.125 → "12.5%"。 */
    public static String percentOneDecimal(double ratio) {
        return oneDecimal(ratio * 100.0d) + "%";
    }
}
