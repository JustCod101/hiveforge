package com.hiveforge.spawner;

import java.util.List;

public class SpawnedWorker {

    private final String id;
    private final String name;
    private final String dir;
    private final AgentDna dna;
    private final List<String> toolsNeeded;

    public SpawnedWorker(String id, String name, String dir,
                         AgentDna dna, List<String> toolsNeeded) {
        this.id = id;
        this.name = name;
        this.dir = dir;
        this.dna = dna;
        this.toolsNeeded = toolsNeeded;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDir() { return dir; }
    public AgentDna getDna() { return dna; }
    public List<String> getToolsNeeded() { return toolsNeeded; }
}
