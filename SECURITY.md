# Security and privacy

## Supported version

Security fixes are applied to the current `main` branch and the newest published GitHub release. Report a vulnerability privately through GitHub's security advisory feature; do not include credentials, calendar data, or private file names in a public issue.

## Data handled by InkDAV

InkDAV stores the account URL and username, cached calendar/task metadata, DAV ETags and sync tokens, file indexes, widget configuration, pending offline mutations, and mirror baselines. The Room database is private to the Android app but is not independently encrypted. Android device encryption and the lock screen are therefore part of the local security boundary.

DAV passwords or device secrets are AES-GCM encrypted with a non-exportable Android Keystore key. The credential preferences, database, and internal offline file cache are excluded from Android backup. Removing an account deletes its cached objects, pending mutations, mirror metadata, and stored credential from this device.

A user-selected physical mirror is intentionally outside the private app sandbox. Files in that folder inherit the storage provider's access rules and may be visible to other apps with user-granted access. Files exposed through InkDAV's `DocumentsProvider` are shared only through Android's system document-picker grants.

InkDAV contains no analytics, advertising SDK, telemetry service, or public-link sharing. It connects only to DAV endpoints configured by the user.

## Network and credential boundary

The account editor accepts HTTPS URLs only. HTTP authentication uses the platform TLS trust store; InkDAV does not disable certificate validation. Redirect following is disabled so an endpoint cannot redirect an authenticated request to another host. Native clients cannot complete NASDrive's browser OIDC flow, so NASDrive accounts use separately revocable device credentials over HTTPS Basic authentication at `/webdav/`.

Conditional `If-Match` and `If-None-Match` writes prevent silent overwrites. CalDAV deletion is applied only from a successfully completed RFC 6578 sync report that returns a new token. Local pending edits turn a remote change or deletion into a visible conflict; the user can keep the server version or preserve the local edit as a separate copy.

## Threat model and limitations

InkDAV does not protect data from an unlocked or rooted device, a compromised DAV server, a malicious storage provider selected for a mirror, or another app to which the user grants a document/folder URI. The application does not implement certificate pinning because it must support user-operated servers and certificate rotation. Server-side encryption, backups, access control, and credential revocation remain the server operator's responsibility.

Release APKs are signed in GitHub Actions using repository secrets. The signing keystore must be backed up offline; losing it prevents trustworthy upgrades under the same Android application identity. Never commit a keystore, passwords, generated secret files, DAV captures, or private integration fixtures.
