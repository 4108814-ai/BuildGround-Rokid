package com.rokid.os.sprite.tts;

import com.rokid.os.sprite.tts.ITtsListener;

interface ITtsServer {
    void playTtsMsg(String text, String param, ITtsListener listener);
    void stopTtsPlay(String id);
    void updateTtsParam(String param);
}
