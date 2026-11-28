# Release cadence

Commits, checks and preview builds are not releases. Accumulate normal improvements into a coherent,
tested package before publishing it. An urgent connectivity, security or update-delivery fix can justify
an immediate patch; cosmetic changes do not need their own download for every user. Do not add unrelated
features or inflate release notes just to make a release look larger.

Write user-facing Russian release notes that explain what changed and where it matters, grouped by
shared improvements, Windows and Android. Include actual verification limits. Keep internal implementation
details out of the headline. Put platform-specific notes in each manifest file's optional `notes` list so
new clients can show them before downloading. Older clients ignore that field.

Keep an unchanged platform on its existing signed build and per-file `version` rather than rebuilding it
with a new number. `mirror-dl.py` verifies and downloads each asset from its own release version. A combined
release number alone must not force Android users to download a Windows-only fix, or vice versa.

Build and verify every changed platform, check signatures/hashes, and preview the desktop build before
delivery. Publish one complete release; update the DL manifest only after all files are verified and in
place. Verify the exact root updater URL with the app's User-Agent as well as the versioned URLs and
GitHub. A successful GitHub upload is not proof that the user-facing DL mirror is current.
