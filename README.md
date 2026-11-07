# Team Locator 1.0

Jeder im Team installiert diese Mod. Wer teilt, schickt seine Position in den
Team-Chat; alle mit der Mod sehen sie als Markierung: Name, darunter Koordinaten
und Entfernung. Ohne Server-Mod, ohne OP.

## Tasten (aenderbar unter Optionen > Steuerung)
- P: Position einmal teilen
- O: Auto-Teilen an/aus (sendet alle 15 s, solange an; unten links steht dann "● Teile Position")
  Auto-Teilen geht beim Verlassen des Servers automatisch aus.

## Einrichten (WICHTIG)
Den richtigen Team-Chat-Befehl des Servers einstellen, z.B.:
    /tl setcommand team chat
oder, falls der Server einen anderen Befehl nutzt (z.B. /tc):
    /tl setcommand tc
Die Mod sendet NIE in den oeffentlichen Chat, immer ueber diesen Befehl.
Teste einmal mit P und /tl hide (macht die Nachrichten sichtbar), ob sie im Team-Chat ankommt.

Weitere Befehle: /tl (Status), /tl interval <sekunden> (min. 5), /tl hide, /tl clear

## Bauen
1. Template auf https://fabricmc.net/develop/template/ erzeugen
   (Minecraft 1.21.11, Mojang Mappings, Package dev.example.teamlocator).
2. Dessen src/main durch den src/main-Ordner aus diesem Projekt ersetzen.
3. ./gradlew build -> build/libs/<name>.jar nach .minecraft/mods/ (bei allen Teammates)

## Falls der Build an Namen scheitert (aendern sich zwischen Versionen)
- Identifier <-> ResourceLocation
- dimension().location() <-> dimension().identifier()
- getGameProfile().name() <-> getGameProfile().getName()
- KeyMapping.Category.MISC <-> "key.categories.misc"

## Hinweis
Pruefe die Regeln des Servers zu Mods und automatischen Nachrichten. Ein zu kurzes
Intervall kann vom Spamfilter erkannt werden; 15 s oder mehr ist sicherer.
