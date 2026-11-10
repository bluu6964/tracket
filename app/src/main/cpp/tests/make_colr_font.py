#!/usr/bin/env python3
"""Builds the COLR v1 test font used by text_selftest (testColrEmoji): /tmp/am_colr_test.ttf
U+1F600 -> gradient ring + rotated H + green bar, U+1F601 -> radial gradient + composite. Needs fontTools and DejaVu Sans."""
from fontTools.ttLib import TTFont
from fontTools.colorLib.builder import buildCOLR, buildCPAL
from fontTools.ttLib.tables import otTables as ot

P = ot.PaintFormat
def solid(i, a=1.0): return {"Format": P.PaintSolid, "PaletteIndex": i, "Alpha": a}
def stops(l): return {"Extend": "pad", "ColorStop": [{"StopOffset": o, "PaletteIndex": i, "Alpha": 1.0} for o, i in l]}
def lin(p0, p1, p2, s): return {"Format": P.PaintLinearGradient, "ColorLine": stops(s), "x0": p0[0], "y0": p0[1], "x1": p1[0], "y1": p1[1], "x2": p2[0], "y2": p2[1]}
def rad(c0, r0, c1, r1, s): return {"Format": P.PaintRadialGradient, "ColorLine": stops(s), "x0": c0[0], "y0": c0[1], "r0": r0, "x1": c1[0], "y1": c1[1], "r1": r1}
def glyph(g, p): return {"Format": P.PaintGlyph, "Glyph": g, "Paint": p}

f = TTFont('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf')
cg = {
    'A': {"Format": P.PaintColrLayers, "Layers": [
        glyph('O', lin((0, 0), (0, 1500), (500, 0), [(0, 0), (1, 1)])),
        {"Format": P.PaintRotateAroundCenter, "Paint": glyph('H', solid(2, 0.8)), "angle": 20, "centerX": 700, "centerY": 700},
        {"Format": P.PaintTranslate, "Paint": glyph('I', solid(3)), "dx": 300, "dy": 100}]},
    'C': {"Format": P.PaintColrLayers, "Layers": [
        glyph('O', rad((700, 700), 0, (700, 700), 800, [(0, 2), (1, 0)])),
        {"Format": P.PaintComposite, "CompositeMode": "src_in", "SourcePaint": glyph('H', solid(1)), "BackdropPaint": glyph('O', solid(3))}]},
}
f['CPAL'] = buildCPAL([[(1, 0, 0, 1), (0, 0, 1, 1), (1, 1, 0, 1), (0, 0.6, 0, 1)]])
f['COLR'] = buildCOLR(cg, version=1, glyphMap=f.getReverseGlyphMap())
cmap = None
for t in f['cmap'].tables:
    if t.platformID == 3 and t.platEncID == 10: cmap = t
if cmap is None:
    from fontTools.ttLib.tables._c_m_a_p import CmapSubtable
    cmap = CmapSubtable.newSubtable(12); cmap.platformID = 3; cmap.platEncID = 10; cmap.language = 0
    cmap.cmap = dict(f.getBestCmap()); f['cmap'].tables.append(cmap)
cmap.cmap[0x1F600] = 'A'
cmap.cmap[0x1F601] = 'C'
f.save('/tmp/am_colr_test.ttf')
print('wrote /tmp/am_colr_test.ttf')
