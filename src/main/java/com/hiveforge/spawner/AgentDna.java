package com.hiveforge.spawner;

public class AgentDna {

    private final String soulMd;
    private final String agentsMd;
    private final String taskMd;

    public AgentDna(String soulMd, String agentsMd, String taskMd) {
        this.soulMd = soulMd;
        this.agentsMd = agentsMd;
        this.taskMd = taskMd;
    }

    public String getSoulMd() { return soulMd; }
    public String getAgentsMd() { return agentsMd; }
    public String getTaskMd() { return taskMd; }
}
