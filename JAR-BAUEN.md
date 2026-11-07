# So bekommst du die fertige JAR (ohne etwas zu installieren)

1. Auf github.com einloggen (kostenloses Konto) -> oben rechts "+" -> "New repository"
   -> Name z.B. team-locator -> "Create repository".
2. Auf der neuen Seite "uploading an existing file" anklicken.
3. ALLE Dateien und Ordner aus diesem entpackten Ordner hineinziehen
   (auch den versteckten Ordner .github! Unter Windows: Ansicht > Ausgeblendete Elemente anzeigen).
   -> "Commit changes".
4. Oben auf "Actions" klicken. Der Build "Build JAR" startet automatisch (ca. 3-5 Minuten).
   Falls er nicht startet: "Build JAR" -> "Run workflow".
5. Wenn er gruen ist: auf den Lauf klicken -> unten bei "Artifacts" -> team-locator-jar herunterladen.
   Darin liegt team-locator-1.0.0.jar (NICHT die -sources.jar nehmen).
6. JAR in .minecraft/mods/ legen, dazu Fabric Loader 1.21.11 und Fabric API.

Ist der Build rot: auf den Lauf -> "build" -> den roten Schritt aufklappen und
die Fehlermeldung an Claude schicken.

## Alternative: lokal bauen
Java 21 (JDK) und Gradle installieren, im Ordner `gradle build` ausfuehren
-> build/libs/team-locator-1.0.0.jar
