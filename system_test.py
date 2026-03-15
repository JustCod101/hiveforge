import urllib.request
import urllib.parse
import urllib.error
import json
import sys
import time

BASE_URL = "http://localhost:8080/api/v1/hive"

def test_execute_hive(query):
    print(f"\n[1] Starting Hive Task with query: '{query}'")
    url = f"{BASE_URL}/execute?query={urllib.parse.quote(query)}"
    
    task_id = None
    
    try:
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req, timeout=600) as response:
            if response.getcode() != 200:
                print(f"Failed to connect to SSE endpoint: {response.getcode()}")
                return None
                
            while True:
                line = response.readline()
                if not line:
                    break # End of stream
                    
                decoded_line = line.decode('utf-8')
                if decoded_line.startswith('data:'):
                    data_str = decoded_line[5:].strip()
                    if not data_str:
                        continue
                        
                    try:
                        data = json.loads(data_str)
                        event_type = data.get('type')
                        message = data.get('message', '')
                        
                        if event_type == 'heartbeat':
                            continue # Skip heartbeat logs
                            
                        event_type_str = event_type if event_type else 'unknown'
                        print(f"  -> Event: {event_type_str:15} | Message: {message}")
                        
                        if event_type == 'final_report':
                            task_id = data.get('taskId')
                            print("\n=== Final Report ===")
                            print(data.get('report'))
                            print("====================")
                        elif event_type == 'hive_error':
                            print(f"\nHive execution failed! Error: {message}")
                            break
                    except json.JSONDecodeError:
                        pass # Sometimes empty or malformed data in SSE
    except urllib.error.URLError as e:
        print(f"Request failed: {e.reason}")
        
    return task_id

def make_get_request(url):
    try:
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req, timeout=5) as response:
            if response.getcode() == 200:
                return json.loads(response.read().decode('utf-8'))
            else:
                print(f"  Failed: HTTP {response.getcode()}")
                return None
    except urllib.error.URLError as e:
        print(f"  Error: {e.reason}")
        return None

def test_get_task(task_id):
    print(f"\n[2] Fetching Task Info for Task ID: {task_id}")
    url = f"{BASE_URL}/{task_id}"
    data = make_get_request(url)
    if data:
        print(f"  Task ID: {data.get('id')}")
        print(f"  Status: {data.get('status')}")
        print(f"  Workers: {data.get('workerCount')}")
        print(f"  Total Tokens: {data.get('totalTokens')}")

def test_get_workers(task_id):
    print(f"\n[3] Fetching Workers for Task ID: {task_id}")
    url = f"{BASE_URL}/{task_id}/workers"
    workers = make_get_request(url)
    worker_ids = []
    if workers is not None:
        print(f"  Found {len(workers)} workers.")
        for w in workers:
            w_id = w.get('id')
            w_name = w.get('worker_name')
            w_status = w.get('status')
            print(f"  - Worker ID: {w_id} | Name: {w_name} | Status: {w_status}")
            if w_id:
                worker_ids.append(w_id)
    return worker_ids

def test_get_worker_trace(worker_id):
    print(f"\n[4] Fetching Trace for Worker ID: {worker_id}")
    url = f"{BASE_URL}/workers/{worker_id}/trace"
    traces = make_get_request(url)
    if traces is not None:
        print(f"  Found {len(traces)} trace steps.")
        for t in traces:
            step_idx = t.get('stepIndex')
            step_type = t.get('stepType')
            tool_name = t.get('toolName', '')
            print(f"    Step {step_idx}: [{step_type}] {tool_name}")

def test_get_worker_dna(worker_id):
    print(f"\n[5] Fetching DNA for Worker ID: {worker_id}")
    url = f"{BASE_URL}/workers/{worker_id}/dna"
    dna = make_get_request(url)
    if dna:
        print(f"  SOUL.md size: {len(dna.get('SOUL.md', ''))} chars")
        print(f"  AGENTS.md size: {len(dna.get('AGENTS.md', ''))} chars")
        print(f"  TASK.md size: {len(dna.get('TASK.md', ''))} chars")

def test_get_history():
    print(f"\n[6] Fetching Task History")
    url = f"{BASE_URL}/history?page=0&size=5"
    history = make_get_request(url)
    if history is not None:
        print(f"  Found {len(history)} recent tasks.")
        for task in history:
            print(f"  - Task: {task.get('id')} | Status: {task.get('status')}")

if __name__ == "__main__":
    print("==================================================")
    print("   HiveForge System End-to-End Test Script")
    print("==================================================")
    
    # 1. Check if the Spring Boot server is running
    print("Checking if HiveForge server is running...")
    try:
        urllib.request.urlopen("http://localhost:8080/actuator/health", timeout=3)
        print("Server is up and running!\n")
    except (urllib.error.URLError, TimeoutError, ConnectionRefusedError):
        print("Error: Spring Boot server is not accessible at http://localhost:8080")
        print("Please start the HiveForgeApplication first.")
        sys.exit(1)

    # 2. Test Execution (SSE)
    query = "分析美股见顶了吗？"
    task_id = test_execute_hive(query)
    
    if task_id:
        # 3. Test Task Query
        test_get_task(task_id)
        
        # 4. Test Workers Query
        worker_ids = test_get_workers(task_id)
        
        # 5. Test Traces and DNA for each Worker
        for w_id in worker_ids:
            test_get_worker_trace(w_id)
            test_get_worker_dna(w_id)
            
    # 6. Test History Query
    test_get_history()
    
    print("\n==================================================")
    print("System test script execution finished.")
    print("==================================================")
