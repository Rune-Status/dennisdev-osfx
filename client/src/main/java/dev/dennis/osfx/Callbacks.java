package dev.dennis.osfx;

import dev.dennis.osfx.api.AbstractFont;
import dev.dennis.osfx.api.Sprite;

public interface Callbacks {
    void onFrameStart();

    void onFrameEnd();

    boolean drawSprite(Sprite sprite, int x, int y);

    boolean drawSprite(Sprite sprite, int x, int y, int alpha);

    boolean drawSprite(Sprite sprite, int x, int y, int width, int height);

    boolean drawSprite(Sprite sprite, int x, int y, int width, int height, int alpha);

    boolean drawGlyph(AbstractFont font, byte[] glyph, int x, int y, int width, int height, int rgb);

    boolean drawGlyph(AbstractFont font, byte[] glyph, int x, int y, int width, int height, int rgb, int alpha);
}
