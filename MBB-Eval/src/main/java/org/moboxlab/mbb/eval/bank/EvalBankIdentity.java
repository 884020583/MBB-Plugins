package org.moboxlab.mbb.eval.bank;

import java.nio.charset.StandardCharsets;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

/**
 * 题库身份（B-4）：persona 绑定字段与 bank_hash。
 *
 * <p>只从题库 JSON 读取「身份相关」的最小字段，不做完整加载校验（字段缺失、personaHash
 * 位数、summary 长度等由 B-3 的校验器负责）。设计上区分两个 hash：</p>
 * <ul>
 *   <li>{@link #bankHash()}：题库自己声明的顶层 {@code hash}，可选；缺失为空串。§5.2.4 规定
 *       声明值必须与加载器计算值一致，一致性判定归 B-3。</li>
 *   <li>{@link #computedHash()}：加载器运行期按文件原始字节（UTF-8 去 BOM）算出的
 *       sha256 前 12 位，即写进 run 快照的 bank_hash，也是 §8.2 可比性判定的唯一依据。</li>
 * </ul>
 *
 * <p>解析失败（空字节、非法 JSON、顶层不是对象）返回 null，不抛异常，交由预检层转成
 * E_BANK_JSON / E_BANK_READ。</p>
 */
public final class EvalBankIdentity {

    private final String personId;
    private final String personaHash;
    private final String bankHash;
    private final String computedHash;

    private EvalBankIdentity(String personId, String personaHash, String bankHash, String computedHash) {
        this.personId = personId;
        this.personaHash = personaHash;
        this.bankHash = bankHash;
        this.computedHash = computedHash;
    }

    /**
     * 从题库文件全部字节解析身份。
     *
     * @param raw 文件原始字节，允许带 UTF-8 BOM
     * @return 解析结果；空字节、非法 JSON、顶层不是对象时返回 null
     */
    public static EvalBankIdentity parse(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return null;
        }
        JSONObject root;
        try {
            String json = new String(EvalHashUtil.stripUtf8Bom(raw), StandardCharsets.UTF_8);
            root = JSON.parseObject(json);
        } catch (RuntimeException e) {
            return null;
        }
        if (root == null) {
            return null;
        }

        JSONObject persona = null;
        Object personaValue = root.get("persona");
        if (personaValue instanceof JSONObject) {
            persona = (JSONObject) personaValue;
        }
        String personId = persona == null ? "" : asString(persona.get("file"));
        String personaHash = persona == null ? "" : asString(persona.get("personaHash"));
        String declaredHash = asString(root.get("hash"));
        return new EvalBankIdentity(personId, personaHash, declaredHash, EvalHashUtil.bankHash(raw));
    }

    /** persona.file，即题库绑定的角色事实文件名；v1 题库未声明时为空串。 */
    public String personId() {
        return personId;
    }

    /** persona.personaHash：题库声明的角色文件 sha256 前 12 位；未声明为空串。 */
    public String personaHash() {
        return personaHash;
    }

    /** 题库声明的顶层 hash；未声明为空串。 */
    public String bankHash() {
        return bankHash;
    }

    /** 加载器按文件字节计算的 bank_hash（12 位小写十六进制），写 run 快照用这个值。 */
    public String computedHash() {
        return computedHash;
    }

    /** 题库是否自声明了 bank hash。 */
    public boolean hasDeclaredBankHash() {
        return !bankHash.isEmpty();
    }

    /** 题库是否声明了 personaHash，即是否参与 persona 绑定比对。 */
    public boolean hasPersonaHash() {
        return !personaHash.isEmpty();
    }

    private static String asString(Object value) {
        return value instanceof String ? (String) value : "";
    }
}
