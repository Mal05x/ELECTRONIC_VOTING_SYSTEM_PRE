import requests

# The URL of your local AI server
url = "http://127.0.0.1:5001/evaluate"
image_path = "test.jpg"

print(f"Sending '{image_path}' to the AI for liveness detection...")

try:
    with open(image_path, "rb") as image_file:
        # We package the image exactly how the ESP32 and Spring Boot will
        files = {"frame": (image_path, image_file, "image/jpeg")}
        response = requests.post(url, files=files)

    print("\n--- AI Response ---")
    print(f"Status Code: {response.status_code}")
    print(f"Result: {response.json()}")

except FileNotFoundError:
    print(f"[ERROR] Could not find '{image_path}'. Make sure you put an image in the folder!")
except requests.exceptions.ConnectionError:
    print("[ERROR] Could not connect to the server. Is uvicorn running in the other terminal?")
