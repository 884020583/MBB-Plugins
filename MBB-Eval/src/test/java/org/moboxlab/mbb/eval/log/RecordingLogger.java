package org.moboxlab.mbb.eval.log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.moboxlab.moboxbot.API.PluginLogger;

/** 只收集日志的假件：不走主程序日志，避免测试写出日志文件。 */
final class RecordingLogger extends PluginLogger {

    private final List<String> infos = new ArrayList<String>();
    private final List<String> warns = new ArrayList<String>();
    private final List<String> errors = new ArrayList<String>();

    RecordingLogger() {
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

    List<String> infos() {
        return Collections.unmodifiableList(infos);
    }

    List<String> warns() {
        return Collections.unmodifiableList(warns);
    }

    List<String> errors() {
        return Collections.unmodifiableList(errors);
    }

    /** INFO / WARN / ERROR 三类日志按输出顺序合并，便于做「所有日志行」的断言。 */
    List<String> all() {
        List<String> all = new ArrayList<String>();
        all.addAll(infos);
        all.addAll(warns);
        all.addAll(errors);
        return Collections.unmodifiableList(all);
    }
}
