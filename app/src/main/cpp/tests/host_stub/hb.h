// Host-test stand-in for <hb.h>: only what text_engine.cpp uses (the real header comes with HarfBuzz).
// The functions are resolved from the system libharfbuzz.so.0.
#pragma once
#include <cstdint>
extern "C" {
typedef struct hb_blob_t hb_blob_t;
typedef struct hb_face_t hb_face_t;
typedef struct hb_font_t hb_font_t;
typedef struct hb_buffer_t hb_buffer_t;
typedef struct hb_feature_t hb_feature_t;
typedef uint32_t hb_codepoint_t;
typedef int32_t hb_position_t;
typedef uint32_t hb_mask_t;
typedef uint32_t hb_tag_t;
typedef enum { HB_DIRECTION_INVALID = 0, HB_DIRECTION_LTR = 4, HB_DIRECTION_RTL, HB_DIRECTION_TTB, HB_DIRECTION_BTT } hb_direction_t;
typedef struct { hb_codepoint_t codepoint; hb_mask_t mask; uint32_t cluster; uint32_t var1; uint32_t var2; } hb_glyph_info_t;
typedef struct { hb_position_t x_advance, y_advance, x_offset, y_offset; uint32_t var; } hb_glyph_position_t;
typedef struct { hb_tag_t tag; float value; } hb_variation_t;
hb_blob_t* hb_blob_create_from_file(const char* file_name);
void hb_blob_destroy(hb_blob_t*);
hb_face_t* hb_face_create(hb_blob_t*, unsigned int index);
void hb_face_destroy(hb_face_t*);
hb_font_t* hb_font_create(hb_face_t*);
void hb_font_destroy(hb_font_t*);
void hb_font_set_scale(hb_font_t*, int x_scale, int y_scale);
void hb_font_set_variations(hb_font_t*, const hb_variation_t*, unsigned int variations_length);
hb_buffer_t* hb_buffer_create(void);
void hb_buffer_destroy(hb_buffer_t*);
void hb_buffer_clear_contents(hb_buffer_t*);
void hb_buffer_add_utf32(hb_buffer_t*, const uint32_t* text, int text_length, unsigned int item_offset, int item_length);
void hb_buffer_guess_segment_properties(hb_buffer_t*);
hb_direction_t hb_buffer_get_direction(hb_buffer_t*);
void hb_buffer_set_direction(hb_buffer_t*, hb_direction_t);
void hb_shape(hb_font_t*, hb_buffer_t*, const hb_feature_t* features, unsigned int num_features);
hb_glyph_info_t* hb_buffer_get_glyph_infos(hb_buffer_t*, unsigned int* length);
hb_glyph_position_t* hb_buffer_get_glyph_positions(hb_buffer_t*, unsigned int* length);
}
