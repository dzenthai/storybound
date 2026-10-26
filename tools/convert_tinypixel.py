"""Convert the licensed CFF font to Minecraft's TrueType provider format.

Run with: uv run --with fonttools python tools/convert_tinypixel.py SOURCE.otf
The derivative uses a new family name as required by the reserved font name.
"""
from pathlib import Path
import sys
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.cu2quPen import Cu2QuPen
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib import TTFont

source = TTFont(sys.argv[1])
glyph_set = source.getGlyphSet()
glyphs = {}
for name in source.getGlyphOrder():
    pen = TTGlyphPen(glyph_set)
    glyph_set[name].draw(Cu2QuPen(pen, max_err=1, reverse_direction=True))
    glyphs[name] = pen.glyph()

license_text = source['name'].getDebugName(13)
builder = FontBuilder(source['head'].unitsPerEm, isTTF=True)
builder.setupGlyphOrder(source.getGlyphOrder())
builder.setupCharacterMap(source.getBestCmap())
builder.setupGlyf(glyphs)
builder.setupHorizontalMetrics(source['hmtx'].metrics)
builder.setupHorizontalHeader(ascent=source['hhea'].ascent, descent=source['hhea'].descent)
builder.setupNameTable({
    'familyName': 'Storybound Tiny', 'styleName': 'Regular',
    'uniqueFontIdentifier': 'Storybound Tiny Regular 1.0',
    'fullName': 'Storybound Tiny Regular', 'psName': 'StoryboundTiny-Regular',
    'version': 'Version 1.0', 'copyright': source['name'].getDebugName(0),
    'licenseDescription': license_text, 'licenseInfoURL': 'https://openfontlicense.org',
})
builder.setupOS2(sTypoAscender=source['OS/2'].sTypoAscender,
                 sTypoDescender=source['OS/2'].sTypoDescender,
                 usWinAscent=source['OS/2'].usWinAscent,
                 usWinDescent=source['OS/2'].usWinDescent)
builder.setupPost()
builder.setupMaxp()
root = Path(__file__).resolve().parents[1]
builder.save(root / 'src/main/resources/assets/storybound/font/storybound_tiny.ttf')
(root / 'src/main/resources/META-INF/licenses/TinyPixel-OFL.txt').write_text(license_text + '\n', encoding='utf-8')
assert builder.font.getBestCmap() == source.getBestCmap()
assert builder.font['hmtx'].metrics == source['hmtx'].metrics
print('Converted', len(glyphs), 'glyphs; character map and advances preserved.')
