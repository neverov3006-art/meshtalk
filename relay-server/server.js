/**
 * MeshTalk relay server — minimal WebSocket router.
 *
 * What it does:
 *  - A phone connects and sends REGISTER {peerId} once, over the socket.
 *  - To send anything else, a phone sends any JSON frame that includes a `targetPeerId`
 *    field — e.g. {type: "ENVELOPE", targetPeerId, envelope} for a normal message, or
 *    {type: "ENVELOPE_CHUNK", targetPeerId, envelopeId, chunkIndex, ...} for one piece
 *    of a large voice-note/file being streamed in pieces (see the Android app's
 *    InternetTransport.kt). The server doesn't parse or care which — it just forwards
 *    the whole frame, verbatim, to targetPeerId's socket if they're connected. This
 *    keeps the server's protocol surface fixed even as the app's wire format evolves.
 *  - If the target isn't connected, the frame is held in a small in-memory queue and
 *    delivered the moment they reconnect (lost on server restart — see note below).
 *
 * What it deliberately does NOT do:
 *  - Decrypt anything, or even look inside `envelope`/chunk fields. They're opaque
 *    base64 blobs to this server; only the two devices holding the derived Double
 *    Ratchet message key (see CryptoManager / DoubleRatchet on the Android side) can
 *    read them. This server only ever sees peerId <-> peerId routing metadata, never
 *    message content — text, voice note, or file, all equally opaque.
 *
 * Known limitation: the pending-message queue is in-memory only. For production use,
 * back it with Redis or a small database so queued messages survive a server restart.
 * That's a reasonable next step, not done here to keep this a runnable single-file server.
 *
 * Run:
 *   npm install ws
 *   node server.js
 *
 * Then point the app's InternetTransport at wss://your-domain/  (put this behind a
 * reverse proxy like nginx/Caddy for TLS in production — this file speaks plain ws://
 * for local testing).
 */

const WebSocket = require('ws');

const PORT = process.env.PORT || 8080;
const wss = new WebSocket.Server({ port: PORT });

// peerId -> WebSocket
const connectedPeers = new Map();

// peerId -> array of queued raw message strings, waiting for that peer to come online
const pendingQueues = new Map();
const MAX_QUEUE_PER_PEER = 500; // basic backpressure so one offline peer can't grow unbounded
// (raised from the original 200 since a chunked file transfer can legitimately queue many
// small frames for one recipient while they're briefly offline)

function enqueue(targetPeerId, rawMessage) {
    const queue = pendingQueues.get(targetPeerId) ?? [];
    queue.push(rawMessage);
    if (queue.length > MAX_QUEUE_PER_PEER) queue.shift(); // drop oldest first
    pendingQueues.set(targetPeerId, queue);
}

function flushQueue(peerId, socket) {
    const queue = pendingQueues.get(peerId);
    if (!queue || queue.length === 0) return;
    for (const rawMessage of queue) {
        socket.send(rawMessage);
    }
    pendingQueues.delete(peerId);
}

wss.on('connection', (socket) => {
    let registeredPeerId = null;

    socket.on('message', (data) => {
        let parsed;
        try {
            parsed = JSON.parse(data.toString());
        } catch {
            return; // ignore malformed frames
        }

        if (parsed.type === 'REGISTER' && typeof parsed.peerId === 'string') {
            registeredPeerId = parsed.peerId;
            connectedPeers.set(registeredPeerId, socket);
            flushQueue(registeredPeerId, socket);
            return;
        }

        // Generic forward: any frame with a targetPeerId gets routed there as-is,
        // whatever its `type` is (ENVELOPE, ENVELOPE_CHUNK, or anything added later).
        if (typeof parsed.targetPeerId === 'string') {
            const targetSocket = connectedPeers.get(parsed.targetPeerId);
            const outgoing = data.toString();
            if (targetSocket && targetSocket.readyState === WebSocket.OPEN) {
                targetSocket.send(outgoing);
            } else {
                enqueue(parsed.targetPeerId, outgoing);
            }
            return;
        }
    });

    socket.on('close', () => {
        if (registeredPeerId && connectedPeers.get(registeredPeerId) === socket) {
            connectedPeers.delete(registeredPeerId);
        }
    });
});

console.log(`MeshTalk relay server listening on ws://0.0.0.0:${PORT}`);
