# Ad auto-skip: how the detector was tuned

This records the measurement that produced the `AdDetector.Config` defaults, so that the next
change to the detector is measured the same way instead of tuned by ear. Everything below ran on
the JVM against real episodes; nothing needed a device.

## Ground truth

Sponsor chapters. Several networks publish chapter lists in which every ad break is a chapter
titled `Sponsor: <name>` (Podcasting 2.0 `<podcast:chapters>` JSON, or ID3 chapters in the MP3).
Those timestamps are the labels. Episodes used, all fetched from the public feeds on 2026-09-18:

| show | episode | labelled breaks | ad style |
|---|---|---|---|
| The Changelog | 684, 685 | 3 + 3 | host-read over a music bed |
| Changelog & Friends | 130 | 3 | host-read over a music bed |
| Go Time | 339 | 3 | host-read over a music bed |
| JS Party | 349 | 3 | host-read over a music bed, one very quiet bed |
| Ship It | 134 | 3 | host-read over a music bed |
| Practical AI | (Transistor id 66185604) | 2 | host-read over a music bed, no bass |
| Accidental Tech Podcast | 709 | 3 | host-read, **no bed at all** |

23 labelled breaks, 3257 s, over 12.6 hours of audio. Six further episodes without sponsor
chapters (Upgrade 637, Linux Unplugged 683/684, Syntax 1039, Mac Power Users 866, Talk Python 563)
were used only to eyeball false positives. Show intros under two minutes and chapters titled
outro/ending/closing/wrapping up are scored as *neutral*: theme music is indistinguishable from a
produced spot, and skipping it is not a mistake most listeners would mind.

## Harness

1. `ffmpeg -i episode.mp3 -ac 1 -ar 16000 -f f32le -` piped into a tiny `main` that feeds the
   real `AudioFeatureExtractor` and writes one `FeatureFrame` per line (a *frames* cache).
2. A bench `main` loads the frames, runs the real `AdDetector` with `Config` knobs taken from
   `-D` system properties, and reports, at the three sensitivity thresholds the app uses
   (0.45 high, 0.60 medium, 0.75 low): breaks hit, share of ad time covered, false segments,
   non-ad minutes that would be skipped, and *overrun*, the seconds a detected segment extends
   past its labelled break into content.
3. Compile with plain `javac` against the module sources plus the androidx annotation jar; no
   Gradle, no Android. A full pass over 14 episodes takes about two seconds once frames are cached.

## What the measurements showed

**The original scoring did nothing.** It combined a timbre change against the episode median
with three signed cues: ads are louder, more compressed (lower crest factor) and more tonal
(lower spectral flatness). On the labelled data those three cues flip sign from show to show:
Changelog ads are 0.6 to 1.0 dB-z *quieter* than the show, have a *higher* crest factor and
*higher* flatness (the bed fills in the spectrum); Friends 130 is the other way round. Only the
timbre term ever fired, capping confidence near 0.45, below the medium threshold. Result at
medium sensitivity: 3 of 23 breaks, 8% of ad time.

**"Different from the episode median" is the wrong question.** On interview shows the guest's
voice sits as far from the median as any ad, and one Changelog guest's boomy microphone gave
non-ad minutes more sustained bass than the ads had.

**The music bed is what is consistent.** Speech pauses several times a second. Taking, for each
FFT bin, the 10th percentile of power across the thirty sub-windows of a one-second window gives
the *spectral floor*: what is still playing when the voice stops. Two absolute readings of it
separate ads regardless of the voices, the microphones or the mastering:

- `floorDb`, the floor relative to the window mean. Plain speech in a quiet room: -35 dB and
  below. Speech over a bed: -30 to -20 dB. Wall-to-wall music: near 0 dB.
- `floorFlatness`, the spectral flatness of the floor between 50 Hz and 3 kHz. Room noise: 0.15
  and up. A bed: well under 0.1 (sustained harmonics).

Either alone fails somewhere (Ship It's room has a quiet tonal hum; JS Party's hosts have a loud
noise-like floor). Their product, each through a saturating ramp, gave a region-level AUC of 0.91
against 60-second non-ad windows; the false alarms left were outro themes.

**Duration is the best second cue.** With per-frame scores smoothed over 10 s and cut with
hysteresis, true breaks produced runs of 65 to 370 s while stings, transitions, laughs and hummy
rooms produced runs of 13 to 68 s (AUC 0.97). The confidence prior therefore weights duration at
0.30 with the plateau starting at 60 s.

**The median beats the mean.** Runs where the bed score flickers on and off (mean 0.4 to 0.48)
were the bulk of the false positives at medium sensitivity; real beds hold a median of 0.5 to 1.0.
Evidence is the run's median bed score.

**Edges must be pulled inward.** A 10 s moving average crosses its thresholds while most of the
window is still outside the bed, so hysteresis edges sit up to 5 s too far out; walking inward
from each edge on a 1.5 s average fixed a 766 s total overrun.

## Result with the shipped defaults

(Before the filled-floor cue below. With it, which is what ships, medium sensitivity gives 19/23
breaks, 9 false segments and 12.7 non-ad minutes; that is the audio baseline in the transcript
section at the end.)

| sensitivity | threshold | breaks hit | ad time covered | false segments | non-ad time skipped |
|---|---|---|---|---|---|
| high | 0.45 | 19 / 23 | 71% | 20 | 16.8 min / 12.6 h |
| medium (default) | 0.60 | 19 / 23 | 68% | 5 | 8.9 min / 12.6 h |
| low | 0.75 | 17 / 23 | 63% | 2 | 6.7 min / 12.6 h |

Overrun into content around the detected breaks: 175 s in total, about 8 s per break. The four
misses are the three ATP breaks (no bed; the detector cannot see them, and it flags nothing else
in that episode either at medium) and the JS Party WorkOS spot, whose bed is quieter than the
level ramp admits. Most of the remaining false segments at medium are outro music that the
chapter files did not label. Partial coverage on some breaks (Changelog's Buildkite spots, for
example) is a bed that drops out mid-ad; the skipper then jumps the first part and the rest plays.

## Produced (dynamically inserted) spots

Distractible "Would You Rather (Part 2)" (Megaphone, 2026-09-19 fetch) was transcribed offline
with vosk to label its spots, since it has no chapters: a 34 s carrier spot at 0:00 and a 30 s
Purina spot at 75:27. Both are mastered spots with drums under the voice: their floor is high
(-15 to -22 dB) but noise-like (flatness 0.2 to 0.5), which the tonal-bed gate rejects. A second
per-frame cue, the *filled floor* (level alone, from -21 dB, saturating at -15 dB, no flatness
condition), catches both; non-ad audio with a floor that high is almost only theme music, plus
JS Party's heavily compressed hosts. Cost on the labelled set at medium sensitivity: false
segments 5 to 9, non-ad minutes 8.9 to 12.7. The pre-roll still only reaches 0.55 (high
sensitivity), because the run merges with the show's own intro bed and a light bed under the
first minute of chat, which dilutes its median. That episode also produces four false runs of
ordinary chat at 0.56 to 0.69, so this show is a good regression case for any further change.

## Foreground work

On the phone the analysis of that 78 min episode was stopped twice before it finished ("was
cancelled" in the WorkManager log), so nothing was ever stored. A plain `Worker` is limited to
about ten minutes and can be stopped sooner; `AdAnalysisWorker` now promotes itself to
foreground work (dataSync type, quiet notification) for the duration of the decode.

## Re-running it

The harness lives outside the repository (it hard-codes local paths and needs ffmpeg and the
downloaded episodes), but it is small: `Extract` (stdin PCM to frames), `Frames` (loader),
`Bench` (scoring) and `Series` (print bed score over a time range). Recreate it from this
description, keep the sponsor-chapter episodes as ground truth, and treat the table above as the
baseline any change has to beat on both the hit count at 0.60 and the non-ad minutes.

## Transcripts and Claude (2026-11)

The audio detector cannot hear a host read without a bed (ATP), and theme music is its main false
positive. Both are easy to tell apart in words. So the episode's published transcript
(`<podcast:transcript>`, timed formats only) is now read for ads too, in one of two ways:

- **On the phone** (`TranscriptAdHeuristic`): phrase scoring. Each strong call to action (a web
  address with a path, an offer code, "terms apply") and each sponsor transition ("brought to
  you by", "after this message from") anchors a read; the read reaches back and forward through
  lines that name the advertiser (names come from the web addresses it gives) and ends at a "back
  to the show" right after the last call. A "Sponsor" speaker tag (Transistor's VTT has them) is an
  ad outright. Reads that point at the show itself become `SELF_PROMO`.
- **By Claude Haiku 5.5** (`ClaudeAdPrompt`, `ClaudeAdClient`), only with the user's own Anthropic
  API key. One Messages API call per transcript with structured output: for each ad, the line it
  starts and ends on plus its first and last few words, which place the edge inside long cues.
  `certain` gives 0.9, `likely` 0.65, `self_promotion` a `SELF_PROMO` segment.

`TranscriptAdFusion` then weighs the audio against the reading: a transcript ad keeps its own
(word) edges and is boosted when the audio agrees; an audio detection inside the transcript's span
(plus 30 s of theme music either side) that no transcript ad supports is multiplied by 0.3 after
Claude (below every threshold) and by 0.85 after the phone's reading; audio outside the span (an
ad stitched in after the transcript was made) is left alone. Merge ranking is MANUAL > CHAPTER >
TRANSCRIPT > DETECTED > SELF_PROMO.

### Data

1. **The 2026-09 episodes, transcribed with vosk** (`vosk-model-small-en-us-0.15`, word timestamps,
   one cue per utterance). Only Practical AI's feed transcript is usable for these shows (the
   Changelog's HTML transcripts have no times and leave the sponsor reads out), so vosk stands in
   for the machine transcripts hosts publish. It is much worse than those: no punctuation, numbers
   spelt out, brand names garbled. That makes it a conservative test, and it was held out: nothing
   was tuned on it. The labels were then *adjudicated*: the "negative" episodes (Syntax, MPU, Talk
   Python, Upgrade, LINUX Unplugged, Distractible) turned out to carry sponsor reads nobody had
   labelled, which counted against the transcript methods as false positives. Every unmatched
   candidate of every method was read in the transcript and labelled ad or neutral (own
   membership/book pitches, charity drives, outro credits), and the transcripts were searched for
   reads no method found (Upgrade's Fitbod read). LINUX Unplugged's "Intro" chapter is a Scribe
   pre-roll. That gives 37 breaks over 15 episodes.
2. **Real feed transcripts with sponsor chapters**: 17 episodes from Practical AI, Build Your SaaS,
   THE Bitcoin Podcast, School of Podcasting and Buzzcast (timed VTT/SRT as served), 21 breaks.
   Two chapters were corrected against the text (a Framer chapter that ran 114 s past the read, a
   ProfitWell chapter that ended 14 s early). Practical AI tags its reads `<v Sponsor>`, so it was
   scored with speaker tags stripped as well as with them. The heuristic was tuned on this set.
   Buzzsprout's inserted ads are not in its transcripts; those episodes were left out.

### Results at the three thresholds (breaks hit, false segments, non-ad minutes skipped)

Adjudicated vosk set, 37 breaks, 15 episodes:

| method | 0.45 | 0.60 (default) | 0.75 |
|---|---|---|---|
| audio only | 22, 39, 31.8 min | 21, 13, 14.7 min | 19, 1, 8.7 min |
| audio + phone reading | 30, 28, 25.3 min | 26, 4, 10.6 min | 18, 0, 4.3 min |
| audio + Claude | 37, 2, 5.0 min | **37, 2, 5.0 min** | 36, 0, 0.4 min |

Original labels (23 breaks): audio 19 / 9 / 12.7 min at 0.60; audio + Claude 23 / 3 / 2.3 min;
the overrun into content around the breaks drops from 174 s to 11 s.

Real feed transcripts, speaker tags stripped, 21 breaks:

| method | 0.45 | 0.60 (default) | 0.75 |
|---|---|---|---|
| audio only | 14, 36, 29.0 min | 12, 30, 23.9 min | 11, 15, 15.7 min |
| phone reading alone | 17, 15, 14.0 min | 17, 10, 11.0 min | 8, 10, 10.2 min |
| audio + phone reading | 20, 42, 35.0 min | 19, 21, 22.2 min | 14, 15, 17.8 min |
| audio + Claude | 20, 12, 4.4 min | **20, 12, 4.4 min** | 20, 8, 2.2 min |

With the published speaker tags the phone reading alone gets 19 of 21. Claude's one miss is the
School of Podcasting episode whose transcript has a gap where the ad is. Most of its "false"
segments on this set are real but unlabelled: Practical AI's outro credit ("thanks to our
partner Prediction Guard, predictionguard.com") and Build Your SaaS pre-rolls that have no
chapter.

### Choices the measurements made

- **Effort `medium`** (Haiku's default) over `low`: on the vosk set 25/25 against 24/25 with
  fewer "likely" guesses on garbled text; equal on real transcripts.
- **Cost**: 15k input and 1k output tokens for a 45-minute episode, 45k and 2k for two hours, so
  about a third of a US cent per hour of audio at Haiku 5.5 prices; a reply takes 5 to 10 s.
- **Unsupported-audio factor**: 0.3 after Claude (any higher only added false segments), 0.85
  after the phone's reading (0.75 lost breaks on the vosk set, 0.95 kept more false segments).
- **The phone reading needs a decent transcript.** On vosk text it finds 13 of 37 alone, against
  17 of 21 on real machine transcripts; its value is in fusion, where it removes most of the audio
  detector's false segments.
- A transcript whose last words end more than 15 s after the audio, or more than 4 minutes before
  it, is not read (`TranscriptLines.fitsEpisode`): its times belong to another cut of the file.
  Ads stitched in after transcription (Buzzsprout, Megaphone) still shift everything after them,
  and nothing here detects that.

### Re-running it

`TxBench` extends the audio bench: a manifest line per episode (truth, frames, transcript, MIME
type, podcast title, website host), knobs as `-D` properties (`-Dc.unsup`, `-Dh.unsup`,
`-Deffort`, `-DstripSpeakers=true`), Claude replies cached per episode and effort so re-scoring is
free, `-Dclaude=true -Dkeyfile=...` to fill the cache. It compiles the module sources plus Gson,
OkHttp, okio, kotlin-stdlib, jsoup, org.json and androidx.annotation with plain `javac`, and
stubs `android.util.Log`.
