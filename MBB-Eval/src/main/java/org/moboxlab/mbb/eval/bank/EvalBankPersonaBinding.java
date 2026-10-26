package org.moboxlab.mbb.eval.bank;

/**
 * 题库 persona 与运行时 capabilities 的绑定校验（B-4）。
 *
 * <p>口径来自 docs/MBB-Eval.md §5.2.1、§10.1 与 §17.1 O11：题库在 persona.personaHash 里
 * 声明角色事实文件的 sha256 前 12 位，运行时 capabilities.personaHash 是 MBB-Roleplay 对
 * 同一文件算出的值，两侧按 12 位小写十六进制比较。不一致默认拒绝跑（E_BANK_PERSONA）；
 * 配置 evalAllowPersonaMismatch=true 时降级为告警并允许试跑，结果按「不可信」标注（§8.5）。</p>
 *
 * <p>四种结果：</p>
 * <ul>
 *   <li>MATCH：题库未声明 personaHash（v1 允许省略，无可比对），或两侧 hash 一致。</li>
 *   <li>PERSONA_MISMATCH：运行时没有已加载的角色（personaId 为空），无法绑定题库声明的
 *       角色文件；题库声明了 personaHash 却没有 persona.file 时也落在这里。</li>
 *   <li>HASH_MISMATCH：两侧都能比对但值不同，说明角色文件已变更或题库来自另一份角色。</li>
 *   <li>NOT_AVAILABLE：运行时没有返回 personaHash（capabilities 不可用或读文件失败），
 *       属于「未知」而不是「不一致」，不允许被 allowPersonaMismatch 放行。</li>
 * </ul>
 *
 * <p>v1 题库没有 personaId 字段，capabilities.personaId 只是角色名（如「天童爱丽丝」），
 * 与 persona.file 不同域，因此不做文本相等比较，只用于判断运行时是否已加载角色。</p>
 */
public final class EvalBankPersonaBinding {

    /** 与 §13.1 一致：角色绑定失败统一报 E_BANK_PERSONA。 */
    public static final String CODE_PERSONA = "E_BANK_PERSONA";

    /** 绑定判定结果。 */
    public enum Result {
        /** 一致，或题库未声明 personaHash。 */
        MATCH,
        /** 运行时没有可绑定的角色。 */
        PERSONA_MISMATCH,
        /** 两侧 hash 不一致。 */
        HASH_MISMATCH,
        /** 运行时未提供 personaHash，无法判定。 */
        NOT_AVAILABLE
    }

    private final Result result;
    private final boolean allowed;
    private final boolean warning;
    private final String code;
    private final String message;
    private final String expectedHash;
    private final String actualHash;

    private EvalBankPersonaBinding(Result result, boolean allowed, boolean warning, String code,
                                   String message, String expectedHash, String actualHash) {
        this.result = result;
        this.allowed = allowed;
        this.warning = warning;
        this.code = code == null ? "" : code;
        this.message = message == null ? "" : message;
        this.expectedHash = expectedHash == null ? "" : expectedHash;
        this.actualHash = actualHash == null ? "" : actualHash;
    }

    /**
     * 校验题库 persona 绑定。
     *
     * @param identity           已解析的题库身份；null 表示题库不可用
     * @param runtimePersonaId   capabilities.personaId；未加载角色时为空串
     * @param runtimePersonaHash capabilities.personaHash；不可用时为空串
     * @param allowMismatch      配置 evalAllowPersonaMismatch，默认 false
     */
    public static EvalBankPersonaBinding bind(EvalBankIdentity identity, String runtimePersonaId,
                                              String runtimePersonaHash, boolean allowMismatch) {
        String actualId = trim(runtimePersonaId);
        String actualHash = trim(runtimePersonaHash);

        if (identity == null) {
            return notAvailable("", actualHash, "题库身份不可用（题库未成功解析），无法校验角色绑定");
        }
        if (!identity.hasPersonaHash()) {
            return new EvalBankPersonaBinding(Result.MATCH, true, false, "",
                    "题库未声明 persona.personaHash，跳过角色绑定比对", "", actualHash);
        }

        String expectedHash = identity.personaHash();
        // 运行时给不出 hash 说明 capabilities 不可用（服务缺失、快照失败或角色文件读不到），
        // 这是「未知」而不是「角色不对」，先于 personaId 判定，避免误报成 PERSONA_MISMATCH
        if (actualHash.isEmpty()) {
            return notAvailable(expectedHash, "",
                    "capabilities 未返回 personaHash，无法校验题库声明的角色绑定；请确认 MBB-Roleplay 已加载角色文件后重试");
        }
        if (!identity.personId().isEmpty() && actualId.isEmpty()) {
            return mismatch(Result.PERSONA_MISMATCH, allowMismatch, expectedHash, actualHash,
                    "运行时未加载角色（capabilities.personaId 为空），无法绑定题库声明的角色文件 "
                            + identity.personId() + "；请确认 MBB-Roleplay 已加载角色后重试");
        }
        if (expectedHash.equalsIgnoreCase(actualHash)) {
            return new EvalBankPersonaBinding(Result.MATCH, true, false, "",
                    "角色绑定一致：题库 personaHash 与运行时的 " + actualHash + " 相同", expectedHash, actualHash);
        }
        return mismatch(Result.HASH_MISMATCH, allowMismatch, expectedHash, actualHash,
                "题库声明的 personaHash 与运行时不一致（题库 " + expectedHash + " / 运行时 " + actualHash
                        + "）：题库绑定的角色文件与 MBB-Roleplay 当前加载的角色不是同一份，请更换题库或同步 personaHash 后重试");
    }

    /** 判定结果。 */
    public Result result() {
        return result;
    }

    /** 是否允许跑；false 时预检应拒绝并报 {@link #code()}。 */
    public boolean allowed() {
        return allowed;
    }

    /** true 表示按「不可信」降级放行（§8.5），需要写告警日志。 */
    public boolean warning() {
        return warning;
    }

    /** 错误码：仅不一致与未知态为 E_BANK_PERSONA，其余为空串。 */
    public String code() {
        return code;
    }

    /** 中文提示，可直接进日志或命令回复；不含 emoji。 */
    public String message() {
        return message;
    }

    /** 题库声明的 personaHash；未声明为空串。 */
    public String expectedHash() {
        return expectedHash;
    }

    /** 运行时 capabilities 提供的 personaHash；不可用为空串。 */
    public String actualHash() {
        return actualHash;
    }

    private static EvalBankPersonaBinding mismatch(Result result, boolean allowMismatch,
                                                   String expectedHash, String actualHash, String message) {
        if (!allowMismatch) {
            return new EvalBankPersonaBinding(result, false, false, CODE_PERSONA, message,
                    expectedHash, actualHash);
        }
        return new EvalBankPersonaBinding(result, true, true, CODE_PERSONA,
                message + "；evalAllowPersonaMismatch=true，已降级为告警并允许试跑（结果按不可信标注）",
                expectedHash, actualHash);
    }

    private static EvalBankPersonaBinding notAvailable(String expectedHash, String actualHash,
                                                       String message) {
        // 未知态不是「不一致」，allowPersonaMismatch 只对不一致生效，这里一律拒绝
        return new EvalBankPersonaBinding(Result.NOT_AVAILABLE, false, false, CODE_PERSONA, message,
                expectedHash, actualHash);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
