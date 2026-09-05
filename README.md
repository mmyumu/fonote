# Fonote

Premier prototype Android de prise de notes football, avec serveur personnel commun au futur client Quest. Aucun abonnement ni API à quota : la composition fournie est celle de la finale France–Croatie du 15 juillet 2018, extraite hors ligne de [StatsBomb Open Data](https://github.com/statsbomb/open-data) (match 8658) vers `android/app/src/main/assets/match.json`. Usage personnel ; ces données ne sont pas redistribuées.

## Ce qui fonctionne dans le code

- Terrain avec les 22 titulaires placés à leur poste réel, chaque équipe dans sa formation (4-4-2 / 4-3-3).
- Deux gestes pour noter : toucher un joueur, puis son action. Il n'y a pas de bouton d'enregistrement — chaque action écrit la note aussitôt.
- Une note décrit un moment, pas un joueur. Tant qu'une note est ouverte, le panneau **collecte** : chaque joueur touché la rejoint avec son action propre, et « A marque, B délivre la passe décisive, C rate son arrêt » s'enchaîne d'un trait en six taps, sans quitter le terrain. Sans note ouverte, toucher un joueur en démarre une. Le seul geste qui reste à dire tout haut est donc la fin d'un moment : **« Terminé »**, qui rend la liste des notes. La corbeille à côté jette la note en cours ; tant qu'aucune action n'a été choisie il n'y a rien à jeter, et le bouton dit « Abandonner ».
- Le résumé d'une note suit toujours le même ordre, quel que soit l'ordre de saisie : le plus fort d'abord, le bon avant le mauvais, départages par l'ordre de la palette. Il se déduit des poids d'actions, sans table supplémentaire, et s'applique au rendu seulement — le journal garde l'ordre réel de saisie, donc changer d'avis sur cet ordre ne réécrit aucune note.
- Le geste retour défait l'écran courant au lieu de quitter : il ferme la note ouverte en la gardant, puis ramène de « Notes » ou « Bilan » au match, et ne sort de l'application qu'en dernier recours.
- Retirer un joueur de la note se fait par **appui long sur le terrain**, là où on l'a mis — la croix de sa pastille fait la même chose. Un tap sur un joueur déjà dans la note vise sa ligne pour corriger son action, ce qui interdisait le double-clic. Retirer le dernier joueur d'une note l'efface.
- Une note laissée ouverte pendant que le match avance est le seul risque de la collecte : passé deux minutes, sa pastille de minute vire à l'ambre.
- Composition dans un bandeau fixe sous le terrain, jamais en surimpression : le terrain reste visible et cliquable. Sa hauteur ne varie pas, pour qu'ouvrir une note ne déplace jamais les joueurs sous le doigt.
- Dix-huit actions appariées geste par geste, réussi au-dessus, raté en dessous : bon/raté, but/CSC, passe décisive/perte de balle, passe/passe ratée, dribble/dribble raté, tir cadré/tir manqué, geste défensif/duel perdu, arrêt/arrêt raté, jaune, rouge. Chacune porte un poids ; polarité, couleur et bilan en découlent, au lieu d'être choisis à la main.
- Bilan par joueur déduit des notes : solde signé sur le maillot au repos, page dédiée avec une note sur dix (base 6, une demi-note par point), le détail qui la justifie et le rappel qu'elle ne mesure que ce qui a été relevé.
- Chronomètre automatique depuis le coup d'envoi : la minute de la note est préremplie. Ajustable sur la minute de la diffusion, pause mi-temps, coup d’envoi à 0′, reprise à 45′ ; il survit à la fermeture de l'application.
- Historique, commentaires facultatifs, suppression.
- `↶` annule la dernière opération, quelle qu'elle soit, et remonte le journal pas à pas : une suppression est défaite, une note complétée revient à sa version précédente, un commentaire au texte qu'il portait. Rien n'est retiré du journal — l'annulation écrit l'opération qui compense, donc elle se synchronise comme le reste. Toute nouvelle action replace le curseur à la fin.
- SQLite sur Android ; enregistrement hors connexion, conservé après fermeture de l'application.
- Synchronisation manuelle avec un serveur SQLite, authentifié par un jeton personnel.
- Export JSON des observations via le partage Android.

Le client est écrit en Java avec les widgets Android natifs, sans dépendance d'interface. Kotlin/Compose évoqué au cadrage n'est pas utilisé dans ce prototype. Aucun SDK fournisseur n'est couplé aux notes.

## Changer de match

`android/app/src/main/assets/match.json` est produit hors ligne, une fois, par un script sans dépendance :

```bash
python3 scripts/fetch-match.py 8658 --id wc2018-final \
  --competition "Coupe du monde 2018" --stage Finale --date 2018-07-15 \
  > android/app/src/main/assets/match.json
```

L'argument est un identifiant [StatsBomb Open Data](https://github.com/statsbomb/open-data) ; les couleurs d'équipe viennent de TheSportsDB (libre, sans clé). Les couleurs officielles sont inutilisables telles quelles sur la pelouse sombre — le marine des Bleus y tombe à 1,02:1 de contraste — donc le script conserve la teinte et remonte la clarté jusqu'à un seuil lisible, avec une cible différente par équipe pour qu'elles se distinguent aussi par la clarté et pas seulement par la teinte. `colourOfficial` garde la valeur d'origine.

Les positions viennent d'une table poste → point tenue dans le script : juste sur les formations classiques, à vérifier sur les systèmes plus rares.

## Démarrer le serveur

Python 3.12 ou plus récent, sans dépendance à installer. Depuis la racine :

```bash
export FONOTE_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
python3 backend/server.py --db fonote.sqlite3
```

Configurer le même jeton dans l'application. Le serveur écoute uniquement sur `127.0.0.1:8080` par défaut. Pour un téléphone connecté en USB :

```bash
adb reverse tcp:8080 tcp:8080
```

Utiliser alors `http://127.0.0.1:8080` dans l'application **debug**. Pour l'émulateur Android : `http://10.0.2.2:8080`.

Le jeton est un secret : ne pas le committer. La version release refuse HTTP en clair. Un déploiement distant doit passer par HTTPS et un serveur HTTP de production ; le serveur Python fourni est destiné au prototype personnel local. Un seul espace personnel par serveur, pas de gestion de comptes.

## Construire Android

Sur cette machine WSL, les outils sont installés dans `.tooling/` (ignoré par Git) : JDK 17, Gradle 8.9, SDK 35, Build-Tools 34, adb, Android Studio et émulateur. Depuis la racine :

```bash
bash scripts/build-android.sh
bash scripts/android-studio.sh
```

Le premier script compile et lance Lint ; le second ouvre Android Studio sur le projet. Le Wrapper Gradle est fourni avec contrôle SHA-256. Sur une autre machine avec Java 17 et le SDK configurés, utiliser `cd android && ./gradlew :app:assembleDebug` (ou `gradlew.bat` sous Windows).

APK : `android/app/build/outputs/apk/debug/app-debug.apk`. L'application cible actuellement API 34 pour ce prototype et fonctionne à partir d'Android 8 (API 26) ; publication Play Store hors périmètre.

Pour utiliser les outils dans un terminal :

```bash
source scripts/android-env.sh
adb devices -l
```

L'émulateur `Fonote_API_35` utilise l'image Google APIs Android 35. Sous WSL, l'accélération exige l'accès à `/dev/kvm`. Si nécessaire, exécuter `sudo usermod -aG kvm mmyumu`, puis ouvrir une session avec les nouveaux groupes (`newgrp kvm`) avant de lancer :

```bash
bash scripts/run-emulator.sh
```

Après démarrage de l'émulateur ou connexion d'un téléphone autorisé :

```bash
source scripts/android-env.sh
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n fr.fonote/.MainActivity
```

Avec plusieurs appareils, ajouter `-s <identifiant>` à adb. Pour un téléphone USB sous WSL, le périphérique doit d'abord être transmis par Windows (usbipd), ou utiliser adb Windows. L'absence d'appareil dans `adb devices` n'empêche pas la compilation.

Compatibilité de l'outillage : [AGP 8.7 / Gradle 8.9 / JDK 17](https://developer.android.com/build/releases/agp-8-7-0-release-notes). HTTP debug et HTTPS release reposent sur la [configuration de sécurité Android](https://developer.android.com/privacy-and-security/security-config).

## Vérifier

```bash
python3 -m unittest discover -s backend -v
```

Parcours Android à vérifier sur appareil : composer une note à plusieurs joueurs sans quitter le terrain, une note générale par `+`, noter hors connexion, fermer et rouvrir en cours de composition, ajouter un commentaire, vérifier le bilan, synchroniser deux fois, annuler puis resynchroniser, importer sur un second appareil. Les notes ne doivent pas être dupliquées ou réapparaître après suppression.

## Contrat du serveur et persistance

Toutes les routes exigent `Authorization: Bearer <jeton>` :

| Route | Réponse / effet |
|---|---|
| `GET /v1/matches` | Le match embarqué, ses 22 joueurs et sa provenance |
| `POST /v1/operations` | Enregistre une opération ; renvoie `{seq, operation}` |
| `GET /v1/operations` | Journal complet ordonné, utilisable par Android ou Quest |

Une opération possède `id` (UUID), `note_id` (UUID) et `kind`. Étendre une note en réémet une sous le même `note_id` avec un `id` neuf : le journal garde les deux, le lecteur retient la dernière et conserve la place chronologique de la première.

- `note` : `match_id`, `minute` (entier) et `entries`, la liste des `{player_id, action}` du moment — un joueur au plus une fois, liste vide pour une note générale. Les notes écrites avant cette forme portaient `player_id` et `action` à la racine ; le journal étant immuable, le serveur et le client acceptent toujours les deux.
- `comment` : `text` (2 000 caractères maximum).
- `delete` : aucun champ supplémentaire.
- `restore` : aucun champ supplémentaire ; rend visible une note supprimée.

Un nouvel envoi du même identifiant et contenu est sans effet. Un contenu différent sous le même identifiant est refusé. Les opérations sont immuables. Le client transmet d'abord ses opérations en attente, puis récupère le journal commun. Une suppression l'emporte sur toute modification concurrente : réécrire une note supprimée ne la fait pas réapparaître, seule une opération `restore` explicite le fait. Pour une note comme pour un commentaire, la dernière version reçue par le serveur prévaut. Avant synchronisation, les opérations locales en attente sont appliquées après celles du serveur.

Le journal conserve les commentaires précédents et marque les notes supprimées comme masquées : supprimer une note dans l'interface n'est **pas** un effacement physique. Un effacement définitif et la purge des appareils nécessiteront un mécanisme dédié. Les UUID de notes ne doivent pas être réutilisés. Le prototype récupère tout le journal : pagination nécessaire si son volume augmente.

Pour sauvegarder la base serveur en cours d'utilisation, utiliser l'API SQLite de sauvegarde (ne pas copier seulement le fichier principal pendant des écritures WAL) :

```bash
python3 -c "import sqlite3; source=sqlite3.connect('fonote.sqlite3'); target=sqlite3.connect('fonote-backup.sqlite3'); source.backup(target); target.close(); source.close()"
```

## Prochaines étapes

Validation sur téléphone, matchs personnels, puis connecteur gratuit pour les vrais matchs selon couverture et droits. Favoris, statistiques externes et interface Quest ne sont pas encore implémentés. Aucune donnée de fournisseur n'est collectée ni archivée à ce stade ; les notes originales restent indépendantes de leurs éventuelles conditions de conservation.
