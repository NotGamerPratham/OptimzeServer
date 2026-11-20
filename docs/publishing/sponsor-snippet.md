# Sponsor banner snippets (NGP Cloud)

Paste-ready blocks for pages that are edited by hand. The banner image is hosted from this repository
(`docs/images/sponsor-ngp-cloud.png`, a PNG copy of the SVG used in the README) and links to https://ngpcloud.in.

## Modrinth (project description, Markdown)

Put this at the very top of the description:

```markdown
[![Sponsored by NGP Cloud - game server & Minecraft hosting in India](https://raw.githubusercontent.com/NotGamerPratham/OptimzeServer/main/docs/images/sponsor-ngp-cloud.png)](https://ngpcloud.in)
```

If Modrinth does not render the image, use the text version instead:

```markdown
**Sponsored by [NGP Cloud](https://ngpcloud.in)** - game server & Minecraft hosting in India, with DDoS protection and 99.9% uptime.
```

## SpigotMC (resource description, BBCode)

```bbcode
[CENTER][URL='https://ngpcloud.in'][IMG]https://raw.githubusercontent.com/NotGamerPratham/OptimzeServer/main/docs/images/sponsor-ngp-cloud.png[/IMG][/URL]
[SIZE=4][B]Sponsored by [URL='https://ngpcloud.in']NGP Cloud[/URL][/B] - game server &amp; Minecraft hosting[/SIZE][/CENTER]
```

## Where the sponsor already appears

- `README.md`: banner image under the badges (links to ngpcloud.in)
- Server console at startup and `/os version`: one plain-text line (hide with `general.show-sponsor: false`)
- GitHub release notes and the Modrinth changelog: a "Sponsor" line added by `.github/workflows/release.yml`
