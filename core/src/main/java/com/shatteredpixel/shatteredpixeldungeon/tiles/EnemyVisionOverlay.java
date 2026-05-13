/*
 * Pixel Dungeon
 * Copyright (C) 2012-2015 Oleg Dolya
 *
 * Shattered Pixel Dungeon
 * Copyright (C) 2014-2024 Evan Debenham
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package com.shatteredpixel.shatteredpixeldungeon.tiles;

import com.badlogic.gdx.graphics.Pixmap;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.watabou.gltextures.TextureCache;
import com.watabou.noosa.Image;
import com.watabou.noosa.NoosaScript;
import com.watabou.noosa.NoosaScriptNoLighting;

/**
 * Renders a semi-transparent red overlay on every floor tile that falls within
 * a visible enemy mob's field of view.  The overlay is only shown when the
 * "Enemy Vision Highlight" setting is enabled, giving players a clear visual
 * cue about the danger zones of enemies they can currently see.
 *
 * Quality attribute (ISO/IEC 25010): Operability → Learnability / Ease of Use.
 * Players no longer need to guess or experiment to learn which tiles are
 * observed by a nearby enemy; the information is surfaced directly in the UI.
 */
public class EnemyVisionOverlay extends Image {

	// ARGB 0x66FF0000: semi-transparent red (~40 % opacity)
	private static final int DANGER_ARGB = 0x66FF0000;
	// ARGB 0x00000000: fully transparent – used to clear tiles
	private static final int CLEAR_ARGB  = 0x00000000;

	private final int mapWidth;
	private final int mapHeight;

	// Power-of-two texture dimensions (required by OpenGL ES)
	private final int texWidth;
	private final int texHeight;

	private final String texKey;

	// Set to true whenever the overlay needs to be redrawn
	private volatile boolean dirty = true;

	public EnemyVisionOverlay(int mapWidth, int mapHeight) {
		super();

		this.mapWidth  = mapWidth;
		this.mapHeight = mapHeight;

		// Round up to nearest power of two
		int tw = 1;
		while (tw < mapWidth)  tw <<= 1;
		int th = 1;
		while (th < mapHeight) th <<= 1;
		texWidth  = tw;
		texHeight = th;

		texKey = "EnemyVisionOverlay_" + texWidth + "x" + texHeight;

		float tileSize = DungeonTilemap.SIZE;
		width  = texWidth  * tileSize;
		height = texHeight * tileSize;

		// Create (or reuse) a transparent backing texture
		texture(TextureCache.create(texKey, texWidth, texHeight));
		texture.bitmap.setColor(argbToGdxRgba(CLEAR_ARGB));
		texture.bitmap.fill();
		texture.bind();

		scale.set(tileSize, tileSize);
	}

	/** Call this to schedule a full redraw of the overlay on the next frame. */
	public void markDirty() {
		dirty = true;
	}

	// -------------------------------------------------------------------------
	// Rendering
	// -------------------------------------------------------------------------

	private void rebuildTexture() {
		Pixmap pix = texture.bitmap;
		pix.setBlending(Pixmap.Blending.None);

		// Start with a fully transparent canvas
		pix.setColor(argbToGdxRgba(CLEAR_ARGB));
		pix.fillRectangle(0, 0, texWidth, texHeight);

		if (SPDSettings.enemyVisionHighlight()
				&& Dungeon.level != null
				&& Dungeon.level.mobs != null
				&& Dungeon.level.heroFOV != null) {

			for (Mob mob : Dungeon.level.mobs) {

				// Only process enemy mobs that the hero can currently see
				if (mob.alignment != Char.Alignment.ENEMY) continue;
				if (mob.pos < 0 || mob.pos >= Dungeon.level.length()) continue;
				if (!Dungeon.level.heroFOV[mob.pos]) continue;

				// Ensure the mob's field-of-view array is populated
				if (mob.fieldOfView == null
						|| mob.fieldOfView.length != Dungeon.level.length()) {
					mob.fieldOfView = new boolean[Dungeon.level.length()];
					Dungeon.level.updateFieldOfView(mob, mob.fieldOfView);
				}

				// Paint red on every cell the mob can see that the hero can also see
				pix.setColor(argbToGdxRgba(DANGER_ARGB));
				for (int cell = 0; cell < Dungeon.level.length(); cell++) {
					if (mob.fieldOfView[cell] && Dungeon.level.heroFOV[cell]) {
						int tx = cell % mapWidth;
						int ty = cell / mapWidth;
						pix.fillRectangle(tx, ty, 1, 1);
					}
				}
			}
		}

		texture.bitmap(pix);
		dirty = false;
	}

	/**
	 * Converts an ARGB8888 integer (as used throughout the Shattered PD codebase)
	 * to the RGBA8888 integer expected by {@link Pixmap#setColor(int)}.
	 *
	 * <p>The transformation is a left-rotation by 8 bits:
	 * {@code RGBA = (ARGB << 8) | (ARGB >>> 24)}.
	 */
	private static int argbToGdxRgba(int argb) {
		return (argb << 8) | (argb >>> 24);
	}

	@Override
	protected NoosaScript script() {
		return NoosaScriptNoLighting.get();
	}

	@Override
	public void draw() {
		if (dirty) {
			rebuildTexture();
		}
		super.draw();
	}

	@Override
	public void destroy() {
		super.destroy();
		TextureCache.remove(texKey);
	}
}
