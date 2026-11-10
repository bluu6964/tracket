// Colour emoji from COLR fonts (v0 layers and v1 paint graphs), drawn with FreeType's COLR API into a premultiplied
// RGBA picture. Android's CBDT emoji font is handled by text_engine.cpp itself; this is for devices whose emoji font
// is a COLR font instead. Needs FreeType 2.13+ (FT_Get_Color_Glyph_Paint).
#pragma once

#include <cstdint>
#include <vector>

#include <ft2build.h>
#include FT_FREETYPE_H

namespace am {
namespace text {

struct ColrImage {
    int w = 0, h = 0;      // picture size in pixels
    int bx = 0, by = 0;    // left bearing, top bearing (pixels from the pen position / baseline, y up)
    std::vector<uint8_t> px;  // premultiplied RGBA8888, w*h*4
};

/** True when the face has COLR colour data we can try (a scalable face with a colour palette). */
bool colrFaceUsable(FT_Face face);

/**
 * Draws colour glyph [gid] at [ppem] pixels per em. Returns false when the glyph has no colour description,
 * is empty, or the paint graph is unusable. Changes the size of [face]: the caller must reset its size cache.
 */
bool colrRender(FT_Library lib, FT_Face face, uint32_t gid, int ppem, ColrImage& out);

}  // namespace text
}  // namespace am
