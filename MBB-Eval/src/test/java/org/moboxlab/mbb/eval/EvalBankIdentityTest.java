package org.moboxlab.mbb.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;
import org.moboxlab.mbb.eval.bank.EvalBankIdentity;
import org.moboxlab.mbb.eval.bank.EvalBankPersonaBinding;
import org.moboxlab.mbb.eval.bank.EvalHashUtil;

/**
 * 题库身份与 hash 工具（B-4：persona 绑定与 bank_hash）。
 *
 * <p>只从构建产物 {@code out/build/classes/bank/} 读取资产，不走 classpath，与
 * {@link EvalBankAssetTest} 保持同一口径。冻结 hash 取自 B-1 已合入的题库资产，
 * 若本测试失败，先确认题库资产是否被有意改动。</p>
 */
public class EvalBankIdentityTest {

    private static final String BANK_FILE_NAME = "bank-1.0.0.json";

    /** bank-1.0.0.json 全文件 sha256 前 12 位（§5.2.4 bank_hash 口径）。 */
    private static final String FROZEN_BANK_HASH = "3e664cf78c0c";

    /** persona-aris.json 全文件 sha256 前 12 位（§5.2.1 persona.personaHash 口径）。 */
    private static final String FROZEN_PERSONA_HASH = "719b9b10ea99";

    @Test
    public void sha256HexShouldMatchKnownVectors() {
        // 独立来源：FIPS 180-2 附录 B/C 的公开测试向量
        assertEquals("空输入的 sha256 应为 e3b0c442…",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                EvalHashUtil.sha256Hex(new byte[0]));
        assertEquals("abc 的 sha256 应为 ba7816bf…",
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                EvalHashUtil.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)));
        assertEquals("空输入与 null 都应返回空串", "", EvalHashUtil.sha256Hex(null));
    }

    @Test
    public void firstNShouldTruncateHexForComparison() {
        String hex = "3e664cf78c0ca886a50126cebff1e458ccca264ea9bcde141c72bfb716ba5eb9";
        assertEquals("前 12 位应与 §5.2.4 口径一致", "3e664cf78c0c", EvalHashUtil.firstN(hex, 12));
        assertEquals("位数不足时原样返回", "3e66", EvalHashUtil.firstN("3e66", 12));
        assertEquals("长度为 0 时返回空串", "", EvalHashUtil.firstN(hex, 0));
        assertEquals("null 输入返回空串", "", EvalHashUtil.firstN(null, 12));
    }

    @Test
    public void bankHashShouldIgnoreUtf8Bom() {
        byte[] body = "{\"schemaVersion\":1}".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);

        assertEquals("BOM 与无 BOM 的 bank_hash 必须同值", EvalHashUtil.bankHash(body),
                EvalHashUtil.bankHash(withBom));
        assertEquals("bank_hash 必须是 12 位", 12, EvalHashUtil.bankHash(body).length());
        assertTrue("bank_hash 必须是 12 位小写十六进制：" + EvalHashUtil.bankHash(body),
                EvalHashUtil.bankHash(body).matches("^[0-9a-f]{12}$"));
        assertFalse("原始字节的 sha256 仍应包含 BOM，用于证明剥离生效",
                EvalHashUtil.sha256Hex(body).equals(EvalHashUtil.sha256Hex(withBom)));
    }

    @Test
    public void bankHashShouldChangeWhenSingleByteChanges() throws Exception {
        byte[] original = Files.readAllBytes(bankFile().toPath());
        byte[] modified = original.clone();
        int index = indexOf(modified, "persona-01".getBytes(StandardCharsets.UTF_8));
        assertTrue("资产中应能找到 persona-01", index >= 0);
        modified[index + "persona".length()] = (byte) 'X';

        String before = EvalHashUtil.bankHash(original);
        String after = EvalHashUtil.bankHash(modified);
        assertFalse("改一个字节后 bank_hash 必须变化：修改前 " + before, before.equals(after));
        assertEquals("bank_hash 长度不应随内容变化", 12, after.length());
    }

    @Test
    public void identityShouldExposeFrozenAssetFields() throws Exception {
        EvalBankIdentity identity = EvalBankIdentity.parse(Files.readAllBytes(bankFile().toPath()));
        assertNotNull("合法题库应能解析身份", identity);
        assertEquals("personId 取 persona.file", "persona-aris.json", identity.personId());
        assertEquals("personaHash 取 persona.personaHash", FROZEN_PERSONA_HASH, identity.personaHash());
        assertEquals("bank-1.0.0.json 未声明顶层 hash，bankHash 应为空串", "", identity.bankHash());
        assertEquals("computedHash 必须等于文件字节 sha256 前 12 位", FROZEN_BANK_HASH,
                identity.computedHash());
    }

    @Test
    public void identityShouldAcceptDeclaredHashAndIgnoreBom() throws Exception {
        String body = "{\"schemaVersion\":1,\"version\":\"1.0.0\",\"hash\":\"3e664cf78c0c\","
                + "\"persona\":{\"file\":\"persona-aris.json\",\"personaHash\":\"719b9b10ea99\"},\"cases\":[]}";
        byte[] plain = body.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[plain.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(plain, 0, withBom, 3, plain.length);

        EvalBankIdentity noBom = EvalBankIdentity.parse(plain);
        EvalBankIdentity bom = EvalBankIdentity.parse(withBom);
        assertNotNull("无 BOM 应能解析", noBom);
        assertNotNull("带 BOM 应能解析", bom);
        assertEquals("声明 hash 应原样暴露", "3e664cf78c0c", noBom.bankHash());
        assertEquals("BOM 与无 BOM 的 computedHash 必须同值", noBom.computedHash(), bom.computedHash());
        assertEquals("BOM 与无 BOM 的 personaHash 必须同值", noBom.personaHash(), bom.personaHash());
        assertTrue("computedHash 应为 12 位小写十六进制：" + bom.computedHash(),
                bom.computedHash().matches("^[0-9a-f]{12}$"));
    }

    @Test
    public void identityShouldTolerateMissingPersonaAndBadJson() {
        EvalBankIdentity bare = EvalBankIdentity.parse("{\"schemaVersion\":1}".getBytes(StandardCharsets.UTF_8));
        assertNotNull("缺少 persona 仍应视为可解析的身份", bare);
        assertEquals("缺 persona 时 personId 为空串", "", bare.personId());
        assertEquals("缺 persona 时 personaHash 为空串", "", bare.personaHash());
        assertEquals("缺 hash 时 bankHash 为空串", "", bare.bankHash());

        assertNull("空字节应返回 null", EvalBankIdentity.parse(new byte[0]));
        assertNull("null 输入应返回 null", EvalBankIdentity.parse(null));
        assertNull("非法 JSON 应返回 null", EvalBankIdentity.parse("not-json".getBytes(StandardCharsets.UTF_8)));
        assertNull("顶层不是对象应返回 null", EvalBankIdentity.parse("[]".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void computedHashShouldChangeWhenSingleByteChanges() throws Exception {
        byte[] original = Files.readAllBytes(bankFile().toPath());
        byte[] modified = original.clone();
        int index = indexOf(modified, "persona-01".getBytes(StandardCharsets.UTF_8));
        assertTrue("资产中应能找到 persona-01", index >= 0);
        modified[index + "persona".length()] = (byte) 'X';

        EvalBankIdentity before = EvalBankIdentity.parse(original);
        EvalBankIdentity after = EvalBankIdentity.parse(modified);
        assertNotNull(before);
        assertNotNull(after);
        assertFalse("改一个字节后 computedHash 必须变化", before.computedHash().equals(after.computedHash()));
    }

    @Test
    public void bankAssetShouldMatchFrozenHashes() throws Exception {
        byte[] bankBytes = Files.readAllBytes(bankFile().toPath());
        assertEquals("bank-1.0.0.json 的冻结 bank_hash 变了，请确认题库资产是否被有意修改",
                FROZEN_BANK_HASH, EvalHashUtil.bankHash(bankBytes));

        byte[] personaBytes = Files.readAllBytes(personaFile().toPath());
        assertEquals("persona-aris.json 的冻结 persona_hash 变了",
                FROZEN_PERSONA_HASH, EvalHashUtil.firstN(EvalHashUtil.sha256Hex(personaBytes), 12));
    }

    @Test
    public void bindingShouldMatchWhenHashesAgree() throws Exception {
        EvalBankIdentity identity = EvalBankIdentity.parse(Files.readAllBytes(bankFile().toPath()));
        EvalBankPersonaBinding binding = EvalBankPersonaBinding.bind(
                identity, "天童爱丽丝", FROZEN_PERSONA_HASH, false);
        assertEquals("同一份角色文件应判定为 MATCH", EvalBankPersonaBinding.Result.MATCH, binding.result());
        assertTrue("MATCH 必须放行", binding.allowed());
        assertFalse("MATCH 不应带告警", binding.warning());
        assertEquals("MATCH 不应带错误码", "", binding.code());
        assertTrue("MATCH 的提示应说明绑定一致：" + binding.message(), binding.message().contains("一致"));
    }

    @Test
    public void bindingShouldRejectHashMismatchByDefault() throws Exception {
        EvalBankIdentity identity = EvalBankIdentity.parse(Files.readAllBytes(bankFile().toPath()));
        EvalBankPersonaBinding binding = EvalBankPersonaBinding.bind(
                identity, "天童爱丽丝", "000000000000", false);
        assertEquals("角色文件已变应判定为 HASH_MISMATCH", EvalBankPersonaBinding.Result.HASH_MISMATCH,
                binding.result());
        assertFalse("默认应拒绝跑", binding.allowed());
        assertFalse("拒绝时不算降级告警", binding.warning());
        assertEquals("不一致应报 E_BANK_PERSONA", "E_BANK_PERSONA", binding.code());
        assertTrue("提示应同时给出两侧 hash：" + binding.message(),
                binding.message().contains(FROZEN_PERSONA_HASH) && binding.message().contains("000000000000"));
        assertTrue("提示应为中文且不含 emoji：" + binding.message(), binding.message().contains("不一致"));
    }

    @Test
    public void allowPersonaMismatchShouldDowngradeToWarning() throws Exception {
        EvalBankIdentity identity = EvalBankIdentity.parse(Files.readAllBytes(bankFile().toPath()));
        EvalBankPersonaBinding binding = EvalBankPersonaBinding.bind(
                identity, "天童爱丽丝", "000000000000", true);
        assertEquals("降级不改变判定结果", EvalBankPersonaBinding.Result.HASH_MISMATCH, binding.result());
        assertTrue("evalAllowPersonaMismatch=true 应放行试跑", binding.allowed());
        assertTrue("放行但必须标记为告警", binding.warning());
        assertEquals("告警仍带 E_BANK_PERSONA 供日志定位", "E_BANK_PERSONA", binding.code());
        assertTrue("提示应说明降级与不可信标注：" + binding.message(),
                binding.message().contains("已降级为告警") && binding.message().contains("不可信"));
    }

    @Test
    public void bindingShouldRejectWhenRuntimePersonaMissing() throws Exception {
        EvalBankIdentity identity = EvalBankIdentity.parse(Files.readAllBytes(bankFile().toPath()));
        EvalBankPersonaBinding rejected = EvalBankPersonaBinding.bind(
                identity, "", FROZEN_PERSONA_HASH, false);
        assertEquals("运行时未加载角色应判定为 PERSONA_MISMATCH",
                EvalBankPersonaBinding.Result.PERSONA_MISMATCH, rejected.result());
        assertFalse("默认应拒绝跑", rejected.allowed());
        assertEquals("角色缺失应报 E_BANK_PERSONA", "E_BANK_PERSONA", rejected.code());
        assertTrue("提示应给出题库声明的角色文件：" + rejected.message(),
                rejected.message().contains("persona-aris.json"));

        EvalBankPersonaBinding downgraded = EvalBankPersonaBinding.bind(
                identity, "", FROZEN_PERSONA_HASH, true);
        assertTrue("allowPersonaMismatch=true 时角色缺失也降级放行", downgraded.allowed());
        assertTrue("降级时必须带告警", downgraded.warning());
    }

    @Test
    public void bindingShouldNotGuessWhenRuntimeHashUnavailable() throws Exception {
        EvalBankIdentity identity = EvalBankIdentity.parse(Files.readAllBytes(bankFile().toPath()));
        EvalBankPersonaBinding binding = EvalBankPersonaBinding.bind(identity, "", "", true);
        assertEquals("capabilities 未返回 personaHash 应判定为 NOT_AVAILABLE",
                EvalBankPersonaBinding.Result.NOT_AVAILABLE, binding.result());
        assertFalse("未知状态不得被 allowPersonaMismatch 放行", binding.allowed());
        assertTrue("提示应说明无法校验：" + binding.message(), binding.message().contains("无法校验"));

        EvalBankPersonaBinding nullIdentity = EvalBankPersonaBinding.bind(null, "天童爱丽丝",
                FROZEN_PERSONA_HASH, true);
        assertEquals("题库身份不可用同样是 NOT_AVAILABLE", EvalBankPersonaBinding.Result.NOT_AVAILABLE,
                nullIdentity.result());
        assertFalse("题库身份不可用不得放行", nullIdentity.allowed());
    }

    @Test
    public void bindingShouldSkipComparisonWhenBankDeclaresNoPersonaHash() throws Exception {
        byte[] noPersonaHash = ("{\"schemaVersion\":1,\"persona\":{\"file\":\"persona-aris.json\"}}")
                .getBytes(StandardCharsets.UTF_8);
        EvalBankIdentity identity = EvalBankIdentity.parse(noPersonaHash);
        assertNotNull(identity);
        EvalBankPersonaBinding binding = EvalBankPersonaBinding.bind(identity, "天童爱丽丝", "", false);
        assertEquals("题库未声明 personaHash 时无可比对，按 MATCH 放行",
                EvalBankPersonaBinding.Result.MATCH, binding.result());
        assertTrue("未声明时放行", binding.allowed());
        assertFalse("未声明时不算告警", binding.warning());
        assertEquals("未声明时无错误码", "", binding.code());
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static File bankFile() throws Exception {
        File file = new File(locateClassesDir(), "bank/" + BANK_FILE_NAME);
        assertTrue("题库资产不存在，请先执行 build.ps1：" + file.getAbsolutePath(), file.isFile());
        return file;
    }

    private static File personaFile() throws Exception {
        File repoRoot = locateClassesDir().getParentFile().getParentFile().getParentFile().getParentFile();
        File file = new File(repoRoot, "MBB-Roleplay/src/main/resources/persona-aris.json").getCanonicalFile();
        assertTrue("找不到角色事实文件：" + file.getAbsolutePath(), file.isFile());
        return file;
    }

    /** 构建产物 classes 目录：test-classes 的兄弟目录 classes（out/build/classes）。 */
    private static File locateClassesDir() throws Exception {
        File testClasses = new File(EvalBankIdentityTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        return new File(testClasses.getParentFile(), "classes");
    }
}