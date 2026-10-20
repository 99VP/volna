# Volna watch rooms

Tiny room server for synchronized watching. Standard library only.

```bash
python3 server.py --host 127.0.0.1 --port 8787
```

For the Android app, the server must be reachable over **HTTPS** because the app
does not permit cleartext HTTP. A quick option is an SSH tunnel:

```bash
ssh -R 80:localhost:8787 nokey@localhost.run
```

The command prints an HTTPS URL. Put that URL into Volna → Settings → Watch
server, for example `https://abc123.lhr.life`.

Create a room in the Watch screen, then share the generated link. Guests open
the link, and the app polls the room state about once per second.

Rooms are in memory and disappear after 6 hours or when the server restarts.
Only the video URL, playback position, and play/pause state are shared.
