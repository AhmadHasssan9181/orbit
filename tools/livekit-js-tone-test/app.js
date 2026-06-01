const { Room, RoomEvent, Track } = LivekitClient;

const urlInput = document.getElementById("urlInput");
const tokenInput = document.getElementById("tokenInput");
const connectBtn = document.getElementById("connectBtn");
const toneBtn = document.getElementById("toneBtn");
const micBtn = document.getElementById("micBtn");
const stopBtn = document.getElementById("stopBtn");
const clearBtn = document.getElementById("clearBtn");
const statusDot = document.getElementById("statusDot");
const statusText = document.getElementById("statusText");
const logOutput = document.getElementById("logOutput");

let room = null;
let audioContext = null;
let oscillator = null;
let gainNode = null;
let toneDestination = null;
let toneTrack = null;
let tonePublication = null;
let micEnabled = false;

function log(message, data) {
  const timestamp = new Date().toLocaleTimeString();
  const suffix = data === undefined ? "" : ` ${safeStringify(data)}`;
  logOutput.textContent += `[${timestamp}] ${message}${suffix}\n`;
  logOutput.scrollTop = logOutput.scrollHeight;
}

function safeStringify(value) {
  try {
    return JSON.stringify(value, null, 2);
  } catch (error) {
    return String(value);
  }
}

function setConnectedState(isConnected) {
  statusDot.classList.toggle("connected", isConnected);
  statusText.textContent = isConnected ? "Connected" : "Disconnected";
  connectBtn.disabled = isConnected;
  toneBtn.disabled = !isConnected;
  micBtn.disabled = !isConnected;
  stopBtn.disabled = !isConnected && !toneTrack && !audioContext && !micEnabled;
}

function publicationDetails(publication) {
  if (!publication) return null;
  const track = publication.track;
  return {
    publicationSid: publication.trackSid || publication.sid || "<none>",
    publicationName: publication.trackName || publication.name || "<none>",
    publicationSource: publication.source || "<none>",
    publicationMuted: publication.isMuted ?? publication.muted ?? "<unknown>",
    trackSid: track?.sid || "<none>",
    trackName: track?.name || track?.mediaStreamTrack?.label || "<none>",
    trackSource: track?.source || "<none>",
    trackMuted: track?.isMuted ?? track?.muted ?? "<unknown>",
    mediaTrackReadyState: track?.mediaStreamTrack?.readyState || "<none>",
  };
}

function requireRoom() {
  if (!room) {
    throw new Error("Connect to LiveKit first.");
  }
  return room;
}

async function connect() {
  const url = urlInput.value.trim();
  const token = tokenInput.value.trim();

  if (!url || !token) {
    log("ERROR missing LiveKit URL or token");
    return;
  }

  try {
    if (room) {
      await stop();
    }

    room = new Room();
    room
      .on(RoomEvent.Connected, () => log("room connected event"))
      .on(RoomEvent.Disconnected, (reason) => {
        log("room disconnected event", { reason });
        setConnectedState(false);
      })
      .on(RoomEvent.LocalTrackPublished, (publication) => {
        log("localTrackPublished event", publicationDetails(publication));
      })
      .on(RoomEvent.LocalTrackUnpublished, (publication) => {
        log("localTrackUnpublished event", publicationDetails(publication));
      });

    log("connecting", { url, jwtSub: decodeJwtSub(token) });
    await room.connect(url, token);

    setConnectedState(true);
    log("connected room", {
      roomName: room.name || room.roomInfo?.name || "<unknown>",
      localParticipantIdentity: room.localParticipant.identity,
      localParticipantSid: room.localParticipant.sid,
    });
  } catch (error) {
    log("ERROR connect failed", errorMessage(error));
    await stopLocalMediaOnly();
    room = null;
    setConnectedState(false);
  }
}

async function publishTone() {
  const activeRoom = requireRoom();

  try {
    await stopLocalMediaOnly();

    audioContext = new AudioContext({ sampleRate: 48000 });
    await audioContext.resume();

    oscillator = audioContext.createOscillator();
    gainNode = audioContext.createGain();
    toneDestination = audioContext.createMediaStreamDestination();

    oscillator.type = "sine";
    oscillator.frequency.value = 1000;
    gainNode.gain.value = 0.18;

    oscillator.connect(gainNode);
    gainNode.connect(toneDestination);
    oscillator.start();

    toneTrack = toneDestination.stream.getAudioTracks()[0];
    log("publishing 1 kHz tone", {
      audioContextSampleRate: audioContext.sampleRate,
      mediaTrackId: toneTrack.id,
      mediaTrackLabel: toneTrack.label,
      mediaTrackReadyState: toneTrack.readyState,
    });

    tonePublication = await activeRoom.localParticipant.publishTrack(toneTrack, {
      name: "audio",
      source: Track.Source.Microphone,
    });

    log("tone published", publicationDetails(tonePublication));
    setConnectedState(true);
  } catch (error) {
    log("ERROR publish tone failed", errorMessage(error));
  }
}

async function publishMicrophone() {
  const activeRoom = requireRoom();

  try {
    await stopLocalMediaOnly();

    log("publishing microphone");
    await activeRoom.localParticipant.setMicrophoneEnabled(true, undefined, {
      name: "audio",
      source: Track.Source.Microphone,
    });
    micEnabled = true;

    const publications = Array.from(activeRoom.localParticipant.audioTrackPublications.values());
    const latestPublication = publications[publications.length - 1];
    log("microphone published", publicationDetails(latestPublication));
    setConnectedState(true);
  } catch (error) {
    log("ERROR publish microphone failed", errorMessage(error));
  }
}

async function stopLocalMediaOnly() {
  if (room?.localParticipant && toneTrack) {
    try {
      room.localParticipant.unpublishTrack(toneTrack, true);
      log("tone track unpublished");
    } catch (error) {
      log("WARN tone unpublish failed", errorMessage(error));
    }
  }

  if (micEnabled && room?.localParticipant) {
    try {
      await room.localParticipant.setMicrophoneEnabled(false);
      log("microphone disabled");
    } catch (error) {
      log("WARN microphone disable failed", errorMessage(error));
    }
  }

  if (oscillator) {
    try {
      oscillator.stop();
    } catch (error) {
      // Oscillator may already be stopped.
    }
    oscillator.disconnect();
  }

  if (gainNode) {
    gainNode.disconnect();
  }

  if (toneTrack) {
    toneTrack.stop();
  }

  if (audioContext) {
    await audioContext.close().catch((error) => log("WARN audioContext close failed", errorMessage(error)));
  }

  audioContext = null;
  oscillator = null;
  gainNode = null;
  toneDestination = null;
  toneTrack = null;
  tonePublication = null;
  micEnabled = false;
}

async function stop() {
  await stopLocalMediaOnly();

  if (room) {
    try {
      await room.disconnect();
      log("room disconnected");
    } catch (error) {
      log("WARN room disconnect failed", errorMessage(error));
    }
  }

  room = null;
  setConnectedState(false);
}

function errorMessage(error) {
  return {
    name: error?.name || "<unknown>",
    message: error?.message || String(error),
    stack: error?.stack || "<none>",
  };
}

function decodeJwtSub(token) {
  try {
    const payload = token.split(".")[1];
    if (!payload) return "<not-a-jwt>";
    const normalized = payload.replace(/-/g, "+").replace(/_/g, "/");
    const padded = normalized.padEnd(normalized.length + ((4 - normalized.length % 4) % 4), "=");
    return JSON.parse(atob(padded)).sub || "<none>";
  } catch (error) {
    return `<decode-failed: ${error.message}>`;
  }
}

connectBtn.addEventListener("click", () => {
  connect();
});

toneBtn.addEventListener("click", () => {
  publishTone();
});

micBtn.addEventListener("click", () => {
  publishMicrophone();
});

stopBtn.addEventListener("click", () => {
  stop();
});

clearBtn.addEventListener("click", () => {
  logOutput.textContent = "";
});

window.addEventListener("beforeunload", () => {
  if (room) {
    room.disconnect();
  }
});

setConnectedState(false);
log("LiveKit JS tone test ready");
