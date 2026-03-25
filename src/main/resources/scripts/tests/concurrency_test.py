import requests
import threading
import sys
import json
import time

BASE_URL = "http://localhost:8080/api/v1"
EMAIL = "testuser@example.com"
PASSWORD = "password123" # Adjust if needed

def login():
    try:
        response = requests.post(f"{BASE_URL}/auth/login", json={
            "email": EMAIL,
            "password": PASSWORD
        })
        response.raise_for_status()
        data = response.json()
        print(f"Login success. RT: {data['data']['refreshToken'][:10]}...")
        return data['data']['refreshToken']
    except Exception as e:
        print(f"Login failed: {e}")
        try:
            print(f"Response: {response.text}")
        except:
            pass
        sys.exit(1)

def refresh_token(token, result_list, index):
    try:
        # print(f"Thread {index} sending request...")
        response = requests.post(f"{BASE_URL}/auth/refresh", json={
            "refreshToken": token
        })
        status = response.status_code
        try:
            body = response.json()
        except:
            body = response.text
        
        result_list.append({
            "thread": index,
            "status": status,
            "body": body
        })
    except Exception as e:
        result_list.append({
            "thread": index,
            "status": "error",
            "error": str(e)
        })

def main():
    print("--- Starting Concurrency Test ---")
    token = login()
    
    threads = []
    results = []
    
    # Create 2 threads
    t1 = threading.Thread(target=refresh_token, args=(token, results, 1))
    t2 = threading.Thread(target=refresh_token, args=(token, results, 2))
    
    threads.append(t1)
    threads.append(t2)
    
    # Start threads as close as possible
    for t in threads:
        t.start()
        
    # Wait for completion
    for t in threads:
        t.join()
        
    print("\n--- Results ---")
    success_count = 0
    fail_count = 0
    
    for res in results:
        print(f"Thread {res['thread']}: Status {res['status']}")
        
        if res['status'] == 200:
            success_count += 1
            print("  -> SUCCESS (Got new token)")
        elif res['status'] == 401 or res['status'] == 403 or res['status'] == 409 or res['status'] == 500:
             # 500 can happen if locking timeout, 409 conflict, 401 unauth
             fail_count += 1
             print(f"  -> FAILED/BLOCKED: {res.get('body', {}).get('message', res.get('body'))}")
        else:
            print(f"  -> UNEXPECTED STATUS: {res.get('body')}")

    if success_count == 1 and fail_count == 1:
        print("\n✅ TEST PASSED: Race condition handled correctly (1 success, 1 fail).")
    elif success_count == 2:
        print("\n❌ TEST FAILED: Race condition detected (Both succeeded).")
    else:
        print("\n⚠️ TEST FAILED: Unexpected outcome (Both failed or other error).")

if __name__ == "__main__":
    main()
