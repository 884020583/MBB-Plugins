package org.moboxlab.mbb.bothub;

import com.alibaba.fastjson.JSONObject;

/**
 * hub 事件回调，client 模式和服务端本地 bot 共用
 */
public interface BotHubEventListener {
    void onEvent(JSONObject message);
}
