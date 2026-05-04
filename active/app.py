import cv2
import mediapipe as mp
import numpy as np
from flask import Flask, request, jsonify

app = Flask(__name__)

# Initialize Google MediaPipe Face Mesh
mp_face_mesh = mp.solutions.face_mesh
face_mesh = mp_face_mesh.FaceMesh(
    max_num_faces=1,             # We only care about the voter
    refine_landmarks=True,       # Needed for precise eye/blink tracking
    min_detection_confidence=0.5,
    min_tracking_confidence=0.5
)

def calculate_distance(point1, point2):
    """Calculates 2D Euclidean distance between two MediaPipe landmarks"""
    return np.sqrt((point1.x - point2.x)**2 + (point1.y - point2.y)**2)

@app.route('/analyze-frame', methods=['POST'])
def analyze_frame():
    # 1. Grab the metadata sent by Spring Boot
    session_id = request.headers.get('X-Session-Id')
    challenge = request.headers.get('X-Challenge')

    # 2. Decode the raw binary JPEG from the ESP32
    img_bytes = np.frombuffer(request.data, np.uint8)
    img = cv2.imdecode(img_bytes, cv2.IMREAD_COLOR)

    if img is None:
        return jsonify({"passed": False, "error": "Invalid image data"})

    # 3. THE GOLDEN RULE: Convert ESP32 BGR to AI RGB!
    rgb_image = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)

    # 4. Process the image through MediaPipe
    results = face_mesh.process(rgb_image)

    # If no face is found, they definitely fail the challenge
    if not results.multi_face_landmarks:
        return jsonify({"passed": False, "message": "No face detected"})

    landmarks = results.multi_face_landmarks[0].landmark
    passed = False

    # ---------------------------------------------------------
    # 5. THE AI CHALLENGE LOGIC (3D Geometry Math)
    # ---------------------------------------------------------

    # Get key points (MediaPipe maps 468 points on the face)
    nose_tip = landmarks[1]
    left_cheek = landmarks[234]  # Far left side of face
    right_cheek = landmarks[454] # Far right side of face

    # Calculate relative distances from nose to edges of the face
    left_dist = calculate_distance(nose_tip, left_cheek)
    right_dist = calculate_distance(nose_tip, right_cheek)

    if challenge == "TURN_HEAD_LEFT":
        # If looking left, the nose gets much closer to the left cheek (in 2D space)
        # We use a ratio to ensure it works no matter how close they are to the camera
        if left_dist < (right_dist * 0.4):
            passed = True

    elif challenge == "TURN_HEAD_RIGHT":
        if right_dist < (left_dist * 0.4):
            passed = True

    elif challenge == "SMILE":
        top_lip = landmarks[13]
        bottom_lip = landmarks[14]
        mouth_open_dist = calculate_distance(top_lip, bottom_lip)
        # If the distance between lips is significant, they are smiling/mouth open
        if mouth_open_dist > 0.05:
            passed = True

    elif challenge == "BLINK":
        # Left eye top and bottom eyelids
        left_eye_top = landmarks[159]
        left_eye_bottom = landmarks[145]
        eye_open_dist = calculate_distance(left_eye_top, left_eye_bottom)
        # If the eyelids touch, the distance approaches 0
        if eye_open_dist < 0.015:
            passed = True

    # 6. Return the verdict to Spring Boot
    if passed:
        print(f"[{session_id}] SUCCESS! Passed challenge: {challenge}")

    return jsonify({"passed": passed})

if __name__ == '__main__':
    # Run the NEW active liveness server on port 5001
    print("🚀 Python MediaPipe Active Liveness Booting on Port 5001...")
    app.run(host='0.0.0.0', port=5001)