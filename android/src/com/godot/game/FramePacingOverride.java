package com.godot.game;

import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the imported payload's {@code override.cfg} free of Swappy frame pacing.
 *
 * Godot 4.5.1 reads {@code display/window/frame_pacing/android/enable_frame_pacing} once while the
 * Vulkan driver initializes ({@code rendering_device_driver_vulkan.cpp:1578}), which happens before
 * any managed patch can run, so the compat DLL cannot change it. The engine does load
 * {@code override.cfg} from the main pack's own directory ({@code project_settings.cpp:649}), and the
 * payload is launched with {@code --main-pack <gameDir>/SlayTheSpire2.pck}, so this file is the only
 * pre-renderer channel we own.
 *
 * Why it is switched off: on this device {@code VK_GOOGLE_display_timing} is unavailable under both
 * the proprietary Adreno and the Turnip driver, so Swappy runs its Fallback path and times a fence
 * until SurfaceFlinger finishes composition (~44 ms) as if it were GPU cost, then sleeps for that
 * duration (~41 ms of a ~50 ms frame). The result is a self-sustaining 20-30 fps plateau after a
 * heavy frame. With pacing off the engine presents straight to {@code VK_PRESENT_MODE_FIFO_KHR}
 * (guaranteed by Vulkan, and Godot hard-falls back to it), and {@code Engine.max_fps} is still
 * enforced independently by {@code OS::add_frame_delay} ({@code core/os/os.cpp:684-717}).
 *
 * A payload that already states this key is left untouched, so editing override.cfg stays the
 * supported way to opt back out.
 */
public final class FramePacingOverride {
	private static final String TAG = "STS2FramePacing";
	private static final String FILE_NAME = "override.cfg";
	private static final String SECTION = "[display]";
	private static final String KEY = "window/frame_pacing/android/enable_frame_pacing";
	private static final String DESIRED_LINE = KEY + "=false";

	private FramePacingOverride() {
	}

	/**
	 * Ensures the game directory's override.cfg disables Swappy pacing. Never throws: a launch must not
	 * fail because this optional adjustment could not be written.
	 */
	public static void ensurePresent(File gameDir) {
		if (gameDir == null || !gameDir.isDirectory()) {
			return;
		}
		File target = new File(gameDir, FILE_NAME);
		try {
			List<String> lines = new ArrayList<>();
			if (target.isFile()) {
				try (BufferedReader reader = new BufferedReader(new FileReader(target))) {
					String line;
					while ((line = reader.readLine()) != null) {
						lines.add(line);
					}
				}
			}
			for (String line : lines) {
				String trimmed = line.trim();
				if (trimmed.startsWith(KEY)) {
					if (trimmed.equals(DESIRED_LINE)) {
						return;
					}
					Log.i(TAG, "Skipping: payload already sets " + trimmed);
					return;
				}
			}
			appendDisplayKey(lines);
			writeAtomically(target, lines);
			Log.i(TAG, "Wrote " + DESIRED_LINE + " to " + target.getAbsolutePath());
		} catch (IOException exception) {
			Log.w(TAG, "Unable to write " + FILE_NAME + ": " + exception.getMessage());
		}
	}

	/** Puts the key inside [display], creating the section only when the file has none. */
	private static void appendDisplayKey(List<String> lines) {
		int sectionIndex = -1;
		for (int i = 0; i < lines.size(); i++) {
			if (SECTION.equals(lines.get(i).trim())) {
				sectionIndex = i;
				break;
			}
		}
		if (sectionIndex >= 0) {
			lines.add(sectionIndex + 1, "");
			lines.add(sectionIndex + 1, DESIRED_LINE);
			return;
		}
		if (!lines.isEmpty() && !lines.get(lines.size() - 1).trim().isEmpty()) {
			lines.add("");
		}
		lines.add(SECTION);
		lines.add("");
		lines.add(DESIRED_LINE);
	}

	/**
	 * Godot reads this file at boot, so an interrupted write would leave the game unparseable. Writing a
	 * sibling and renaming keeps the payload either fully old or fully new.
	 */
	private static void writeAtomically(File target, List<String> lines) throws IOException {
		File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(temporary))) {
			for (String line : lines) {
				writer.write(line);
				writer.newLine();
			}
		}
		if (!temporary.renameTo(target)) {
			if (!target.delete() || !temporary.renameTo(target)) {
				throw new IOException("Could not replace " + target.getAbsolutePath());
			}
		}
	}
}
