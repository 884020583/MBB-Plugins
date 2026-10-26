package org.moboxlab.mbb.eval.report;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 测试专用五维权重：与 rubric 的默认权重一致（persona .30 / boundary .25 / memory .20 /
 * knowledge .15 / style .10），供报表用例显式注入。
 *
 * <p>生产代码不再内置缺省权重（§6.1），因此测试也必须显式注入，才能断言总分与 delta。</p>
 */
final class TestReportWeights {

    private TestReportWeights() {
    }

    static Map<String, Double> map() {
        Map<String, Double> weights = new LinkedHashMap<String, Double>();
        weights.put(EvalReportData.DIM_PERSONA, Double.valueOf(0.30D));
        weights.put(EvalReportData.DIM_BOUNDARY, Double.valueOf(0.25D));
        weights.put(EvalReportData.DIM_MEMORY, Double.valueOf(0.20D));
        weights.put(EvalReportData.DIM_KNOWLEDGE, Double.valueOf(0.15D));
        weights.put(EvalReportData.DIM_STYLE, Double.valueOf(0.10D));
        return weights;
    }
}
