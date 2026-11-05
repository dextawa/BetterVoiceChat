"""Point the test Velocity at the local Paper server (offline mode, no forwarding)."""
import re
import sys

path = sys.argv[1]
text = open(path, encoding="utf-8").read()
text = re.sub(r'(?m)^online-mode = .*$', 'online-mode = false', text)
text = re.sub(r'(?m)^player-info-forwarding-mode = .*$', 'player-info-forwarding-mode = "none"', text)
text = re.sub(r'(?ms)^\[servers\].*?(?=^\[forced-hosts\])',
              '[servers]\nlobby = "127.0.0.1:25601"\ntry = ["lobby"]\n\n', text)
text = re.sub(r'(?ms)^\[forced-hosts\].*?(?=^\[advanced\])', '[forced-hosts]\n\n', text)
open(path, "w", encoding="utf-8").write(text)
print("configured", path)
