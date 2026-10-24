"""Runs gallery-dl from the wheels next to this file, on the Python that ships with Glint."""

import os
import sys

here = os.path.dirname(os.path.abspath(__file__))
deps = os.path.join(here, "deps")
paths = [os.path.join(here, "gallery_dl.whl")]
paths += sorted(os.path.join(deps, name) for name in os.listdir(deps) if name.endswith(".whl"))
sys.path[0:0] = paths

# gallery-dl hands some videos to yt-dlp; Glint's yt-dlp is a zip it can import from.
ytdlp = os.environ.get("GLINT_YTDLP")
if ytdlp and os.path.isfile(ytdlp):
    sys.path.append(ytdlp)

from gallery_dl import main  # noqa: E402

sys.exit(main())
