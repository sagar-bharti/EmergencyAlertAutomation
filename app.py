import os
import tempfile
from flask import Flask, request, jsonify
from faster_whisper import WhisperModel
from twilio.rest import Client

app = Flask(__name__)

print("Loading model...")
model = WhisperModel("tiny", device="cpu", compute_type="int8")
print("Model ready!")

# Twilio config
TWILIO_SID   = 'AC83bdbc7030aaf272835d920ed5f52bb4'
TWILIO_TOKEN = 'e9ff220dd5d983d0a3a69f770783443c'
TWILIO_FROM  = '+12182979590'

# Emergency contacts — jinhe SMS jaayega
EMERGENCY_CONTACTS = ['+918085055261', '+917987504890']

EMERGENCY_KEYWORDS = [
    "help", "help me", "save me", "emergency",
    "bachao", "madad", "bachao mujhe", "madad karo",
    "help karo", "bachaa lo", "koi hai", "please help",
    "बचाओ", "मदद", "मदद करो"
]

def detect_keywords(text):
    if not text:
        return False, []
    text_lower = text.lower().strip()
    found = [kw for kw in EMERGENCY_KEYWORDS if kw in text_lower]
    return len(found) > 0, found

def send_twilio_sms(message):
    try:
        client = Client(TWILIO_SID, TWILIO_TOKEN)
        for number in EMERGENCY_CONTACTS:
            client.messages.create(
                body=message,
                from_=TWILIO_FROM,
                to=number
            )
            print(f"✅ SMS sent to {number}")
    except Exception as e:
        print(f"❌ Twilio error: {e}")

@app.route('/health', methods=['GET'])
def health():
    return jsonify({"status": "ok", "model": "faster-whisper-tiny"})

@app.route('/analyze', methods=['POST'])
def analyze():
    try:
        if 'audio' not in request.files:
            return jsonify({"emergency": False, "error": "no_audio_file"}), 400

        audio_file = request.files['audio']
        audio_bytes = audio_file.read()

        if len(audio_bytes) == 0:
            return jsonify({"emergency": False, "error": "empty_audio"}), 400

        print(f"Audio received: {len(audio_bytes)} bytes")

        with tempfile.NamedTemporaryFile(suffix=".m4a", delete=False) as tmp:
            tmp.write(audio_bytes)
            tmp_path = tmp.name

        try:
            segments, info = model.transcribe(
                tmp_path,
                language=None,
                beam_size=1
            )
            transcript = " ".join([s.text for s in segments]).strip()
            detected_lang = info.language

            print(f"Transcript: '{transcript}' | Language: {detected_lang}")

            is_emergency, keywords_found = detect_keywords(transcript)

            # Emergency detect hote hi auto SMS
            # if is_emergency:
            #     msg = f"🚨 EMERGENCY ALERT!\nMujhe madad chahiye!\nSuna: {transcript}\n- EmergencyApp"
            #     send_twilio_sms(msg)
            #     print("🚨 Emergency triggered! SMS sent.")

            if is_emergency:
              msg = f"🚨 EMERGENCY ALERT!\nMujhe madad chahiye!\nSuna: {transcript}\n- EmergencyApp"
              send_twilio_sms(msg)
              print("🚨 Emergency triggered! SMS sent.")

            return jsonify({
                "emergency": is_emergency,
                "transcript": transcript,
                "language": detected_lang,
                "keywords_found": keywords_found
            })

        finally:
            os.unlink(tmp_path)

    except Exception as e:
        print(f"Error: {e}")
        return jsonify({"emergency": False, "error": str(e)}), 500

if __name__ == '__main__':
    port = int(os.environ.get('PORT', 5000))
    app.run(host='0.0.0.0', port=port, debug=False)
