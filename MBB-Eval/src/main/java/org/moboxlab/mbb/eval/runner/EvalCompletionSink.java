package org.moboxlab.mbb.eval.runner;

/**
 * 完成摘要的回送端口（规格 §9.3.1、§9.4 第 4 步、§16.8 G-11、§15.9 I-2）。
 *
 * <p>runner 在一个 run 收口为 COMPLETED、报表落盘之后，把
 * {@code report/EvalGroupSummaryFormatter} 生成的 ≤15 行纯文本交给本端口，由命令层负责发回
 * 发起会话。端口刻意不依赖命令层与主程序的 {@code CommandSender}：runner 只决定「发什么、什么时候发」，
 * 「发到哪里」由受理时的调用方注入。</p>
 *
 * <p>发送失败由实现抛 RuntimeException 表达；runner 只记 WARN，不改变 run 终态与报表
 * （§9.4 判定规则 7）。</p>
 */
public interface EvalCompletionSink {

    /**
     * 发送一段纯文本摘要（≤15 行、无 Markdown、无 emoji，§8.6）。
     *
     * @param text 摘要正文，由 {@code EvalGroupSummaryFormatter#toPlainText} 生成，原样发送
     */
    void send(String text);
}
