package org.moboxlab.mbb.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.moboxlab.mbb.eval.bank.EvalBankLocation;
import org.moboxlab.mbb.eval.bank.EvalBankLocator;
import org.moboxlab.mbb.eval.bank.EvalBankReleaseResult;
import org.moboxlab.mbb.eval.bank.EvalBankReleaser;
import org.moboxlab.mbb.eval.bank.EvalBankResources;
import org.moboxlab.moboxbot.API.PluginLogger;

/**
 * 题库定位与内置题库释放用例（B-2）。
 *
 * <p>覆盖 docs/MBB-Eval.md §5.1 路径口径、§5.8.1 释放策略与 §5.8.2 降级表，
 * 以及 §15.2 验收 B1（首次释放）、B3（内置版本只增不删）、B4（自定义题库缺失不创建空文件）、
 * B23（evals 目录缺失报 E_ROLEPLAY_DATA_NOT_FOUND）、B24（目标不可写时报 E_BANK_READ 且不留半写文件）。
 * 夹具用 java.nio.file 临时目录伪造 ./MoBoxBot/plugins，不触碰真实运行目录。</p>
 */
public class EvalBankLocatorTest {

    private File tempDir;
    private File pluginsRoot;
    private File dataFolder;
    private File evalsDir;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("mbb-eval-bank").toFile();
        pluginsRoot = new File(tempDir, "MoBoxBot/plugins");
        dataFolder = new File(pluginsRoot, "MBB-Eval");
        assertTrue(dataFolder.mkdirs());
        evalsDir = new File(pluginsRoot, "MBB-Roleplay/evals");
    }

    @After
    public void tearDown() {
        deleteRecursively(tempDir);
    }

    @Test
    public void emptyConfigShouldResolveToBuiltinBankUnderRoleplayEvals() {
        EvalBankLocation location = EvalBankLocator.locate(dataFolder, "");

        assertTrue(location.valid());
        assertEquals("", location.invalidReason());
        assertTrue(location.fromBuiltin());
        assertTrue(location.configBlank());
        assertEquals("bank-1.0.0.json", location.bankFileName());
        assertEquals(new File(evalsDir, "bank-1.0.0.json").getAbsoluteFile(), location.bankFile().getAbsoluteFile());
        assertEquals(new File(pluginsRoot, "MBB-Roleplay/evals").getAbsoluteFile(),
                location.evalsDirectory().getAbsoluteFile());
        assertFalse(location.exists());
        assertFalse(location.dataDirectoryUsable());
        assertEquals(EvalBankLocation.DirectoryStatus.EVALS_MISSING, location.directoryStatus());
        assertEquals("bank-1.0.0.json", location.pinTargetFileName());
        assertTrue(location.warnings().isEmpty());
        // 定位只做推导，不产生任何目录副作用。
        assertFalse(evalsDir.exists());
    }

    @Test
    public void builtinVersionShouldDriveDefaultFileName() {
        EvalBankLocation location = EvalBankLocator.locate(dataFolder, "  ", "1.0.1");

        assertTrue(location.fromBuiltin());
        assertEquals("bank-1.0.1.json", location.bankFileName());
        assertEquals("bank/bank-1.0.1.json", EvalBankLocator.builtinBankResourcePath(location.bankFileName()));
    }

    @Test
    public void missingPluginsRootShouldBeReportedAsDataDirectoryMissing() {
        File absentDataFolder = new File(tempDir, "nowhere/MBB-Eval");
        EvalBankLocation location = EvalBankLocator.locate(absentDataFolder, "");

        assertFalse(location.dataDirectoryUsable());
        assertEquals(EvalBankLocation.DirectoryStatus.PLUGINS_ROOT_MISSING, location.directoryStatus());
    }

    @Test
    public void explicitRelativeFileShouldResolveUnderEvalsDir() throws IOException {
        assertTrue(evalsDir.mkdirs());
        File custom = new File(evalsDir, "bank-custom.json");
        write(custom, "{\"version\":\"9.9.9\"}");

        EvalBankLocation location = EvalBankLocator.locate(dataFolder, "bank-custom.json");

        assertTrue(location.valid());
        assertFalse(location.fromBuiltin());
        assertFalse(location.configBlank());
        assertTrue(location.exists());
        assertTrue(location.dataDirectoryUsable());
        assertEquals(custom.getAbsoluteFile(), location.bankFile().getAbsoluteFile());
        assertEquals("", location.pinTargetFileName());
    }

    @Test
    public void absolutePathShouldBeAcceptedWithWarning() {
        File absolute = new File(tempDir, "shared/bank-1.0.0.json");
        EvalBankLocation location = EvalBankLocator.locate(dataFolder, absolute.getAbsolutePath());

        assertTrue(location.valid());
        assertFalse(location.fromBuiltin());
        assertEquals(absolute.getAbsoluteFile(), location.bankFile().getAbsoluteFile());
        assertTrue(location.warnings().contains(EvalBankLocator.WARN_ABSOLUTE_PATH));

        EvalBankReleaseResult result = EvalBankReleaser.release(location,
                new FakeResources("{}".getBytes(StandardCharsets.UTF_8), "bank/bank-1.0.0.json"),
                new RecordingLogger());
        assertEquals(EvalBankReleaseResult.Status.SKIPPED_CUSTOM, result.status());
        assertEquals(EvalBankLocator.CODE_BANK_READ, result.errorCode());
        assertFalse(absolute.exists());
    }

    @Test
    public void illegalOrEscapingPathShouldBeRejectedOrWarned() {
        EvalBankLocation directory = EvalBankLocator.locate(dataFolder, "sub/");
        assertFalse(directory.valid());
        assertTrue(directory.invalidReason().length() > 0);

        EvalBankLocation escaping = EvalBankLocator.locate(dataFolder, "../outside.json");
        assertTrue(escaping.valid());
        assertTrue(escaping.warnings().contains(EvalBankLocator.WARN_ESCAPES_EVALS));
    }

    @Test
    public void missingBuiltinBankShouldBeReleasedByteForByteFromResources() throws IOException {
        assertTrue(evalsDir.mkdirs());
        byte[] resource = "{\"version\":\"1.0.0\"}".getBytes(StandardCharsets.UTF_8);
        FakeResources resources = new FakeResources(resource, "bank/bank-1.0.0.json");
        RecordingLogger logger = new RecordingLogger();

        EvalBankLocation location = EvalBankLocator.locate(dataFolder, "");
        EvalBankReleaseResult result = EvalBankReleaser.release(location, resources, logger);

        assertEquals(EvalBankReleaseResult.Status.RELEASED, result.status());
        assertTrue(result.released());
        assertFalse(result.failed());
        assertEquals("", result.errorCode());
        assertEquals(1, resources.reads);
        assertEquals("bank/bank-1.0.0.json", resources.lastPath);
        File released = new File(evalsDir, "bank-1.0.0.json");
        assertTrue(released.isFile());
        assertEquals(resource.length, released.length());
        assertTrue(java.util.Arrays.equals(resource, Files.readAllBytes(released.toPath())));
        assertEquals(released.getAbsoluteFile(), result.bankFile().getAbsoluteFile());
        assertEquals(resource.length, result.bytesWritten());
        assertEquals(1, logger.infos.size());
        assertTrue(logger.infos.get(0).contains("已释放内置题库"));
        assertTrue(logger.infos.get(0).contains(released.getAbsolutePath()));
        assertTrue(logger.errors.isEmpty());
        assertNoTempFiles();
    }

    @Test
    public void pinnedBuiltinVersionFileShouldBeReReleasedWhenMissing() throws IOException {
        assertTrue(evalsDir.mkdirs());
        FakeResources resources = new FakeResources("{\"version\":\"1.0.0\"}".getBytes(StandardCharsets.UTF_8),
                "bank/bank-1.0.0.json");
        RecordingLogger logger = new RecordingLogger();

        EvalBankLocation location = EvalBankLocator.locate(dataFolder, "bank-1.0.0.json");
        assertFalse(location.configBlank());
        assertFalse(location.fromBuiltin());
        EvalBankReleaseResult result = EvalBankReleaser.release(location, resources, logger);

        assertEquals(EvalBankReleaseResult.Status.RELEASED, result.status());
        assertEquals("bank/bank-1.0.0.json", resources.lastPath);
        assertTrue(new File(evalsDir, "bank-1.0.0.json").isFile());
        assertEquals(1, logger.infos.size());
        assertNoTempFiles();
    }

    @Test
    public void existingFileShouldNeverBeOverwrittenOrDeleted() throws IOException {
        assertTrue(evalsDir.mkdirs());
        File existing = new File(evalsDir, "bank-1.0.0.json");
        byte[] userContent = "{\"version\":\"1.0.0\",\"note\":\"user edited\"}".getBytes(StandardCharsets.UTF_8);
        Files.write(existing.toPath(), userContent);
        FakeResources resources = new FakeResources("{\"version\":\"1.0.0\"}".getBytes(StandardCharsets.UTF_8),
                "bank/bank-1.0.0.json");
        RecordingLogger logger = new RecordingLogger();

        EvalBankLocation location = EvalBankLocator.locate(dataFolder, "");
        EvalBankReleaseResult result = EvalBankReleaser.release(location, resources, logger);

        assertEquals(EvalBankReleaseResult.Status.SKIPPED_EXISTS, result.status());
        assertEquals(0, resources.reads);
        assertTrue(java.util.Arrays.equals(userContent, Files.readAllBytes(existing.toPath())));
        assertTrue(result.errorCode().isEmpty());
        assertTrue(logger.errors.isEmpty());
        assertNoTempFiles();
    }

    @Test
    public void missingCustomBankShouldNeverBeCreated() {
        assertTrue(evalsDir.mkdirs());
        FakeResources resources = new FakeResources("{}".getBytes(StandardCharsets.UTF_8), "bank/bank-1.0.0.json");
        RecordingLogger logger = new RecordingLogger();

        EvalBankLocation location = EvalBankLocator.locate(dataFolder, "bank-custom.json");
        EvalBankReleaseResult result = EvalBankReleaser.release(location, resources, logger);

        assertEquals(EvalBankReleaseResult.Status.SKIPPED_CUSTOM, result.status());
        assertEquals(EvalBankLocator.CODE_BANK_READ, result.errorCode());
        assertEquals(1, resources.reads);
        assertEquals("bank/bank-custom.json", resources.lastPath);
        assertFalse(new File(evalsDir, "bank-custom.json").exists());
        assertEquals(0, evalsDir.listFiles().length);
        assertTrue(result.message().contains(new File(evalsDir, "bank-custom.json").getAbsolutePath()));
    }

    @Test
    public void missingEvalsDirectoryShouldNotBeCreated() {
        FakeResources resources = new FakeResources("{}".getBytes(StandardCharsets.UTF_8), "bank/bank-1.0.0.json");
        RecordingLogger logger = new RecordingLogger();

        EvalBankLocation location = EvalBankLocator.locate(dataFolder, "");
        EvalBankReleaseResult result = EvalBankReleaser.release(location, resources, logger);

        assertEquals(EvalBankReleaseResult.Status.SKIPPED_NO_DATA_DIRECTORY, result.status());
        assertEquals(EvalBankLocator.CODE_DATA_NOT_FOUND, result.errorCode());
        assertEquals(0, resources.reads);
        assertFalse(evalsDir.exists());
        assertEquals(1, logger.warns.size());
        assertTrue(logger.warns.get(0).contains(EvalBankLocator.CODE_DATA_NOT_FOUND));
    }

    @Test
    public void unwritableTargetShouldFailWithAbsolutePathAndNoHalfWrittenFile() throws IOException {
        assertTrue(evalsDir.mkdirs());
        File occupied = new File(evalsDir, "bank-1.0.0.json");
        assertTrue(occupied.mkdirs());
        FakeResources resources = new FakeResources("{\"version\":\"1.0.0\"}".getBytes(StandardCharsets.UTF_8),
                "bank/bank-1.0.0.json");
        RecordingLogger logger = new RecordingLogger();

        EvalBankLocation location = EvalBankLocator.locate(dataFolder, "");
        EvalBankReleaseResult result = EvalBankReleaser.release(location, resources, logger);

        assertEquals(EvalBankReleaseResult.Status.FAILED, result.status());
        assertTrue(result.failed());
        assertEquals(EvalBankLocator.CODE_BANK_READ, result.errorCode());
        assertTrue(result.message().contains(occupied.getAbsolutePath()));
        assertEquals(1, logger.errors.size());
        assertTrue(logger.errors.get(0).contains(occupied.getAbsolutePath()));
        assertTrue(occupied.isDirectory());
        assertNoTempFiles();
    }

    @Test
    public void unreadableBuiltinResourceShouldFailWithoutCreatingAnything() throws IOException {
        assertTrue(evalsDir.mkdirs());
        FakeResources resources = new FakeResources(null, "bank/bank-1.0.0.json");
        RecordingLogger logger = new RecordingLogger();

        EvalBankLocation location = EvalBankLocator.locate(dataFolder, "");
        EvalBankReleaseResult result = EvalBankReleaser.release(location, resources, logger);

        assertEquals(EvalBankReleaseResult.Status.FAILED, result.status());
        assertEquals(EvalBankLocator.CODE_BANK_READ, result.errorCode());
        assertFalse(new File(evalsDir, "bank-1.0.0.json").exists());
        assertNoTempFiles();
    }

    @Test
    public void builtinResourcePathShouldMatchPackagedJarEntry() throws Exception {
        String resourcePath = EvalBankLocator.builtinBankResourcePath(EvalBankLocator.DEFAULT_BANK_FILE_NAME);
        assertEquals("bank/bank-1.0.0.json", resourcePath);
        File jar = locateJar();
        ZipFile zip = new ZipFile(jar);
        try {
            ZipEntry entry = zip.getEntry(resourcePath);
            assertNotNull("JAR 内缺少内置题库资源：" + resourcePath + "，释放时将无法读取", entry);
            File source = new File(jar.getParentFile().getParentFile(),
                    "src/main/resources/" + resourcePath);
            assertTrue("找不到内置题库源文件：" + source.getAbsolutePath(), source.isFile());
            assertEquals("JAR 内题库字节应与源资源一致", source.length(), entry.getSize());
        } finally {
            zip.close();
        }
    }

    /** 定位构建产物 JAR：test-classes 的同级 out 目录。 */
    private static File locateJar() throws Exception {
        File testClasses = new File(EvalBankLocatorTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        File jar = new File(testClasses.getParentFile().getParentFile(), "MBB-Eval.jar");
        assertTrue("找不到构建产物，请先执行 build.ps1 -Test：" + jar.getAbsolutePath(), jar.isFile());
        return jar;
    }

    private void assertNoTempFiles() {
        File[] files = evalsDir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            String name = file.getName();
            assertFalse("残留临时文件：" + name, name.endsWith(".tmp") || name.startsWith(".bank-"));
        }
    }

    private static void write(File file, String content) throws IOException {
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

    /** 伪造 JAR 资源读取器：只对约定路径返回内容，其余路径按「资源不存在」返回 null。 */
    private static final class FakeResources implements EvalBankResources {
        private final byte[] payload;
        private final String expectedPath;
        private int reads;
        private String lastPath;

        private FakeResources(byte[] payload, String expectedPath) {
            this.payload = payload;
            this.expectedPath = expectedPath;
        }

        @Override
        public byte[] read(String resourcePath) {
            reads++;
            lastPath = resourcePath;
            return expectedPath.equals(resourcePath) ? payload : null;
        }
    }

    /** 内存日志收集器，避免测试依赖真实插件日志输出。 */
    private static final class RecordingLogger extends PluginLogger {
        private final List<String> infos = new ArrayList<String>();
        private final List<String> warns = new ArrayList<String>();
        private final List<String> errors = new ArrayList<String>();

        private RecordingLogger() {
            super("test", null);
        }

        @Override
        public void sendInfo(String message) {
            infos.add(message);
        }

        @Override
        public void sendWarn(String message) {
            warns.add(message);
        }

        @Override
        public void sendError(String message) {
            errors.add(message);
        }
    }
}
