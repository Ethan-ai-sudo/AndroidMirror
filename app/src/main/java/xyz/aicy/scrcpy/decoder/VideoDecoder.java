package xyz.aicy.scrcpy.decoder;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import xyz.aicy.scrcpy.model.VideoPacket;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class VideoDecoder {
    private MediaCodec mCodec;
    private Worker mWorker;
    private AtomicBoolean mIsConfigured = new AtomicBoolean(false);
    private static final int SAMPLE_QUEUE_CAPACITY = 30;
    // C1: output-dequeue timeout. Finite (not -1) so the loop wakes to re-poll the sample queue
    // and feed the next frame even when the in-flight pipeline is momentarily empty (e.g. between
    // repeat frames on a static screen); finite (not 0) so the thread does not busy-spin. 10ms is
    // upstream scrcpy's drain value.
    private static final long OUTPUT_BUFFER_TIMEOUT_US = 10_000L;

    public void decodeSample(byte[] data, int offset, int size, long presentationTimeUs, int flags) {
        if (mWorker != null) {
            mWorker.decodeSample(data, offset, size, presentationTimeUs, flags);
        }
    }

    public void configure(Surface surface, int width, int height, ByteBuffer csd0, ByteBuffer csd1) {
        if (mWorker != null) {
            mWorker.configure(surface, width, height, csd0, csd1);
        }
    }


    public void start() {
        if (mWorker == null) {
            mWorker = new Worker();
            mWorker.setRunning(true);
            mWorker.start();
        }
    }

    public void stop() {
        if (mWorker != null) {
            mWorker.setRunning(false);
            mWorker = null;
            mIsConfigured.set(false);
            if (mCodec != null) {
                mCodec.stop();
            }
        }
    }

    private class Worker extends Thread {

        private AtomicBoolean mIsRunning = new AtomicBoolean(false);
        private final BlockingQueue<Sample> sampleQueue = new ArrayBlockingQueue<>(SAMPLE_QUEUE_CAPACITY);

        Worker() {
        }

        private void setRunning(boolean isRunning) {
            mIsRunning.set(isRunning);
        }

        private void configure(Surface surface, int width, int height, ByteBuffer csd0, ByteBuffer csd1) {
            int csd0Len = csd0 == null ? -1 : csd0.remaining();
            int csd1Len = csd1 == null ? -1 : csd1.remaining();
            if (surface == null || !surface.isValid() || csd0 == null || csd1 == null || csd0Len <= 0 || csd1Len <= 0) {
                Log.w("Scrcpy", "Video configure skipped: surface=" + (surface != null)
                        + " csd0=" + csd0Len + " csd1=" + csd1Len);
                return;
            }
            if (mIsConfigured.get()) {
                mIsConfigured.set(false);
                if (mCodec != null) {
                    mCodec.stop();
                }
            }
            sampleQueue.clear();
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
            format.setByteBuffer("csd-0", csd0);
            format.setByteBuffer("csd-1", csd1);
            try {
                mCodec = MediaCodec.createDecoderByType("video/avc");
            } catch (IOException e) {
                throw new RuntimeException("Failed to create codec", e);
            }
            mCodec.configure(format, surface, null, 0);
            mCodec.start();
            Log.d("Scrcpy", "Video decoder configured: " + width + "x" + height);
            mIsConfigured.set(true);
        }


        public void decodeSample(byte[] data, int offset, int size, long presentationTimeUs, int flags) {
            if (!mIsConfigured.get() || !mIsRunning.get()) {
                return;
            }
            Sample sample = new Sample(data, offset, size, presentationTimeUs, flags);
            // C2: a keyframe must always be delivered (it resyncs the stream); if the queue is
            // full, evict the oldest entry to make room. A P-frame that arrives on a full queue is
            // discarded (drop-newest): the contiguous run already queued stays decodable (each
            // P-frame depends on its predecessor), which avoids decode artifacts from a broken
            // dependency chain. CONFIG(2)/END(4) never reach here (routed to configure()/loop).
            if (flags == VideoPacket.Flag.KEY_FRAME.getFlag()) {
                if (!sampleQueue.offer(sample)) {
                    sampleQueue.poll();
                    sampleQueue.offer(sample);
                }
            } else {
                // Drop-newest: offer returns false if full → the incoming P-frame is discarded.
                sampleQueue.offer(sample);
            }
        }

        @Override
        public void run() {
            try {
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                Sample pendingSample = null;
                while (mIsRunning.get()) {
                    if (mIsConfigured.get()) {
                        if (pendingSample == null) {
                            pendingSample = sampleQueue.poll();
                        }
                        if (pendingSample != null) {
                            int inputIndex = mCodec.dequeueInputBuffer(0);
                            if (inputIndex >= 0) {
                                ByteBuffer buffer;
                                buffer = mCodec.getInputBuffer(inputIndex);
                                if (buffer != null) {
                                    buffer.put(pendingSample.data, pendingSample.offset, pendingSample.size);
                                    mCodec.queueInputBuffer(inputIndex, 0, pendingSample.size, pendingSample.presentationTimeUs, pendingSample.flags);
                                    pendingSample = null;
                                }
                            }
                            // C1: no Thread.sleep(2) on "no free input buffer" — draining output
                            // (below) frees input buffers, and the blocking output dequeue yields
                            // the CPU for up to OUTPUT_BUFFER_TIMEOUT_US.
                        }

                        // C1: block on output drain with a finite timeout (NOT -1, NOT 0):
                        //  - 0 would busy-spin (the old behavior);
                        //  - -1 would stall when the in-flight pipeline is empty (e.g. between
                        //    repeat frames on a static screen: the thread blocks here with no
                        //    input queued, so a newly-arrived frame can never be fed → freeze).
                        // A finite timeout yields the CPU between frames yet wakes often enough to
                        // re-poll the sample queue and feed the next frame.
                        int outputIndex = mCodec.dequeueOutputBuffer(info, OUTPUT_BUFFER_TIMEOUT_US);
                        if (outputIndex >= 0) {
                            // setting true is telling system to render frame onto Surface
                            mCodec.releaseOutputBuffer(outputIndex, true);
                            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                                break;
                            }
                        }
                    } else {
                        // just waiting to be configured, then decode and render
                        try {
                            Thread.sleep(5);
                        } catch (InterruptedException ignore) {
                        }
                    }
                }
            } catch (IllegalStateException e) {
            }

        }
    }

    private static class Sample {
        final byte[] data;
        final int offset;
        final int size;
        final long presentationTimeUs;
        final int flags;

        Sample(byte[] data, int offset, int size, long presentationTimeUs, int flags) {
            this.data = data;
            this.offset = offset;
            this.size = size;
            this.presentationTimeUs = presentationTimeUs;
            this.flags = flags;
        }
    }
}
