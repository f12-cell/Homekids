import requests
import json

def test():
    try:
        r = requests.get("http://localhost:8000/api/connected")
        print(f"Status: {r.status_code}")
        print(f"Data: {json.dumps(r.json(), indent=2)}")
    except Exception as e:
        print(f"Error: {e}")

if __name__ == "__main__":
    test()
