# Git Push: PAT over HTTPS Only

`GitService.push()` authenticates via `UsernamePasswordCredentialsProvider("token", token)`
— the PAT goes in as the password with a generic `"token"` username. No SSH key support.

```kotlin
git.push()
    .setRemote(remoteUrl)
    .setRefSpecs(refSpec)
    .setCredentialsProvider(UsernamePasswordCredentialsProvider("token", token))
    .call()
```

- **Why:** The `"token"` username is a generic placeholder that works across git hosts
  supporting PAT-over-HTTPS auth, not a GitHub-specific convention. SSH isn't supported —
  the app has no key management/storage; PAT auth reuses the existing `SecurePrefs` token
  storage instead.
- **How to apply:** The remote URL must be an HTTPS URL; the token comes from
  `SecurePrefs.getGitToken()`. Don't add SSH remote support without also adding key
  storage/management — there's no seam for it today.
