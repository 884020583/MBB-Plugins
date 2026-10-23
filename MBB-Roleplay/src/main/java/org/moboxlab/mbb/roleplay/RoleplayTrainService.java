package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.Command.CommandSender;
import org.moboxlab.moboxbot.API.Storage.StorageService;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 群聊语料训练
 *
 * 把数据库里已经存着的群消息按「前文 -> 这一条」切成训练样本，提交给 MBB-Model。
 * 每次训练记录一个截止游标，下次只训练上次截止之后的新消息，不会重复喂老数据。
 *
 * /role learn 采集的特挑语料走另一条路径（submitLearned），两者共用同一个服务端接口。
 */
public class RoleplayTrainService {
    private static final String KEY_CUTOFF_ROW = "trainCutoffRowID";
    private static final String KEY_CUTOFF_TEXT = "trainCutoffText";
    private static final String SYSTEM_PROMPT =
            "你是一个QQ群聊里的普通群友，说话自然口语化、不端着，不使用助理式表达，只输出要说的那一句话。";
    private static final Pattern TRAINING_MEDIA = Pattern.compile(
            "(?i)[\\[［【](?:图片|表情包?|视频|语音|文件|动画表情|image|photo|sticker)[\\]］】]"
                    +"|<(?:image|photo|sticker)>"
                    +"|\\[CQ:(?:image|mface|face|record|video|file)[^]]*]");

    private final RoleplayService service;

    public RoleplayTrainService(RoleplayService service) {
        this.service = service;
    }

    // ---- 截止游标 ----

    long cutoffRowID() {
        try {
            return Long.parseLong(service.safe(storage().get(service.plugin(),KEY_CUTOFF_ROW)));
        } catch (Exception e) {
            return 0L;
        }
    }

    String cutoffText() {
        String text = service.safe(storage().get(service.plugin(),KEY_CUTOFF_TEXT));
        return text.isEmpty() ? "（还没训练过，下次会从最早的消息开始）" : text;
    }

    void setCutoff(long rowID,long timeMillis) {
        storage().set(service.plugin(),KEY_CUTOFF_ROW,String.valueOf(rowID));
        storage().set(service.plugin(),KEY_CUTOFF_TEXT,formatTime(timeMillis));
    }

    void resetCutoff() {
        storage().set(service.plugin(),KEY_CUTOFF_ROW,"0");
        storage().set(service.plugin(),KEY_CUTOFF_TEXT,"");
    }

    /**
     * 手工把截止游标设到某个时间点：取该时间之前最后一条群消息的位置
     */
    JSONObject setCutoffByTime(String text) {
        long millis = parseTime(text);
        if (millis <= 0) return fail("时间格式不对，示例：2026-10-19 02:00:00");
        List<JSONObject> rows = storage().query(
                "SELECT `ID`,`messageTime` FROM `"+RoleplayService.MSG_TABLE+"` "
                        +"WHERE `messageTime` <= ? ORDER BY `messageTime` DESC LIMIT 1",
                millis);
        if (rows == null || rows.isEmpty()) return fail("没有找到该时间之前的群消息");
        JSONObject row = rows.get(0);
        setCutoff(row.getLongValue("ID"),row.getLongValue("messageTime"));
        JSONObject result = new JSONObject(true);
        result.put("status",true);
        result.put("cutoffText",formatTime(row.getLongValue("messageTime")));
        return result;
    }

    // ---- 群聊语料 ----

    /**
     * 导出截止游标之后的新消息，切成 ShareGPT 样本
     */
    JSONObject exportCorpus(int limit) {
        return exportCorpus(limit,false);
    }

    JSONObject exportCorpus(int limit,boolean fromBeginning) {
        long cutoff = fromBeginning ? 0L : cutoffRowID();
        int max = limit > 0 ? limit : Math.max(1,service.config().trainMaxSamplesPerRun);
        String sql = "SELECT * FROM `"+RoleplayService.MSG_TABLE+"` WHERE `ID` > ?"
                +(service.config().trainIncludeBotMessages ? "" : " AND `isBot` = 0")
                +" ORDER BY `ID` ASC LIMIT ?";
        List<JSONObject> rows = storage().query(sql,cutoff,max);
        JSONArray records = new JSONArray();
        long lastRowID = cutoff;
        long lastTime = 0L;
        int scanned = 0;
        int skipped = 0;
        for (JSONObject row : rows == null ? new ArrayList<JSONObject>() : rows) {
            if (row == null) continue;
            scanned++;
            lastRowID = Math.max(lastRowID,row.getLongValue("ID"));
            lastTime = Math.max(lastTime,row.getLongValue("messageTime"));
            String text = service.safe(row.getString("content")).trim();
            if (text.isEmpty() || text.length() > service.config().trainMaxChars) {
                skipped++;
                continue;
            }
            if (containsTrainingMedia(text)) {
                skipped++;
                continue;
            }
            if (isCommand(text)) {
                skipped++;
                continue;
            }
            String context = contextText(row);
            JSONObject record = new JSONObject(true);
            record.put("system",SYSTEM_PROMPT);
            JSONArray conversations = new JSONArray();
            JSONObject human = new JSONObject(true);
            human.put("from","human");
            human.put("value",(context.isEmpty() ? "" : "[群聊记录]\n"+context+"\n")
                    +"请以群友的口吻接一句，只输出这句话本身。");
            conversations.add(human);
            JSONObject gpt = new JSONObject(true);
            gpt.put("from","gpt");
            gpt.put("value",text);
            conversations.add(gpt);
            record.put("conversations",conversations);
            records.add(record);
        }
        JSONObject result = new JSONObject(true);
        result.put("status",true);
        result.put("records",records);
        result.put("scanned",scanned);
        result.put("skipped",skipped);
        result.put("lastRowID",lastRowID);
        result.put("lastTime",lastTime);
        return result;
    }

    /**
     * 导出并提交训练；只有提交成功才推进截止游标
     */
    JSONObject trainCorpus(int limit) {
        return trainCorpus(limit,false);
    }

    JSONObject trainCorpusFresh(int limit) {
        return trainCorpus(limit,true);
    }

    private JSONObject trainCorpus(int limit,boolean fresh) {
        if (!service.config().trainEnable) return fail("训练功能当前已关闭（trainEnable=false）");
        JSONObject exported = exportCorpus(limit,fresh);
        JSONArray records = exported.getJSONArray("records");
        if (records == null || records.isEmpty()) {
            return fail(fresh
                    ? "本地没有可用于新 adapter 的群消息。"
                    : "上次截止之后没有新的可用群消息。截止："+cutoffText());
        }
        JSONObject payload = new JSONObject(true);
        payload.put("name","group-corpus");
        payload.put("records",records);
        JSONObject submitted = client().post("/train/dataset",payload);
        if (submitted == null || !submitted.getBooleanValue("status")) {
            return fail(submitted == null ? "没有配置模型服务地址" : submitted.getString("message"));
        }
        long lastRowID = exported.getLongValue("lastRowID");
        long lastTime = exported.getLongValue("lastTime");
        setCutoff(lastRowID,lastTime);
        JSONObject result = new JSONObject(true);
        result.put("status",true);
        result.put("samples",records.size());
        result.put("scanned",exported.getIntValue("scanned"));
        result.put("skipped",exported.getIntValue("skipped"));
        result.put("cutoffText",formatTime(lastTime));
        result.put("datasetId",service.safe(submitted.getString("datasetId")));
        result.put("mode",fresh ? "fresh" : "continue");
        appendAutoStart(result,service.safe(submitted.getString("datasetId")),fresh ? "fresh" : "continue");
        return result;
    }

    // ---- 特挑语料 ----

    /**
     * 把 /role learn 采集的 JSONL 转成 ShareGPT 再提交
     */
    JSONObject trainLearned() {
        if (!service.config().trainEnable) return fail("训练功能当前已关闭（trainEnable=false）");
        File file = learnedFile();
        if (!file.exists()) return fail("还没有用 /role learn 采集过语料");
        JSONArray records = new JSONArray();
        int skipped = 0;
        try {
            for (String line : Files.readAllLines(file.toPath(),StandardCharsets.UTF_8)) {
                if (line == null || line.trim().isEmpty()) continue;
                JSONObject raw;
                try {
                    raw = JSONObject.parseObject(line);
                } catch (Exception e) {
                    skipped++;
                    continue;
                }
                if (raw == null) continue;
                JSONObject target = raw.getJSONObject("target");
                if (target == null) {
                    skipped++;
                    continue;
                }
                String text = service.safe(target.getString("text")).trim();
                if (text.isEmpty() || containsTrainingMedia(text)) {
                    skipped++;
                    continue;
                }
                String context = learnedContextText(raw.getJSONArray("contextBefore"));
                JSONObject record = new JSONObject(true);
                record.put("system",SYSTEM_PROMPT);
                JSONArray conversations = new JSONArray();
                JSONObject human = new JSONObject(true);
                human.put("from","human");
                human.put("value",(context.isEmpty() ? "" : "[群聊记录]\n"+context+"\n")
                        +"请以群友的口吻接一句，只输出这句话本身。");
                conversations.add(human);
                JSONObject gpt = new JSONObject(true);
                gpt.put("from","gpt");
                gpt.put("value",text);
                conversations.add(gpt);
                record.put("conversations",conversations);
                records.add(record);
            }
        } catch (Exception e) {
            return fail("读取采集文件失败："+e.getMessage());
        }
        if (records.isEmpty()) return fail("采集文件里没有可用样本");
        JSONObject payload = new JSONObject(true);
        payload.put("name","learned-corpus");
        payload.put("records",records);
        JSONObject submitted = client().post("/train/dataset",payload);
        if (submitted == null || !submitted.getBooleanValue("status")) {
            return fail(submitted == null ? "没有配置模型服务地址" : submitted.getString("message"));
        }
        JSONObject result = new JSONObject(true);
        result.put("status",true);
        result.put("samples",records.size());
        result.put("skipped",skipped);
        result.put("datasetId",service.safe(submitted.getString("datasetId")));
        result.put("mode","continue");
        appendAutoStart(result,service.safe(submitted.getString("datasetId")),"continue");
        return result;
    }

    // ---- 服务状态 ----

    JSONObject serviceStatus() {
        JSONObject health = client().get("/health");
        JSONObject result = new JSONObject(true);
        result.put("status",truthy(health,"status"));
        result.put("health",health);
        if (result.getBooleanValue("status")) {
            result.put("train",client().get("/train/status"));
        }
        return result;
    }

    /**
     * 训练是后台任务，启动之后定期轮询，结束或失败时主动回一条结果给发起的群。
     */
    void trackJob(CommandSender sender,String jobId) {
        trackJob(sender,jobId,0);
    }

    private void trackJob(CommandSender sender,String jobId,int attempt) {
        if (sender == null || jobId == null || jobId.trim().isEmpty()) return;
        if (service.plugin().getServer() == null
                || service.plugin().getServer().getPluginManager() == null) return;
        // 10 秒一次，最多轮询两小时，避免异常任务无限占着定时器
        if (attempt > 720) {
            sender.sendMessage("训练任务 "+jobId+" 长时间没有结果，请用 /role train 手动查看。");
            return;
        }
        service.plugin().getServer().getPluginManager().runTaskLater(service.plugin(),
                () -> pollJob(sender,jobId,attempt),10);
    }

    private void pollJob(CommandSender sender,String jobId,int attempt) {
        JSONObject status = client().get("/train/status?jobId="+jobId);
        if (status == null || !status.getBooleanValue("status")) {
            trackJob(sender,jobId,attempt+1);
            return;
        }
        String state = service.safe(status.getString("state"));
        if ("done".equals(state)) {
            sender.sendMessage(resultText("训练完成",status));
            return;
        }
        if ("failed".equals(state)) {
            sender.sendMessage(resultText("训练失败",status));
            return;
        }
        trackJob(sender,jobId,attempt+1);
    }

    /**
     * 训练结果文本，/role train 和完成通知共用
     */
    String resultText(String title,JSONObject status) {
        if (status == null) return title;
        StringBuilder builder = new StringBuilder(title);
        builder.append("\n任务：").append(service.safe(status.getString("jobId")));
        builder.append("\n状态：").append(service.safe(status.getString("state")));
        int progress = status.getIntValue("progress");
        builder.append("（").append(progress).append("%）");
        String message = service.safe(status.getString("message"));
        if (!message.isEmpty()) builder.append("\n信息：").append(message);
        if (status.getIntValue("samples") > 0) {
            builder.append("\n样本：").append(status.getIntValue("samples")).append(" 条");
        }
        if (status.getIntValue("trainedSamples") > 0) {
            builder.append("\n实际训练：").append(status.getIntValue("trainedSamples")).append(" 条");
        }
        if (status.getIntValue("skippedSamples") > 0) {
            builder.append("\n跳过异常样本：").append(status.getIntValue("skippedSamples")).append(" 条");
        }
        if (status.getIntValue("steps") > 0) {
            builder.append("\n步数：").append(status.getIntValue("steps"));
        }
        if (status.get("finalLoss") != null) {
            builder.append("\n最终 loss：").append(status.get("finalLoss"));
        }
        if (status.getLongValue("durationMs") > 0) {
            builder.append("\n耗时：").append(status.getLongValue("durationMs") / 1000L).append(" 秒");
        }
        String adapter = service.safe(status.getString("adapter"));
        if (!adapter.isEmpty()) {
            builder.append("\nadapter：").append(adapter);
            builder.append("\n用 /role train reload 让推理端生效");
        }
        String requestedMode = service.safe(status.getString("requestedMode"));
        String resolvedMode = service.safe(status.getString("resolvedMode"));
        if (!requestedMode.isEmpty()) {
            builder.append("\n训练方式：").append("fresh".equals(requestedMode)
                    ? "新开 adapter" : "继续上一个 adapter");
        }
        if (!resolvedMode.isEmpty() && !resolvedMode.equals(requestedMode)) {
            builder.append("（实际按 ").append(resolvedMode).append(" 执行）");
        }
        String initialAdapter = service.safe(status.getString("initialAdapter"));
        if (!initialAdapter.isEmpty()) {
            builder.append("\n起点 adapter：").append(initialAdapter);
        }
        return builder.toString();
    }

    JSONObject reloadAdapter() {
        JSONObject result = client().post("/train/reload",new JSONObject(true));
        return result == null ? fail("没有配置模型服务地址") : result;
    }

    File learnedFile() {
        String directory = service.config().learnDirectory;
        if (directory == null || directory.trim().isEmpty()) directory = "train-corpus";
        return new File(new File(service.plugin().getDataFolder(),directory.trim()),"learned.jsonl");
    }

    // ---- 内部 ----

    private void appendAutoStart(JSONObject result,String datasetId,String mode) {
        if (!service.config().trainAutoStart || datasetId.isEmpty()) {
            result.put("started",false);
            return;
        }
        JSONObject startPayload = new JSONObject(true);
        startPayload.put("datasetId",datasetId);
        JSONObject options = new JSONObject(true);
        options.put("mode",mode);
        startPayload.put("options",options);
        JSONObject started = client().post("/train/start",startPayload);
        boolean ok = started != null && started.getBooleanValue("status");
        result.put("started",ok);
        if (ok) {
            result.put("jobId",service.safe(started.getString("jobId")));
        } else if (started != null) {
            result.put("startMessage",service.safe(started.getString("message")));
        }
    }

    private String contextText(JSONObject target) {
        int before = Math.max(0,service.config().trainContextBefore);
        if (before <= 0) return "";
        List<JSONObject> rows = storage().query(
                "SELECT * FROM `"+RoleplayService.MSG_TABLE+"` WHERE `groupID`=? AND `ID`<? "
                        +"ORDER BY `ID` DESC LIMIT ?",
                target.getLongValue("groupID"),target.getLongValue("ID"),before);
        if (rows == null || rows.isEmpty()) return "";
        Collections.reverse(rows);
        StringBuilder builder = new StringBuilder();
        for (JSONObject row : rows) {
            if (row == null) continue;
            String text = stripTrainingMedia(row.getString("content")).replace("\n"," ");
            if (text.isEmpty()) continue;
            if (builder.length() > 0) builder.append("\n");
            builder.append(service.safe(row.getString("userName"))).append("：")
                    .append(service.shortText(text,80));
        }
        return builder.toString();
    }

    private String learnedContextText(JSONArray rows) {
        if (rows == null || rows.isEmpty()) return "";
        StringBuilder builder = new StringBuilder();
        int limit = Math.min(rows.size(),Math.max(1,service.config().trainContextBefore));
        int start = Math.max(0,rows.size()-limit);
        for (int i = start; i < rows.size(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (row == null) continue;
            String text = stripTrainingMedia(row.getString("text")).replace("\n"," ");
            if (text.isEmpty()) continue;
            if (builder.length() > 0) builder.append("\n");
            builder.append(service.safe(row.getString("userName"))).append("：")
                    .append(service.shortText(text,80));
        }
        return builder.toString();
    }

    private boolean isCommand(String text) {
        String value = text.trim();
        if (value.startsWith("@")) value = value.replaceFirst("^@[0-9]+\\s*","").trim();
        String prefixes = service.config().commandPrefixes;
        if (prefixes == null || prefixes.trim().isEmpty()) prefixes = "/";
        for (String item : prefixes.split(",")) {
            String prefix = item == null ? "" : item.trim();
            if (!prefix.isEmpty() && value.startsWith(prefix)) return true;
        }
        return false;
    }

    private boolean containsTrainingMedia(String text) {
        return text != null && TRAINING_MEDIA.matcher(text).find();
    }

    private String stripTrainingMedia(String text) {
        String value = service.safe(text);
        return TRAINING_MEDIA.matcher(value).replaceAll(" ")
                .replaceAll("\\s+"," ").trim();
    }

    private RoleplayModelClient client() {
        return service.modelClient();
    }

    private StorageService storage() {
        return service.storage();
    }

    private JSONObject fail(String message) {
        JSONObject result = new JSONObject(true);
        result.put("status",false);
        result.put("message",message);
        return result;
    }

    /**
     * status 字段兼容布尔和 "ok"/"true" 字符串两种写法
     */
    private boolean truthy(JSONObject json,String key) {
        if (json == null) return false;
        Object value = json.get(key);
        if (value instanceof Boolean) return ((Boolean) value).booleanValue();
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        String text = service.safe(value == null ? "" : String.valueOf(value)).toLowerCase(Locale.ROOT);
        return "true".equals(text) || "ok".equals(text) || "yes".equals(text) || "1".equals(text);
    }

    private String formatTime(long millis) {
        if (millis <= 0) return "";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.CHINA).format(new Date(millis));
    }

    private long parseTime(String text) {
        String value = text == null ? "" : text.trim();
        String[] patterns = new String[]{
                "yyyy-MM-dd HH:mm:ss","yyyy-MM-dd HH:mm","yyyy-MM-dd",
                "yyyy/MM/dd HH:mm:ss","yyyy/MM/dd HH:mm","yyyy/MM/dd"};
        for (String pattern : patterns) {
            try {
                return new SimpleDateFormat(pattern,Locale.CHINA).parse(value).getTime();
            } catch (Exception ignored) {
            }
        }
        return 0L;
    }
}
