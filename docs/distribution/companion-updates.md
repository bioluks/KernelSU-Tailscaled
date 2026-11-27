# Companion update channels

The optional Android companion (package `com.askingmeric.tailmate`) requires
Android 8.0+, root approval in the root manager, and an independently installed
TailMate module (4.0.0 or later). These channels update **only the APK**, never
the module ZIP. The module remains usable without the companion.

The pre-4.0.0 companion used package `io.github.bioluks.tailscaled`. Android
treats TailMate as a different app: uninstall the old companion and install the
TailMate one; existing Obtainium entries for the old package must be replaced
with the imports below.

4.0.1 is signed with the new TailMate release key; 4.0.0 used the old
KernelSU-Tailscaled key (`0924BB04…626AE3`). Android cannot update 4.0.0 to
4.0.1 in place: uninstall the 4.0.0 companion once and install 4.0.1. The
module is unaffected.

## F-Droid-compatible repositories

| Channel | Add repository | Repository source |
| --- | --- | --- |
| GitHub Pages | [Add GitHub channel](https://bioluks.github.io/fdroid/repo?fingerprint=5D8E267599C867F92FF842FF9C5AA056482A9EEE65B87257686248D57A8AF84A) | [bioluks/fdroid](https://github.com/bioluks/fdroid) |
| GitScape / Forgejo | [Add Forgejo channel](https://git.dreamscape.one/execd/fdroid/raw/branch/site/repo?fingerprint=67B4275D8E7753CDE9EF0B39AD6A4A78641EA9ED7E7FC402FC2FEAB274460F5B) | [execd/fdroid](https://git.dreamscape.one/execd/fdroid) |

If a link does not open your F-Droid client, copy its URL into **Settings →
Repositories → Add repository**. Keep the `fingerprint` parameter. Choose either
channel; adding both is unnecessary. Both contain original stable-release APKs
from 4.0.1 on, the first release signed with the TailMate key (earlier releases
are not listed), and show each version with its release date. Neither is the
official F-Droid repository nor an endorsement by its maintainers. Drafts and
prereleases are excluded.

The official f-droid.org listing (once accepted) publishes the **same
developer-signed APK**: f-droid.org rebuilds it from source, verifies it matches
the release APK, and ships the release APK. Switching between f-droid.org, these
channels, Obtainium and GitHub therefore needs no reinstall. Recipe and
verification notes: [distribution/fdroiddata](../../distribution/fdroiddata/README.md).

| Identity | SHA-256 |
| --- | --- |
| GitHub repository certificate | `5D8E267599C867F92FF842FF9C5AA056482A9EEE65B87257686248D57A8AF84A` |
| Forgejo repository certificate | `67B4275D8E7753CDE9EF0B39AD6A4A78641EA9ED7E7FC402FC2FEAB274460F5B` |
| Android APK signing certificate, all channels (from 4.0.1) | `3E3238FA4A9ECBCFB5C4BB6B3E7166AD5AF3B6E667B02B2015E3CC966D9CD089` (`CN=TailMate, O=Meriç Aşkın`) |

The two repository certificates differ intentionally. The APK certificate is the
same, so Android can update an existing release installation when changing
channels. A debug-signed build cannot update to the release signer in place.

## Obtainium

Install [Obtainium](https://github.com/ImranR98/Obtainium/releases), then choose one:

- [Import GitHub stable companion](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.askingmeric.tailmate%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fbioluks%2FTailMate%22%2C%22author%22%3A%22bioluks%22%2C%22name%22%3A%22TailMate%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Afalse%2C%5C%22includeDrafts%5C%22%3Afalse%2C%5C%22includeZips%5C%22%3Afalse%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22%5ETailMate-companion-v%5B0-9%5D%2B%5C%5C%5C%5C.%5B0-9%5D%2B%5C%5C%5C%5C.%5B0-9%5D%2B%28_tailscale%5B0-9.%5D%2B%29%3F%5C%5C%5C%5C.apk%24%5C%22%7D%22%7D).
- [Import Forgejo stable companion](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.askingmeric.tailmate%22%2C%22url%22%3A%22https%3A%2F%2Fgit.dreamscape.one%2Fexecd%2FTailMate%22%2C%22author%22%3A%22bioluks%22%2C%22name%22%3A%22TailMate%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Afalse%2C%5C%22includeDrafts%5C%22%3Afalse%2C%5C%22includeZips%5C%22%3Afalse%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22%5ETailMate-companion-v%5B0-9%5D%2B%5C%5C%5C%5C.%5B0-9%5D%2B%5C%5C%5C%5C.%5B0-9%5D%2B%28_tailscale%5B0-9.%5D%2B%29%3F%5C%5C%5C%5C.apk%24%5C%22%7D%22%2C%22overrideSource%22%3A%22Codeberg%22%7D).

The links use Obtainium's supported full-config deep link through its redirect
page, which offers an installation prompt when Obtainium is absent. They exclude
prereleases, drafts and module ZIPs, and select only the companion APK. The
Forgejo import explicitly selects Obtainium's **Forgejo (Codeberg)** handler for
the self-hosted URL. No token is needed for either public repository.

Import payloads: [GitHub](../../distribution/obtainium-github.json) and
[Forgejo](../../distribution/obtainium-forgejo.json). Both identify Android package
`com.askingmeric.tailmate`; importing both is not intended to create two apps.
For a manual setup, use the corresponding source repository URL and filter APK
names with `^TailMate-companion-v[0-9]+\.[0-9]+\.[0-9]+(_tailscale[0-9.]+)?\.apk$`.
(The pre-4.0.0 filter lacked the `_tailscale…` suffix group and therefore did not
match the 3.4.0+ asset names.)

## Maintainer operations

Source and releases are independently writable:

- GitHub: https://github.com/bioluks/TailMate (formerly `KernelSU-Tailscaled`; old URLs redirect)
- Forgejo: https://git.dreamscape.one/execd/TailMate

Forgejo repositories have `mirror=false`, `fork=false`, and no migration URL.
Forgejo releases are an explicit publication decision, not automatic GitHub
mirroring; each release carries byte-identical assets. Existing module ZIPs retain their
embedded GitHub update URL; moving the source repository does not rewrite
signed/published ZIPs.

The [GitHub F-Droid publisher](https://github.com/bioluks/fdroid/actions/workflows/publish.yml)
runs daily at 04:17 UTC and can be dispatched manually. It reads GitHub releases.
The [Forgejo F-Droid publisher](https://github.com/bioluks/fdroid/actions/workflows/forgejo.yml)
runs daily at 04:47 UTC or manually. GitHub is only its job executor: it fetches
Forgejo's own updater/configuration, reads Forgejo releases, signs with a separate
key, and writes Forgejo's `site` branch with a token restricted to `execd/fdroid`.
It does not use the instance administrator credential. There is no server SSH
publisher, running service or timer. Forgejo itself has no Actions runner.

For immediate refresh:

```sh
gh workflow run publish.yml -R bioluks/fdroid
gh workflow run forgejo.yml -R bioluks/fdroid
```

Never pass `bootstrap=true` after initial deployment. Publishers authenticate the
previous signed index, pin historical release hashes, validate APK signatures and
package/version, and reject replacement/disappearing stable releases. A failed
run leaves the existing public repository intact. Keep the successful previous
signed deployment if a new refresh fails; Android will not downgrade an installed
app merely because an older version is indexed. The 4.0.0 switch from the old
package was a one-time transition under the same repository keys; a later
package change would need the same explicit updater change.

For a separately accepted local Forgejo release:

```sh
git push forgejo master "refs/tags/$TAG:refs/tags/$TAG"   # HTTPS token with repo write scope
fj -H git.dreamscape.one release create --repo execd/TailMate \
  --tag "$TAG" --body "$(cat "$NOTES")" \
  --attach "$ARTIFACTS/TailMate-companion-$TAG.apk" \
  --attach "$ARTIFACTS/TailMate-$TAG.zip" \
  --attach "$ARTIFACTS/TailMate-arm-$TAG.zip" \
  --attach "$ARTIFACTS/TailMate-arm64-$TAG.zip" \
  "TailMate $TAG"
```

Do not overwrite published assets or republish an unapproved build. Existing
acceptance and release-signing gates still apply.

### Signing-key custody

Private local backups (never commit or publish):

- `~/.local/share/kernelsu-tailscaled/fdroid-github/`
- `~/.local/share/kernelsu-tailscaled/fdroid-forgejo/`

Make an encrypted offline backup of both directories. GitHub Actions secrets
cannot be read back. Losing a repository key prevents continuing its trusted
channel; losing the separate Android release key prevents normal APK updates.
The Forgejo private backup also contains its repository-scoped publication token.
Rotate that token if compromised; do not rotate the repository key casually.

## References

- [F-Droid binary repository setup](https://f-droid.org/docs/Setup_an_F-Droid_App_Repo/)
- [GitHub Pages workflow deployment](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages)
- [Obtainium supported deep links](https://wiki.obtainium.imranr.dev/deep_links/)
