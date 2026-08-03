package com.winlator.core;

import android.content.Context;
import android.os.Environment;

import com.winlator.xenvironment.ImageFs;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RuntimePaths {
    private static final Pattern PRIVATE_ROOT = Pattern.compile(
            "/data/(?:data|user/0)/[^/]+"
    );

    private RuntimePaths() {}

    public static File storageDir(Context context) {
        return new File(context.getDataDir(), "storage");
    }

    public static File gamepadSharedMemoryDir(Context context) {
        return new File(context.getFilesDir(), "gamepad_shm");
    }

    public static String defaultDrives(Context context) {
        return "D:" + Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                + "E:" + storageDir(context).getAbsolutePath();
    }

    public static String rebasePrivatePath(Context context, String value) {
        if (value == null) return "";
        Matcher matcher = PRIVATE_ROOT.matcher(value);
        return matcher.replaceAll(Matcher.quoteReplacement(context.getDataDir().getAbsolutePath()));
    }

    public static String rebasePrivateStorageDrive(Context context, String drives) {
        return rebasePrivatePath(context, drives);
    }

    public static String resolveDrives(Context context, String drives) {
        String rebased = rebasePrivateStorageDrive(context, drives);
        return rebased.isBlank() ? defaultDrives(context) : rebased;
    }

    public static String[] mediaConversionEnvVars(File imageFsRoot) {
        File home = new File(imageFsRoot, "home/xuser");
        return new String[] {
                "MEDIACONV_AUDIO_DUMP_FILE=" + new File(home, "audio.dmp"),
                "MEDIACONV_VIDEO_DUMP_FILE=" + new File(home, "video.dmp"),
                "MEDIACONV_VIDEO_TRANSCODED_FILE=" + new File(home, "transcoded.mkv"),
                "MEDIACONV_AUDIO_TRANSCODED_FILE=" + new File(home, "transcoded.wav"),
                "MEDIACONV_BLANK_AUDIO_FILE=" + new File(home, "blank.wav"),
                "MEDIACONV_BLANK_VIDEO_FILE=" + new File(home, "blank.mkv"),
        };
    }

    public static String dxvkCachePath(File imageFsRoot) {
        return new File(imageFsRoot, ImageFs.CACHE_PATH.replaceFirst("^/", "")).getAbsolutePath();
    }
}
