package app.buddy.assistant;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Trims a video file to its first maxMs by copying encoded samples (no re-encode):
 * fast, lossless, and the output stays a plain MP4 the player accepts. Used by the
 * background picker — wallpapers are capped at 20 seconds.
 */
final class VideoTrim {

    static boolean trimTo(String src, String dst, long maxMs) {
        MediaExtractor ex = new MediaExtractor();
        MediaMuxer mux = null;
        try {
            ex.setDataSource(src);
            mux = new MediaMuxer(dst, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            Map<Integer, Integer> trackMap = new HashMap<>();
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && (mime.startsWith("video/") || mime.startsWith("audio/"))) {
                    ex.selectTrack(i);
                    trackMap.put(i, mux.addTrack(f));
                }
            }
            if (trackMap.isEmpty()) return false;
            mux.start();
            long maxUs = maxMs * 1000L;
            ByteBuffer buf = ByteBuffer.allocate(1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                int track = ex.getSampleTrackIndex();
                if (track < 0) break;
                Integer out = trackMap.get(track);
                if (out == null) {
                    ex.advance();
                    continue;
                }
                info.offset = 0;
                info.size = ex.readSampleData(buf, 0);
                if (info.size < 0) break;
                info.presentationTimeUs = ex.getSampleTime();
                if (info.presentationTimeUs > maxUs + 500_000L) break; // past the cap (+keyframe slack)
                info.flags = ex.getSampleFlags();
                mux.writeSampleData(out, buf, info);
                ex.advance();
            }
            mux.stop();
            return new File(dst).length() > 0;
        } catch (Exception e) {
            try {
                new File(dst).delete();
            } catch (Exception ignored) {
            }
            return false;
        } finally {
            try {
                if (mux != null) mux.release();
            } catch (Exception ignored) {
            }
            ex.release();
        }
    }

    /** Duration in ms, or -1 when unreadable. */
    static long durationMs(String path) {
        android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
        try {
            r.setDataSource(path);
            String d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d == null ? -1 : Long.parseLong(d);
        } catch (Exception e) {
            return -1;
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
    }
}
