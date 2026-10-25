package com.ylports.mother3recomp;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Keeps the diagnostics of the last sessions. Each start overwrites
 * android-runtime.log and the engine rewrites its coverage report and
 * self-heal proposal at exit, so a problem seen in one session was lost once
 * the game ran again. Before a game starts, the previous session's files are
 * copied to files/sessions/<end time>/ (the newest {@link #KEEP} are kept),
 * where tools/rg556-health.sh collects them. Copies only: the launcher still
 * shows the live log after a crash.
 */
final class SessionArchive {
    private static final String TAG = "Mother3Sessions";
    private static final int KEEP = 20;
    private static final long MAX_FILE_BYTES = 4L << 20;

    private SessionArchive() {}

    static void archivePrevious(File filesDir) {
        File log = new File(filesDir, "android-runtime.log");
        if (!log.isFile() || log.length() == 0) return;
        File root = new File(filesDir, "sessions");
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            .format(new Date(log.lastModified()));
        File dir = new File(root, stamp);
        if (dir.exists()) return;  // this session is archived already
        if (!dir.mkdirs()) {
            Log.w(TAG, "cannot create " + dir);
            return;
        }
        List<File> files = new ArrayList<>();
        files.add(log);
        File[] reports = filesDir.listFiles((d, name) ->
            name.startsWith("recomp_coverage_") || name.startsWith("recomp_master_misses_"));
        if (reports != null) files.addAll(Arrays.asList(reports));
        for (File file : files) {
            if (file.length() > MAX_FILE_BYTES) continue;
            try {
                copy(file, new File(dir, file.getName()));
            } catch (IOException e) {
                Log.w(TAG, "cannot archive " + file.getName(), e);
            }
        }
        prune(root);
    }

    private static void prune(File root) {
        String[] names = root.list();
        if (names == null || names.length <= KEEP) return;
        Arrays.sort(names);  // yyyyMMdd-HHmmss sorts by time
        for (int i = 0; i < names.length - KEEP; ++i) {
            File dir = new File(root, names[i]);
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) child.delete();
            }
            dir.delete();
        }
    }

    private static void copy(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[16 << 10];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
        }
    }
}
