package dn.util;

import com.badlogic.gdx.graphics.profiling.GLProfiler;
import java.lang.management.ManagementFactory;
import java.util.Arrays;

/** Fixed-storage smoke metrics; it performs no recurring frame allocation. */
public final class FrameMetrics {
  private static final int WARMUP_FRAMES = 60;
  private static final int MAX_SAMPLES = 1_200;

  private final GLProfiler glProfiler;
  private final double[] frameMilliseconds = new double[MAX_SAMPLES];
  private final double[] renderMilliseconds = new double[MAX_SAMPLES];
  private final com.sun.management.ThreadMXBean allocationBean;
  private final long renderThreadId;
  private final long launchStartedNanos;
  private long firstFrameNanos;
  private int frames;
  private int samples;
  private long allocationStart;
  private long renderStartedNanos;
  private long allocatedBytes;
  private int allocationSamples;
  private int maximumDrawCalls;

  public FrameMetrics(GLProfiler glProfiler, long launchStartedNanos) {
    this.glProfiler = glProfiler;
    this.launchStartedNanos = launchStartedNanos;
    java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
    allocationBean = bean instanceof com.sun.management.ThreadMXBean sunBean
        && sunBean.isThreadAllocatedMemorySupported() ? sunBean : null;
    renderThreadId = Thread.currentThread().threadId();
    if (allocationBean != null && !allocationBean.isThreadAllocatedMemoryEnabled()) {
      allocationBean.setThreadAllocatedMemoryEnabled(true);
    }
  }

  public void beginFrame() {
    if (firstFrameNanos == 0L) {
      firstFrameNanos = System.nanoTime();
    }
    glProfiler.reset();
    allocationStart = allocationBean == null ? -1L : allocationBean.getThreadAllocatedBytes(renderThreadId);
    renderStartedNanos = System.nanoTime();
  }

  public void endFrame(float deltaSeconds) {
    double renderTimeMilliseconds = (System.nanoTime() - renderStartedNanos) / 1_000_000.0;
    frames++;
    if (frames <= WARMUP_FRAMES) {
      return;
    }
    if (samples < frameMilliseconds.length) {
      frameMilliseconds[samples] = deltaSeconds * 1_000.0;
      renderMilliseconds[samples] = renderTimeMilliseconds;
      samples++;
    }
    maximumDrawCalls = Math.max(maximumDrawCalls, glProfiler.getDrawCalls());
    if (allocationStart >= 0L) {
      long frameBytes = allocationBean.getThreadAllocatedBytes(renderThreadId) - allocationStart;
      if (frameBytes >= 0L) {
        allocatedBytes += frameBytes;
        allocationSamples++;
      }
    }
  }

  public String report() {
    if (samples == 0) {
      return "No post-warmup samples were captured";
    }
    double total = 0.0;
    double renderTotal = 0.0;
    for (int index = 0; index < samples; index++) {
      total += frameMilliseconds[index];
      renderTotal += renderMilliseconds[index];
    }
    Arrays.sort(frameMilliseconds, 0, samples);
    Arrays.sort(renderMilliseconds, 0, samples);
    int p95Index = Math.min(samples - 1, (int) Math.ceil(samples * 0.95) - 1);
    String meanAllocations = allocationSamples == 0
        ? "unsupported"
        : String.format("%.1f bytes/frame", (double) allocatedBytes / allocationSamples);
    return String.format(
        "firstFrame=%.3fs, elapsed=%.3fs, samples=%d, meanFrame=%.3fms, p95Frame=%.3fms, "
            + "meanRender=%.3fms, p95Render=%.3fms, meanAllocations=%s, maxDrawCalls=%d",
        (firstFrameNanos - launchStartedNanos) / 1_000_000_000.0,
        (System.nanoTime() - launchStartedNanos) / 1_000_000_000.0,
        samples,
        total / samples,
        frameMilliseconds[p95Index],
        renderTotal / samples,
        renderMilliseconds[p95Index],
        meanAllocations,
        maximumDrawCalls);
  }
}
