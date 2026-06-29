MSIX Store logos
================

Put the PNG logo assets the AppxManifest.xml references in THIS folder. The
release workflow copies them into the package's Assets\ directory before packing.

Required (minimum), transparent PNG:

    StoreLogo.png            50 x 50
    Square150x150Logo.png   150 x 150
    Square44x44Logo.png      44 x 44

Recommended to also add the scaled variants the Store/Start menu use (same names
with .scale-100/.scale-200 etc., and the Square44x44 target-size variants). The
easiest way to generate the full set is Microsoft's "Image type" asset generator
in Visual Studio, or the MSIX Packaging Tool, from one high-resolution square
source image.

Until these exist, the guarded "Build MSIX" step in the release workflow is
skipped, so normal (.exe / .jar) builds are unaffected.

The base set (StoreLogo / Square150x150Logo / Square44x44Logo + their
.scale-200 variants) was rendered from the app's icon vector source at each
size (crisp at any scale), e.g. with ImageMagick:

    magick -background none -density 384 phonalyser.svg -resize SxS <name>.png
