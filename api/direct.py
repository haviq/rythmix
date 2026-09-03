import json
import re

import yt_dlp
from vercel import Request, Response


def handler(request: Request) -> Response:
    video_id = (request.args.get("v") or "").strip()
    if not re.fullmatch(r"[A-Za-z0-9_-]{6,20}", video_id):
        return Response(json.dumps({"error": "bad id"}), status_code=400)

    ydl_opts = {
        "format": "bestaudio/best",
        "quiet": True,
        "noplaylist": True,
        "socket_timeout": 20,
    }
    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(
                "https://www.youtube.com/watch?v=" + video_id, download=False
            )
            url = info.get("url")
            if not url:
                return Response(json.dumps({"error": "no stream url"}), status_code=502)
            return Response(json.dumps({"url": url}), content_type="application/json")
    except Exception as e:  # noqa: BLE001
        return Response(
            json.dumps({"error": str(e)}), status_code=502
        )
