package com.ylports.mother3recomp;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * Keeps copies of the last suspend states. Players often go days without an
 * in-game save, so the suspend state holds their progress, yet it is the most
 * fragile file: only the engine build and ROM bytes that wrote it can load it,
 * it is not cloud synced, a cloud download stops the game from resuming it,
 * and the next suspend overwrites it. Before a game starts, the current state
 * (and its RetroAchievements sidecar) is copied to
 * files/suspend-backups/<stem>.<state time>.state; the newest {@link #KEEP}
 * are kept. {@link #restore} puts one back for the next PLAY.
 */
final class SuspendBackups {
    private static final String TAG = "Mother3Suspend";
    private static final int KEEP = 8;

    private SuspendBackups() {}

    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    static final int NO_SAVE = -1;

    private static String stem(String romFile) {
        return romFile.contains(".") ? romFile.substring(0, romFile.lastIndexOf('.')) : romFile;
    }

    static void keep(File filesDir, String romFile) {
        String stem = stem(romFile);
        File state = new File(filesDir, "roms/" + stem + ".suspend.state");
        if (!state.isFile() || state.length() == 0) return;
        File root = new File(filesDir, "suspend-backups");
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            .format(new Date(state.lastModified()));
        File copy = new File(root, stem + "." + stamp + ".state");
        if (copy.exists()) return;  // this state is backed up already
        if (!root.isDirectory() && !root.mkdirs()) {
            Log.w(TAG, "cannot create " + root);
            return;
        }
        try {
            File sidecar = new File(state.getPath() + ".ra");
            if (sidecar.isFile()) copyAtomically(sidecar, new File(copy.getPath() + ".ra"));
            // The state last: its presence marks a complete backup.
            copyAtomically(state, copy);
        } catch (IOException e) {
            Log.w(TAG, "cannot back up " + state.getName(), e);
            return;
        }
        prune(root, stem);
    }

    /** The backups of this ROM's suspend state, newest first. */
    static File[] list(File filesDir, String romFile) {
        String stem = stem(romFile);
        File[] all = new File(filesDir, "suspend-backups").listFiles(
            (d, name) -> name.startsWith(stem + ".") && name.endsWith(".state"));
        if (all == null) return new File[0];
        Arrays.sort(all, (a, b) -> b.getName().compareTo(a.getName()));
        return all;
    }

    /**
     * Makes {@code backup} the state the next PLAY resumes. The current state
     * is backed up first, so a restore can itself be undone. Not while the
     * game runs: it would overwrite the files when it suspends.
     */
    static void restore(File filesDir, String romFile, File backup) throws IOException {
        keep(filesDir, romFile);
        String stem = stem(romFile);
        File state = new File(filesDir, "roms/" + stem + ".suspend.state");
        File sidecar = new File(state.getPath() + ".ra");
        File backupSidecar = new File(backup.getPath() + ".ra");
        copyAtomically(backup, state);
        // RetroAchievements progress must match the state it belongs to.
        if (backupSidecar.isFile()) copyAtomically(backupSidecar, sidecar);
        else sidecar.delete();
        // The engine resumes the state only while this marker exists and
        // names the battery save as it is now.
        writeMarker(filesDir, stem, "0");
    }

    /**
     * Engines from mstan e353848 on resume a suspend state only when its
     * marker ("frame", then the battery save's SHA-1) still matches the
     * battery save, so a newer or downloaded .sav is never rolled back.
     * Markers written before that hold only the frame and would be refused,
     * discarding the player's progress on the first launch of the update.
     * Before a game starts, such a marker is completed with the battery save
     * as it is now: what the older engines trusted when they resumed it.
     */
    static void upgradeMarker(File filesDir, String romFile) {
        String stem = stem(romFile);
        File marker = new File(filesDir, "roms/" + stem + ".suspend.pending");
        if (!marker.isFile()) return;
        try {
            String[] fields = new String(readAll(marker), java.nio.charset.StandardCharsets.US_ASCII)
                .trim().split("\\s+");
            if (fields.length != 1 || fields[0].isEmpty()) return;  // already fingerprinted
            writeMarker(filesDir, stem, fields[0]);
        } catch (IOException e) {
            Log.w(TAG, "cannot upgrade " + marker.getName(), e);
        }
    }

    /** The engine's suspend marker: the frame, then the battery save's SHA-1 ("missing" without one). */
    private static void writeMarker(File filesDir, String stem, String frame) throws IOException {
        File save = new File(filesDir, "roms/" + stem + ".sav");
        String battery;
        if (save.isFile()) {
            try {
                byte[] digest = java.security.MessageDigest.getInstance("SHA-1").digest(readAll(save));
                StringBuilder hex = new StringBuilder();
                for (byte b : digest) hex.append(String.format(Locale.US, "%02x", b & 0xff));
                battery = hex.toString();
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IOException(e);
            }
        } else {
            battery = "missing";
        }
        File marker = new File(filesDir, "roms/" + stem + ".suspend.pending");
        File temp = new File(marker.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write((frame + "\n" + battery + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            out.getFD().sync();
        }
        if (!temp.renameTo(marker)) {
            temp.delete();
            throw new IOException("cannot write " + marker);
        }
    }

    private static byte[] readAll(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[64 << 10];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    /**
     * Whole days by which the in-game save trails the suspend state the next
     * PLAY resumes, {@link #NO_SAVE} when there is no in-game save at all, or
     * 0 when there is nothing to warn about. Progress made since the in-game
     * save lives only in that fragile state.
     */
    static int saveLagDays(File filesDir, String romFile) {
        String stem = stem(romFile);
        File state = new File(filesDir, "roms/" + stem + ".suspend.state");
        File marker = new File(filesDir, "roms/" + stem + ".suspend.pending");
        File save = new File(filesDir, "roms/" + stem + ".sav");
        if (!state.isFile() || !marker.isFile()) return 0;
        if (!save.isFile()) return NO_SAVE;
        long lag = state.lastModified() - save.lastModified();
        return lag >= DAY_MS ? (int)(lag / DAY_MS) : 0;
    }

    private static void prune(File root, String stem) {
        String[] names = root.list((d, name) -> name.startsWith(stem + ".") && name.endsWith(".state"));
        if (names == null || names.length <= KEEP) return;
        Arrays.sort(names);  // yyyyMMdd-HHmmss sorts by time
        for (int i = 0; i < names.length - KEEP; ++i) {
            new File(root, names[i]).delete();
            new File(root, names[i] + ".ra").delete();
        }
    }

    private static void copyAtomically(File from, File to) throws IOException {
        File temp = new File(to.getPath() + ".tmp");
        try (InputStream in = new FileInputStream(from);
             FileOutputStream out = new FileOutputStream(temp)) {
            byte[] buffer = new byte[64 << 10];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            out.getFD().sync();
        }
        if (!temp.renameTo(to)) {
            temp.delete();
            throw new IOException("cannot write " + to);
        }
    }
}
