package com.hiveforge.llm;

public interface StreamCallback {

    void onEvent(String type, String message);
}
