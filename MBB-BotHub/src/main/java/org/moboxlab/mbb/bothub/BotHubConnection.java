package org.moboxlab.mbb.bothub;

import com.alibaba.fastjson.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 一条 hub TCP 连接的收发封装
 */
public class BotHubConnection {
    private final Socket socket;
    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final Object writeLock = new Object();
    private volatile boolean closed = false;

    public BotHubConnection(Socket socket) throws Exception {
        this.socket = socket;
        this.socket.setTcpNoDelay(true);
        this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.UTF_8));
    }

    public JSONObject read() throws Exception {
        return BotHubProtocol.read(reader);
    }

    public void send(JSONObject message) {
        if (message == null || closed) return;
        synchronized (writeLock) {
            if (closed) return;
            try {
                BotHubProtocol.write(writer,message);
            } catch (Exception e) {
                close();
            }
        }
    }

    public boolean isClosed() {
        return closed || socket.isClosed();
    }

    public String remoteAddress() {
        try {
            return socket.getRemoteSocketAddress() == null ? "" : socket.getRemoteSocketAddress().toString();
        } catch (Exception e) {
            return "";
        }
    }

    public void close() {
        if (closed) return;
        closed = true;
        try {
            socket.close();
        } catch (Exception ignored) {
        }
    }
}
