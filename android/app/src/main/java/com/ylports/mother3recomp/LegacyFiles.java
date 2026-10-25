package com.ylports.mother3recomp;

import android.util.Log;

import java.io.File;

/**
 * Moves, once, the files the RG556 Android port (savemosca/Mother3Recomp-Android)
 * kept as roms/mother3_en.* to this app's names (roms/<ROM stem>.*). That port
 * used the same application id and is signed with the same key on the
 * player's machine, so installing this app over it keeps app-private storage:
 * the ROM, the in-game save, the suspend state with its RetroAchievements
 * sidecar, and the suspend backups. Nothing is overwritten: a group whose
 * target already exists is left where it is. The suspend state, its sidecar
 * and its resume marker move together, the marker last.
 */
final class LegacyFiles {
    private static final String TAG = "Mother3Legacy";
    private static final String OLD_STEM = "mother3_en";

    private LegacyFiles() {}

    static void migrate(File filesDir, String romFile) {
        String stem = romFile.contains(".") ? romFile.substring(0, romFile.lastIndexOf('.')) : romFile;
        if (stem.equals(OLD_STEM)) return;
        File roms = new File(filesDir, "roms");
        move(roms, stem, ".gba");
        move(roms, stem, ".sav");
        move(roms, stem, ".suspend.state", ".suspend.state.ra", ".suspend.pending");
        File[] backups = new File(filesDir, "suspend-backups")
            .listFiles((d, name) -> name.startsWith(OLD_STEM + "."));
        if (backups != null) {
            for (File backup : backups) {
                File target = new File(backup.getParentFile(),
                    stem + backup.getName().substring(OLD_STEM.length()));
                if (!target.exists() && !backup.renameTo(target)) Log.w(TAG, "cannot move " + backup);
            }
        }
    }

    /** Moves roms/mother3_en<suffix> to roms/<stem><suffix> for all suffixes, or none. */
    private static void move(File roms, String stem, String... suffixes) {
        boolean any = false;
        for (String suffix : suffixes) {
            if (new File(roms, stem + suffix).exists()) return;  // this app has its own
            any |= new File(roms, OLD_STEM + suffix).exists();
        }
        if (!any) return;
        for (String suffix : suffixes) {
            File from = new File(roms, OLD_STEM + suffix);
            if (!from.exists()) continue;
            if (from.renameTo(new File(roms, stem + suffix)))
                Log.i(TAG, "moved " + from.getName() + " to " + stem + suffix);
            else
                Log.w(TAG, "cannot move " + from);
        }
    }
}
