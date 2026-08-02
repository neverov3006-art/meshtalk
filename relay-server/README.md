# MeshTalk relay server

Minimal WebSocket router so two phones can reach each other over the internet when
they're not in Bluetooth/WiFi range. See the comment block at the top of `server.js`
for exactly what it does and doesn't see (short version: routing metadata only, never
message plaintext — it forwards the same encrypted envelope the mesh transport uses).

## Local test run

```
npm install
npm start
```

Server listens on `ws://0.0.0.0:8080`. Point the app's `InternetTransport` at
`ws://<your-machine-ip>:8080` for local testing between two phones on different networks
(e.g. one on WiFi, one on cellular).

## Production deployment

1. Deploy `server.js` anywhere that runs Node (a small VPS, Fly.io, Render, etc.).
2. Put it behind a reverse proxy (nginx or Caddy) that terminates TLS, so the app can
   connect over `wss://` instead of plain `ws://`. Example Caddy config:
   ```
   relay.yourdomain.com {
       reverse_proxy localhost:8080
   }
   ```
3. Update `relayServerUrl` in `MeshTalkApp.kt` to your `wss://relay.yourdomain.com` URL.

## Known limitation

The pending-message queue (for offline recipients) is in-memory only — a server
restart drops anything queued. Fine for casual friend-group use; if you want delivery
guarantees across restarts, swap `pendingQueues` for Redis or a small SQLite table
keyed by peerId.
