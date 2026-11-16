# Irish Claims (Meteor addon)

Shows server claim / territory messages in Irish (Gaeilge).

## Build it

Easiest (no Java needed):
1. Make a new GitHub repository and upload everything in this folder (including the hidden `.github` folder).
2. GitHub builds it automatically: open the Actions tab, click the latest run, and download "irish-claims-mod" under Artifacts.

On your own PC (needs JDK 25):
    ./gradlew build        (Windows: gradlew.bat build)
The jar ends up in build/libs/.

## Use it
Put the jar in your mods folder next to Meteor Client. In game, open Meteor (right shift),
find the "Gaeilge" category, and turn on "irish-claims".

Add your own messages under the "translations" setting, one per line:
    English text => Irish text
