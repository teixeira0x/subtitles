package com.teixeira.subtitles.utils;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import androidx.annotation.Nullable;
import java.util.concurrent.TimeUnit;

public class VideoUtils {

  @Nullable
  public static Bitmap getVideoThumbnail(String videoPath) {
    Bitmap thumbnail = null;
    MediaMetadataRetriever retriever = null;

    try {
      retriever = new MediaMetadataRetriever();
      retriever.setDataSource(videoPath);
      thumbnail = retriever.getFrameAtTime();
    } catch (Exception e) {
      e.printStackTrace();
    }

    return thumbnail;
  }

  public static String getTime(long ms) {
    long hours = TimeUnit.MILLISECONDS.toHours(ms);
    long minutes = TimeUnit.MILLISECONDS.toMinutes(ms) - TimeUnit.HOURS.toMinutes(hours);
    long seconds =
        TimeUnit.MILLISECONDS.toSeconds(ms)
            - TimeUnit.HOURS.toSeconds(hours)
            - TimeUnit.MINUTES.toSeconds(minutes);
    long milliseconds =
        ms
            - TimeUnit.HOURS.toMillis(hours)
            - TimeUnit.MINUTES.toMillis(minutes)
            - TimeUnit.SECONDS.toMillis(seconds);

    return String.format("%02d:%02d:%02d,%03d", hours, minutes, seconds, milliseconds);
  }

  public static long getMilliSeconds(String time) {
    String[] parts = time.split(":");
    if (parts.length != 3) {
      throw new IllegalArgumentException("Time format must be hh:mm:ss,SSS");
    }

    long hours = Long.parseLong(parts[0]);
    long minutes = Long.parseLong(parts[1]);

    String[] second = parts[2].split(",");
    if (second.length != 2) {
      throw new IllegalArgumentException("Time format must be hh:mm:ss,SSS");
    }

    long seconds = Long.parseLong(second[0]);
    long milliseconds = Long.parseLong(second[1]);

    long totalMillis =
        TimeUnit.HOURS.toMillis(hours)
            + TimeUnit.MINUTES.toMillis(minutes)
            + TimeUnit.SECONDS.toMillis(seconds)
            + milliseconds;

    return totalMillis;
  }

  private VideoUtils() {}
}