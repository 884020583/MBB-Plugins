package org.moboxlab.mbb.eval.bank;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 题库 hash 工具（B-4）。
 *
 * <p>口径来自 docs/MBB-Eval.md §5.2.4 / §5.2.1：{@code bank_hash} 与 {@code persona_hash}
 * 都是「文件原始字节的 sha256，取前 12 位小写十六进制」。题库文件允许带 UTF-8 BOM，
 * 计算前先剥掉，保证 BOM 与无 BOM 得到同一个 bank_hash。</p>
 *
 * <p>本类只做纯计算，不读文件、不校验 JSON；加载器负责读字节并调用这里。这样
 * persona 绑定（{@link EvalBankPersonaBinding}）与将来的 run 快照写入共用同一套口径，
 * 也避免和 MBB-Roleplay 的 gateway 实现各写一份。</p>
 */
public final class EvalHashUtil {

    /** bank_hash 与 persona_hash 的固定长度：§4.3、§8.2 九键均按 12 位比较。 */
    public static final int HASH_LENGTH = 12;

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private EvalHashUtil() {
    }

    /** 全量 sha256 小写十六进制；输入为 null 时返回空串，便于调用方判空。 */
    public static String sha256Hex(byte[] bytes) {
        if (bytes == null) {
            return "";
        }
        byte[] hash;
        try {
            hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            // Java 8 规范要求实现必须提供 SHA-256，走到这里说明运行环境异常
            return "";
        }
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            hex.append(Character.forDigit((value >> 4) & 0x0F, 16));
            hex.append(Character.forDigit(value & 0x0F, 16));
        }
        return hex.toString();
    }

    /** 取十六进制串前 n 位；null 或 n<=0 返回空串，串本身更短时原样返回。 */
    public static String firstN(String hex, int length) {
        if (hex == null || length <= 0) {
            return "";
        }
        return hex.length() <= length ? hex : hex.substring(0, length);
    }

    /** 剥掉 UTF-8 BOM；无 BOM 时原样返回同一个数组（不复制）。 */
    public static byte[] stripUtf8Bom(byte[] bytes) {
        if (bytes == null || bytes.length < UTF8_BOM.length) {
            return bytes;
        }
        for (int i = 0; i < UTF8_BOM.length; i++) {
            if (bytes[i] != UTF8_BOM[i]) {
                return bytes;
            }
        }
        byte[] body = new byte[bytes.length - UTF8_BOM.length];
        System.arraycopy(bytes, UTF8_BOM.length, body, 0, body.length);
        return body;
    }

    /**
     * bank_hash：原始字节去 BOM 后的 sha256 前 12 位小写十六进制（§5.2.4）。
     *
     * @param raw 题库文件全部字节；null 表示文件不可读，返回空串
     */
    public static String bankHash(byte[] raw) {
        if (raw == null) {
            return "";
        }
        return firstN(sha256Hex(stripUtf8Bom(raw)), HASH_LENGTH);
    }

    /** persona_hash：角色文件原始字节的 sha256 前 12 位；不剥 BOM，与 Roleplay gateway 保持一致。 */
    public static String personaHash(byte[] raw) {
        if (raw == null) {
            return "";
        }
        return firstN(sha256Hex(raw), HASH_LENGTH);
    }
}
