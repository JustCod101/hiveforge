package com.hiveforge.trace;

import com.hiveforge.repository.WorkerTraceRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TraceService {

    private final WorkerTraceRepository traceRepo;

    public TraceService(WorkerTraceRepository traceRepo) {
        this.traceRepo = traceRepo;
    }

    public List<WorkerTrace> getTraceByWorkerId(String workerId) {
        return traceRepo.findByWorkerId(workerId);
    }
}
