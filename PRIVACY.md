# Privacy policy

Yagi does not collect, store or send any personal data to its developer. It has no accounts, no analytics, no crash reporting, no advertising and no server of its own.

## What stays on your device

Your subscriptions, queue, playback history, bookmarks, downloads, ad-detection results and settings are stored only on your device. Database and OPML exports are written only where you choose to save them.

## Who the app talks to

Yagi only connects to other services to do what you ask it to:

- **Podcast publishers and their hosts:** fetching feeds, episodes, cover images, chapters and transcripts. These requests come from your device, so those servers see your IP address, as with any download.
- **Podcast search:** what you type in the search box goes to the directories that answer it: Apple's iTunes Search API, Podcast Index and fyyd. Browsing top lists also uses Apple's iTunes API.
- **Synchronisation, only if you set it up:** gpodder.net or your own Nextcloud server, with the credentials you enter.
- **Anthropic, only if you enter your own API key** (Settings > Ad skipping > Claude API key): to find ads, the podcast's title, the episode's title and the text of the episode's published transcript are sent to Anthropic's Claude API, under your Anthropic account and its terms. Nothing else is sent, and nothing at all for episodes without a transcript. Without a key, transcripts are read on your device. The key is kept out of the app's settings export and device backups.

## Bug reports

Settings > About & support > Report bug writes the app's log to a file and opens your device's share sheet, so you can decide where (if anywhere) it goes, then opens a new issue on GitHub. Nothing is sent automatically. Web addresses in the log are cut down to the site name before it is shared, so private feed links and their access tokens stay out of it, but GitHub issues are public: read the log before attaching it.

## Contact

Questions about this policy go in the [issue tracker](https://github.com/JapanColorado/Yagi/issues).
