"""crforge game-art extractor: reads Supercell SC2 (.sc) and SCTX files from a game install.

Format knowledge comes from sc-workshop/SupercellFlash and SupercellTexture
(MIT, see schemas/LICENSE-sc-workshop.txt); this is an independent Python
implementation. Used only on game files from the user's own install.
"""
import os
import sys

_GEN = os.path.join(os.path.dirname(__file__), "gen")
if _GEN not in sys.path:
    sys.path.insert(0, _GEN)
