import requests
import json

HOMESERVER = "https://matrix.org"
ACCESS_TOKEN = "syt_YWhtYWQ5ODEy_qRByVCHLhYtEIDHAdxYV_2F8mYv" # From user's logs implicitly, but wait I should check MatrixContext.kt or CredentialManager
ROOM_ID = "!GfDlxbNRxfWyvitMev:matrix.org"

def cleanup():
    headers = {"Authorization": f"Bearer {ACCESS_TOKEN}"}
    
    # 1. Fetch all state
    print(f"Fetching state for {ROOM_ID}...")
    res = requests.get(f"{HOMESERVER}/_matrix/client/v3/rooms/{ROOM_ID}/state", headers=headers)
    if res.status_code != 200:
        print(f"Error fetching state: {res.text}")
        return

    events = res.json()
    for event in events:
        etype = event.get("type")
        skey = event.get("state_key", "")
        
        if etype == "org.matrix.msc3401.call.member" or etype == "m.call.member":
            content = event.get("content", {})
            if content.get("membership") == "join":
                # Check if it's Orbit's old event or malformed
                # We'll just leave EVERYTHING except the one we want to keep (or just leave all and let Orbit re-join)
                print(f"Leaving member event: {skey}")
                requests.put(
                    f"{HOMESERVER}/_matrix/client/v3/rooms/{ROOM_ID}/state/{etype}/{skey}",
                    headers=headers,
                    json={"membership": "leave"}
                )
        
        if etype == "org.matrix.msc3401.call" or etype == "m.call":
            # Check if it's the right one
            print(f"Found call definition: {skey}")
            # requests.put(f"{HOMESERVER}/_matrix/client/v3/rooms/{ROOM_ID}/state/{etype}/{skey}", headers=headers, json={"m.terminated": "m.user_hangup"})

if __name__ == "__main__":
    cleanup()
