from flask import Flask, request, jsonify
import requests
import os

app = Flask(__name__)

BREVO_API_KEY = os.environ.get("BREVO_API_KEY")  # set this as an environment variable, never hardcode

@app.route('/send-photo', methods=['POST'])
def send_photo():
    data = request.json
    recipient_email = data.get('email')
    image_base64 = data.get('image_base64')  # Android sends the photo already base64-encoded

    print(f"[send-photo] Received request for: {recipient_email}")

    if not recipient_email or not image_base64:
        print("[send-photo] Missing email or image, rejecting")
        return jsonify({"error": "Missing email or image"}), 400

    payload = {
        "sender": {"name": "Photobooth", "email": "andreasoeh@outlook.dk"},
        "to": [{"email": recipient_email}],
        "subject": "Your Photobooth Picture!",
        "htmlContent": "<html><body><p>Here's your photo from the booth!</p></body></html>",
        "attachment": [
            {"content": image_base64, "name": "photo.jpg"}
        ]
    }

    headers = {
        "api-key": BREVO_API_KEY,
        "Content-Type": "application/json"
    }

    print(f"[send-photo] Sending to Brevo API for: {recipient_email}")
    response = requests.post(
        "https://api.brevo.com/v3/smtp/email",
        json=payload,
        headers=headers
    )

    if response.status_code < 300:
        print(f"[send-photo] Brevo accepted the email for: {recipient_email} (status {response.status_code})")
    else:
        print(f"[send-photo] Brevo rejected the email for: {recipient_email} (status {response.status_code}) - {response.text}")

    return jsonify({"status": response.status_code, "result": response.json()}), response.status_code

if __name__ == '__main__':
    app.run(host='127.0.0.1', port=5000)