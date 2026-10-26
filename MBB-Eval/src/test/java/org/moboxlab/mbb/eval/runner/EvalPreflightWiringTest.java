package org.moboxlab.mbb.eval.runner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.moboxlab.mbb.eval.TestEvalAssets;
import org.moboxlab.mbb.eval.bank.EvalBankIdentity;
import org.moboxlab.mbb.eval.bank.EvalBankProblem;
import org.moboxlab.mbb.eval.command.EvalCommand;
import org.moboxlab.mbb.eval.config.EvalConfig;
import org.moboxlab.mbb.eval.gateway.EvalCapabilityClient;
import org.moboxlab.mbb.eval.judge.EvalJudgePrompt;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.CapabilitiesStatusSource;
import org.moboxlab.mbb.eval.store.EvalSchemaService;
import org.moboxlab.mbb.eval.store.EvalStorageFake;
import org.moboxlab.mbb.eval.store.EvalStore;
import org.moboxlab.moboxbot.API.Command.CommandPermission;
import org.moboxlab.moboxbot.API.Command.CommandSender;
import org.moboxlab.moboxbot.API.PluginLogger;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

/**
 * B-5 预检接入与命令回显用例：题库失败时 /eval run 回显前 5 条且两次一致，
 * /eval report 等只读命令不被禁用（§9.5、§9.3.1、§13.1 规则 4、§15.3 B23/B29）。
 */
public class EvalPreflightWiringTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private EvalStorageFake storage;
    private EvalStore store;
    private EvalRunner runner;
    private EvalConfig config;
    private RecordingLogger logger;
    private EvalDataBootstrap bootstrap;
    private CapabilitiesStatusSource capabilitiesSource;

    @Before
    public void setUp() throws Exception {
        File dbFile = temporaryFolder.newFile("eval-wiring.db");
        storage = new EvalStorageFake(dbFile);
        assertTrue(new EvalSchemaService(storage, null).ensureSchema());
        logger = new RecordingLogger();
        store = new EvalStore(storage, logger);
        config = EvalConfig.load(null);
        capabilitiesSource = capabilitiesSource();

        File dataFolder = TestEvalAssets.dataFolder(temporaryFolder.newFolder());
        File evals = TestEvalAssets.ensureEvalsDirectory(dataFolder);
        JSONObject bank = TestEvalAssets.builtinBank();
        TestEvalAssets.writeValidRubric(evals, bank);
        bank.remove("hash");
        for (int i = 0; i < 7; i++) {
            bank.getJSONArray("cases").getJSONObject(i).put("id", "bad-" + i);
        }
        TestEvalAssets.write(evals, TestEvalAssets.BANK_FILE_NAME, bank.toJSONString());

        bootstrap = EvalDataBootstrap.load(dataFolder, TestEvalAssets.BANK_FILE_NAME,
                EvalJudgePrompt.PROMPT_VERSION, null, null, false, null, logger);
        assertFalse("夹具应是失败题库", bootstrap.bank().ok());

        EvalPreflight preflight = EvalPreflight.builder()
                .config(config)
                .logger(logger)
                .bank(EvalStageSources.bank(bootstrap.bank()))
                .rubric(EvalStageSources.rubric(bootstrap.rubric()))
                .capabilities(capabilitiesSource)
                .persona(EvalStageSources.persona(bootstrap.bank(), capabilitiesSource, false))
                .build();
        runner = new EvalRunner(store, config, preflight, logger, "V0.1.0.0.1824");
    }

    @After
    public void tearDown() {
        if (runner != null) {
            runner.shutdown();
        }
        if (storage != null) {
            storage.close();
        }
    }

    @Test
    public void rejectedRunShouldCarryFirstFiveProblemDetails() {
        EvalRunner.AcceptResult result = runner.accept(EvalStore.TIER_SMOKE);
        assertEquals(EvalRunner.AcceptKind.REJECTED, result.kind());
        assertEquals(EvalBankProblem.ID, result.code());
        assertEquals("细节最多 5 条", 5, result.details().size());
        assertTrue(result.details().get(0).contains("cases[0].id"));
        assertTrue(result.details().get(4).contains("cases[4].id"));
        assertTrue("消息应含错误总数：" + result.message(), result.message().contains("7"));
        assertTrue("提示应含题库绝对路径：" + result.hint(),
                result.hint().contains(bootstrap.bank().absolutePath()));
    }

    @Test
    public void commandShouldRenderFiveErrorsAndKeepOrderStable() {
        EvalCommand command = new EvalCommand(runner, config);
        RecordingSender first = new RecordingSender();
        assertTrue(command.execute(first, new String[] { "eval", "run" }));

        List<String> firstMessages = new ArrayList<String>(first.messages);
        assertTrue("拒绝回执应有多行：" + firstMessages, firstMessages.size() >= 7);
        for (String message : firstMessages) {
            assertTrue("所有回执必须带 [eval] 前缀：" + message, message.startsWith("[eval] "));
        }
        String joined = first.joined();
        assertTrue(joined, joined.contains("cases[0].id"));
        assertTrue(joined, joined.contains("cases[4].id"));
        assertFalse("不得回显第 6 条错误：" + joined, joined.contains("cases[5].id"));
        assertTrue("必须给出下一步动作：" + joined, joined.contains("下一步"));

        RecordingSender second = new RecordingSender();
        assertTrue(command.execute(second, new String[] { "eval", "run" }));
        assertEquals("两次回显必须完全一致", firstMessages, second.messages);
    }

    @Test
    public void readonlyReportShouldStayAvailableWhenBankFails() {
        EvalCommand command = new EvalCommand(runner, config);
        RecordingSender sender = new RecordingSender();
        assertTrue(command.execute(sender, new String[] { "eval", "report",
                "r-20260101000000-smoke-abcd" }));

        assertEquals(1, sender.messages.size());
        String message = sender.messages.get(0);
        assertTrue("report 不应被题库失败禁用：" + message, message.startsWith("[eval] "));
        assertFalse("report 不得回落成预检失败：" + message, message.contains("预检失败"));
        assertTrue("report 仍是明确占位：" + message, message.contains("后续切片"));
    }

    @Test
    public void missingDirectoryRejectionShouldCarryAbsolutePath() throws Exception {
        File dataFolder = TestEvalAssets.dataFolder(temporaryFolder.newFolder());
        RecordingLogger localLogger = new RecordingLogger();
        EvalDataBootstrap broken = EvalDataBootstrap.load(dataFolder,
                TestEvalAssets.BANK_FILE_NAME, EvalJudgePrompt.PROMPT_VERSION, null, null, false,
                null, localLogger);
        EvalPreflight preflight = EvalPreflight.builder()
                .config(config)
                .logger(localLogger)
                .bank(EvalStageSources.bank(broken.bank()))
                .rubric(EvalStageSources.rubric(broken.rubric()))
                .capabilities(capabilitiesSource)
                .persona(EvalStageSources.persona(broken.bank(), capabilitiesSource, false))
                .build();
        EvalRunner localRunner = new EvalRunner(store, config, preflight, localLogger, "V0.1.0.0.1824");
        try {
            EvalRunner.AcceptResult result = localRunner.accept(EvalStore.TIER_SMOKE);
            assertEquals(EvalRunner.AcceptKind.REJECTED, result.kind());
            assertEquals("E_ROLEPLAY_DATA_NOT_FOUND", result.code());
            String text = result.message() + " " + result.hint();
            assertTrue("必须给实际查找路径：" + text,
                    text.contains(TestEvalAssets.evalsDirectory(dataFolder).getAbsolutePath()));
        } finally {
            localRunner.shutdown();
        }
    }

    /** 真实能力快照：接口可用，personaHash 与内置题库声明一致，避免人格项抢先拒绝。 */
    private CapabilitiesStatusSource capabilitiesSource() throws Exception {
        final String personaHash = EvalBankIdentity.parse(
                TestEvalAssets.builtinBankBytes()).personaHash();
        final EvalCapabilityClient client = new EvalCapabilityClient(
                new EvalCapabilityClient.CapabilitiesSource() {
                    @Override
                    public Object fetch() {
                        JSONObject capabilities = new JSONObject();
                        capabilities.put("status", true);
                        capabilities.put("evalApiVersion", 1);
                        capabilities.put("action", "capabilities");
                        JSONArray actions = new JSONArray();
                        actions.add("capabilities");
                        actions.add("eval-generate");
                        capabilities.put("actions", actions);
                        capabilities.put("evalApiEnable", true);
                        capabilities.put("personaId", "persona-aris.json");
                        capabilities.put("personaHash", personaHash);
                        capabilities.put("configFingerprint", "test");
                        capabilities.put("aiProfile", "default");
                        capabilities.put("evalProfile", "default");
                        capabilities.put("replyMaxTokens", 2048);
                        capabilities.put("promptTotalChars", 8000);
                        capabilities.put("toolEnable", false);
                        capabilities.put("replyReviewEnable", false);
                        capabilities.put("maxHistoryRounds", 20);
                        capabilities.put("maxHistoryChars", 30000);
                        return capabilities;
                    }
                }, logger);
        return new CapabilitiesStatusSource() {
            @Override
            public EvalCapabilityClient.Snapshot snapshot() {
                return client.snapshot();
            }
        };
    }

    private static final class RecordingSender implements CommandSender {
        private final Set<CommandPermission> granted = new LinkedHashSet<CommandPermission>();
        private final List<String> messages = new ArrayList<String>();
        private final List<String> replies = new ArrayList<String>();

        private RecordingSender() {
            granted.add(CommandPermission.OWNER);
            granted.add(CommandPermission.BOT_ADMIN);
        }

        @Override
        public boolean isGroup() {
            return false;
        }

        @Override
        public long getGroupID() {
            return 0L;
        }

        @Override
        public long getUserID() {
            return 10001L;
        }

        @Override
        public String getName() {
            return "tester";
        }

        @Override
        public String getCommandPrefix() {
            return "/";
        }

        @Override
        public boolean hasPermission(CommandPermission permission) {
            return permission == CommandPermission.EVERYONE || granted.contains(permission);
        }

        @Override
        public void sendMessage(String message) {
            messages.add(message);
        }

        @Override
        public void reply(String message) {
            replies.add(message);
        }

        private String joined() {
            StringBuilder text = new StringBuilder();
            for (String message : messages) {
                text.append(message).append('\n');
            }
            return text.toString();
        }
    }

    private static final class RecordingLogger extends PluginLogger {
        private final List<String> infos = new ArrayList<String>();
        private final List<String> warns = new ArrayList<String>();
        private final List<String> errors = new ArrayList<String>();

        private RecordingLogger() {
            super("eval-wiring-test", null);
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