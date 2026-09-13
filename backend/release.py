"""The Android build this server hands out to the apps that talk to it."""
import hashlib
import json
import threading
from pathlib import Path

# What the Android build writes beside every APK it produces: the package, its version code and
# name, and the file they describe. Publishing a version is copying that folder's two files.
METADATA = 'output-metadata.json'


class Release:
    """
    A folder holding a signed APK and the `output-metadata.json` written beside it by the
    build, as found in `android/app/build/outputs/apk/release/`. Both are read again on every
    request, so a version is published by copying the files, without restarting anything. The
    hash is the one costly read; it is kept while the file keeps its name, size and date.

    Anything missing or unreadable means there is nothing to hand out: an app asking is told
    nothing new, which is also the truth for a server nobody publishes on.
    """

    def __init__(self, folder=None):
        self.folder = Path(folder) if folder else None
        self.lock = threading.Lock()
        self.hashed = None

    def latest(self):
        """The published APK as `{code, name, size, sha256, file}`, or None."""
        if self.folder is None:
            return None
        try:
            metadata = json.loads((self.folder / METADATA).read_text(encoding='utf-8'))
            if metadata.get('applicationId') != 'fr.fonote':
                return None
            element = metadata['elements'][0]
            # The name is taken, never the path: the metadata only ever points beside itself.
            apk = self.folder / Path(element['outputFile']).name
            code, name = int(element['versionCode']), str(element['versionName'])
            stat = apk.stat()
            key = (apk.name, stat.st_size, stat.st_mtime_ns)
            with self.lock:
                if self.hashed is None or self.hashed[0] != key:
                    digest = hashlib.sha256()
                    with apk.open('rb') as source:
                        for chunk in iter(lambda: source.read(1 << 20), b''):
                            digest.update(chunk)
                    self.hashed = (key, digest.hexdigest())
                sha256 = self.hashed[1]
        except (OSError, ValueError, KeyError, IndexError, TypeError, AttributeError):
            return None
        return {'code': code, 'name': name, 'size': stat.st_size, 'sha256': sha256, 'file': apk}


def described(latest):
    """What `/v1/health` says of a release: everything but where it lies on this machine."""
    return None if latest is None else {k: v for k, v in latest.items() if k != 'file'}
