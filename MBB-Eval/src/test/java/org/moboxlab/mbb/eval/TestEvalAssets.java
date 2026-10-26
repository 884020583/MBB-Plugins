package org.moboxlab.mbb.eval;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;

import org.moboxlab.mbb.eval.judge.EvalJudgePrompt;
import org.moboxlab.mbb.eval.rubric.EvalRubricLoader;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.parser.Feature;

/**
 * B-5 测试共用夹具：内置题库字节、临时插件数据目录与合法 rubric 文件。
 *
 * <p>题库字节直接取构建产物 {@code out/build/classes/bank/bank-1.0.0.json}，
 * 与 {@code EvalBankValidatorTest} 同一口径；bank_hash 由内容现算，不写死常量。
 * rubric 夹具按 §6.2.2 逐字段构造，version 后缀用 {@link EvalRubricLoader#contentSha8(String)}
 * 现算，style 目录取题库实际挂载的键，保证 bank 与 rubric 联动校验通过。</p>
 */
public final class TestEvalAssets {

    public static final String BANK_FILE_NAME = "bank-1.0.0.json";

    private TestEvalAssets() {
    }

    /** 构建产物中的内置题库字节；找不到时直接失败，避免静默跳过。 */
    public static byte[] builtinBankBytes() throws IOException {
        File file = new File(classesDirectory(), "bank/" + BANK_FILE_NAME);
        if (!file.isFile()) {
            throw new AssertionError("题库资产不存在，请先执行 build.ps1：" + file.getAbsolutePath());
        }
        return Files.readAllBytes(file.toPath());
    }

    public static JSONObject builtinBank() throws IOException {
        return parse(new String(builtinBankBytes(), StandardCharsets.UTF_8));
    }

    /** 按 §5.1 建临时插件数据目录：<root>/MoBoxBot/plugins/MBB-Eval。 */
    public static File dataFolder(File root) {
        File dataFolder = new File(new File(new File(root, "MoBoxBot"), "plugins"), "MBB-Eval");
        if (!dataFolder.mkdirs() && !dataFolder.isDirectory()) {
            throw new AssertionError("无法创建临时插件数据目录：" + dataFolder.getAbsolutePath());
        }
        return dataFolder;
    }

    /** evals 目录：<plugins>/MBB-Roleplay/evals。 */
    public static File evalsDirectory(File dataFolder) {
        return new File(new File(dataFolder.getParentFile(), "MBB-Roleplay"), "evals");
    }

    public static File ensureEvalsDirectory(File dataFolder) {
        File evals = evalsDirectory(dataFolder);
        if (!evals.mkdirs() && !evals.isDirectory()) {
            throw new AssertionError("无法创建临时 evals 目录：" + evals.getAbsolutePath());
        }
        return evals;
    }

    public static File write(File directory, String fileName, byte[] bytes) throws IOException {
        File file = new File(directory, fileName);
        Files.write(file.toPath(), bytes);
        return file;
    }

    public static File write(File directory, String fileName, String text) throws IOException {
        return write(directory, fileName, text.getBytes(StandardCharsets.UTF_8));
    }

    /** 写一份与题库联动可过的合法 rubric，返回文件。 */
    public static File writeValidRubric(File evalsDirectory, JSONObject bank) throws IOException {
        JSONObject rubric = rubricObject(bank);
        String sha8 = EvalRubricLoader.contentSha8(rubric.toJSONString());
        String version = "1.0.0-" + sha8;
        rubric.put("version", version);
        return write(evalsDirectory, "rubric-" + version + ".json", rubric.toJSONString());
    }

    /** 按 §6.2.2 构造 rubric 对象（version 先占位，调用方回填）。 */
    public static JSONObject rubricObject(JSONObject bank) {
        JSONObject root = new JSONObject(true);
        root.put("version", "1.0.0-00000000");
        root.put("promptVersion", EvalJudgePrompt.PROMPT_VERSION);
        JSONArray dimensions = new JSONArray();
        dimensions.add(dimension("persona", 0.30, "persona 锚点", "这就是我会做的事。"));
        dimensions.add(dimension("boundary", 0.25, "boundary 锚点", "这个忙我帮不了。"));
        dimensions.add(dimension("memory", 0.20, "memory 锚点", "你上次说的那本书，我记住了。"));
        dimensions.add(dimension("knowledge", 0.15, "knowledge 锚点", "答案包含全部要点。"));
        dimensions.add(dimension("style", 0.10, "style 锚点", "短句，口语，没有列表。"));
        root.put("dimensions", dimensions);
        root.put("prechecks", new JSONObject(true));
        root.put("budget", new JSONObject(true));

        JSONObject hard = new JSONObject(true);
        JSONObject soft = new JSONObject(true);
        collectStyleIds(bank, hard, soft);
        JSONObject style = new JSONObject(true);
        style.put("hard", hard);
        style.put("soft", soft);
        JSONObject deterministic = new JSONObject(true);
        deterministic.put("style", style);
        root.put("deterministic", deterministic);
        return root;
    }

    private static JSONObject dimension(String id, double weight, String anchor, String positive) {
        JSONObject dimension = new JSONObject(true);
        dimension.put("id", id);
        dimension.put("weight", weight);
        JSONObject scale = new JSONObject(true);
        scale.put("0", anchor + "：0 档");
        scale.put("1", anchor + "：1 档");
        scale.put("2", anchor + "：2 档");
        dimension.put("scale", scale);
        JSONArray examples = new JSONArray();
        JSONObject example = new JSONObject(true);
        example.put("positive", positive);
        example.put("negative", anchor + "：反例");
        examples.add(example);
        dimension.put("examples", examples);
        return dimension;
    }

    /** 从题库用例的 style 挂载里收集 hard / soft 键，作为 rubric 确定性目录。 */
    private static void collectStyleIds(JSONObject bank, JSONObject hard, JSONObject soft) {
        if (bank == null) {
            return;
        }
        JSONArray cases = bank.getJSONArray("cases");
        if (cases == null) {
            return;
        }
        Set<String> hardIds = new LinkedHashSet<String>();
        Set<String> softIds = new LinkedHashSet<String>();
        for (int i = 0; i < cases.size(); i++) {
            JSONObject item = cases.getJSONObject(i);
            if (item == null) {
                continue;
            }
            JSONObject style = item.getJSONObject("style");
            if (style == null) {
                continue;
            }
            JSONObject hardMount = style.getJSONObject("hard");
            if (hardMount != null) {
                hardIds.addAll(hardMount.keySet());
            }
            JSONObject softMount = style.getJSONObject("soft");
            if (softMount != null) {
                softIds.addAll(softMount.keySet());
            }
        }
        for (String id : hardIds) {
            hard.put(id, new JSONObject(true));
        }
        for (String id : softIds) {
            soft.put(id, Boolean.TRUE);
        }
    }

    public static JSONObject parse(String text) {
        return JSON.parseObject(text, Feature.OrderedField);
    }

    private static File classesDirectory() {
        try {
            File testClasses = new File(TestEvalAssets.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return new File(testClasses.getParentFile(), "classes");
        } catch (Exception e) {
            throw new AssertionError("无法定位构建产物目录：" + e.getMessage(), e);
        }
    }
}
