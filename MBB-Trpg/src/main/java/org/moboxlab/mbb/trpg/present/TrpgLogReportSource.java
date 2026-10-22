package org.moboxlab.mbb.trpg.present;

import org.moboxlab.mbb.trpg.script.Clue;
import org.moboxlab.mbb.trpg.script.Script;
import org.moboxlab.mbb.trpg.script.ScriptCatalog;
import org.moboxlab.mbb.trpg.session.GrowthOutcome;
import org.moboxlab.mbb.trpg.session.MemberView;
import org.moboxlab.mbb.trpg.session.SessionEvent;
import org.moboxlab.mbb.trpg.session.SessionSnapshot;
import org.moboxlab.mbb.trpg.store.LogRow;
import org.moboxlab.mbb.trpg.store.SessionStore;

import java.util.ArrayList;
import java.util.List;

/**
 * 战报数据来源：局已收尾后从快照、剧本与日志补齐战报字段。
 *
 * <p>结局标题与叙述取自收尾摘要（摘要由 {@code SummaryResolver} 写成「标题\n叙述」）；
 * 检定次数取最近日志中 check/san/opposed 的条数，成功数与明细归后续切片按日志内容解析。</p>
 */
public final class TrpgLogReportSource implements TrpgReportSource {

    /** 毫秒换算分钟的除数。 */
    private static final long MILLIS_PER_MINUTE = 60000L;

    /** 战报只统计最近这批日志，避免长局把内存打满。 */
    private static final int LOG_SCAN_LIMIT = 200;

    private final SessionStore store;
    private final ScriptCatalog catalog;
    private final TrpgNameResolver names;
    private final int retentionDays;

    public TrpgLogReportSource(SessionStore store, ScriptCatalog catalog, TrpgNameResolver names,
                               int retentionDays) {
        if (store == null) {
            throw new IllegalArgumentException("存储不能为空");
        }
        if (catalog == null) {
            throw new IllegalArgumentException("剧本库不能为空");
        }
        if (names == null) {
            throw new IllegalArgumentException("昵称解析器不能为空");
        }
        this.store = store;
        this.catalog = catalog;
        this.names = names;
        this.retentionDays = Math.max(1, retentionDays);
    }

    @Override
    public TrpgReport report(SessionEvent event, Script script) {
        if (event == null) {
            throw new IllegalArgumentException("事件不能为空");
        }
        SessionSnapshot snapshot = event.snapshot();
        if (snapshot == null) {
            return TrpgReport.builder().retentionDays(retentionDays).build();
        }
        Script source = script == null ? catalog.get(snapshot.scriptId()) : script;
        TrpgReport.Builder report = TrpgReport.builder()
                .scriptName(source == null ? snapshot.scriptId() : source.name())
                .startedAt(snapshot.startedAt())
                .endedAt(snapshot.endedAt())
                .durationMinutes(durationMinutes(snapshot))
                .kpName(names.name(snapshot.groupId(), snapshot.kpUserId()))
                .clueTotal(source == null ? 0 : source.clues().size())
                .retentionDays(retentionDays)
                .growthLines(growthLines(event.growth()))
                .playerNames(playerNames(snapshot))
                .clueNames(clueNames(snapshot, source));
        applySummary(report, snapshot.summary());
        applyChecks(report, snapshot);
        return report.build();
    }

    private static int durationMinutes(SessionSnapshot snapshot) {
        if (snapshot.startedAt() <= 0 || snapshot.endedAt() <= snapshot.startedAt()) {
            return 0;
        }
        return (int) ((snapshot.endedAt() - snapshot.startedAt()) / MILLIS_PER_MINUTE);
    }

    private List<String> playerNames(SessionSnapshot snapshot) {
        List<String> result = new ArrayList<String>();
        for (MemberView member : snapshot.members()) {
            if (member.inSession() && member.userId() != snapshot.kpUserId()) {
                result.add(names.name(snapshot.groupId(), member.userId()));
            }
        }
        return result;
    }

    private List<String> clueNames(SessionSnapshot snapshot, Script script) {
        List<String> result = new ArrayList<String>();
        for (String key : snapshot.unlockedClues()) {
            Clue clue = script == null ? null : script.clueById(key);
            result.add(clue == null ? key : clue.name());
        }
        return result;
    }

    private static List<String> growthLines(List<GrowthOutcome> growth) {
        List<String> lines = new ArrayList<String>();
        for (GrowthOutcome outcome : growth) {
            lines.add(outcome.skillId() + " " + outcome.from() + " → " + outcome.to());
        }
        return lines;
    }

    private static void applySummary(TrpgReport.Builder report, String summary) {
        String text = summary == null ? "" : summary.trim();
        if (text.isEmpty()) {
            return;
        }
        int newline = text.indexOf('\n');
        if (newline < 0) {
            report.endingTitle(text);
            return;
        }
        report.endingTitle(text.substring(0, newline).trim());
        report.endingNarration(text.substring(newline + 1).trim());
    }

    private void applyChecks(TrpgReport.Builder report, SessionSnapshot snapshot) {
        if (snapshot.sessionId() <= 0) {
            return;
        }
        List<LogRow> logs = store.listLogs(snapshot.sessionId(), LOG_SCAN_LIMIT, 0);
        int total = 0;
        for (LogRow log : logs) {
            String type = log.eventType();
            if ("check".equals(type) || "san".equals(type) || "opposed".equals(type)) {
                total++;
            }
        }
        report.checkTotal(total);
    }
}
