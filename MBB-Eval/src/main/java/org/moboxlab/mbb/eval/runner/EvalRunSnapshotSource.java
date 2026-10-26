package org.moboxlab.mbb.eval.runner;

import com.alibaba.fastjson.JSONObject;

/**
 * 受理快照来源（§8.2 九键）。
 *
 * <p>实现方从题库、rubric、capabilities 与配置读取当次运行的快照；返回的 JSON 键名与 run 行
 * 列名一致：{@code bank_file / bank_version / bank_hash / persona_id / persona_version /
 * persona_hash / rubric_version / config_fingerprint / eval_profile / judge_profile /
 * judge_model / generation_model / eval_api_version}。</p>
 *
 * <p>缺键不是错误：{@link EvalRunner} 会把缺失值写空串并逐键记「{键} 未取得」WARN，
 * 由报表标注数据缺失，绝不因为快照不全而拒绝受理。</p>
 */
public interface EvalRunSnapshotSource {

    /** 采集一次快照；允许返回 null（等价于全部缺失）。 */
    JSONObject snapshot();
}
