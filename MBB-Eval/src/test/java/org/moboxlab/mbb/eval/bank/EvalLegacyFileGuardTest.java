package org.moboxlab.mbb.eval.bank;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import org.junit.Test;

/**
 * A10 旧文件守卫：启动期只在发现 roleplay-evals.json 时 WARN 一次，
 * 不读取、不删除、不阻断启动（规格 §5.8.2）。
 */
public class EvalLegacyFileGuardTest {

    @Test
    public void existingLegacyFileShouldProduceSingleLineWarning() throws Exception {
        File evals = createTempDir();
        File legacy = new File(evals, EvalLegacyFileGuard.LEGACY_FILE_NAME);
        write(legacy, "{\"cases\":[]}".getBytes("UTF-8"));

        String warning = EvalLegacyFileGuard.startupWarning(evals);
        assertFalse("命中旧文件必须给告警", warning.isEmpty());
        assertTrue("必须说明已废弃：" + warning, warning.contains("旧文件已废弃"));
        assertTrue("必须给新文件名：" + warning, warning.contains("bank-<version>.json"));
        assertTrue("必须给绝对路径：" + warning, warning.contains(legacy.getAbsolutePath()));
        assertTrue("必须声明不删除：" + warning, warning.contains("不删除"));
        assertTrue("旧文件不得被删除", legacy.isFile());
    }

    @Test
    public void missingLegacyFileShouldStaySilent() throws Exception {
        File evals = createTempDir();
        assertEquals("", EvalLegacyFileGuard.startupWarning(evals));
        assertEquals("", EvalLegacyFileGuard.startupWarning((File) null));
    }

    private static File createTempDir() throws IOException {
        File dir = File.createTempFile("mbb-eval-legacy", ".dir");
        assertTrue(dir.delete());
        assertTrue(dir.mkdirs());
        return dir;
    }

    private static void write(File file, byte[] bytes) throws IOException {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
    }
}
