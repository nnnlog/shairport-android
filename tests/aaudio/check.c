// Device check for Shairport's aaudio backend (native/patches/shairport-sync/0007).
// Plays only zeros. Build and run with tests/aaudio/run.sh.
#include "audio_aaudio.c"

#include <assert.h>
#include <unistd.h>

shairport_cfg config;

void mutex_unlock(void *arg) { pthread_mutex_unlock((pthread_mutex_t *)arg); }

static int relayed_frames, relayed_rate, relayed_flushes, silent;
static uint64_t relayed_playtime;
int android_relay(__attribute__((unused)) const void *buf, int frames, int rate,
                  uint64_t playtime) {
  if (frames == 0) {
    relayed_flushes++;
    return 0;
  }
  relayed_frames = frames;
  relayed_rate = rate;
  relayed_playtime = playtime;
  return silent;
}

void parse_audio_options(__attribute__((unused)) const char *stanza,
                         __attribute__((unused)) uint32_t formats,
                         __attribute__((unused)) uint32_t rates,
                         __attribute__((unused)) uint32_t channels) {}

int32_t search_for_suitable_configuration(unsigned int channels, unsigned int rate,
                                          unsigned int format,
                                          __attribute__((unused)) int (*check)(unsigned int,
                                                                               unsigned int,
                                                                               unsigned int)) {
  return CHANNELS_TO_ENCODED_FORMAT(channels) | RATE_TO_ENCODED_FORMAT(rate) |
         FORMAT_TO_ENCODED_FORMAT(format);
}

static long play_seconds(double seconds) {
  static int16_t silence[2 * 441];
  for (int i = 0; i < (int)(seconds * 100); i++)
    assert(audio_aaudio.play(silence, 441, 0, 0, 0) == 0);
  long frames = -1;
  assert(audio_aaudio.delay(&frames) == 0);
  printf("delay after %.1f s: %ld frames (%.0f ms)\n", seconds, frames, frames * 1000.0 / 44100);
  return frames;
}

int main(void) {
  assert(audio_aaudio.init(0, NULL) == 0);
  int32_t format = audio_aaudio.get_configuration(2, 44100, SPS_FORMAT_S16_LE);
  assert(RATE_FROM_ENCODED_FORMAT(format) == 44100 && CHANNELS_FROM_ENCODED_FORMAT(format) == 2);
  assert(audio_aaudio.configure(format, NULL) == 0);

  long frames = -1;
  assert(audio_aaudio.delay(&frames) == 0 && frames == 0); // not started yet
  // Blocking writes keep the device buffer full: the delay is the whole queue plus the
  // hardware path, and it must stay bounded while we keep writing.
  long first = play_seconds(1.0), second = play_seconds(2.0);
  assert(relayed_frames == 0); // untimed frames stay here
  static int16_t timed[2 * 441];
  assert(audio_aaudio.play(timed, 441, 0, 1, 123456789) == 0);
  assert(relayed_frames == 441 && relayed_rate == 44100 && relayed_playtime == 123456789);
  // A silent tap: it gets the frames, this device plays zeros (so does this check).
  static int16_t loud[2 * 441];
  for (int i = 0; i < 2 * 441; i++)
    loud[i] = 1000;
  silent = 1;
  assert(audio_aaudio.play(loud, 441, 0, 1, 123456789) == 0);
  for (int i = 0; i < 2 * 441; i++)
    assert(loud[i] == 0);
  silent = 0;
  assert(first > 0 && first < 44100 && second > 0 && second < 44100);
  long drift = second - first;
  assert(drift > -8192 && drift < 8192); // within one deep-buffer burst

  audio_aaudio.flush(); // closes; the next play() reopens with fresh counters
  assert(relayed_flushes == 1); // the tap's app drops what it holds too
  assert(audio_aaudio.delay(&frames) == 0 && frames == 0);
  long reopened = play_seconds(0.5);
  assert(reopened > 0 && reopened < 44100);

  // An audio tap may ask for 1 s (its head start): the buffer must hold it.
  config.audio_backend_buffer_desired_length = 1.0;
  audio_aaudio.flush();
  long deep = play_seconds(2.0);
  assert(deep > 44100 * 3 / 4 && deep < 44100 * 3 / 2);

  // AirPlay 2's low-latency stream (patch 0012): a short buffer, opened and started ahead of its
  // audio by prepare(), and still running after a flush; stop() closes it.
  audio_aaudio.stop();
  assert(stream == NULL);
  config.low_latency_output_active = 1;
  config.low_latency_audio_backend_buffer_desired_length = 0.030;
  int32_t ll_format = audio_aaudio.get_configuration(2, 48000, SPS_FORMAT_S16_LE);
  assert(audio_aaudio.configure(ll_format, NULL) == 0);
  assert(stream_low_latency == 1);
  audio_aaudio.stop(); // the stream before: its format is remembered
  assert(stream == NULL && running == 0);
  assert(audio_aaudio.prepare() == 0);
  assert(stream != NULL && running == 1 && stream_low_latency == 1);
  usleep(300000); // the SETUP-to-audio wait: it plays nothing meanwhile
  assert(audio_aaudio.configure(ll_format, NULL) == 0);
  assert(running == 1); // configure keeps the stream prepare() started
  static int16_t ll_silence[2 * 480];
  for (int i = 0; i < 100; i++)
    assert(audio_aaudio.play(ll_silence, 480, 0, 0, 0) == 0);
  long ll = -1;
  assert(audio_aaudio.delay(&ll) == 0);
  printf("low-latency delay after 1 s: %ld frames (%.1f ms)\n", ll, ll * 1000.0 / 48000);
  assert(ll > 48000 * 0.030 * 3 / 4 && ll < 48000 * 0.060); // the 30 ms buffer and the device's
  audio_aaudio.flush();
  assert(stream != NULL && running == 1); // ready for the resume
  for (int i = 0; i < 50; i++)
    assert(audio_aaudio.play(ll_silence, 480, 0, 0, 0) == 0);
  assert(audio_aaudio.delay(&ll) == 0);
  assert(ll > 48000 * 0.030 * 3 / 4 && ll < 48000 * 0.060);
  audio_aaudio.stop();
  assert(stream == NULL && running == 0);
  config.low_latency_output_active = 0;

  audio_aaudio.deinit();
  puts("PASS aaudio backend");
  return 0;
}
